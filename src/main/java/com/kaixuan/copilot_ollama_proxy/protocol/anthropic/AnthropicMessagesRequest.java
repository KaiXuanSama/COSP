package com.kaixuan.copilot_ollama_proxy.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Anthropic Messages API 的入站请求 DTO。
 *
 * <h2>为何只有请求侧 DTO，没有响应侧</h2>
 * 响应全程以 {@code String} 透传，这是<strong>有意的</strong>：
 * 对上游格式差异免疫是当前的优势，引入响应 DTO 等于加一道「上游字段必须符合预期结构」
 * 的约束。这与非流式那次的结论一致。
 *
 * <p>请求侧则需要 DTO，因为要判 {@code stream}、取 {@code model} 做路由，
 * 且 Spring 需要一个类型来反序列化 body。
 *
 * <h2>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 是必需的</h2>
 * Anthropic 的请求字段还在增加（{@code thinking}、{@code cache_control}、
 * {@code tool_choice} 等），且各客户端（Claude Desktop / Claude Code / SDK）
 * 发送的字段集不完全相同。严格模式会让一个未知字段直接 400 ——
 * 而本代理的职责是转发，不是校验。
 *
 * <p>正因为如此，本 DTO <strong>只声明路由与分流真正需要的字段</strong>，
 * 其余字段由 {@link #additionalProperties} 原样收集并回填到出站请求体，
 * 不逐个建模 —— 逐个建模等于承诺跟随 Anthropic 的字段演进，那是笔还不清的债。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicMessagesRequest {

    /** 模型名，可能带 {@code [provider-key]} 前缀。路由依赖它。 */
    private String model;

    /** 对话消息列表。原样转发，不做结构校验。 */
    private List<Map<String, Object>> messages;

    /**
     * 系统提示词。
     *
     * <p>Anthropic 允许它是字符串或 content block 数组，故用 {@code Object} 承载 ——
     * 建模成 String 会让数组形态的请求反序列化失败。
     */
    private Object system;

    /**
     * 最大输出 token 数。
     *
     * <p>Anthropic 规范里<strong>必填</strong>，但本 DTO 不加校验：
     * 缺失时由上游服务补默认值（见 {@code GenericAnthropicChatService#ensureMaxTokens}），
     * 这样下游忘带也不会被本代理拒绝。
     */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /** 是否流式。控制器据此分流，缺省视为 false。 */
    private Boolean stream;

    /**
     * 其余所有字段的原样收集器。
     *
     * <p>用 {@code @JsonAnySetter} 而非逐个建模：见类注释。
     * 出站时这些字段被原样放回请求体，因此 {@code temperature}、{@code tools}、
     * {@code thinking} 等无需本类知晓即可透传。
     */
    private final Map<String, Object> additionalProperties = new java.util.LinkedHashMap<>();

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = messages;
    }

    public Object getSystem() {
        return system;
    }

    public void setSystem(Object system) {
        this.system = system;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    /** 是否流式请求；{@code null} 视为非流式，与 OpenAI 侧 {@code isStream()} 同一约定。 */
    public boolean isStream() {
        return stream != null && stream;
    }

    @com.fasterxml.jackson.annotation.JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        additionalProperties.put(name, value);
    }
}
