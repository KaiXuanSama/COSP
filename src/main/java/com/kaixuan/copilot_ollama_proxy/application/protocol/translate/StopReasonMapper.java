package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

/**
 * Anthropic {@code stop_reason} → OpenAI {@code finish_reason}。
 *
 * <h2>未知值原样透传</h2>
 * 这是本类唯一需要解释的决定。三个参考项目里 new-api 的 default 分支也是原样透传，
 * 与本服务「不做自动降级、尊重上游」的原则一致：强行把没见过的终止原因归一到
 * {@code stop}，等于把「上游给了个我们不认识的信号」伪装成正常结束，
 * 下游就再也没有机会发现异常。
 *
 * <p>契约第 8 节。
 */
final class StopReasonMapper {

    private StopReasonMapper() {
    }

    /**
     * 映射终止原因。
     *
     * @param stopReason Anthropic 的 stop_reason；null 时返回 null（调用方据此发不带
     *                   finish_reason 的帧）
     * @return OpenAI 的 finish_reason
     */
    static String toFinishReason(String stopReason) {
        if (stopReason == null || stopReason.isBlank()) {
            return null;
        }
        return switch (stopReason) {
            case "end_turn" -> "stop";
            // 具体命中的停止序列值丢失 —— OpenAI 的 finish_reason 是枚举，没有地方放它。
            case "stop_sequence" -> "stop";
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            // pause_turn 表示上游主动暂停、期待续接，语义上是「没说完」，
            // 归到 length 而非 stop：后者会让下游认为回答已完整。
            case "pause_turn" -> "length";
            case "model_context_window_exceeded" -> "length";
            default -> stopReason;
        };
    }
}
