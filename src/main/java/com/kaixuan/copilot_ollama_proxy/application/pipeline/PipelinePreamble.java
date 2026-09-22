package com.kaixuan.copilot_ollama_proxy.application.pipeline;

import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolDispatchDecision;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;

/**
 * 主干前奏的产出 —— 两个「本次请求已经确定下来」的结论。
 *
 * <h2>为何单独成类型，而不是两个参数穿线</h2>
 * 这两个值在主干上<strong>总是成对出现、成对传递</strong>（「跟谁说话」与
 * 「用哪种协议说」），后续所有步骤都要它们。让前奏把它们当一个值交出来，
 * 「前奏做完了」这件事在类型上就看得见 —— 而不是靠调用方记得按顺序声明两个变量。
 *
 * <p>与方向文档 §2.3 的取向一致：<strong>链接的是类型，不是类</strong>。
 *
 * <h2>它不属于 {@link RequestPipelineContext}</h2>
 * ctx 是「随步骤被逐步填充的状态池」，而这两个值只有前奏之后才知道 ——
 * 按 3.4a/b 的边界，ctx 仍在端点服务侧创建，故此处不合并。
 * 等 3.4d-2 把 ctx 创建上移到端点、主干回填时，这层包装会被 ctx 吸收
 * （见 plan_ Step 3.4「实施时发现的依赖」）。
 *
 * @param route    解析出的供应商与目标模型
 * @param decision 协议调度结论（直连 / 翻译、上游协议）
 */
public record PipelinePreamble(ResolvedProviderRoute route, ProtocolDispatchDecision decision) {
}
