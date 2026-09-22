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
 * <h2>它与 {@link RequestPipelineContext} 的分工</h2>
 * ctx 是「随步骤被逐步填充的状态池」；本类型是<strong>前奏这一步的返回值</strong> ——
 * 它存在的意义是让「前奏做完了」在类型上看得见。
 *
 * <p><strong>3.4c-1 之后两者的关系已定型</strong>：ctx 在端点创建（只含下游侧事实），
 * 主干跑前奏拿到本类型，随即由 {@code ctx.applyRouting(...)} 把这两个值<strong>回填进 ctx</strong>。
 * 因此本类型不「被 ctx 吸收」—— 它是前奏的<strong>送出口</strong>，而 ctx 是归宿；
 * 两者一个负责「交出来」、一个负责「存下去」，分工不变。
 *
 * <p>（本段早先写「等 3.4d-2 把 ctx 创建上移到端点时，这层包装会被 ctx 吸收」——
 * 那个预测是错的：ctx 确实上移了，但前奏仍需一个返回值把结论交出来。）
 *
 * @param route    解析出的供应商与目标模型
 * @param decision 协议调度结论（直连 / 翻译、上游协议）
 */
public record PipelinePreamble(ResolvedProviderRoute route, ProtocolDispatchDecision decision) {
}
