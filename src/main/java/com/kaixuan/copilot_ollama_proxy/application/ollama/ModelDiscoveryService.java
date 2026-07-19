package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRouteResolver;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.generic.discovery.GenericDiscoveryService;
import org.springframework.stereotype.Service;

/**
 * Ollama 模型发现应用服务。
 * 模型列表由 {@code ModelCatalogService} 直接从数据库聚合，本服务仅处理模型详情查询。
 */
@Service
public class ModelDiscoveryService {

    private final ProviderRouteResolver providerRouteResolver;
    private final GenericDiscoveryService genericDiscoveryService;

    /**
     * 创建模型发现应用服务。
     *
     * @param providerRouteResolver 供应商模型路由解析器
     * @param genericDiscoveryService 统一模型详情执行器
     */
    public ModelDiscoveryService(ProviderRouteResolver providerRouteResolver,
                                 GenericDiscoveryService genericDiscoveryService) {
        this.providerRouteResolver = providerRouteResolver;
        this.genericDiscoveryService = genericDiscoveryService;
    }

    /**
     * 查询指定模型的 Ollama 详情。
     *
     * @param modelName 请求模型名称
     * @return 模型详情；没有唯一匹配的供应商模型时返回 {@code null}
     */
    public OllamaShowResponse showModel(String modelName) {
        ResolvedProviderRoute route = providerRouteResolver.resolve(modelName);
        if (route == null) {
            return null;
        }
        return genericDiscoveryService.showModel(route);
    }
}