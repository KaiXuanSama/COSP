package com.kaixuan.copilot_ollama_proxy.upstream.requestbody.system;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;

import java.util.Map;

/**
 * 把 {@code messages} 里的 system 消息抬到顶层 {@code system} 字段的<strong>支线</strong>。
 *
 * <h2>为什么它是支线而不是主干</h2>
 * 按方向文档 §2.1 的判据：<strong>协议差异是代码 → 支线；是数据 → 主干</strong>。
 * 「system 可以是顶层字段」是 Anthropic 独有的形态约束，OpenAI 的 Chat 把它作为
 * {@code messages} 里 {@code role:system} 的一条、Responses 用顶层 {@code instructions} ——
 * 三者的表达方式各不相同，且差异落在<strong>代码</strong>上。因此这是协议强关联步骤。
 *
 * <p>当前只有 {@link com.kaixuan.copilot_ollama_proxy.upstream.requestbody.system.MessagesSystemPromptStage}
 * 一个实现；另两条协议查不到实现 → <strong>跳过本步</strong>。
 * 「跳过」比「不存在」更贴合意图：它表达「这一步在别的协议上不需要」。
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
     * 就地把 system 提示词抬到顶层。
     *
     * @param body 当前请求体，会被原地修改；没有可抬升内容时保持原样
     */
    void apply(Map<String, Object> body);
}
