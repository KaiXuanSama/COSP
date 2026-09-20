package com.kaixuan.copilot_ollama_proxy.protocol.openai;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI Responses API 的入站请求 DTO。
 *
 * <h2>为何只有请求侧 DTO，没有响应侧</h2>
 * 响应全程以 {@code String} 透传，这与 Anthropic 侧是<strong>同一个决定</strong>：
 * 对上游格式差异免疫是当前的优势，引入响应 DTO 等于加一道「上游字段必须符合预期结构」
 * 的约束。而 Responses 的事件类型比 Anthropic 还多（{@code response.output_item.added}、
 * {@code response.function_call_arguments.delta} 等十余种，各家兼容端点还有自己的变体），
 * 建模它们就是承诺跟随全部变体演进。
 *
 * <p>请求侧则需要 DTO，因为要判 {@code stream}、取 {@code model} 做路由，
 * 且 Spring 需要一个类型来反序列化 body。
 *
 * <h2>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 是必需的</h2>
 * Responses 的请求字段很多且仍在增加（{@code instructions}、{@code reasoning}、
 * {@code include}、{@code store}、{@code previous_response_id}、{@code text.format} 等），
 * 严格模式会让一个未知字段直接 400 —— 而本代理的职责是转发，不是校验。
 *
 * <p>正因为如此，本 DTO <strong>只声明路由与分流真正需要的字段</strong>，
 * 其余字段由 {@link #getAdditionalProperties()} 原样收集并回填到出站请求体。
 *
 * <h2>有状态字段原样透传，不解释也不缓存</h2>
 * {@code previous_response_id} / {@code store} / {@code conversation} 落在
 * {@code additionalProperties} 里被原样带给上游。直连场景下这是正确的：
 * 下游与上游说的是同一种协议，上游自己维护那份状态，代理没有理由介入。
 *
 * <p>它们只在<strong>跨协议翻译</strong>时才成为问题（Chat 是无状态的，
 * 一个「接着上次那个响应继续」的请求无法表达成 Chat 报文）。届时的处理方式属于
 * 翻译器的职责，不是本 DTO 的。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesRequest {

    /** 模型名，可能带 {@code [provider-key]} 前缀。路由依赖它。 */
    private String model;

    /**
     * 输入内容。
     *
     * <p>Responses 允许它是<strong>字符串或数组</strong>（数组元素又可以是消息对象、
     * {@code function_call}、{@code function_call_output}、{@code reasoning} 等多种形态），
     * 故用 {@code Object} 承载 —— 建模成 {@code List} 会让字符串形态的请求
     * 反序列化失败，而那是官方文档里的第一个示例。
     *
     * <p>这与 Anthropic DTO 的 {@code system} 用 {@code Object} 是同一个理由。
     */
    private Object input;

    /** 是否流式。控制器据此分流，缺省视为 false。 */
    private Boolean stream;

    /**
     * 其余所有字段的原样收集器。
     *
     * <p>用 {@code @JsonAnySetter} 而非逐个建模：见类注释。出站时这些字段被原样放回
     * 请求体，因此 {@code instructions}、{@code reasoning}、{@code tools}、
     * {@code max_output_tokens} 等无需本类知晓即可透传。
     */
    private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Object getInput() {
        return input;
    }

    public void setInput(Object input) {
        this.input = input;
    }

    public Boolean getStream() {
        return stream;
    }

    public void setStream(Boolean stream) {
        this.stream = stream;
    }

    /** 是否流式请求；{@code null} 视为非流式，与另两条线路的 {@code isStream()} 同一约定。 */
    public boolean isStream() {
        return stream != null && stream;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        additionalProperties.put(name, value);
    }
}
