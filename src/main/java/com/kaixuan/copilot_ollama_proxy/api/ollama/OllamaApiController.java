package com.kaixuan.copilot_ollama_proxy.api.ollama;

import com.kaixuan.copilot_ollama_proxy.application.ollama.CompositeOllamaService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaTagsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ollama API 兼容控制器 —— 模拟 Ollama 服务器对外暴露的 REST 接口。
 * Copilot 等客户端连接到 localhost:11434 后，会按 Ollama 的协议依次调用这些端点。
 * 本控制器只负责路由和参数接收，真正的业务逻辑（协议转换、上游调用）委托给 {@link OllamaService}。
 * 端点清单：
 * - GET  /api/version  → 返回伪装的 Ollama 版本号
 * - GET  /api/tags     → 返回可用模型列表
 * - POST /api/show     → 返回指定模型的详细信息（上下文长度、能力等）
 * - POST /api/chat     → 核心对话接口，支持流式和非流式两种模式
 */
@RestController @RequestMapping("/api")
public class OllamaApiController {

    private final CompositeOllamaService ollamaService;
    private final ProviderConfigRepository providerConfigRepository;
    private final String defaultVersion;

    public OllamaApiController(CompositeOllamaService ollamaService, ProviderConfigRepository providerConfigRepository,
            @Value("${ollama.version}") String defaultVersion) {
        this.ollamaService = ollamaService;
        this.providerConfigRepository = providerConfigRepository;
        this.defaultVersion = defaultVersion;
    }

    /**
     * 返回 Ollama 版本号。
     * 优先从数据库读取用户配置的伪造版本号，不存在则使用 application.yml 默认值。
     * Copilot 在连接时会调用此接口确认 Ollama 服务是否可用。
     */
    @GetMapping("/version")
    public Map<String, String> version() {
        String dbVersion = providerConfigRepository.findConfigValue("fake_version");
        String ver = (dbVersion != null && !dbVersion.isBlank()) ? dbVersion : defaultVersion;
        return Map.of("version", ver);
    }

    /**
     * 返回可用模型列表。
     * 从数据库聚合所有已启用服务商下用户勾选的模型。
     * 如果没有任何模型启用（或所有服务商均未启用），则回退返回 "nano_llm"。
     */
    @GetMapping("/tags")
    public Mono<OllamaTagsResponse> tags() {
        return Mono.fromCallable(() -> {
            var response = new OllamaTagsResponse();
            List<OllamaTagsResponse.ModelInfo> allModels = new ArrayList<>();

            // 从数据库读取所有已启用服务商及其已启用模型
            List<Map<String, Object>> activeProviders = providerConfigRepository
                    .findAllActiveProvidersWithEnabledModels();
            for (Map<String, Object> provider : activeProviders) {
                String providerKey = (String) provider.getOrDefault("providerKey", "unknown");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> models = (List<Map<String, Object>>) provider.get("models");
                if (models == null)
                    continue;
                for (Map<String, Object> m : models) {
                    String modelName = (String) m.getOrDefault("modelName", "");
                    if (modelName.isEmpty())
                        continue;
                    boolean capsTools = Boolean.TRUE.equals(m.get("capsTools"));
                    boolean capsVision = Boolean.TRUE.equals(m.get("capsVision"));
                    allModels.add(createModelInfo(modelName, providerKey, capsTools, capsVision));
                }
            }

            if (allModels.isEmpty()) {
                // 没有任何启用的模型，回退返回 nano_llm
                allModels.add(createNanoLlmInfo());
            }

            response.setModels(allModels);
            return response;
        });
    }

    private OllamaTagsResponse.ModelInfo createModelInfo(String modelName, String providerKey, boolean capsTools,
            boolean capsVision) {
        var info = new OllamaTagsResponse.ModelInfo();
        info.setName(modelName);
        info.setModel(modelName);
        info.setModifiedAt(java.time.Instant.now().toString());
        info.setSize(0);
        info.setDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
        var details = new OllamaTagsResponse.ModelDetails();
        details.setFormat(providerKey);
        details.setFamily(providerKey.substring(0, 1).toUpperCase() + providerKey.substring(1));
        details.setFamilies(List.of(providerKey));
        details.setParameterSize("unknown");
        details.setQuantizationLevel("none");
        info.setDetails(details);
        return info;
    }

    private OllamaTagsResponse.ModelInfo createNanoLlmInfo() {
        var nanoModel = new OllamaTagsResponse.ModelInfo();
        nanoModel.setName("nano_llm");
        nanoModel.setModel("nano_llm");
        nanoModel.setModifiedAt(java.time.Instant.now().toString());
        nanoModel.setSize(0);
        nanoModel.setDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
        var details = new OllamaTagsResponse.ModelDetails();
        details.setFormat("gguf");
        details.setFamily("nano");
        details.setFamilies(List.of("nano"));
        details.setParameterSize("1B");
        details.setQuantizationLevel("none");
        nanoModel.setDetails(details);
        return nanoModel;
    }

    /**
     * 返回指定模型的详细信息。
     * Copilot 会调用此接口获取模型的上下文长度、能力（completion/tools/vision）等参数。
     * 这些参数决定了 Copilot 如何使用该模型（例如上下文长度决定了单次对话的最大 token 数）。
     */
    @PostMapping("/show")
    public OllamaShowResponse show(@RequestBody OllamaShowRequest request) {
        return ollamaService.showModel(request.getModel());
    }

    /**
     * 核心对话接口 —— 接收用户的聊天请求，转发给 Mimo 后端，返回模型的回复。
     * 支持两种模式：
     * - 流式（stream=true）：返回 NDJSON 格式，每个 JSON 对象是一个增量 chunk，Copilot 可以实时显示
     * - 非流式（stream=false）：等待模型生成完毕后一次性返回完整回复
     * produces = APPLICATION_NDJSON_VALUE 表示响应格式为换行分隔的 JSON（Newline Delimited JSON），
     * 这是 Ollama 流式响应的标准格式。
     */
    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<OllamaChatResponse> chat(@RequestBody OllamaChatRequest request) {
        if (request.isStream()) {
            return ollamaService.chatStream(request);
        }
        return ollamaService.chat(request).flux();
    }
}
