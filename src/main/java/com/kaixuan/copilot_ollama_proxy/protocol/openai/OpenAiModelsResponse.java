package com.kaixuan.copilot_ollama_proxy.protocol.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * OpenAI /v1/models 接口响应 DTO。
 * <p>
 * 返回格式遵循 OpenAI API 规范：
 * <pre>
 * {
 *   "object": "list",
 *   "data": [
 *     {
 *       "id": "model-id",
 *       "object": "model",
 *       "created": 1686935002,
 *       "owned_by": "provider-key"
 *     }
 *   ]
 * }
 * </pre>
 */
public class OpenAiModelsResponse {

    @JsonProperty("object")
    private String object = "list";

    @JsonProperty("data")
    private List<ModelData> data;

    /**
     * 默认构造函数。
     */
    public OpenAiModelsResponse() {
    }

    /**
     * 带参构造函数。
     * @param data 模型数据列表
     */
    public OpenAiModelsResponse(List<ModelData> data) {
        this.data = data;
    }

    @JsonProperty("object")
    public String getObject() {
        return object;
    }

    @JsonProperty("object")
    public void setObject(String object) {
        this.object = object;
    }

    @JsonProperty("data")
    public List<ModelData> getData() {
        return data;
    }

    @JsonProperty("data")
    public void setData(List<ModelData> data) {
        this.data = data;
    }

    /**
     * 模型数据项。
     */
    public static class ModelData {

        @JsonProperty("id")
        private String id;

        @JsonProperty("object")
        private String object = "model";

        @JsonProperty("created")
        private long created;

        @JsonProperty("owned_by")
        private String ownedBy;

        /**
         * 默认构造函数。
         */
        public ModelData() {
        }

        /**
         * 带参构造函数。
         * @param id 模型 ID
         * @param created 创建时间戳（Unix 时间戳）
         * @param ownedBy 所有者（通常是 provider key）
         */
        public ModelData(String id, long created, String ownedBy) {
            this.id = id;
            this.created = created;
            this.ownedBy = ownedBy;
        }

        @JsonProperty("id")
        public String getId() {
            return id;
        }

        @JsonProperty("id")
        public void setId(String id) {
            this.id = id;
        }

        @JsonProperty("object")
        public String getObject() {
            return object;
        }

        @JsonProperty("object")
        public void setObject(String object) {
            this.object = object;
        }

        @JsonProperty("created")
        public long getCreated() {
            return created;
        }

        @JsonProperty("created")
        public void setCreated(long created) {
            this.created = created;
        }

        @JsonProperty("owned_by")
        public String getOwnedBy() {
            return ownedBy;
        }

        @JsonProperty("owned_by")
        public void setOwnedBy(String ownedBy) {
            this.ownedBy = ownedBy;
        }
    }
}
