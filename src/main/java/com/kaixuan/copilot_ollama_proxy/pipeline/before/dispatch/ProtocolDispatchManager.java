package com.kaixuan.copilot_ollama_proxy.pipeline.before.dispatch;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.RequestProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate.TranslationRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 协议调度管理器 —— 决定一次调用走直连还是走翻译。
 *
 * <h2>职责边界</h2>
 * 只回答「用哪种上游协议、要不要翻译」，不负责选服务实例、不发请求、不碰报文。
 * 因此它是一个无 I/O 的纯决策组件，可以被纯单元测试穷举四种组合。
 *
 * <h2>调度规则</h2>
 * <ol>
 *   <li><strong>同名协议优先</strong> —— 供应商支持下游同名协议时一律直连。
 *       两种都支持时也走这条，因为直连不经翻译、无信息损耗。</li>
 *   <li>否则按 {@link #TRANSLATION_FALLBACK_ORDER} 挑一个<strong>供应商支持
 *       且去程翻译已实现</strong>的其它协议并标记需要翻译。</li>
 *   <li>供应商一种都不支持时抛 {@link NoSupportedProtocolException}；
 *       支持但候选方向的去程翻译都没实现时抛 {@link ProtocolTranslationNotSupportedException}。</li>
 * </ol>
 *
 * <h2>为何本类需要知道「哪些去程翻译已实现」</h2>
 * 这是规则 2 那句「按优先级往下找已实现的方向」的直接要求 —— 不知道谁实现了，
 * 就无法「跳过没实现的、降级到下一个」。翻译器<strong>自己声明方向</strong>
 * （{@link RequestProtocolTranslator#downstreamProtocol()} /
 * {@link RequestProtocolTranslator#upstreamProtocol()}），本类构造期把全部去程翻译器的
 * 方向收成一个 {@code Set<TranslationRoute>}，判定时查它。信息来源与
 * {@code TranslatorRegistry} <strong>同一批 Spring Bean</strong>，因此不会出现
 * 「本类说有、Registry 查无」的矛盾 —— 两者各取所需（本类只要方向判优先级，
 * Registry 要实例做翻译），是「各建各的索引」而非「复制逻辑」。
 *
 * <p>这与项目其它地方「能力靠声明、不靠猜」一致（{@code caps_tools} 声明能力、
 * 翻译器声明方向）。本类仍是<strong>无 I/O 的纯决策组件</strong>：构造期读一次声明，
 * 之后 {@link #dispatch} 不碰任何外部状态，可被纯单元测试穷举。
 *
 * <h2>这个「已实现」判定终会失效，但无害、不留 TODO</h2>
 * 3 个直连方向永不经翻译，真正受本判定管辖的是 <strong>6 个跨协议方向</strong>。
 * 这 6 个的去程翻译全部实现后，「跳过没实现的」这个分支变永真，判定退化成等价于
 * 单纯的 {@code supported} 检查 —— 届时它是死代码但无害（除非再引入新协议）。
 * 因此它是「6 个跨协议去程全实现之前的守卫」，不需要标 TODO 说将来移除。
 *
 * <p>路由与协议是<strong>两个独立维度</strong>：{@code ProviderRouteResolver} 先按模型名
 * 选出供应商（规则不变，无前缀模型仍要求唯一匹配），本类再判断协议怎么走。
 * 刻意不让协议参与候选集筛选 —— 否则同一个模型名在两个端点上可能路由到不同供应商，
 * 「路由只由模型名决定」这条可预测性就没了。
 */
@Service
public class ProtocolDispatchManager {

    private static final Logger log = LoggerFactory.getLogger(ProtocolDispatchManager.class);

    /**
     * 需要翻译时挑上游协议的先后顺序（规则 2）。
     *
     * <h2>为何不用 {@code WireProtocol.values()}</h2>
     * 两个协议时结果唯一（除下游协议外只剩一个候选），遍历 {@code values()} 看上去无害。
     * 但三个协议起就不同了：候选可能有两个，而「挑哪个」会变成受 {@link WireProtocol} 里
     * 常量<strong>书写顺序</strong>摆布的隐式行为 —— 而那个枚举的注释明确声明自己的顺序
     * 不承载语义。因此回退序必须独立声明。
     *
     * <h2>顺序依据：已实现优先，其次兼容面</h2>
     * <ol>
     *   <li>{@link WireProtocol#CHAT} —— 兼容面最广，且字段最少，往它翻的信息损耗最小；</li>
     *   <li>{@link WireProtocol#MESSAGES} —— 已有一个方向完整实现（C2M 去程 + M2C 回程）；</li>
     *   <li>{@link WireProtocol#RESPONSES} —— 排最后：C2R / R2C 均未实现，
     *       且它字段最富（{@code reasoning.encrypted_content} 在 Chat 里无处安放），
     *       往回翻必然丢信息。</li>
     * </ol>
     *
     * <p>这个顺序有实际差别：供应商勾了 MESSAGES + RESPONSES、下游打 chat 时，
     * 会挑 MESSAGES（已实现）而非 RESPONSES（未实现）。<strong>挑一个已实现的方向
     * 优于挑未实现的</strong> —— 后者会让一个本来能跑的调用抛「未实现」。
     *
     * <h2>前端的展示序必须与本顺序保持一致</h2>
     * 前端 {@code ALL_WIRE_PROTOCOLS} 决定协议地址行从上到下的排列，而<strong>用户会把
     * 那个排列读成优先级</strong>。它曾取「语义序」（两个 OpenAI 接口相邻，即
     * {@code CHAT, RESPONSES, MESSAGES}），读起来更顺，但会让人预期上面那个场景会选
     * RESPONSES —— 一条尚未实现的翻译路径。已改为与本常量同序。
     *
     * <p>因此这两处是<strong>同一个事实的两个表达</strong>，改一处必须改另一处。
     * 与下面那些顺序不同，它们本就该分叉。
     *
     * <h2>其余三个顺序互不相同，不要「统一」</h2>
     * <ul>
     *   <li><b>落库 JSON 元素序</b>（{@code ProviderAdminService} 的 {@code TreeSet}）——
     *       字母序，与 {@code schema.sql} 默认值一致，便于直接查库对比；</li>
     *   <li><b>拉取模型的线路优先级</b>（前端 {@code MODEL_PULL_PRIORITY}）—— 按请求头相似度
     *       （{@code CHAT, RESPONSES, MESSAGES}），因为拉取不经翻译，只差一个
     *       {@code anthropic-version} 头；</li>
     *   <li><b>枚举声明序</b>（{@link WireProtocol}）—— <strong>不承载语义</strong>，仅为可读性。</li>
     * </ul>
     *
     * <p>落库序与本顺序当前<strong>数值上相同</strong>（都是 CHAT, MESSAGES, RESPONSES），
     * 但理由完全无关：前者是字母序的巧合，后者是实现状态的排序。一旦某个方向被实现，
     * 后者就该变而前者不该变。<strong>不要把它们合并成一个常量。</strong>
     */
    // TODO(待实现) 协议流转编排：让用户自行指定「下游协议 → 上游协议」的映射，
    //  粒度到供应商与模型，取代本常量这个全局固定顺序。
    //  全局顺序无法覆盖真实场景 —— 例如某供应商不支持 CHAT、只支持 MESSAGES 与 RESPONSES，
    //  而它名下模型 1 只支持 RESPONSES、模型 2 只支持 MESSAGES：本常量对这两个模型
    //  只能给出同一个答案（MESSAGES），于是模型 1 必然被送错线路。
    //  当前这个顺序是「在没有编排能力时把错误率压到最低」的过渡方案，不是目标形态。
    //  编排能力将在 C2R 翻译落地之前规划与实现；在那之前直连场景不受影响 ——
    //  规则 1（同名协议优先直连）根本不读这个顺序，而三条线路默认全勾时绝大多数
    //  调用都走规则 1。
    static final List<WireProtocol> TRANSLATION_FALLBACK_ORDER =
            List.of(WireProtocol.CHAT, WireProtocol.MESSAGES, WireProtocol.RESPONSES);

    /**
     * 已实现的去程翻译方向集合 —— 构造期从所有去程翻译器的声明收集而来。
     *
     * <p>只收<strong>去程</strong>（请求翻译）方向，不看回程：回程缺失是「先写去程、
     * 用真实上游验证请求是否被接受」的正常中间态（见 {@code BeforeSend.translateStep} 与
     * {@code TranslatorRegistry.findResponseTranslator} 的注释），不该让调度提前拒绝。
     * 「能不能把请求发成上游能懂的形态」只由去程决定。
     *
     * <p>用 {@link TranslationRoute}（{@code (下游, 上游)} 对）作元素：与
     * {@code TranslatorRegistry} 的键类型一致，判定 {@code (下游, 候选上游)} 有没有去程
     * 翻译器时直接构造一个 {@code TranslationRoute} 查表即可。
     */
    private final Set<TranslationRoute> implementedRequestRoutes;

    /**
     * 由 Spring 集合注入构造。
     *
     * <p>收集容器里全部去程翻译器实现，读它们<strong>自己声明</strong>的方向
     * （{@link RequestProtocolTranslator#downstreamProtocol()} /
     * {@link RequestProtocolTranslator#upstreamProtocol()}）建成方向集。空 List 合法
     * （一个去程翻译器都没有），那样规则 2 会对任何跨协议请求都找不到已实现候选、抛
     * {@link ProtocolTranslationNotSupportedException} —— 与「翻译能力尚未落地」的事实一致。
     *
     * <p>与 {@code TranslatorRegistry} 吃<strong>同一批 Bean</strong>，因此两者的方向视图
     * 天然一致：本类说「(CHAT, MESSAGES) 有去程」时，Registry 必能查到那个翻译器实例。
     *
     * @param requestTranslators 所有去程翻译器实现（Spring 收集）
     */
    public ProtocolDispatchManager(List<RequestProtocolTranslator> requestTranslators) {
        Set<TranslationRoute> routes = new HashSet<>();
        for (RequestProtocolTranslator translator : requestTranslators) {
            routes.add(new TranslationRoute(translator.downstreamProtocol(), translator.upstreamProtocol()));
        }
        this.implementedRequestRoutes = Set.copyOf(routes);
    }

    /**
     * 为一次调用决定上游协议与是否需要翻译。
     *
     * @param downstreamProtocol 下游使用的协议（由它打的端点决定）
     * @param provider           已由路由解析器选出的目标供应商
     * @return 调度结论
     * @throws NoSupportedProtocolException 供应商未声明支持任何协议
     * @throws ProtocolTranslationNotSupportedException 供应商支持跨协议，但候选方向的去程翻译都未实现
     */
    public ProtocolDispatchDecision dispatch(WireProtocol downstreamProtocol,
                                             ProviderRuntimeConfiguration provider) {
        Set<WireProtocol> supported = ProviderProtocolSupport.of(provider);

        // 规则 1：同名协议直连。两种都支持时也走这里 —— 直连无翻译损耗，恒优于绕一圈。
        if (supported.contains(downstreamProtocol)) {
            return ProtocolDispatchDecision.direct(downstreamProtocol);
        }

        // 规则 3（提前判）：一种协议都没勾。用户把协议全部取消勾选就会走到这里 ——
        // 与「勾了但翻译没实现」是两回事，可操作动作也不同（前者「至少勾一个」、
        // 后者「换供应商或等实现」），故先分出来，用各自的异常类型。
        // 用独立异常类型（而非裸 IllegalStateException）是为了让控制器能精确识别并给
        // 400 + 可操作消息；直接判 IllegalStateException 会把任何库抛的同类异常
        // 一并译成「协议没配」。
        if (supported.isEmpty()) {
            throw new NoSupportedProtocolException(provider.providerKey());
        }

        // 规则 2：下游协议不被支持，按优先级挑一个「供应商支持 且 去程翻译已实现」的
        // 其它协议并标记需要翻译。
        //
        // 判据是「supported ∩ 已实现去程」而非仅 supported：这正是「按优先级往下找已实现
        // 方向」的落点 —— 勾了但没实现的方向要被跳过，降级到下一个候选。
        //   下游 CHAT + 上游 MESSAGES：C2M 去程已实现，命中；
        //   其余方向：去程翻译未实现，跳过（届时若无其它候选则落到下面报错）。
        // 候选顺序取 TRANSLATION_FALLBACK_ORDER 而非 values()，理由见那个常量的注释。
        // 翻译器一律套在上游服务外侧（装饰器），因而在 retryWhen 之外 ——
        // 空响应判定与落库看到的必须是上游原生形态。
        // 契约见 docs/PROTOCOL_TRANSLATION_CONTRACT.md（请求侧）与
        // docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md（响应侧）。
        for (WireProtocol candidate : TRANSLATION_FALLBACK_ORDER) {
            if (supported.contains(candidate)
                    && implementedRequestRoutes.contains(new TranslationRoute(downstreamProtocol, candidate))) {
                log.info("供应商 {} 不支持下游协议 {}，改用 {} 并需要翻译",
                        provider.providerKey(), downstreamProtocol, candidate);
                return ProtocolDispatchDecision.translated(downstreamProtocol, candidate);
            }
        }

        // 规则 2 的失败态：供应商勾了跨协议，但候选方向的去程翻译一个都没实现。
        // 报错点从旧的 translateStep 前移到此 —— 因为「跳过没实现的」这个决定就发生在这里，
        // 由本类报出比让调用方选中一个再撞墙更早、消息也更准（能说「到 X 的翻译均未实现」）。
        // 抛第一个候选作为代表：优先级最高的那个是用户最可能期待的方向。
        WireProtocol representative = TRANSLATION_FALLBACK_ORDER.stream()
                .filter(supported::contains)
                .findFirst()
                .orElseThrow(() -> new NoSupportedProtocolException(provider.providerKey()));
        throw new ProtocolTranslationNotSupportedException(
                provider.providerKey(), downstreamProtocol, representative);
    }
}
