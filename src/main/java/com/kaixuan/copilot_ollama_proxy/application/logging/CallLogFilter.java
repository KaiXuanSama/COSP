package com.kaixuan.copilot_ollama_proxy.application.logging;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 调用日志列表的检索条件。
 *
 * <p>所有字段都可缺省；{@link #NONE} 表示不加任何条件，此时列表查询与引入检索前完全一致。
 * 不同字段之间取 AND，状态类与精确状态码之间取 OR。决策见 ADR-0020。
 *
 * <p>时间按 {@code api_call_log.created_at} 的存储格式比较：服务器本地时间、无时区、精确到秒。
 * 该列是 TEXT，格式固定为 {@code yyyy-MM-ddTHH:mm:ss}，字典序即时间序，
 * 所以只接受这一种格式 —— 带毫秒或时区后缀的输入与库内字符串比较会得出错误结果。
 *
 * @param from          起始时间（含），null 表示不限
 * @param to            结束时间（含），null 表示不限
 * @param statusClasses 状态类集合，如 2xx
 * @param statusCodes   精确状态码集合，可含 -1（非 HTTP 异常）
 * @param provider      供应商标识（{@code provider_key}），null 表示不限
 * @param providerMatch 供应商匹配方式
 * @param model         上游模型名，null 表示不限
 * @param modelMatch    模型匹配方式
 */
public record CallLogFilter(LocalDateTime from,
                            LocalDateTime to,
                            Set<StatusClass> statusClasses,
                            Set<Integer> statusCodes,
                            String provider,
                            MatchMode providerMatch,
                            String model,
                            MatchMode modelMatch) {

    /** 无任何检索条件。 */
    public static final CallLogFilter NONE = new CallLogFilter(null, null, Set.of(), Set.of(),
            null, MatchMode.EXACT, null, MatchMode.EXACT);

    /**
     * 文本条件的输入上限。
     *
     * <p>不取列宽（provider_key 声明为 VARCHAR(30)）：SQLite 不强制 VARCHAR 长度，
     * 库里可能存在更长的值，按列宽拒绝会让这些行永远查不到。这里只防超长输入。
     */
    static final int MAX_TEXT_LENGTH = 200;

    /** 状态取值个数上限。正常使用不超过十几个，上限只防构造出超长 SQL。 */
    static final int MAX_STATUS_TOKENS = 20;

    /** 与 created_at 的存储格式一致；STRICT 拒绝 2 月 30 日这类日期。 */
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    public CallLogFilter {
        statusClasses = statusClasses == null || statusClasses.isEmpty()
                ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(statusClasses));
        statusCodes = statusCodes == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(statusCodes));
        providerMatch = providerMatch == null ? MatchMode.EXACT : providerMatch;
        modelMatch = modelMatch == null ? MatchMode.EXACT : modelMatch;
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
    }

    /** 文本匹配方式。 */
    public enum MatchMode {
        /** 全字匹配：等值比较，区分大小写。 */
        EXACT,
        /** 部分匹配：LIKE 包含，ASCII 不区分大小写（SQLite 默认行为）。 */
        CONTAINS;

        static MatchMode parse(String paramName, String raw) {
            if (raw == null || raw.isBlank()) {
                return EXACT;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "exact" -> EXACT;
                case "contains" -> CONTAINS;
                default -> throw new IllegalArgumentException(paramName + " 只能是 exact 或 contains：" + raw);
            };
        }
    }

    /** 状态类，与前端状态配色分组一致。 */
    public enum StatusClass {
        SUCCESS("2xx", 200, 299),
        CLIENT_ERROR("4xx", 400, 499),
        SERVER_ERROR("5xx", 500, 599);

        private final String token;
        private final int min;
        private final int max;

        StatusClass(String token, int min, int max) {
            this.token = token;
            this.min = min;
            this.max = max;
        }

        public String token() {
            return token;
        }

        public int min() {
            return min;
        }

        public int max() {
            return max;
        }
    }

    /** 是否没有任何检索条件。 */
    public boolean isEmpty() {
        return from == null && to == null && statusClasses.isEmpty() && statusCodes.isEmpty()
                && provider == null && model == null;
    }

    /** {@link #from} 按 created_at 存储格式输出，供 SQL 比较。 */
    public String fromText() {
        return from == null ? null : TIME_FORMAT.format(from);
    }

    /** {@link #to} 按 created_at 存储格式输出，供 SQL 比较。 */
    public String toText() {
        return to == null ? null : TIME_FORMAT.format(to);
    }

    /**
     * 从原始查询参数解析检索条件。
     *
     * <p>空串与纯空白视为未传。参数非法时抛 {@link IllegalArgumentException}，
     * 消息指明参数名，控制器将其原样作为 400 响应的 message。
     *
     * @param status 状态取值列表，元素可为 {@code 2xx}/{@code 4xx}/{@code 5xx} 或整数状态码；
     *               单个元素内也可用逗号分隔
     */
    public static CallLogFilter parse(String from, String to, List<String> status,
                                      String provider, String providerMatch,
                                      String model, String modelMatch) {
        Set<StatusClass> classes = EnumSet.noneOf(StatusClass.class);
        Set<Integer> codes = new LinkedHashSet<>();
        int tokenCount = 0;
        if (status != null) {
            for (String item : status) {
                if (item == null) {
                    continue;
                }
                for (String raw : item.split(",")) {
                    String token = raw.trim();
                    if (token.isEmpty()) {
                        continue;
                    }
                    if (++tokenCount > MAX_STATUS_TOKENS) {
                        throw new IllegalArgumentException("status 最多 " + MAX_STATUS_TOKENS + " 个取值");
                    }
                    parseStatusToken(token, classes, codes);
                }
            }
        }
        return new CallLogFilter(parseTime("from", from), parseTime("to", to), classes, codes,
                parseText("provider", provider), MatchMode.parse("providerMatch", providerMatch),
                parseText("model", model), MatchMode.parse("modelMatch", modelMatch));
    }

    private static void parseStatusToken(String token, Set<StatusClass> classes, Set<Integer> codes) {
        String lower = token.toLowerCase(Locale.ROOT);
        for (StatusClass statusClass : StatusClass.values()) {
            if (statusClass.token.equals(lower)) {
                classes.add(statusClass);
                return;
            }
        }
        int code;
        try {
            code = Integer.parseInt(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("status 取值无效：" + token + "（可用 2xx、4xx、5xx、-1 或 100~599 的状态码）");
        }
        // -1 是落库时「没有拿到 HTTP 响应」的占位值，其余必须是合法 HTTP 状态码。
        if (code != -1 && (code < 100 || code > 599)) {
            throw new IllegalArgumentException("status 取值无效：" + token + "（可用 2xx、4xx、5xx、-1 或 100~599 的状态码）");
        }
        codes.add(code);
    }

    private static LocalDateTime parseTime(String paramName, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim(), TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(paramName + " 格式应为 yyyy-MM-ddTHH:mm:ss：" + raw);
        }
    }

    private static String parseText(String paramName, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(paramName + " 长度不能超过 " + MAX_TEXT_LENGTH);
        }
        return value;
    }
}
