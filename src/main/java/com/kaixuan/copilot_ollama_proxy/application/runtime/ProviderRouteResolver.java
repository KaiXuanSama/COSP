package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 供应商模型路由解析器。
 *
 * 统一解析带前缀和无前缀的模型名，并返回唯一的运行时供应商配置。
 * 无前缀模型命中多个供应商时拒绝解析，要求调用方使用供应商前缀。
 */
@Service
public class ProviderRouteResolver {

    private final RuntimeProviderCatalog runtimeProviderCatalog;

    /**
     * 创建供应商模型路由解析器。
     *
     * @param runtimeProviderCatalog 启用供应商的运行时配置目录
     */
    public ProviderRouteResolver(RuntimeProviderCatalog runtimeProviderCatalog) {
        this.runtimeProviderCatalog = runtimeProviderCatalog;
    }

    /**
     * 解析客户端模型名对应的唯一供应商和实际模型。
     *
     * @param requestedModel 客户端请求的模型名，可包含 {@code [provider-key]} 前缀
     * @return 解析成功时返回路由；无匹配或无前缀歧义时返回 null
     */
    public ResolvedProviderRoute resolve(String requestedModel) {
        ModelNameUtil.ParseResult parsed = ModelNameUtil.parse(requestedModel);
        String model = parsed.modelName();
        if (model == null || model.isBlank()) {
            return null;
        }

        if (parsed.hasProviderPrefix()) {
            ProviderRuntimeConfiguration provider = runtimeProviderCatalog
                    .getActiveProvider(parsed.providerKey().toLowerCase());
            if (provider == null || !provider.supportsModel(model)) {
                return null;
            }
            return new ResolvedProviderRoute(provider, model, requestedModel);
        }

        List<ProviderRuntimeConfiguration> matches = runtimeProviderCatalog.getActiveProviders().stream()
                .filter(provider -> provider.supportsModel(model))
                .toList();
        if (matches.size() != 1) {
            return null;
        }
        return new ResolvedProviderRoute(matches.getFirst(), model, requestedModel);
    }
}
