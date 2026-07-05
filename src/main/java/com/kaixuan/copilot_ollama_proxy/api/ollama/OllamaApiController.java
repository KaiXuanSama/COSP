package com.kaixuan.copilot_ollama_proxy.api.ollama;

import com.kaixuan.copilot_ollama_proxy.application.ollama.CompositeOllamaService;
import com.kaixuan.copilot_ollama_proxy.application.catalog.ModelCatalogService;
import com.kaixuan.copilot_ollama_proxy.application.config.AppConfigService;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaTagsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ollama API 兼容控制器 —— 模拟 Ollama 服务器对外暴露的模型发现接口。
 *
 * Copilot 等客户端连接到 localhost:11434 后，会按 Ollama 的协议依次调用这些端点
 * 进行模型发现和能力查询。实际聊天走 OpenAI 协议（/v1/chat/completions）。
 *
 * 端点清单：
 * - GET  /api/version  → 返回伪装的 Ollama 版本号
 * - GET  /api/tags     → 返回可用模型列表
 * - POST /api/show     → 返回指定模型的详细信息（上下文长度、能力等）
 */
@RestController @RequestMapping("/api")
public class OllamaApiController {

    private final CompositeOllamaService ollamaService;
    private final ModelCatalogService modelCatalogService;
    private final AppConfigService appConfigService;
    private final String defaultVersion;

    public OllamaApiController(CompositeOllamaService ollamaService,
                               ModelCatalogService modelCatalogService,
                               AppConfigService appConfigService,
                               @Value("${ollama.version}") String defaultVersion) {
        this.ollamaService = ollamaService;
        this.modelCatalogService = modelCatalogService;
        this.appConfigService = appConfigService;
        this.defaultVersion = defaultVersion;
    }

    /**
     * 返回 Ollama 版本号。
     * 优先从数据库读取用户配置的伪造版本号，不存在则使用 application.yml 默认值。
     * Copilot 在连接时会调用此接口确认 Ollama 服务是否可用。
     */
    @GetMapping("/version")
    public Mono<Map<String, String>> getVersion() {
        return Mono.fromCallable(() -> {
            String dbVersion = appConfigService.findValue("fake_version");
            String ver = (dbVersion != null && !dbVersion.isBlank()) ? dbVersion : defaultVersion;
            return Map.of("version", ver);
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 返回可用模型列表。
     * 通过 {@link ModelCatalogService} 聚合所有已启用服务商下用户勾选的模型。
     * 如果没有任何模型启用（或所有服务商均未启用），则回退返回 "nano_llm"。
     */
    @GetMapping("/tags")
    public Mono<OllamaTagsResponse> listTags() {
        return Mono.fromCallable(() -> {
            var response = new OllamaTagsResponse();
            List<OllamaTagsResponse.ModelInfo> allModels = new ArrayList<>();

            for (var model : modelCatalogService.listAvailableModels()) {
                allModels.add(createModelInfo(model));
            }

            if (allModels.isEmpty()) {
                // 没有任何启用的模型，回退返回 nano_llm
                allModels.add(createNanoLlmInfo());
            }

            response.setModels(allModels);
            return response;
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 构造单个模型的 ModelInfo 对象。
     * 模型名称会添加供应商前缀，格式为 [ProviderKey] modelName。
     * 根据可用模型描述符生成符合 Ollama 规范的模型信息。
     * @param model 可用模型描述符（含原始供应商键、展示前缀名、能力标记）
     * @return 构造好的 ModelInfo 对象
     */
    private OllamaTagsResponse.ModelInfo createModelInfo(com.kaixuan.copilot_ollama_proxy.application.catalog.AvailableModel model) {
        String providerKey = model.providerKey();
        var info = new OllamaTagsResponse.ModelInfo();
        String prefixedName = model.prefixedName();
        info.setName(prefixedName);
        info.setModel(prefixedName);
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

        // 从数据库读取能力列表，避免插件因 tags 缺少 capabilities 而无法展示工具/视觉功能
        List<String> capabilities = new ArrayList<>();
        capabilities.add("completion");
        if (model.capsTools()) capabilities.add("tools");
        if (model.capsVision()) capabilities.add("vision");
        info.setCapabilities(capabilities);

        // 在 tags 中直接返回上下文长度和最大输出，避免插件额外调用 /api/show
        // 插件计算显示的总上下文 = context_length + max_output_tokens，
        // 因此 context_length 需减去 max_output_tokens 才能让显示值等于用户配置的上下文大小
        if (model.contextSize() > 0) {
            int maxOutput = model.maxOutputTokens() > 0 ? model.maxOutputTokens() : 8192;
            info.setContextLength(Math.max(model.contextSize() - maxOutput, maxOutput));
            info.setMaxOutputTokens(maxOutput);
        }

        return info;
    }

    /**
     * 构造 nano_llm 模型的 ModelInfo 对象。
     * 这是一个兜底模型，当数据库中没有任何启用的模型时返回。
     * 模型名称固定为 "nano_llm"，能力包含 "completion" 和 "tools"，以确保 Copilot 可以选中使用。
     */
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
        nanoModel.setCapabilities(List.of("completion", "tools"));
        return nanoModel;
    }

    /**
     * 返回指定模型的详细信息。
     * Copilot 会调用此接口获取模型的上下文长度、能力（completion/tools/vision）等参数。
     * 这些参数决定了 Copilot 如何使用该模型（例如上下文长度决定了单次对话的最大 token 数）。
     * 如果请求的是兜底模型 "nano_llm"，直接构造响应，不经过 provider 链。
     */
    @PostMapping("/show")
    public Mono<OllamaShowResponse> showModel(@RequestBody OllamaShowRequest request) {
        if ("nano_llm".equals(request.getModel())) {
            return Mono.just(createNanoLlmShowResponse());
        }
        return Mono.fromCallable(() -> ollamaService.showModel(request.getModel()))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 构造 nano_llm 兜底模型的 /api/show 响应。
     * 当数据库中没有任何启用的模型时，tags 接口会返回 nano_llm，
     * Copilot 随后会调用 show 接口获取模型详情，此处直接构造响应避免 provider 链查找失败。
     */
    private OllamaShowResponse createNanoLlmShowResponse() {
        OllamaShowResponse response = new OllamaShowResponse();
        response.setParameters("temperature 0.7\nnum_ctx 4096");
        response.setLicense("Proprietary");
        response.setModifiedAt(java.time.Instant.now().toString());
        response.setCapabilities(List.of("completion", "tools"));

        var details = new OllamaShowResponse.ShowDetails();
        details.setParentModel("");
        details.setFormat("gguf");
        details.setFamily("nano");
        details.setFamilies(List.of("nano"));
        details.setParameterSize("1B");
        details.setQuantizationLevel("none");
        response.setDetails(details);

        response.setModelInfo(Map.of("general.architecture", "nano", "general.basename", "nano_llm", "nano.context_length", 4096, "nano.embedding_length", 8192));
        return response;
    }
}
