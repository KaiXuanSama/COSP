package com.kaixuan.copilot_ollama_proxy.pipeline;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.RequestProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.ResponseProtocolTranslator;
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
 *       {@code body} 是 {@code Map}，原地改它本来就是主干的正常动作。
 *       3.4c-1 起 {@code model} / {@code upstreamProtocol} / {@code provider} /
 *       {@code translationContext} <strong>也可变</strong>：端点建 ctx 时它们还未知，
 *       由主干分步回填（{@link #applyRouting} / {@link #applyTranslation}）。</li>
 *   <li><strong>允许冗余</strong>：可以携带<em>当前无人读取</em>的字段。
 *       冗余字段是给后续步骤与后续功能预留的座位，读取方出现时不必再改本类的形状。
 *       例如 {@code translationContext} 在 3.4c-2 之前一直无人读 ——
 *       它作为「响应侧的座位」存在了几个版本，直到回程翻译接进主干才被用上。</li>
 * </ul>
 *
 * <h2>它装「数据」，也装「本次选中的策略」（阶段 4 刀 3 起）</h2>
 * 原则上本类装的是<strong>数据</strong>（协议、body、头）。阶段 4 块化后，它另装两个
 * <strong>策略对象</strong>：{@link #requestTranslator} / {@link #responseTranslator} ——
 * 本次请求按协议对选中的去程 / 回程翻译器。这不违反「装数据」的初衷：
 * 翻译器是<strong>无状态单例</strong>（{@code @Component}），把它的引用放进 per-request 的 ctx
 * 与放一个「函数指针」等价（C 里按菜单底标载入不同函数指针再调用，是同一手法）——
 * 没有状态泄漏。判据是：<strong>「本次请求选中的策略」进 ctx，「谁都能用的工具」（注册表、装配器）
 * 留在块类里当依赖</strong>。
 *
 * <p>为何回程翻译器<strong>必须</strong>进 ctx：它在<strong>发送后块</strong>（{@code AfterSend}）
 * 被调用，跨了「发送前块选中它」与「发送后块用它」的边界 —— 跨边界的策略只能经 ctx 传。
 * 去程翻译器在发送前块当场选、当场用，严格说不必进 ctx；一并存入是为了让二者作为
 * 「本次请求的翻译对」成为一个对称的单一概念（代价仅一个引用字段）。
 *
 * <p><strong>出站头与地址同理跨块（阶段 4 刀 3 B）</strong>：{@link #outboundHeaders} /
 * {@link #outboundBaseUrl} 由发送前块的出站装配步骤算好，发送后块 {@code buildWebClient}
 * 直接铺进 WebClient。它们是<strong>数据</strong>（装配的产物）而非策略，与 body 同类 ——
 * 「这次请求实际发什么头、发去哪」是本次请求特有的事实，故进 ctx。
 *
 * <h2>两个创建入口（不要用错）</h2>
 * <table>
 *   <caption>工厂方法</caption>
 *   <tr><th>入口</th><th>用途</th><th>{@code bodyProtocol} 初值</th></tr>
 *   <tr><td>{@link #forEndpoint}</td><td><strong>唯一生产入口</strong>：端点建 ctx，
 *       只填下游侧事实，路由与上游协议由主干回填</td><td>{@code downstream}</td></tr>
 *   <tr><td>{@link #of}</td><td><strong>完整形态</strong>：body 已是最终（上游）形态，
 *       供测试构造任意组合</td><td>{@code upstream}</td></tr>
 * </table>
 * 两者差别只在「翻译是否已发生」，故初值相反。生产路径只走前者。
 *
 * <h2>{@code bodyProtocol} 与两侧协议的分界</h2>
 * <ul>
 *   <li>{@link #downstreamProtocol} / {@link #upstreamProtocol} —— <strong>路由事实</strong>：
 *       「跟谁说话」；</li>
 *   <li>{@link #bodyProtocol} —— <strong>形态事实</strong>：{@code body} 长什么样，
 *       且是<strong>阶段查表的键</strong>。翻译是让两者分道扬镳的唯一动作。</li>
 * </ul>
 *
 * <p><strong>{@code bodyProtocol} 的初值 = {@code downstreamProtocol}</strong>（3.4c-1 起）：
 * 端点创建 ctx 时 body 还是下游形态，要等主干上的 translate 把它换成上游形态
 * （{@code replaceBody} 成对更新）。写错会很安静：查表用错的键，
 * 症状是「某个阶段没执行」而没有任何报错。
 *
 * <p>3.4c-1 之前它初值取 {@code upstreamProtocol} —— 那时翻译在应用服务层完成，
 * 执行器拿到的 body 已是上游形态。改用下游作初值是「翻译进主干」的必然结果，
 * 不是口味变化。
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
     * <p>它是执行器入口需要的那个值（{@code route.model()}，已剥供应商前缀）。
     * <strong>写入分两步</strong>（3.4c-1 起）：端点创建时先放**请求模型名**
     * （可带 {@code [provider-key]} 前缀），主干解析后回填**目标模型名**。
     * 两步值在生产路径上通常相同，但不能合并 —— 解析结果只有主干才有。
     */
    private String model;

    /** 下游使用的协议（由它打的端点决定）。创建时即确定，不随阶段变。 */
    private final WireProtocol downstreamProtocol;

    /**
     * 本次调用是否为<strong>流式</strong>（下游请求体里的 {@code stream}）。
     *
     * <p>它是<strong>请求级事实</strong>：端点建 ctx 时就知道（控制器进的是流式还是非流式入口），
     * 且整条主干与执行器都要用（{@code RequestBodyAssembler} 写 {@code stream} 字段、
     * {@code OutboundRequestAssembler} 选 {@code Accept} 头、{@code saveUsage} 选 ttfb 口径……）。
     *
     * <p><strong>为何是一个显式字段，而不是去 body 里读 {@code stream}</strong>：
     * 读 body 会把「怎么执行」编码进「数据」—— 而那个字段是装配步骤
     * （{@code writeProtocolFields}）写的，两者一旦不一致就会静默走错路。
     * 与 {@link #downstreamProtocol} 同一性质：都是「这次请求是什么」而非「报文长什么样」。
     *
     * <p>3.5a 之前它以 {@code boolean stream} <strong>参数</strong>在三处穿线
     * （各执行器的 {@code prepareRequestBody} / {@code buildWebClient} / {@code saveUsage}）——
     * 提进本类就是把已经在传的东西从参数搬进状态池。
     */
    private final boolean stream;

    /**
     * 实际对上游使用的协议（由供应商配置与调度决定）。
     *
     * <p><strong>可变</strong>：端点创建 ctx 时还不知道它（要先 resolve + dispatch），
     * 由主干解析步骤回填（3.4c-1）。回填前它的值只是**临时占位**（取 downstream），
     * 因此回填前不得据此判断是否需翻译 —— 那时还没有结论可言。
     */
    private WireProtocol upstreamProtocol;

    /**
     * 本次调用解析出的供应商运行时配置。
     *
     * <p><strong>可变</strong>：与 {@link #upstreamProtocol} 同理，由主干解析步骤回填。
     * 回填前为 null。
     */
    private ProviderRuntimeConfiguration provider;

    /** 下游请求头，供出站头装配与鉴权头探测使用。 */
    private final HttpHeaders downstreamHeaders;

    /** 本次调用唯一标识，用于生命周期事件、取消注册与日志关联。 */
    private final String requestId;

    /**
     * 响应侧需要知道的请求侧事实。直连时为 null（没有去程翻译就没有它）。
     *
     * <p><strong>可变</strong>：翻译槽执行后回填（3.4c-1）。
     */
    private TranslationContext translationContext;

    /**
     * 本次请求选中的<strong>去程</strong>翻译器（下游 → 上游）。直连时为 null。
     *
     * <p><strong>可变</strong>：由发送前块的翻译步骤按协议对查表后写入（{@link #applyTranslators}）。
     * 它在发送前块当场用掉，存入 ctx 只为与回程翻译器成对（见类注释「装策略」段）。
     */
    private RequestProtocolTranslator requestTranslator;

    /**
     * 本次请求选中的<strong>回程</strong>翻译器（上游 → 下游）。直连或回程未实现时为 null。
     *
     * <p><strong>可变</strong>：由发送前块的翻译步骤按协议对查表后写入（{@link #applyTranslators}）。
     * 它在<strong>发送后块</strong>被读取（跨块边界），因此<strong>必须</strong>经 ctx 传递 ——
     * 这是本字段存在的根本理由（见类注释「装策略」段）。
     */
    private ResponseProtocolTranslator responseTranslator;

    /**
     * 装配好的<strong>出站请求头</strong> —— 发往上游那份 headers 的最终形态。
     *
     * <p><strong>可变</strong>：由发送前块的出站装配步骤写入（{@link #applyOutbound}）。
     * 它在<strong>发送后块</strong>被消费（{@code buildWebClient} 直接铺进 WebClient），
     * 跨了「发送前块装配」与「发送后块发送」的边界 —— 与翻译对同一性质，故经 ctx 传。
     *
     * <p>它是<strong>数据</strong>而非策略：三层装配（下游头透传 / 鉴权头再分配 / 请求头规则）
     * 加协议特定头（如 {@code anthropic-version}）的产物，回填前为 null。装配逻辑本身
     * 仍在无状态协作者里（{@code ProviderRequestHeaderService} + 出站支线），ctx 只存结果。
     */
    private HttpHeaders outboundHeaders;

    /**
     * 装配好的<strong>出站基础地址</strong> —— 已按协议选源（Chat 用 base_url、
     * Anthropic 用 anthropic_base_url、Responses 用 responses_base_url）并归一化。
     *
     * <p><strong>可变</strong>：与 {@link #outboundHeaders} 一同由出站装配步骤写入，
     * 发送后块 {@code buildWebClient} 直接用它当 {@code baseUrl}。回填前为 null。
     */
    private String outboundBaseUrl;

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
                                   TranslationContext translationContext, boolean stream) {
        this.body = body;
        this.model = model;
        this.downstreamProtocol = downstreamProtocol;
        this.upstreamProtocol = upstreamProtocol;
        this.provider = provider;
        this.downstreamHeaders = downstreamHeaders;
        this.requestId = requestId;
        this.translationContext = translationContext;
        this.stream = stream;
        // bodyProtocol 初值取 downstreamProtocol：端点建 ctx 时 body 还是下游形态，
        // 要等主干上的 translate 把它换成上游形态（replaceBody 成对更新）。
        // 理由与写错的代价见类注释。
        this.bodyProtocol = downstreamProtocol;
    }

    /**
     * <strong>端点侧</strong>创建一个上下文（3.4c-1 起的唯一生产入口）。
     *
     * <p>创建点是端点服务 —— 它只知道「下游打的是哪个端点、请求的模型名是什么」，
     * 而 <strong>不知道供应商与上游协议</strong>（那要等主干 resolve + dispatch）。
     * 因此这两个字段先留空（{@code upstreamProtocol} 暂取 downstream 占位、
     * {@code provider} 为 null），由主干解析步骤回填 —— 见 {@link #applyRouting}。
     *
     * <p>这与方向文档 §2.4 的「ctx 是本次调用的小型状态池」一致：
     * 它不是一次填满的常量包，而是随步骤逐步被填的状态。
     *
     * @param body               原始请求体（下游形态，尚未经过任何阶段改写）
     * @param requestedModel     客户端请求的模型名（可带 {@code [provider-key]} 前缀）
     * @param downstreamProtocol 下游协议（本端点服务的那个）
     * @param downstreamHeaders  下游请求头
     * @param requestId          本次调用唯一标识
     * @param stream             本次调用是否流式
     */
    public static RequestPipelineContext forEndpoint(Map<String, Object> body,
                                                     String requestedModel,
                                                     WireProtocol downstreamProtocol,
                                                     HttpHeaders downstreamHeaders,
                                                     String requestId,
                                                     boolean stream) {
        return new RequestPipelineContext(body, requestedModel, downstreamProtocol,
                downstreamProtocol, null, downstreamHeaders, requestId, null, stream);
    }

    /**
     * 主干解析步骤的<strong>回填</strong>：把「跟谁说话」与「用哪种协议说」写进 ctx。
     *
     * <p>它把这四件事一起写，因为它们**本就是一个结论的四个面**：
     * 路由结果（目标模型名 + 供应商）与调度结果（上游协议）。分开写会留下
     * 「模型名与供应商不同步」这类自相矛盾的中间态。
     *
     * <p>同时把 {@code bodyProtocol} 同步为回填前的形态事实 —— 此时 body 仍是**下游形态**，
     * 故它等于 {@code downstreamProtocol}（即初值，无需再改）。
     * 真正把它改成上游形态的是翻译槽的 {@link #replaceBody}。
     *
     * @param resolvedModel  已剥供应商前缀的目标模型名
     * @param provider       解析出的供应商运行时配置
     * @param upstreamProtocol 调度得出的上游协议
     */
    public void applyRouting(String resolvedModel, ProviderRuntimeConfiguration provider,
                             WireProtocol upstreamProtocol) {
        this.model = resolvedModel;
        this.provider = provider;
        this.upstreamProtocol = upstreamProtocol;
    }

    /**
     * 翻译槽的<strong>回填</strong>：把去程翻译产出的事实写进 ctx。
     *
     * <p>它做两件事，且必须一起做：
     * <ul>
     *   <li>把 body 换成上游形态（{@link #replaceBody} 同时改查表键）；</li>
     *   <li>记下响应侧需要的事实（{@link TranslationContext}）。</li>
     * </ul>
     * 分开写会留下「body 已是上游形态但回程不知道这个事实」的中间态。
     *
     * @param translatedBody    去程翻译产出的上游形态请求体
     * @param upstreamProtocol  上游协议（与 {@link #applyRouting} 给定的一致）
     * @param translationContext 去程翻译产出的响应侧事实
     */
    public void applyTranslation(Map<String, Object> translatedBody, WireProtocol upstreamProtocol,
                                 TranslationContext translationContext) {
        replaceBody(translatedBody, upstreamProtocol);
        this.translationContext = translationContext;
    }

    /**
     * 记下本次请求选中的<strong>翻译对</strong>（去程 + 回程）。
     *
     * <p>由发送前块的翻译步骤按协议对查表后调用。回程翻译器随后在<strong>发送后块</strong>
     * 被读取（跨块边界），故必须经 ctx 传 —— 这是本方法与两个字段存在的根本理由。
     * 去程一并存入只为对称（见类注释「装策略」段）。直连时两者皆传 null。
     *
     * @param requestTranslator  去程翻译器；直连传 null
     * @param responseTranslator 回程翻译器；直连或回程未实现传 null
     */
    public void applyTranslators(RequestProtocolTranslator requestTranslator,
                                 ResponseProtocolTranslator responseTranslator) {
        this.requestTranslator = requestTranslator;
        this.responseTranslator = responseTranslator;
    }

    /** 本次请求选中的去程翻译器；直连时为 null。 */
    public RequestProtocolTranslator requestTranslator() {
        return requestTranslator;
    }

    /** 本次请求选中的回程翻译器；直连或回程未实现时为 null。发送后块据此决定翻译还是透传。 */
    public ResponseProtocolTranslator responseTranslator() {
        return responseTranslator;
    }

    /**
     * 出站装配步骤的<strong>回填</strong>：把发往上游的最终请求头与基础地址写进 ctx。
     *
     * <p>由发送前块的出站装配步骤调用（阶段 4 刀 3 B）。二者一起写，因为它们同源 ——
     * 都是「这次请求实际怎么发给上游」的产物。发送后块 {@code buildWebClient} 随后直接消费，
     * 不再自己装配头或解析地址。
     *
     * @param outboundHeaders 三层装配 + 协议特定头后的最终出站请求头
     * @param outboundBaseUrl  已按协议选源并归一化的上游基础地址
     */
    public void applyOutbound(HttpHeaders outboundHeaders, String outboundBaseUrl) {
        this.outboundHeaders = outboundHeaders;
        this.outboundBaseUrl = outboundBaseUrl;
    }

    /** 装配好的出站请求头；发送后块直接铺进 WebClient。出站装配步骤执行前为 null。 */
    public HttpHeaders outboundHeaders() {
        return outboundHeaders;
    }

    /** 装配好的出站基础地址（已选源 + 归一化）；发送后块用作 baseUrl。装配前为 null。 */
    public String outboundBaseUrl() {
        return outboundBaseUrl;
    }

    /**
     * 创建一个<strong>完整形态</strong>的上下文 —— 路由与翻译都已就绪，
     * {@code body} 已是最终（上游）形态。
     *
     * <p><strong>它不是生产入口</strong>（生产走 {@link #forEndpoint} + {@link #applyRouting}
     * + {@link #applyTranslation}）。它服务的是「直接拿一个就绪的 ctx 去调执行器」的场合：
     * 单元测试与 {@code testing/PipelineContexts} 需要构造任意组合的 ctx
     * （半轮态、全实现、直连）来验证执行器的拦截与落库，而那些测试的 body
     * 本来就是 Anthropic / Responses 形态 —— 与 {@code upstreamProtocol} 一致。
     *
     * <p>因此本工厂把 {@link #bodyProtocol} 设为 {@code upstreamProtocol}，
     * 而不是构造器默认的 {@code downstreamProtocol}。两者的差别只在「翻译是否已发生」。
     *
     * @param body               请求体（<strong>已是最终形态</strong>）
     * @param model              用于上游请求的模型名
     * @param downstreamProtocol 下游协议
     * @param upstreamProtocol   上游协议
     * @param translationContext 去程翻译产出的事实；直连传 null
     * @param stream             本次调用是否流式
     */
    public static RequestPipelineContext of(Map<String, Object> body,
                                            String model,
                                            WireProtocol downstreamProtocol,
                                            WireProtocol upstreamProtocol,
                                            ProviderRuntimeConfiguration provider,
                                            HttpHeaders downstreamHeaders,
                                            String requestId,
                                            TranslationContext translationContext,
                                            boolean stream) {
        RequestPipelineContext ctx = new RequestPipelineContext(body, model, downstreamProtocol, upstreamProtocol,
                provider, downstreamHeaders, requestId, translationContext, stream);
        // 完整形态：body 已是上游形态，故查表键取 upstream（与构造器的端点默认相反）。
        ctx.bodyProtocol = upstreamProtocol;
        return ctx;
    }

    /** 当前请求体。 */
    public Map<String, Object> body() {
        return body;
    }

    /** 本次调用用于上游请求的模型名。 */
    public String model() {
        return model;
    }

    /** 本次调用是否流式 —— 请求级事实，供执行器与主干选机制。 */
    public boolean stream() {
        return stream;
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

    /**
     * 响应侧需要知道的请求侧事实；直连时为 null。
     *
     * <p>由翻译槽的 {@link #applyTranslation} 写入，由主干在调回程翻译时读取
     * （{@code includeUsage} 等）。3.4c-2 之前主干无人读它，故那时它只是「预留的座位」。
     */
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
     * <p><strong>只能在主干回填路由之后调用</strong>（{@link #applyRouting}）。
     * 端点刚建出的 ctx 里 {@code provider} 为 null，那时 {@code upstreamProtocol}
     * 只是占位值，据此判断会得到错误的答案且不会报错 —— 故这里显式拦住。
     * 这正是本项目最反复的失效形态：错误答案静默地看起来像正确答案。
     *
     * @throws IllegalStateException 路由尚未回填（在端点与主干之间误调）
     */
    public boolean translationNeeded() {
        if (provider == null) {
            throw new IllegalStateException(
                    "路由尚未回填，此时 upstreamProtocol 只是占位值 —— "
                            + "translationNeeded() 只能在主干 applyRouting 之后调用");
        }
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
