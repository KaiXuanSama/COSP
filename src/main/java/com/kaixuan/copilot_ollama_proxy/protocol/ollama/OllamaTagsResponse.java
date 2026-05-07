package com.kaixuan.copilot_ollama_proxy.protocol.ollama;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Ollama /api/tags 响应体 —— 返回可用模型列表。
 * Copilot 调用此接口后，会将返回的模型展示在模型选择下拉框中。
 * 本代理返回的是伪装的 Mimo 模型信息，格式与真实 Ollama 一致。
 */
public class OllamaTagsResponse {

    /** 模型列表 */
    private List<ModelInfo> models;

    public List<ModelInfo> getModels() {
        return models;
    }

    public void setModels(List<ModelInfo> models) {
        this.models = models;
    }

    /**
     * 单个模型的摘要信息。
     * 注意区分 name 和 model：name 是显示名称（如 "mimo-v2.5-pro"），model 是内部标识。
     */
    public static class ModelInfo {
        /** 模型的显示名称，Copilot 用这个名字展示给用户 */
        private String model;
        /** 模型标识符 */
        private String name;
        /** 最后修改时间 */
        @JsonProperty("modified_at")
        private String modifiedAt;
        /** 模型文件大小（字节），本代理填 0 */
        private long size;
        /** 模型摘要哈希，用于校验，本代理生成随机值 */
        private String digest;
        /** 模型的详细信息（格式、参数量等） */
        private ModelDetails details;
        /**
         * 模型支持的能力列表。
         * Copilot 根据此字段决定模型是否可用以及启用哪些功能：
         * - "completion"：基础文本补全
         * - "tools"：支持工具调用（function calling），Copilot 要求模型必须具备此能力才能被选中
         * - "vision"：支持图片理解
         */
        private List<String> capabilities;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getModifiedAt() {
            return modifiedAt;
        }

        public void setModifiedAt(String modifiedAt) {
            this.modifiedAt = modifiedAt;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public String getDigest() {
            return digest;
        }

        public void setDigest(String digest) {
            this.digest = digest;
        }

        public ModelDetails getDetails() {
            return details;
        }

        public void setDetails(ModelDetails details) {
            this.details = details;
        }

        public List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities;
        }
    }

    /**
     * 模型的详细信息，用于 /api/tags 和 /api/show 响应中。
     */
    public static class ModelDetails {
        /** 模型格式，如 "gguf"（真实 Ollama）或 "mimo"（本代理） */
        private String format;
        /** 模型家族，如 "Mimo" */
        private String family;
        /** 模型所属的所有家族列表 */
        private List<String> families;
        /** 参数量描述，如 "1T/42B"、"42B" */
        @JsonProperty("parameter_size")
        private String parameterSize;
        /** 量化级别，本代理填 "none"（Mimo 是云端模型，不涉及量化） */
        @JsonProperty("quantization_level")
        private String quantizationLevel;

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
