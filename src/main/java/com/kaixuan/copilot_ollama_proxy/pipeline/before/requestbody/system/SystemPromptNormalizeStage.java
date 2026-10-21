package com.kaixuan.copilot_ollama_proxy.pipeline.before.requestbody.system;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;

import java.util.Map;

/**
 * system 提示词的协议特定归一化<strong>支线</strong>。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：<strong>契约</strong>（支线） · 位置：{@code pipeline/before/requestbody/system/}
 * 步骤「system 归一化」—— 按当前请求体形态决定 system 的落点
 * <p>完整步骤树见 {@code pipeline/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为什么它是支线而不是主干</h2>
 * 判据是：<strong>协议差异是代码 → 支线；是数据 → 主干</strong>。
 * MESSAGES 把 system 抬到顶层，CHAT 把翻译保留的顶层 system 落回 messages，
 * RESPONSES 按既有约定改写 input 中的 system 角色。表达方式各不相同，
 * 且差异落在<strong>代码</strong>上，因此共享归一化插槽而不是主干上的协议分支。
 *
 * <p>三种协议各一个实现，经 {@code bodyProtocol} 查表。
 * {@link ChatSystemPromptStage} 无顶层 system 时完整 no-op，不改变标准 Chat 直连。
 *
 * <h2>为何不需要 model 与 provider</h2>
 * 载荷纯由请求体自身决定（哪些 message 是 system 角色），不依赖任何模型配置。
 * 因此签名只收 {@code body} —— 与另两条支线的签名不同是<strong>刻意的</strong>：
 * 三个接口各自服务自己的职责，强制统一只会让本方法多两个用不上的参数。
 */
public interface SystemPromptNormalizeStage {

    /** 本支线服务的协议 —— 查表键。 */
    WireProtocol protocol();

    /**
     * 就地归一化 system 的协议形态。
     *
     * @param body 当前可变请求体；没有需归一化的内容时保持原样
     */
    void apply(Map<String, Object> body);
}
