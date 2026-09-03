package com.kaixuan.copilot_ollama_proxy.application.protocol;

/**
 * 请求无法翻译到目标协议时抛出的异常。
 *
 * <h2>为何与 {@link ProtocolTranslationNotSupportedException} 分开</h2>
 * 那个表达「这条协议组合本代理还没实现」，是**代理的能力缺口**，改配置就能绕开；
 * 本异常表达「这个请求本身无法表达成目标协议」，是**请求内容的问题**，
 * 换供应商也没用，必须下游改请求。两者都应回 4xx，但排查方向完全不同，
 * 消息也因此不能共用一套模板。
 *
 * <h2>什么情况该抛它</h2>
 * 契约（{@code docs/PROTOCOL_TRANSLATION_CONTRACT.md} 第 6.1 节）规定四类：
 * <ul>
 *   <li>{@code stop} 数组含非字符串元素</li>
 *   <li>{@code tool_calls[].function.arguments} 不是合法 JSON</li>
 *   <li>未知 {@code role}</li>
 *   <li>目标协议无法表达下游的显式要求</li>
 * </ul>
 *
 * <p>反面标准：凡是能靠「丢弃」得到一个合法请求的，都不抛这个异常 ——
 * 静默丢弃无对应物的字段是契约第 5 节明确的处置，报错会让大量正常请求失败。
 * 抛异常只留给「继续下去必然产出一个语义错误的请求」的情形。
 *
 * <h2>为何带字段路径</h2>
 * 翻译失败时下游拿到的是一个 400，它需要知道改哪里。只说「翻译失败」
 * 等于让调用方去猜；带上 {@code messages[2].role} 这样的路径才可操作。
 */
public class RequestTranslationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String fieldPath;

    /**
     * @param fieldPath 出问题的字段路径，如 {@code messages[2].role}；可为 null
     * @param reason    人类可读的原因，会直接出现在下游看到的错误体里
     */
    public RequestTranslationException(String fieldPath, String reason) {
        super(buildMessage(fieldPath, reason));
        this.fieldPath = fieldPath;
    }

    private static String buildMessage(String fieldPath, String reason) {
        if (fieldPath == null || fieldPath.isBlank()) {
            return "请求无法翻译到目标协议: " + reason;
        }
        return "请求无法翻译到目标协议: " + fieldPath + " " + reason;
    }

    /** 出问题的字段路径，可能为 null。 */
    public String fieldPath() {
        return fieldPath;
    }
}
