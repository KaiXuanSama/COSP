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
 *   <li>供应商一种都不支持时抛异常（当前的乐观假设下不可达，但规则要完备）。</li>
 * </ol>
 *
 * <h2>为何在无事可调的阶段就建这个类</h2>
 * 第一阶段所有供应商都被乐观地认为支持两种协议，规则 1 恒成立，本类的输出永远是直连 ——
 * 删掉它功能不变。留着的理由是接缝：若此刻不把决策点独立出来，
 * 届时加翻译就会在 {@code ChatCompletionService} 与控制器里长出协议分支，
 * 两条链各自分叉之后再往中间塞一层，改动面远大于现在建好。
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
     * @throws IllegalStateException 供应商未声明支持任何协议
     */
    public ProtocolDispatchDecision dispatch(WireProtocol downstreamProtocol,
                                             ProviderRuntimeConfiguration provider) {
        Set<WireProtocol> supported = ProviderProtocolSupport.of(provider);

        // 规则 1：同名协议直连。两种都支持时也走这里 —— 直连无翻译损耗，恒优于绕一圈。
        if (supported.contains(downstreamProtocol)) {
            return ProtocolDispatchDecision.direct(downstreamProtocol);
        }

        // 规则 2：下游协议不被支持，改用供应商支持的其它协议并标记需要翻译。
        // V8.8 协议支持落库后本分支可达：用户只勾了 OpenAI 而下游打 /v1/messages 就会走到这里。
        // TODO 调用方（ChatCompletionService 等）收到 translationNeeded=true 时抛
        //  ProtocolTranslationNotSupportedException；待 ProtocolTranslator 有实现后，
        //  改为按本结论挑选对应翻译器并把它套在上游服务外侧（装饰器，不进重试内侧）。
        //  翻译实现不必从零推导：cc switch / sub2api / new api 等开源项目已有成熟的帧映射方案。
        for (WireProtocol candidate : WireProtocol.values()) {
            if (supported.contains(candidate)) {
                log.info("供应商 {} 不支持下游协议 {}，改用 {} 并需要翻译",
                        provider.providerKey(), downstreamProtocol, candidate);
                return ProtocolDispatchDecision.translated(downstreamProtocol, candidate);
            }
        }

        // 规则 3：一种都不支持。乐观假设下不可达，但不留静默失败的口子 ——
        // 字段落库后若被配成空集合，这里要能明确报出来而不是让调用方拿到 null。
        throw new IllegalStateException(
                "供应商 " + provider.providerKey() + " 未声明支持任何线路协议，无法调度");
    }
}
