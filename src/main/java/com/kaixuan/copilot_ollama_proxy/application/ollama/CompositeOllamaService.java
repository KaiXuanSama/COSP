package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import org.springframework.stereotype.Service;

/**
 * Ollama 服务路由器。
 * 根据模型名称将详情查询路由到统一的发现服务。
 *
 * 模型列表由 {@code ModelCatalogService} 直接从数据库聚合；
 * 实际聊天走 OpenAI 协议路径（CompositeUpstreamChatService）。
 */
@Service
public class CompositeOllamaService {

    private final OllamaServiceResolver ollamaServiceResolver;

    public CompositeOllamaService(OllamaServiceResolver ollamaServiceResolver) {
        this.ollamaServiceResolver = ollamaServiceResolver;
    }

    private OllamaService resolveService(String modelName) {
        return ollamaServiceResolver.resolve(modelName);
    }

    public OllamaShowResponse showModel(String modelName) {
        OllamaService service = resolveService(modelName);
        if (service == null) {
            return null;
        }
        return service.showModel(modelName);
    }
}
