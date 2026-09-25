package com.kaixuan.copilot_ollama_proxy.upstream.outbound;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 出站装配支线的查表 —— 按 {@link WireProtocol} 选协议特定的 {@link OutboundRequestStage}。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：<strong>注册表</strong>（接入点级） · 位置：{@code upstream/outbound/}
 * 步骤「出站请求装配」—— {@link OutboundRequestAssembler} 靠它按 {@code ctx.upstreamProtocol()}
 * 选中本次线路的实现。
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 *
 * <h2>未命中是<strong>报错</strong>，与请求体支线相反</h2>
 * 请求体支线未命中表示「这种协议没有这一步」（如 Chat 不抬升 system），是领域事实、可跳过。
 * 出站装配不同：<strong>每条上游线路都必须解析出一个地址</strong>才能发请求 —— 查不到实现
 * 意味着装配坏了（某协议的 {@code @Component} 没被扫到），而不是「这种协议不发请求」。
 * 因此本表用 {@link #require} 而非 {@code find...Optional}，与 {@code UpstreamExecutorRegistry}
 * 同一取向：装配问题当场报错，不静默走一条没有地址的死路。
 *
 * <h2>键由实现声明，同键冲突启动即失败</h2>
 * 与 {@code UpstreamExecutorRegistry} / {@code RequestBodyStageRegistry} 同一机制：
 * 键写在实现的 {@link OutboundRequestStage#protocol()} 里，同一协议两个实现视为配置错误、
 * 构造期即抛，不运行期随机择一。
 */
@Component
public class OutboundRequestStageRegistry {

    private final Map<WireProtocol, OutboundRequestStage> stages;

    /**
     * 由 Spring 集合注入构造。
     *
     * @param stages 容器里全部 {@link OutboundRequestStage} 实现（三条线路各一）
     */
    public OutboundRequestStageRegistry(List<OutboundRequestStage> stages) {
        Map<WireProtocol, OutboundRequestStage> byProtocol = new HashMap<>();
        for (OutboundRequestStage stage : stages) {
            OutboundRequestStage previous = byProtocol.put(stage.protocol(), stage);
            if (previous != null) {
                throw new IllegalStateException(
                        "协议 " + stage.protocol() + " 的出站装配支线有两个实现："
                                + previous.getClass().getName() + " 与 " + stage.getClass().getName()
                                + "。每个协议只能有一个实现");
            }
        }
        this.stages = Map.copyOf(byProtocol);
    }

    /**
     * 取本协议线路的出站装配实现；未命中即报错。
     *
     * @param protocol 本次调用的上游协议
     * @return 该协议的出站装配实现
     * @throws IllegalStateException 该协议没有出站装配实现（装配坏了，不是领域事实）
     */
    public OutboundRequestStage require(WireProtocol protocol) {
        OutboundRequestStage stage = stages.get(protocol);
        if (stage == null) {
            throw new IllegalStateException(
                    "协议 " + protocol + " 没有出站装配实现 —— 每条上游线路都必须能解析出地址，"
                            + "缺一个会让那条线路的请求发不出去（装配坏了，不是领域事实）");
        }
        return stage;
    }
}
