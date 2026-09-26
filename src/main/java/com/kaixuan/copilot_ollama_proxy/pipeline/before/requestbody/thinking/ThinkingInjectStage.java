package com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.thinking;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;

import java.util.Map;

/**
 * 思考注入的<strong>支线</strong> —— 把模型配置里的思考档位与方式写成出站字段。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：<strong>契约</strong>（支线） · 位置：{@code pipeline/before/requestbody/thinking/}
 * 步骤「思考注入」—— 深度与方式两维，作为一个整体施加
 * <p>完整步骤树见 {@code pipeline/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为什么它是支线而不是主干</h2>
 * 判据是：<strong>协议差异是代码 → 支线</strong>。
 * 三种协议的出站字段<strong>各不相同</strong>：
 * <ul>
 *   <li>Chat —— 顶层 {@code reasoning_effort}，{@code off} 档另写 {@code thinking}；</li>
 *   <li>Responses —— 嵌套 {@code reasoning.effort}；</li>
 *   <li>MESSAGES —— 顶层 {@code output_config.effort}，另有独立的 {@code thinking} 对象维度。</li>
 * </ul>
 * 三者无法用同一份数据表达，因此是协议强关联步骤，而不是主干上的一串 {@code if}。
 *
 * <h2>它是「一个支线」而非「两个」</h2>
 * Anthropic 侧有<strong>两个正交维度</strong>（深度与方式），但它们必须作为一个整体施加：
 * <strong>深度先、方式后</strong>，且深度写了 {@code thinking:{"type":"disabled"}} 时
 * <strong>跳过方式</strong>。拆成两个支线会让「谁先谁后、要不要跳过」变成跨支线的隐式契约 ——
 * 而那是同一条协议里的同一件事，应当由一个实现整体负责。
 *
 * <p>本支线还负责剥掉 {@code reasoning_effort} 兼容副本 —— 那个字段的生命周期
 * <strong>完全由本步骤支配</strong>（供其判定「下游已表态」），因此它是本步骤的
 * <strong>内部临时产物</strong>：独立成阶段反而让「谁该删它」变成跨阶段的隐式契约。
 *
 * <h2>三协议各一个实现</h2>
 * MESSAGES = {@code MessagesThinkingStage}（两维 + 剥兼容副本）、
 * CHAT = {@code ChatThinkingStage}（写 {@code reasoning_effort}；off 档写 {@code thinking:"disabled"}）、
 * RESPONSES = {@code ResponsesThinkingStage}（写 {@code reasoning.effort}）。
 * 后两者不需要 Anthropic 那套「深度先方式后、off 档跳过方式」编排 —— 那套约束只存在于
 * 两个正交维度之间，单字段协议一行就够。
 * <p>注：本接口的「剥兼容副本」职责只对 Anthropic 有意义 —— 那个副本是 C2M 翻译器留的。
 */
public interface ThinkingInjectStage {

    /** 本支线服务的协议 —— 查表键。 */
    WireProtocol protocol();

    /**
     * 就地把思考的两个维度写进请求体。
     *
     * @param body          当前请求体，会被原地修改
     * @param resolvedModel 已剥供应商前缀的真实模型名，用于查该模型的配置
     * @param provider      本次调用的供应商运行时配置
     */
    void apply(Map<String, Object> body, String resolvedModel, ProviderRuntimeConfiguration provider);
}
