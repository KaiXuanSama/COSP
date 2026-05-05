package com.kaixuan.copilot_ollama_proxy.protocol.ollama;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Ollama /api/show 响应体 —— 返回模型的完整详细信息。
 * Copilot 调用此接口来确定模型的上下文窗口大小、支持的能力等关键参数。
 * 这些参数直接影响 Copilot 如何使用模型（例如决定单次发送多少内容）。
 * JsonInclude(NON_NULL) 表示值为 null 的字段不会出现在 JSON 中，保持响应简洁。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OllamaShowResponse {

    /**
     * 模型参数字符串，每行一个 key-value。
     * 例如："temperature 0.7\nnum_ctx 1000000"
     * Copilot 会解析 num_ctx 来确定上下文窗口大小。
     */
    private String parameters;

    /** 模型许可证，如 "Apache 2.0" */
    private String license;

    /** 模型最后修改时间 */
    @JsonProperty("modified_at")
    private String modifiedAt;

    /** 模型详细信息（格式、家族、参数量等） */
    private ShowDetails details;

    /** 模型的提示模板，描述 system 和 prompt 如何组合 */
    private String template;

    /**
     * 模型支持的能力列表。Copilot 根据此字段决定启用哪些功能：
     * - "completion"：基础文本补全
     * - "tools"：支持工具调用（function calling）
     * - "vision"：支持图片理解
     */
    private List<String> capabilities;

    /**
     * 模型的元数据信息，key 格式遵循 Ollama 规范。
     * 关键字段：
     * - general.architecture：架构名称（如 "mimo"），Copilot 用它来查找对应的参数
     * - mimo.context_length：上下文窗口大小（token 数），Copilot 用此值决定消息截断策略
     * - mimo.embedding_length：嵌入维度
     * - general.parameter_count：总参数量
     */
    @JsonProperty("model_info")
    private Map<String, Object> modelInfo;

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license;
    }

    public String getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(String modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public ShowDetails getDetails() {
        return details;
    }

    public void setDetails(ShowDetails details) {
        this.details = details;
    }

    public String getTemplate() {
        return template;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities;
    }

    public Map<String, Object> getModelInfo() {
        return modelInfo;
    }

    public void setModelInfo(Map<String, Object> modelInfo) {
        this.modelInfo = modelInfo;
    }

    /**
     * 模型的高级详细信息，包含格式、家族、参数量等。
     */
    public static class ShowDetails {
        /** 父模型（如微调模型的基模型），本代理为空 */
        @JsonProperty("parent_model")
        private String parentModel;
        /** 模型格式，如 "mimo" */
        private String format;
        /** 模型家族，如 "Mimo" */
        private String family;
        /** 所属家族列表 */
        private List<String> families;
        /** 参数量描述，如 "1T/42B" */
        @JsonProperty("parameter_size")
        private String parameterSize;
        /** 量化级别，云端模型填 "none" */
        @JsonProperty("quantization_level")
        private String quantizationLevel;

        public String getParentModel() {
            return parentModel;
        }

        public void setParentModel(String parentModel) {
            this.parentModel = parentModel;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public String getFamily() {
            return family;
        }

        public void setFamily(String family) {
            this.family = family;
        }

        public List<String> getFamilies() {
            return families;
        }

        public void setFamilies(List<String> families) {
            this.families = families;
        }

        public String getParameterSize() {
            return parameterSize;
        }

        public void setParameterSize(String parameterSize) {
            this.parameterSize = parameterSize;
        }

        public String getQuantizationLevel() {
            return quantizationLevel;
        }

        public void setQuantizationLevel(String quantizationLevel) {
            this.quantizationLevel = quantizationLevel;
        }
    }
}
