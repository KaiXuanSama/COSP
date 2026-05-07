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
    private final OllamaServiceResolver ollamaServiceResolver;

    public CompositeOllamaService(List<OllamaService> ollamaServices, OllamaServiceResolver ollamaServiceResolver) {
        this.ollamaServices = ollamaServices;
        this.ollamaServiceResolver = ollamaServiceResolver;
        log.info("CompositeOllamaService 初始化完成，注册服务商数: {}", ollamaServices.size());
    }

    /**
     * 根据模型名称找到对应的服务商。
     */
    private OllamaService resolveService(String modelName) {
        return ollamaServiceResolver.resolve(modelName);
    }

    public Mono<OllamaTagsResponse> listModels() {
        return Flux.fromIterable(ollamaServices).flatMap(service -> service.listModels().onErrorResume(exception -> {
            log.warn("获取服务商 [{}] 模型列表失败: {}", service.getProviderKey(), exception.getMessage());
            return Mono.empty();
        })).flatMapIterable(response -> response.getModels() == null ? List.of() : response.getModels()).collectList().map(models -> {
            OllamaTagsResponse combined = new OllamaTagsResponse();
            combined.setModels(models);
            return combined;
        });
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
