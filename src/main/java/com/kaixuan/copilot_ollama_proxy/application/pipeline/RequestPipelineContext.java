package com.kaixuan.copilot_ollama_proxy.application.pipeline;

import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.springframework.http.HttpHeaders;

import java.util.Map;

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
 *       冗余字段是给后续步骤与后续功能预留的座位 —— 见下面两个已知的例子。
 *       读取方出现时不必再改本类的形状（也就不必再动所有传递点）。</li>
 * </ul>
 *
 * <h2>两个「现在还没人读」的字段（刻意保留）</h2>
 * <ul>
 *   <li>{@link #translationContext} —— 由去程翻译产出、供<strong>响应侧</strong>使用。
 *       当前只在翻译路线下非空，且主干上无人读它（响应侧还没接进来）。</li>
 *   <li>{@link #execution} —— 目前只有空响应拦截读它
 *       （判断半轮实现态是否要跳过拦截）。</li>
 * </ul>
 * 留它们的价值是形状稳定：读的人出现时不必再动传递链。
 *
 * <h2>{@code bodyProtocol} 当前恒等于 {@code upstreamProtocol}</h2>
 * 它记的是「<strong>当前 {@link #body} 是哪种协议的形态</strong>」，并且是阶段查表的键。
 *
 * <p><strong>它现在恒等于 {@link #upstreamProtocol}，因为翻译发生在应用服务层</strong>
 * （编排器早已把 body 换成上游形态，再交给执行器与主干）。
 * 等 3.4 把 translate 移进主干之后，它才会变成「初值 = downstream、被 translate 改写」。
 *
 * <p>这不是设计选择而是<strong>当前事实</strong>，且写错会很安静：
 * 查表用错的键，症状是「某个阶段没执行」而没有任何报错。
 *
 * <h2>与流级状态的分界（不要混）</h2>
 * 本类装的是<strong>请求级</strong>事实 —— 整条主干共用，重试<strong>不</strong>重置。
 * 而 {@code contentEmitted} / {@code reasoningBuffer} / {@code chunkId} 是<strong>流级</strong>
 * 累积量（重试一次就得重置），它们<strong>不属于本类</strong>，由流算子的闭包持有、
 * 以方法参数穿线传递（见方向文档 §2.4「两级状态要分清」）。
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
     * @see RequestPipelineContext 类注释「两个『现在还没人读』的字段」
     */
    private final TranslationContext translationContext;

    /** 管道执行登记：哪些步骤真的执行了。 */
    private PipelineExecution execution;

    private RequestPipelineContext(Map<String, Object> body, WireProtocol bodyProtocol,
                                   WireProtocol downstreamProtocol, WireProtocol upstreamProtocol,
                                   ProviderRuntimeConfiguration provider, HttpHeaders downstreamHeaders,
                                   String requestId, TranslationContext translationContext,
                                   PipelineExecution execution) {
        this.body = body;
        this.bodyProtocol = bodyProtocol;
        this.downstreamProtocol = downstreamProtocol;
        this.upstreamProtocol = upstreamProtocol;
        this.provider = provider;
        this.downstreamHeaders = downstreamHeaders;
        this.requestId = requestId;
        this.translationContext = translationContext;
        this.execution = execution;
    }

    /**
     * 在应用服务层的组装期创建一个上下文。
     *
     * <p>创建点是「已经知道路由与调度结论、但还没发请求」的那一刻 ——
     * 因为本类的大多数字段正是那两个结论的产物。
     *
     * @param body               原始请求体（尚未经过任何阶段改写）
     * @param downstreamProtocol 下游协议
     * @param upstreamProtocol   上游协议
     * @param translationContext 去程翻译产出的上下文；直连传 null
     */
    public static RequestPipelineContext of(Map<String, Object> body,
                                            WireProtocol downstreamProtocol,
                                            WireProtocol upstreamProtocol,
                                            ProviderRuntimeConfiguration provider,
                                            HttpHeaders downstreamHeaders,
                                            String requestId,
                                            TranslationContext translationContext) {
        // bodyProtocol 初值取 upstreamProtocol：执行器拿到的 body 已经是上游形态
        // （翻译在编排层完成）。理由与写错的代价见类注释。
        //
        // 执行登记一开始就把两侧协议登记进去：那是本类已经知道的事实，
        // 而 PipelineExecution 的空登记**不携带协议**，会让它的
        // shouldApplyEmptyResponseGate() 把一次跨协议调用误判成直连
        // （跨协议 + 回程未登记的半轮态本该跳过拦截，却被当成直连而照常拦截）。
        PipelineExecution execution = PipelineExecution.of(downstreamProtocol, upstreamProtocol);
        return new RequestPipelineContext(body, upstreamProtocol, downstreamProtocol, upstreamProtocol,
                provider, downstreamHeaders, requestId, translationContext, execution);
    }

    /** 当前请求体。 */
    public Map<String, Object> body() {
        return body;
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

    /** 响应侧需要知道的请求侧事实；直连时为 null。 */
    public TranslationContext translationContext() {
        return translationContext;
    }

    /** 管道执行登记。 */
    public PipelineExecution execution() {
        return execution;
    }

    /** 替换执行登记（步骤声明自己执行完时使用）。 */
    public void updateExecution(PipelineExecution execution) {
        this.execution = execution;
    }
}
