package com.kaixuan.copilot_ollama_proxy.application.pipeline;

import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.springframework.http.HttpHeaders;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 一次请求在主干上的<strong>上下文</strong> —— 随步骤顺序传递的那个对象。
 *
 * <h2>它为什么存在</h2>
 * 它不是为了「少传几个参数」，而是为了<strong>舍弃状态池</strong>。
 *
 * <p>主干是一根线，线上每一步都需要一些「本次请求的事实」：发给谁、两侧各是什么协议、
 * 请求体现在长什么样、下游带了哪些头。此前的做法是让每个方法各自持有或重新获取它们
 * （参数散传、或在服务层与执行器间来回穿透），于是「谁知道什么」变得依赖调用位置。
 * 收进一个对象、随线传递之后，<strong>每一步只取自己要的那几样，其余放着不动</strong> ——
 * 像旋转寿司：客人排排坐，各取所需。
 *
 * <h2>它刻意是可变、且允许冗余</h2>
 * <ul>
 *   <li><strong>可变</strong>：{@link #body} 会随阶段被改写（翻译把它换成上游形态、
 *       各注入步骤往里补字段）。做成不可变只会得到「外表不可变、内里可变」的假象 ——
 *       {@code body} 是 {@code Map}，原地改它本来就是主干的正常动作。</li>
 *   <li><strong>允许冗余</strong>：可以携带<em>当前无人读取</em>的字段。
 *       冗余字段是给后续步骤与后续功能预留的座位 —— 见 {@link #translationContext}。
 *       读取方出现时不必再改本类的形状（也就不必再动所有传递点）。</li>
 * </ul>
 *
 * <h2>{@code bodyProtocol} 与两侧协议的分界</h2>
 * <ul>
 *   <li>{@link #downstreamProtocol} / {@link #upstreamProtocol} —— <strong>路由事实</strong>：
 *       「跟谁说话」；</li>
 *   <li>{@link #bodyProtocol} —— <strong>形态事实</strong>：{@code body} 长什么样，
 *       且是<strong>阶段查表的键</strong>。翻译是让两者分道扬镳的唯一动作。</li>
 * </ul>
 *
 * <p><strong>当前 {@code bodyProtocol} 恒等于 {@code upstreamProtocol}</strong>，
 * 因为翻译发生在应用服务层（编排器早已把 body 换成上游形态，再交给执行器与主干）。
 * 等 3.4 把 translate 移进主干之后，它才会变成「初值 = downstream、被 translate 改写」。
 * 这不是设计选择而是<strong>当前事实</strong>，且写错会很安静：
 * 查表用错的键，症状是「某个阶段没执行」而没有任何报错。
 *
 * <h2>与流级状态的分界（不要混）</h2>
 * 本类装的是<strong>请求级</strong>事实 —— 整条主干共用，重试<strong>不</strong>重置。
 * 而 {@code contentEmitted} / {@code reasoningBuffer} / {@code chunkId} 是<strong>流级</strong>
 * 累积量（重试一次就得重置），它们<strong>不属于本类</strong>，由流算子的闭包持有、
 * 以方法参数穿线传递（见方向文档 §2.4「两级状态要分清」）。
 *
 * <h2>它并入了原本的 {@code PipelineExecution}（3.3b-1）</h2>
 * 本类引入时，那个类已经存在（2.1a 引入，用于承载「哪些步骤执行了」）。
 * 那次合并要解决的是<strong>同一份事实有两个家</strong>：两个类<strong>都持有两侧协议</strong>，
 * 而 {@link #translationNeeded()} 与 {@link #shouldApplyEmptyResponseGate()}
 * 都是<strong>从协议派生</strong>的判据 —— 它们挂在前者身上只是因为当时还没有本类。
 *
 * <p>协议是请求事实，归属本类；脚手架因此拆除。
 * <strong>{@link PipelineStep} 保持独立</strong>：它是步骤的词汇表（enum），
 * 与本类零字段重复，且被十多个文件引用 —— 并入的收益与代价不成比例。
 */
public final class RequestPipelineContext {

    /** 当前请求体。形状随阶段变化 —— 翻译会把它换成上游协议的形态。 */
    private Map<String, Object> body;

    /**
     * 当前 {@link #body} 是哪种协议的形态 —— <strong>阶段查表的键</strong>。
     *
     * <p>与 {@link #downstreamProtocol} / {@link #upstreamProtocol} 并列存在的原因就在这里：
     * 那两者描述「两侧各是什么协议」（路由事实），本字段描述「手里这份 body 长什么样」
     * （形态事实）。翻译是让两者分道扬镳的唯一动作。
     */
    private WireProtocol bodyProtocol;

    /**
     * 本次调用用于上游请求的模型名。
     *
     * <p>它是执行器入口需要的那个值（旧签名里的 {@code route.model()}，已剥供应商前缀）。
     * 写入时机随阶段推进：当前（3.4d-1）由创建方在组装期直接写入；
     * 等 3.4d-2 把 ctx 创建上移到端点后，改为**端点写请求模型名、主干解析后回填目标模型名**
     * （见 plan_ Step 3.4 「Q1 a-1」），届时本字段随 {@code provider} / {@code upstreamProtocol}
     * 一起改为可变。
     */
    private final String model;

    /** 下游使用的协议（由它打的端点决定）。 */
    private final WireProtocol downstreamProtocol;

    /** 实际对上游使用的协议（由供应商配置与调度决定）。 */
    private final WireProtocol upstreamProtocol;

    /** 本次调用解析出的供应商运行时配置。 */
    private final ProviderRuntimeConfiguration provider;

    /** 下游请求头，供出站头装配与鉴权头探测使用。 */
    private final HttpHeaders downstreamHeaders;

    /** 本次调用唯一标识，用于生命周期事件、取消注册与日志关联。 */
    private final String requestId;

    /**
     * 响应侧需要知道的请求侧事实。直连时为 null（没有去程翻译就没有它）。
     *
     * <p><strong>当前主干上无人读它</strong>（它属响应侧，还没接进主干）。
     * 按「允许冗余」照样携带，读取方出现时不必再动传递链。
     */
    private final TranslationContext translationContext;

    /**
     * 已执行的步骤。可变：步骤随推进逐个登记自己。
     *
     * <p>被跳过的步骤<strong>没有机会登记自己</strong>（它压根没执行），
     * 因此登记只能由编排层代劳 —— 这也说明它天然是请求级事实。
     */
    private final Set<PipelineStep> completedSteps = EnumSet.noneOf(PipelineStep.class);

    private RequestPipelineContext(Map<String, Object> body, String model, WireProtocol downstreamProtocol,
                                   WireProtocol upstreamProtocol, ProviderRuntimeConfiguration provider,
                                   HttpHeaders downstreamHeaders, String requestId,
                                   TranslationContext translationContext) {
        this.body = body;
        this.model = model;
        this.downstreamProtocol = downstreamProtocol;
        this.upstreamProtocol = upstreamProtocol;
        this.provider = provider;
        this.downstreamHeaders = downstreamHeaders;
        this.requestId = requestId;
        this.translationContext = translationContext;
        // bodyProtocol 初值取 upstreamProtocol：执行器拿到的 body 已经是上游形态
        // （翻译在编排层完成）。理由与写错的代价见类注释。
        this.bodyProtocol = upstreamProtocol;
    }

    /**
     * 在应用服务层的组装期创建一个上下文。
     *
     * <p>创建点是「已经知道路由与调度结论、但还没发请求」的那一刻 ——
     * 因为本类的大多数字段正是那两个结论的产物。
     *
     * @param body               原始请求体（尚未经过任何阶段改写）
     * @param model              本次调用用于上游请求的模型名
     * @param downstreamProtocol 下游协议
     * @param upstreamProtocol   上游协议
     * @param translationContext 去程翻译产出的上下文；直连传 null
     */
    public static RequestPipelineContext of(Map<String, Object> body,
                                            String model,
                                            WireProtocol downstreamProtocol,
                                            WireProtocol upstreamProtocol,
                                            ProviderRuntimeConfiguration provider,
                                            HttpHeaders downstreamHeaders,
                                            String requestId,
                                            TranslationContext translationContext) {
        return new RequestPipelineContext(body, model, downstreamProtocol, upstreamProtocol, provider,
                downstreamHeaders, requestId, translationContext);
    }

    /**
     * 组装期创建一个<strong>直连</strong>上下文：两侧同协议、无翻译、无登记步骤。
     *
     * <p>直连是最常见的路径（三条线路各有一个直连分支），因此给它一个具名工厂，
     * 让「这不是翻译路线」在调用点就看得见 —— 而不是写成
     * {@code of(body, p, p, …)} 那样靠两个参数恰好相同来表达。
     *
     * <p>它与 3.3b-1 那个过渡辅助（已随旧重载一起退役）在判据上完全等价：
     * {@code translationNeeded()} 为 false → 空响应拦截照常介入。
     * 但两者性质不同 —— 那个是「没有 ctx 时凑一个」，本方法供**组装层显式调用**。
     *
     * @param body             原始请求体
     * @param model            本次调用用于上游请求的模型名
     * @param protocol         两侧共用的协议
     * @param translationContext 直连没有去程翻译，通常传 null
     */
    public static RequestPipelineContext direct(Map<String, Object> body,
                                                String model,
                                                WireProtocol protocol,
                                                ProviderRuntimeConfiguration provider,
                                                HttpHeaders downstreamHeaders,
                                                String requestId,
                                                TranslationContext translationContext) {
        return new RequestPipelineContext(body, model, protocol, protocol, provider,
                downstreamHeaders, requestId, translationContext);
    }

    /** 当前请求体。 */
    public Map<String, Object> body() {
        return body;
    }

    /** 本次调用用于上游请求的模型名。 */
    public String model() {
        return model;
    }

    /**
     * 替换当前请求体，<strong>同时更新 {@link #bodyProtocol}</strong>。
     *
     * <p>两件事必须一起做：只换 body 会让 {@code bodyProtocol} 撒谎，
     * 后续阶段据此查表就会挑到按旧协议写的实现 —— 而「挑错实现」通常不报错，
     * 只是那个实现往 body 里写的字段名不对。
     *
     * @param body         新请求体
     * @param bodyProtocol 新请求体所属的协议
     */
    public void replaceBody(Map<String, Object> body, WireProtocol bodyProtocol) {
        this.body = body;
        this.bodyProtocol = bodyProtocol;
    }

    /** 当前 body 是哪种协议的形态 —— 阶段查表的键。 */
    public WireProtocol bodyProtocol() {
        return bodyProtocol;
    }

    /** 下游使用的协议。 */
    public WireProtocol downstreamProtocol() {
        return downstreamProtocol;
    }

    /** 实际对上游使用的协议。 */
    public WireProtocol upstreamProtocol() {
        return upstreamProtocol;
    }

    /** 本次调用解析出的供应商运行时配置。 */
    public ProviderRuntimeConfiguration provider() {
        return provider;
    }

    /** 下游请求头。 */
    public HttpHeaders downstreamHeaders() {
        return downstreamHeaders;
    }

    /** 本次调用唯一标识。 */
    public String requestId() {
        return requestId;
    }

    /** 响应侧需要知道的请求侧事实；直连时为 null，且当前主干上无人读它。 */
    public TranslationContext translationContext() {
        return translationContext;
    }

    // ==================== 步骤登记（可变） ====================

    /**
     * 登记一个<strong>已执行</strong>的步骤。
     *
     * <p>这是 3.3b-1 从 {@code PipelineExecution} 搬来的入口 ——
     * 那个类原本用不可变的 {@code withCompleted()} 返回新实例，
     * 但它的持有者（本类）已经是可变的，再套一层不可变只是多一次拷贝。
     */
    public void markCompleted(PipelineStep step) {
        completedSteps.add(step);
    }

    /** 该步骤是否登记为已执行。 */
    public boolean hasCompleted(PipelineStep step) {
        return completedSteps.contains(step);
    }

    /** 已执行的步骤集合（只读）。 */
    public Set<PipelineStep> completedSteps() {
        return Collections.unmodifiableSet(completedSteps);
    }

    // ==================== 派生判据 ====================

    /**
     * 两侧协议是否不同，即本次调用是否需要翻译组件介入。
     *
     * <p>协议在本类里必定非空（创建时就要给出），因此这里不处理「未知」态 ——
     * 那个态随 {@code PipelineExecution.empty()} 的退役一起消失了。
     */
    public boolean translationNeeded() {
        return downstreamProtocol != upstreamProtocol;
    }

    /**
     * 空响应拦截是否应当介入。
     *
     * <h2>判据：回程翻译未执行 → 跳过</h2>
     * 「需要翻译，但回程没接」这个组合就是<strong>开发者的半轮实现态</strong>：
     * 去程已接（请求发得出去、上游能理解），回程还没接。
     *
     * <p>此时交给下游的帧是<strong>上游协议的形态</strong>。拦截要判「这一轮有没有内容」，
     * 而它的判据属于本服务所服务的那个协议 —— 对着另一个协议的帧，
     * 判定结果不承载任何信息，只会把过程拖成「扣住 → 判否 → 重试 →
     * 白等完整轮预算（生产值约 62 秒、6 次上游调用）→ 才原样放行」。
     *
     * <p>开发者要的恰恰是那批帧本身：他正在对齐新写的去程翻译，
     * 需要看上游到底发了什么。帧本来就在手里，不该被重试机制压住一分钟。
     *
     * <h2>为何是「回程未执行」而非「两侧协议不同」</h2>
     * 两侧协议不同但<strong>回程已接</strong>时（C2M 现状），拦截照常生效是正确的 ——
     * 一条真正空的跨协议响应同样应该被识别并重试。
     * 判据因此落在「这个步骤有没有实现」，而不是「协议同不同」。
     *
     * <h2>三种情形各行其道</h2>
     * <table>
     *   <caption>拦截是否介入</caption>
     *   <tr><th>情形</th><th>登记</th><th>拦截</th></tr>
     *   <tr><td>直连</td><td>两侧同协议</td><td>介入（与重构前一致）</td></tr>
     *   <tr><td>C2M（全实现）</td><td>去程 + 回程均已登记</td><td>介入</td></tr>
     *   <tr><td>半轮实现</td><td>仅去程已登记</td><td><strong>跳过</strong>：整轮放行，不判空、不重试</td></tr>
     * </table>
     *
     * @return true 表示照常拦截；false 表示跳过本步骤
     */
    public boolean shouldApplyEmptyResponseGate() {
        // 直连：帧的形状与下游期待一致，拦截照常。
        if (!translationNeeded()) {
            return true;
        }
        // 跨协议：只有回程已接，拦截才判得有意义。
        return hasCompleted(PipelineStep.RESPONSE_TRANSLATION);
    }
}
