package com.kaixuan.copilot_ollama_proxy.application.protocol;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
 * 触发，规则 3 由显式空集合触发。规则 2 的两个方向<strong>处境不同</strong> ——
 * 下游 OpenAI + 上游 Anthropic（O2A 去程 + A2O 回程）已实现并实测，
 * 反方向仍由调用方抛 {@link ProtocolTranslationNotSupportedException}。
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
        // 本类只给结论，不判断该组合有没有实现 —— 那是调用方的事，且两个方向的状态不同：
        //   下游 OPENAI + 上游 ANTHROPIC：ChatCompletionService 已挂 O2A 去程 + A2O 回程；
        //   下游 ANTHROPIC + 上游 OPENAI：MessagesService 抛 ProtocolTranslationNotSupportedException。
        // 翻译器一律套在上游服务外侧（装饰器），因而在 retryWhen 之外 ——
        // 空响应判定与落库看到的必须是上游原生形态。
        // 契约见 docs/PROTOCOL_TRANSLATION_CONTRACT.md（请求侧）与
        // docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md（响应侧）。
        for (WireProtocol candidate : WireProtocol.values()) {
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
