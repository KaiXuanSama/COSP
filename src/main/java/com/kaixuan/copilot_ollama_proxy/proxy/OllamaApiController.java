package com.kaixuan.copilot_ollama_proxy.proxy;

import com.kaixuan.copilot_ollama_proxy.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.ollama.OllamaShowRequest;
import com.kaixuan.copilot_ollama_proxy.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.ollama.OllamaTagsResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Ollama API 兼容控制器 —— 模拟 Ollama 服务器对外暴露的 REST 接口。
 * Copilot 等客户端连接到 localhost:11434 后，会按 Ollama 的协议依次调用这些端点。
 * 本控制器只负责路由和参数接收，真正的业务逻辑（协议转换、上游调用）委托给 {@link MimoProxyService}。
 * 端点清单：
 * - GET  /api/version  → 返回伪装的 Ollama 版本号
 * - GET  /api/tags     → 返回可用模型列表
 * - POST /api/show     → 返回指定模型的详细信息（上下文长度、能力等）
 * - POST /api/chat     → 核心对话接口，支持流式和非流式两种模式
 */
@RestController @RequestMapping("/api")
public class OllamaApiController {

    private final MimoProxyService proxyService;
    /** 伪装的 Ollama 版本号，从 application.yml 的 ollama.version 配置读取 */
    private final String ollamaVersion;

    public OllamaApiController(MimoProxyService proxyService, @Value("${ollama.version}") String ollamaVersion) {
        this.proxyService = proxyService;
        this.ollamaVersion = ollamaVersion;
    }

    /**
     * 返回 Ollama 版本号。
     * Copilot 在连接时会调用此接口确认 Ollama 服务是否可用。
     */
    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of("version", ollamaVersion);
    }

    /**
     * 返回可用模型列表。
     * Copilot 会调用此接口获取所有可用模型，展示在模型选择下拉框中。
     * 返回的是伪装的 Mimo 模型信息，格式与 Ollama 一致。
     */
    @GetMapping("/tags")
    public Mono<OllamaTagsResponse> tags() {
        return proxyService.listModels();
    }

    /**
     * 返回指定模型的详细信息。
     * Copilot 会调用此接口获取模型的上下文长度、能力（completion/tools/vision）等参数。
     * 这些参数决定了 Copilot 如何使用该模型（例如上下文长度决定了单次对话的最大 token 数）。
     */
    @PostMapping("/show")
    public OllamaShowResponse show(@RequestBody OllamaShowRequest request) {
        return proxyService.showModel(request.getModel());
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
            return proxyService.chatStream(request);
        }
        return proxyService.chat(request).flux();
    }
}
