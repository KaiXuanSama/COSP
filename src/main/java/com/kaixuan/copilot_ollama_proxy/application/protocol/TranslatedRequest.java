package com.kaixuan.copilot_ollama_proxy.application.protocol;

import java.util.Map;

/**
 * 请求体翻译的完整产物。
 *
 * <p>不能只返回 {@code Map<String, Object>}——响应侧需要请求期上下文。
 * 见 {@code docs/PROTOCOL_TRANSLATION_CONTRACT.md} 第 7 节。
 *
 * @param body 已翻译的上游请求体
 * @param context 响应侧需要的上下文
 */
public record TranslatedRequest(
        Map<String, Object> body,
        TranslationContext context) {
}
