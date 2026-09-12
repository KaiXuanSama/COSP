package com.kaixuan.copilot_ollama_proxy.application.protocol;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 *   <li>否则挑供应商支持的其它协议并标记需要翻译。</li>
 *   <li>供应商一种都不支持时抛 {@link NoSupportedProtocolException}。</li>
 * </ol>
 *
 * <h2>三条规则现在都可达</h2>
 * V8.8 把协议支持落库之后本类不再是纯接缝：规则 2 由「只勾了一种协议、下游打另一个端点」
 * 触发，规则 3 由显式空集合触发。规则 2 的各个方向<strong>处境不同</strong> ——
 * 下游 Chat + 上游 Messages（C2M 去程 + M2C 回程）已实现并实测，
 * 其余方向仍由调用方抛 {@link ProtocolTranslationNotSupportedException}。
 * 本类不区分这个差异：它只回答「要不要翻译」，谁有实现是调用方的事。
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
     * 为一次调用决定上游协议与是否需要翻译。
     *
     * @param downstreamProtocol 下游使用的协议（由它打的端点决定）
     * @param provider           已由路由解析器选出的目标供应商
     * @return 调度结论
     * @throws NoSupportedProtocolException 供应商未声明支持任何协议
     */
    public ProtocolDispatchDecision dispatch(WireProtocol downstreamProtocol,
                                             ProviderRuntimeConfiguration provider) {
        Set<WireProtocol> supported = ProviderProtocolSupport.of(provider);

        // 规则 1：同名协议直连。两种都支持时也走这里 —— 直连无翻译损耗，恒优于绕一圈。
        if (supported.contains(downstreamProtocol)) {
            return ProtocolDispatchDecision.direct(downstreamProtocol);
        }

        // 规则 2：下游协议不被支持，改用供应商支持的其它协议并标记需要翻译。
        //
        // 本类只给结论，不判断该组合有没有实现 —— 那是调用方的事，且各方向状态不同：
        //   下游 CHAT + 上游 MESSAGES：ChatCompletionService 已挂 C2M 去程 + M2C 回程；
        //   其余方向：各应用服务抛 ProtocolTranslationNotSupportedException。
        // 候选顺序取 TRANSLATION_FALLBACK_ORDER 而非 values()，理由见那个常量的注释。
        // 翻译器一律套在上游服务外侧（装饰器），因而在 retryWhen 之外 ——
        // 空响应判定与落库看到的必须是上游原生形态。
        // 契约见 docs/PROTOCOL_TRANSLATION_CONTRACT.md（请求侧）与
        // docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md（响应侧）。
        for (WireProtocol candidate : TRANSLATION_FALLBACK_ORDER) {
            if (supported.contains(candidate)) {
                log.info("供应商 {} 不支持下游协议 {}，改用 {} 并需要翻译",
                        provider.providerKey(), downstreamProtocol, candidate);
                return ProtocolDispatchDecision.translated(downstreamProtocol, candidate);
            }
        }

        // 规则 3：一种都不支持。用户把协议全部取消勾选就会走到这里 ——
        // 要能明确报出来而不是让调用方拿到 null。
        // 用独立异常类型（而非裸 IllegalStateException）是为了让控制器能精确识别并给
        // 400 + 可操作消息；直接判 IllegalStateException 会把任何库抛的同类异常
        // 一并译成「协议没配」。
        throw new NoSupportedProtocolException(provider.providerKey());
    }
}
