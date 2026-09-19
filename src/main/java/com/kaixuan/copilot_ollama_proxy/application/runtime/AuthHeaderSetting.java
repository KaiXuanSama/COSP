package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 出站鉴权头的装配方式：模式 + 承载头。
 *
 * <h2>为何这不是「选哪个头」一个值的事</h2>
 * 出站鉴权头曾经由代码<strong>按上游协议</strong>决定（Messages 发 {@code x-api-key}，
 * Chat / Responses 发 {@code Authorization: Bearer}）。那条映射建立在「Anthropic 官方用
 * x-api-key」之上，实测不成立：
 * <ul>
 *   <li>Claude CLI 的头名由<strong>凭据环境变量</strong>决定，与协议无关 ——
 *       {@code ANTHROPIC_API_KEY} 走 {@code x-api-key}，{@code ANTHROPIC_AUTH_TOKEN}
 *       走 {@code Authorization: Bearer}。</li>
 *   <li>部分中转站只认 {@code Authorization}，收到 {@code x-api-key} 会直接拒绝。</li>
 * </ul>
 * 根因不是「选错了那一档」，而是<strong>这个选择本身不该由代码替用户做</strong>。
 * 它与本项目「会进入出站请求的东西都建模成 {@code {值, 模式}}」是同一个原则：单个值
 * 无法表达「下游自己做了选择怎么办」。
 *
 * <h2>两个维度</h2>
 * <table>
 *   <tr><th>模式</th><th>含义</th></tr>
 *   <tr><td>{@link Mode#DOWNSTREAM}</td><td>下游恰好带了一种鉴权头就沿用那一种</td></tr>
 *   <tr><td>{@link Mode#CONFIGURED}</td><td>始终用配置的 {@link #header()}</td></tr>
 * </table>
 * {@link Header} 既定「下游带了哪一种」，也定「配置取哪一种」。
 *
 * <pre>
 *              下游恰好带 1 个       下游带 0 个或 2 个
 * DOWNSTREAM   认下游选的那个头     兜底：用配置的 Header
 * CONFIGURED   用配置的 Header      用配置的 Header
 * </pre>
 *
 * <p>「带 0 个或 2 个」都属异常输入，本服务兜底。因此 {@link Mode#DOWNSTREAM} 下
 * {@link #header()} <strong>不是</strong>「用不上」，而是「异常时的兜底」——
 * 语义与「不生效」完全不同。
 *
 * <h2>持久化形态</h2>
 * 存在 {@code provider_config.auth_header} 一列里，形态是 JSON：
 * <pre>{@code {"mode":"DOWNSTREAM","header":"AUTHORIZATION"}}</pre>
 * 不拆成两列的理由与 {@code reasoning_effort} 那次相同：这是一次纯粹的
 * <strong>内容形态</strong>变更，拆列要改 schema、迁移、行记录与三处读写。
 *
 * <p><strong>值用枚举名而非头名字面量</strong>：头名大小写不敏感且存在拼写变体，
 * 枚举名是稳定标识，显示文本由前端决定（「显示 Authorization 而不是
 * ANTHROPIC_AUTH_TOKEN」这条需求是同一逻辑）。
 *
 * <h2>它不决定「发几个头」</h2>
 * 本设置只回答「用哪个头承载供应商 key」。需要两个头同时出现这类诉求仍归请求头规则层表达，
 * 因为规则层在装配<strong>之后</strong>执行并保留最终决定权。
 */
public record AuthHeaderSetting(Mode mode, Header header) {

    /**
     * 鉴权头的选择模式。
     *
     * <p>两档的差别只在「下游带了头时听谁的」。之所以必须有 {@link #DOWNSTREAM}，
     * 是因为下游（Claude CLI / cc-switch）已经替用户做了选择，而它比本服务更清楚
     * 自己用的是哪个凭据变量；之所以必须有 {@link #CONFIGURED}，是因为下游的选择
     * 未必是上游想听的（Claude CLI 默认发 Authorization，但只认 x-api-key 的
     * Anthropic 原生端点确实存在）。
     */
    public enum Mode {
        /** 取下游：下游恰好带了一种就沿用那一种，带 0 或 2 种时兜底用配置的 {@link Header}。 */
        DOWNSTREAM,
        /** 取设置：始终用配置的 {@link Header}，忽略下游的选择。 */
        CONFIGURED
    }

    /**
     * 承载供应商 key 的出站头。
     *
     * <p>两个取值对应两种既成的上游约定，二者在语义上<strong>互斥</strong>：
     * 一次出站只发其中一个（另见类注释「它不决定发几个头」）。
     */
    public enum Header {
        /** {@code Authorization: Bearer <供应商 key>}。 */
        AUTHORIZATION("Authorization"),
        /** {@code x-api-key: <供应商 key>}。 */
        X_API_KEY("x-api-key");

        private final String headerName;

        Header(String headerName) {
            this.headerName = headerName;
        }

        /**
         * 该取值在 HTTP 上的头名字面量。
         *
         * <p>本类选择落库枚举名而不是头名，正是为了让这个映射只存在一处 ——
         * 头名大小写不敏感且有拼写变体（{@code X-Api-Key} / {@code x-api-key}），
         * 存字面量就等于把「第几处写的是哪种拼写」变成配置的一部分。
         */
        public String headerName() {
            return headerName;
        }
    }

    /**
     * 列缺省值与 {@code schema.sql} 的 DEFAULT 逐字一致。
     *
     * <p>三处分叉（{@code schema.sql}、V14 迁移、本常量）会让「新库」「升级后的库」
     * 「保存过一次的供应商」在直接查库时看起来是三种配置，因此改动必须三处同步。
     *
     * <p>默认取「取下游 + Authorization」而不是「取设置 + x-api-key」：前者让存量行为
     * 几乎不变 —— 下游带了什么就还发什么。原 Messages 供应商在「下游带 x-api-key」时
     * 行为完全一致；只有「下游一个头都没带」的原 Messages 供应商从 {@code x-api-key}
     * 变为 {@code Authorization}，而那正是本次要修的场景。
     */
    public static final String DEFAULT_AUTH_HEADER_JSON =
            "{\"mode\":\"DOWNSTREAM\",\"header\":\"AUTHORIZATION\"}";

    /** 持久化 JSON 里承载模式的键名。 */
    public static final String MODE_KEY = "mode";

    /** 持久化 JSON 里承载头名的键名。 */
    public static final String HEADER_KEY = "header";

    private static final Mode DEFAULT_MODE = Mode.DOWNSTREAM;

    private static final Header DEFAULT_HEADER = Header.AUTHORIZATION;

    /** 配置缺失时的兜底：取下游 + Authorization。 */
    public static AuthHeaderSetting defaults() {
        return new AuthHeaderSetting(DEFAULT_MODE, DEFAULT_HEADER);
    }

    public AuthHeaderSetting {
        mode = mode == null ? DEFAULT_MODE : mode;
        header = header == null ? DEFAULT_HEADER : header;
    }

    /**
     * 解析持久化的配置字符串。
     *
     * <p>解析失败回退 {@link #defaults()} 而不抛异常 —— 值来自数据库，一行脏数据不该让
     * 聊天链路失败。这与 {@code MaxOutputTokensSetting.parse} / {@code ReasoningEffortSetting.parse}
     * 的口径一致：<strong>读取路径宽容，写入路径严格</strong>。写入侧的严格校验在
     * {@code ProviderAdminService}（表单入口），因此脏值不会被新写入放大。
     *
     * @param raw          持久化的原始字符串，可为 null
     * @param objectMapper JSON 解析器；为 null 时直接回退默认值
     */
    public static AuthHeaderSetting parse(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank() || objectMapper == null) {
            return defaults();
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return defaults();
        }
        try {
            JsonNode parsed = objectMapper.readTree(trimmed);
            return new AuthHeaderSetting(
                    parseMode(parsed.path(MODE_KEY).asText(null)),
                    parseHeader(parsed.path(HEADER_KEY).asText(null)));
        } catch (Exception exception) {
            return defaults();
        }
    }

    /** 认不出的模式名回退默认模式，而不是让整条配置失效。 */
    private static Mode parseMode(String raw) {
        if (raw != null && !raw.isBlank()) {
            for (Mode candidate : Mode.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        return DEFAULT_MODE;
    }

    /** 认不出的头名同样只回退自己那一维，不影响模式。 */
    private static Header parseHeader(String raw) {
        if (raw != null && !raw.isBlank()) {
            for (Header candidate : Header.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        return DEFAULT_HEADER;
    }

    /**
     * 序列化为持久化用的 JSON。
     *
     * <p>手工拼接而不用 {@code ObjectMapper}：两个字段都已在构造器里归一化过
     * （枚举，且不可能为 null），不存在需要转义的内容。
     */
    public String serialize() {
        return "{\"" + MODE_KEY + "\":\"" + mode.name()
                + "\",\"" + HEADER_KEY + "\":\"" + header.name() + "\"}";
    }

    /**
     * 按模式与探测结果定出本次出站用哪个头。
     *
     * <p>两个布尔是<strong>下游带来了哪些头</strong>的探测结果，由调用方在剥离下游凭据之前
     * 采集（顺序依赖见 {@code ProviderRequestHeaderService#applyHeaders}）。
     * 本方法不读请求，只做决策 —— 因此它可以被穷举测试而没有 I/O。
     *
     * <p>「下游恰好带一个」用相等判断表达：两个都为 {@code false}（一个都没带）或
     * 都为 {@code true}（两个都带了，例如同时设了 {@code ANTHROPIC_API_KEY} 与
     * {@code ANTHROPIC_AUTH_TOKEN}）时无法判断意图，两种都走兜底。
     *
     * @param downstreamHasAuthorization 下游是否带了非空白的 {@code Authorization}
     * @param downstreamHasApiKey        下游是否带了非空白的 {@code x-api-key}
     * @return 本次出站应注入供应商 key 的头；调用方负责先删掉下游的两个头
     */
    public Header resolveHeader(boolean downstreamHasAuthorization, boolean downstreamHasApiKey) {
        if (mode == Mode.CONFIGURED || downstreamHasAuthorization == downstreamHasApiKey) {
            return header;
        }
        return downstreamHasAuthorization ? Header.AUTHORIZATION : Header.X_API_KEY;
    }
}
