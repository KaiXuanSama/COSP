package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaTagsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Ollama 服务组合器 —— 代理所有已注册的 OllamaService 实现，
 * 根据模型名称路由到对应的服务商。
 *
 * 注意：此类不实现 OllamaService 接口，避免 Spring 自动注入时产生循环依赖。
 * 控制器直接注入此类。
 */
@Service
public class CompositeOllamaService {

    private static final Logger log = LoggerFactory.getLogger(CompositeOllamaService.class);

    private final List<OllamaService> ollamaServices;

    public CompositeOllamaService(List<OllamaService> ollamaServices) {
        this.ollamaServices = ollamaServices;
        log.info("CompositeOllamaService 初始化，已注册 {} 个服务商: {}", ollamaServices.size(),
                ollamaServices.stream().map(OllamaService::getProviderKey).collect(Collectors.joining(", ")));
    }

    /**
     * 根据模型名称找到对应的服务商。
     */
    private OllamaService resolveService(String modelName) {
        for (OllamaService service : ollamaServices) {
            if (service.supportsModel(modelName)) {
                log.debug("模型 [{}] 路由到服务商 [{}]", modelName, service.getProviderKey());
                return service;
            }
        }
        if (!ollamaServices.isEmpty()) {
            log.warn("模型 [{}] 未找到匹配的服务商，使用默认 [{}]", modelName, ollamaServices.get(0).getProviderKey());
            return ollamaServices.get(0);
        }
        return null;
    }

    public Mono<OllamaTagsResponse> listModels() {
        OllamaTagsResponse combined = new OllamaTagsResponse();
        List<OllamaTagsResponse.ModelInfo> allModels = new java.util.ArrayList<>();
        for (OllamaService service : ollamaServices) {
            try {
                OllamaTagsResponse response = service.listModels().block();
                if (response != null && response.getModels() != null) {
                    allModels.addAll(response.getModels());
                }
            } catch (Exception e) {
                log.warn("获取服务商 [{}] 模型列表失败: {}", service.getProviderKey(), e.getMessage());
            }
        }
        combined.setModels(allModels);
        return Mono.just(combined);
    }

    public OllamaShowResponse showModel(String modelName) {
        OllamaService service = resolveService(modelName);
        if (service == null) {
            return null;
        }
        return service.showModel(modelName);
    }

    public Mono<OllamaChatResponse> chat(OllamaChatRequest request) {
        OllamaService service = resolveService(request.getModel());
        if (service == null) {
            return Mono.error(new RuntimeException("没有可用的服务商来处理模型: " + request.getModel()));
        }
        return service.chat(request);
    }

    public Flux<OllamaChatResponse> chatStream(OllamaChatRequest request) {
        OllamaService service = resolveService(request.getModel());
        if (service == null) {
            return Flux.error(new RuntimeException("没有可用的服务商来处理模型: " + request.getModel()));
        }
        return service.chatStream(request);
    }
}
