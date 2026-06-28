package com.kaixuan.copilot_ollama_proxy.application.catalog;

/**
 * 可用模型描述符 —— 中立的模型元数据，供 api 层映射为各协议的响应 DTO。
 *
 * @param providerKey   原始供应商键（如 "custom-agentrouter"、"deepseek"）
 * @param displayKey    展示用供应商键（自定义供应商已去除 "custom-" 前缀）
 * @param modelName     模型原始名称（不含供应商前缀）
 * @param prefixedName  带供应商前缀的展示名称（如 "[deepseek]deepseek-v4-flash"）
 * @param capsTools     是否支持工具调用
 * @param capsVision    是否支持视觉
 */
public record AvailableModel(String providerKey, String displayKey, String modelName, String prefixedName,
                             boolean capsTools, boolean capsVision) {
}
