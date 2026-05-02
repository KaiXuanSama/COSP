package com.kaixuan.copilot_ollama_proxy.ollama;

/**
 * Ollama /api/show 请求体。
 * Copilot 调用此接口获取指定模型的详细信息（上下文长度、能力、参数量等）。
 * 示例请求：{"model": "mimo-v2.5-pro"}
 */
public class OllamaShowRequest {

    /** 要查询的模型名称 */
    private String model;

    /** 是否返回详细信息（本代理忽略此字段，始终返回完整信息） */
    private boolean verbose;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }
}
