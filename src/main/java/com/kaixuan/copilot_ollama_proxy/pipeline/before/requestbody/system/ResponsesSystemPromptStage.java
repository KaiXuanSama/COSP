package com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.system;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link SystemPromptNormalizeStage} 的 <strong>RESPONSES</strong> 实现 —— C2R 方向。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>包装</strong>（RESPONSES） · 位置：{@code pipeline/before/requestbody/system/}
 * 步骤「system 归一化」—— Responses 侧把 message item 的 {@code system} role 改写为
 * {@code developer}
 * <p>完整步骤树见 {@code pipeline/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 *
 * <h2>与 MESSAGES 实现的分工差异</h2>
 * 同一接口的两个协议实现做的是<strong>不同的事</strong>：
 * {@link MessagesSystemPromptStage} 把 system 消息<strong>抬到顶层</strong>（Anthropic 的
 * system 是顶层字段）；本类只<strong>改写 role</strong>（Responses 的 message item 官方
 * 角色集合不含 system，等价物是 developer）。两者共同的域是「system 的协议特定归一化」，
 * 这正是它们共享一个步骤接口的理由。
 *
 * <h2>查表键为何自然命中</h2>
 * 键是 {@code bodyProtocol}：C2R 线路上翻译步骤（{@code translateStep}）先把 body 换成
 * Responses 形态并同步切键，因此装配步骤（{@code assembleStep}）查 RESPONSES 时本支线
 * 自动命中 —— {@code RequestBodyAssembler} 与注册表零改动。RESPONSES 直连线路同样命中
 * （下游 Responses 客户端若发非标准 {@code system} role，也在这里归一）。
 *
 * <h2>为何委托给静态工具而非把逻辑搬进来</h2>
 * 与 {@link MessagesSystemPromptStage} 同一取向：纯逻辑留在
 * {@link SystemPromptNormalizer#rewriteSystemToDeveloper}，本类只承担
 * <strong>支线的三件事</strong> —— 声明协议键、带 {@code @Component} 让 Spring 收集、
 * 把调用转给静态工具。决策依据与方案选型（CPA 对齐、就地改写不搬 instructions）
 * 见 C2R 计划 docs/features/protocol-translation/chat-responses/PLAN.md §0 决策 #1。
 */
@Component
public class ResponsesSystemPromptStage implements SystemPromptNormalizeStage {

    @Override
    public WireProtocol protocol() {
        return WireProtocol.RESPONSES;
    }

    @Override
    public void apply(Map<String, Object> body) {
        SystemPromptNormalizer.rewriteSystemToDeveloper(body);
    }
}
