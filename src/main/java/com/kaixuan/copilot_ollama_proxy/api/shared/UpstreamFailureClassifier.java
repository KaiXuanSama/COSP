package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ResponseTranslationException;
import com.kaixuan.copilot_ollama_proxy.application.runtime.UnresolvedModelRouteException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 把上游调用链抛出的异常归入一个<strong>失败类别</strong>，并带回那个被识别出来的具体异常。
 *
 * <h2>为何要收归一处</h2>
 * 三个控制器（OpenAi / Anthropic / Responses）此前各自维护一套 {@code findXxx} ——
 * 14 个方法里有 12 个是逐字相同的三份拷贝，另有 3 份 {@code isClientDisconnect}。
 * 那套写法的真实风险不是重复本身，而是<strong>同一件事在三条线路上得到不同结论</strong>：
 * 加一个新异常类型时必须记得改三处，漏一处的症状是「这条线路报 502 无法连接到上游，
 * 那条线路报 400 并说清原因」—— 而两条线路面对的是同一个上游故障。
 *
 * <h2>只做分类，不做渲染</h2>
 * 本类不产出任何 HTTP 状态码、错误 JSON 骨架或日志文案。那些是各控制器的
 * <strong>出口</strong>，由下游协议决定：Chat 与 Responses 都用
 * {@code {"error":{...}}} 嵌套形，Anthropic 多一层 {@code "type":"error"}，
 * 而 Responses 的<strong>流式</strong>错误是扁平事件体、与非流式刻意不同。
 * 统一它们会把合法差异抹平，所以本类的职责边界停在「这是什么失败」。
 *
 * <h2>类别是「用户该去改什么」的划分，不是异常类的镜像</h2>
 * 四个 4xx 类别各自指向一个不同的动作，消息因此不能共用模板：
 * <table>
 *   <caption>可操作性对照</caption>
 *   <tr><th>类别</th><th>用户该做什么</th></tr>
 *   <tr><td>{@link FailureKind#PROTOCOL_UNSUPPORTED}</td>
 *       <td>改<strong>供应商配置</strong>：勾上该协议，或换一个支持它的供应商</td></tr>
 *   <tr><td>{@link FailureKind#NO_SUPPORTED_PROTOCOL}</td>
 *       <td>改<strong>供应商配置</strong>：至少勾一种（没有别的选择）</td></tr>
 *   <tr><td>{@link FailureKind#REQUEST_TRANSLATION}</td>
 *       <td>改<strong>请求</strong>：消息里带着 {@code messages[2].role} 这样的字段路径</td></tr>
 *   <tr><td>{@link FailureKind#UNRESOLVED_MODEL_ROUTE}</td>
 *       <td>改<strong>模型名</strong>：未知、前缀不存在，或无前缀却命中多个供应商</td></tr>
 * </table>
 * 四者与 {@link FailureKind#UPSTREAM_HTTP}（上游确实回了错误响应，原样透传状态码与错误体）
 * 的区别是<strong>「上游到底有没有被连上」</strong>：前四类都没连上。
 * 把它们落进「无法连接到上游服务」会把排查方向指向网络，而真正要改的是配置、请求或模型名 ——
 * 这正是这套分类存在的全部理由。
 *
 * <h2>解包顺序即优先级</h2>
 * 每条规则做一次<strong>全链扫描</strong>，而不是逐节点依次判定。两者在
 * 「链上同时出现两个不同类别」时结论不同：假设链是 {@code [请求翻译异常 → 协议不可用异常]}，
 * 全链扫描给出<strong>协议不可用</strong>（优先级高者胜），逐节点判定给出<strong>请求翻译</strong>（浅者胜）。
 *
 * <p>取前者有两个理由：
 * <ol>
 *   <li><strong>与重构前的行为逐字等价。</strong>原先各 {@code findXxx} 各自独立扫描全链，
 *       调用顺序构成事实上的优先级 —— 本类只是把那件事写明白。</li>
 *   <li><strong>它才能兑现「协议类失败必须在 {@code WebClientResponseException} 之前判定」
 *       这条既有约束。</strong>该约束写在原先 {@code findProtocolException} 的 Javadoc 里，
 *       按全链扫描成立而与异常在链上的深度无关；按逐节点判定会在 HTTP 异常更靠链首时失效。</li>
 * </ol>
 *
 * <h2>客户端断连不在类别枚举里</h2>
 * {@link #isClientDisconnect(Throwable)} 是独立谓词，调用方在<strong>分类之前</strong>先问它。
 * 理由是断连根本不是「失败」：它对应 CANCELED 终态与静默断连，与 FAILED + 错误体的处置
 * 完全不同；把它塞进枚举会让每个渲染方法都必须处理一个它永远不会收到的类别。
 */
public final class UpstreamFailureClassifier {

    /**
     * 失败类别。<strong>声明顺序仅为可读性</strong> —— 真正的优先级在 {@link #RULES}，
     * 那是一份显式声明的列表，不要依赖这里的声明顺序。
     */
    public enum FailureKind {

        /** 供应商没勾这个协议，而跨协议翻译尚未实现。改配置能绕开。 */
        PROTOCOL_UNSUPPORTED,

        /** 供应商一个协议都没勾。同样改配置，但只有「至少勾一个」这一个选项。 */
        NO_SUPPORTED_PROTOCOL,

        /** 下游请求本身无法表达成上游协议。必须改请求，消息里带字段路径。 */
        REQUEST_TRANSLATION,

        /** 模型名没解析到唯一供应商。上游从未被连接。 */
        UNRESOLVED_MODEL_ROUTE,

        /** 上游确实回了错误响应（4xx / 5xx）。状态码与错误体原样透传，即 {@code cause} 一定是 {@link WebClientResponseException}。 */
        UPSTREAM_HTTP,

        /** 请求发出去了、上游也回了 2xx，但响应报文解析不了。 */
        RESPONSE_TRANSLATION,

        /** 以上都不是。落进各控制器原有的兜底文案。 */
        UNKNOWN
    }

    /**
     * 分类结果：类别 + 被识别出来的那个异常。
     *
     * <h2>为何把异常一起带出来，而不是只返回类别</h2>
     * 渲染需要<strong>载荷</strong>：HTTP 类要给状态码与错误体，其余要给消息，
     * 兜底要给原始异常以便提取根因。若只返回类别，调用方必须再扫一遍链去取那个异常 ——
     * 两次扫描必须结论一致，而那种一致性没有任何东西在保证。
     * 一次扫描同时给出「是哪类」与「是哪一个」，一致性就成了结构事实。
     *
     * @param kind  失败类别
     * @param cause 被识别出来的异常；{@link FailureKind#UNKNOWN} 时是传入链的<strong>最外层</strong>异常
     */
    public record Failure(FailureKind kind, Throwable cause) {

        /** 是否为指定类别。比直接比 {@link #kind()} 更短，且调用点读起来更像一句话。 */
        public boolean is(FailureKind candidate) {
            return kind == candidate;
        }

        /** 被识别异常的 message。渲染错误体时直接用它，不再自行拼装成因。 */
        public String message() {
            return cause == null ? null : cause.getMessage();
        }

        /**
         * 取出上游 HTTP 异常。
         *
         * <p>只在 {@link FailureKind#UPSTREAM_HTTP} 下合法。误用时抛 {@link IllegalStateException}
         * 而不是裸 {@link ClassCastException}：后者在日志里只留下两行类名，
         * 看不出「哪个类别被当成了 HTTP 失败」。
         */
        public WebClientResponseException asHttpFailure() {
            if (cause instanceof WebClientResponseException upstream) {
                return upstream;
            }
            throw new IllegalStateException(
                    "Failure kind " + kind + " 没有 HTTP 上游异常，只有 UPSTREAM_HTTP 才有");
        }
    }

    /**
     * 判定规则表。<strong>列表顺序即优先级</strong>，靠前者胜。
     *
     * <p>新增类别时加一行即可，三个控制器自动一起生效 —— 这正是收归一处要买的东西。
     * 但注意：加在这里只解决「分类一致」，各控制器若要让新类别有正确的状态码与错误体，
     * 仍需在它自己的出口里加一个分支（那是协议决定的，无法共用）。
     */
    private static final List<Rule> RULES = List.of(
            new Rule(FailureKind.PROTOCOL_UNSUPPORTED,
                    throwable -> throwable instanceof ProtocolTranslationNotSupportedException),
            new Rule(FailureKind.NO_SUPPORTED_PROTOCOL,
                    throwable -> throwable instanceof NoSupportedProtocolException),
            new Rule(FailureKind.REQUEST_TRANSLATION,
                    throwable -> throwable instanceof RequestTranslationException),
            new Rule(FailureKind.UNRESOLVED_MODEL_ROUTE,
                    throwable -> throwable instanceof UnresolvedModelRouteException),
            // 必须排在响应翻译之前：上游回错误响应时链上可能有多个异常，
            // 而「上游说了什么」比「我们解析不了什么」更接近事实。
            new Rule(FailureKind.UPSTREAM_HTTP,
                    throwable -> throwable instanceof WebClientResponseException),
            new Rule(FailureKind.RESPONSE_TRANSLATION,
                    throwable -> throwable instanceof ResponseTranslationException));

    /**
     * 客户端断连的异常<strong>简单类名</strong>白名单。
     *
     * <p>按类名而非类型判定，因为这些异常分散在三个不同的类加载来源里
     * （Reactor Netty 的 {@code AbortedException}、Servlet 容器的 {@code ClientAbortException}、
     * JDK 的 {@code EOFException}、Spring 的 {@code AsyncRequestNotUsableException}），
     * 没有一个共同的父类型可用，且其中几个在 WebFlux 运行时并不总是出现在 classpath 上。
     */
    private static final List<String> CLIENT_DISCONNECT_NAMES = List.of(
            "AbortedException", "ClientAbortException", "EOFException", "AsyncRequestNotUsableException");

    private UpstreamFailureClassifier() {
    }

    /**
     * 把异常链归入一个失败类别。
     *
     * <p><strong>不要用它判客户端断连</strong>：先调 {@link #isClientDisconnect(Throwable)}。
     * 断连不是失败（见类注释），因此不在 {@link FailureKind} 里，本方法也不会返回它。
     *
     * @param throwable 最外层异常；null 归为 {@link FailureKind#UNKNOWN}
     */
    public static Failure classify(Throwable throwable) {
        List<Throwable> chain = chainOf(throwable);
        for (Rule rule : RULES) {
            for (Throwable node : chain) {
                if (rule.matches().test(node)) {
                    return new Failure(rule.kind(), node);
                }
            }
        }
        // 兜底带最外层异常而非最深的一个：各控制器的兜底日志要从它身上
        // 提取根因与请求 URL，那里有自己的一套「跳过无意义包装」判据。
        return new Failure(FailureKind.UNKNOWN, throwable);
    }

    /**
     * 异常链中是否有客户端断连异常。
     *
     * <p>要沿链找而不是只看最外层：Netty / WebFlux 会把断连包几层再抛出来，
     * 只看最外层会漏判，症状是<strong>僵尸 Toast</strong> —— 终态事件不发，
     * 前端那条记录永远停在 CHUNK 上。
     */
    public static boolean isClientDisconnect(Throwable throwable) {
        for (Throwable node : chainOf(throwable)) {
            if (CLIENT_DISCONNECT_NAMES.contains(node.getClass().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    /** 把异常链摊平成列表，一次遍历后续规则复用，避免每条规则各扫一遍。 */
    private static List<Throwable> chainOf(Throwable throwable) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    private record Rule(FailureKind kind, Predicate<Throwable> matches) {
    }
}
