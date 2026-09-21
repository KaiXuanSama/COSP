package com.kaixuan.copilot_ollama_proxy.provider.stage;

import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;

import java.util.Map;

/**
 * 补齐 {@code max_tokens} 的<strong>支线</strong>。
 *
 * <h2>为什么它是支线而不是主干</h2>
 * 按方向文档 §2.1 的判据：<strong>协议差异是代码 → 支线</strong>。
 * {@code max_tokens} 在 Anthropic 是<strong>必填</strong>（缺失直接 400），
 * 而在 Chat 与 Responses 里都是<strong>可选</strong>的 ——
 * 「缺失时要不要补一个」这个决定本身就是协议差异，且它落在<strong>代码</strong>上
 * （要读模型配置、要处理别名、要清非法值）。因此是协议强关联步骤。
 *
 * <p>当前只有 {@link com.kaixuan.copilot_ollama_proxy.provider.stage.messages.MessagesMaxTokensStage}
 * 一个实现；另两条协议查不到实现 → <strong>跳过本步</strong>。
 *
 * <h2>另两条线路「不注入」是刻意的，不是缺口</h2>
 * Chat 的 {@code max_tokens} 与 Responses 的 {@code max_output_tokens} 都是可选的，
 * 接上会给所有「下游没带」的调用凭空补一个上限 —— 而 Copilot 通常就是不带。
 * 因此这两条协议**不该**有这个实现。详见
 * {@code GenericResponsesChatService#prepareRequestBody} 的说明。
 */
public interface MaxTokensNormalizeStage {

    /** 本支线服务的协议 —— 查表键。 */
    WireProtocol protocol();

    /**
     * 就地把 {@code max_tokens} 落定为最终值。
     *
     * <p>实现内部的三步顺序（别名归一化 → 清非法值 → 按模式注入）不可交换，
     * 理由见实现类。
     *
     * @param body          当前请求体，会被原地修改
     * @param resolvedModel 已剥供应商前缀的真实模型名，用于查该模型的配置
     * @param provider      本次调用的供应商运行时配置
     */
    void apply(Map<String, Object> body, String resolvedModel, ProviderRuntimeConfiguration provider);
}
