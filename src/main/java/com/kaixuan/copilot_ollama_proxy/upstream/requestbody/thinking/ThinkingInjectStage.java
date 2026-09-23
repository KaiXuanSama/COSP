package com.kaixuan.copilot_ollama_proxy.upstream.requestbody.thinking;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;

import java.util.Map;

/**
 * 思考注入的<strong>支线</strong> —— 把模型配置里的思考档位与方式写成出站字段。
 *
 * <h2>为什么它是支线而不是主干</h2>
 * 按方向文档 §2.1 的判据：<strong>协议差异是代码 → 支线</strong>。
 * 三种协议的出站字段<strong>各不相同</strong>：
 * <ul>
 *   <li>Chat —— 顶层 {@code reasoning_effort}，{@code off} 档另写 {@code thinking}；</li>
 *   <li>Responses —— 嵌套 {@code reasoning.effort}；</li>
 *   <li>MESSAGES —— 顶层 {@code output_config.effort}，另有独立的 {@code thinking} 对象维度。</li>
 * </ul>
 * 三者无法用同一份数据表达，因此是协议强关联步骤。
 * 方向文档把这条列为**「协议差异表达成代码」的现成样本**。
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
 * <h2>当前只有 MESSAGES 一个实现</h2>
 * Chat 与 Responses 的注入目前仍在各自执行器的 <code>applyReasoningEffort</code> 里
 * （它们分别写 {@code reasoning_effort} 与 {@code reasoning.effort}）。
 * 把它们也支线化是后续步骤的事；本步只搬 Anthropic 侧这一份。
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
