package com.kaixuan.copilot_ollama_proxy.application.runtime;

/**
 * 已解析的供应商模型路由。
 *
 * 路由在应用层完成后，将供应商配置与实际模型名显式传递给统一执行器，
 * 避免执行器重新扫描配置目录或依赖线程上下文。
 *
 * @param provider 目标供应商的运行时配置
 * @param model 实际上游模型名，不包含供应商前缀
 * @param requestedModel 客户端原始请求模型名
 */
public record ResolvedProviderRoute(
        ProviderRuntimeConfiguration provider,
        String model,
        String requestedModel) {
}
