package com.kaixuan.copilot_ollama_proxy.application.ollama;

import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaTagsResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OllamaService {

    Mono<OllamaTagsResponse> listModels();

    OllamaShowResponse showModel(String modelName);

    Mono<OllamaChatResponse> chat(OllamaChatRequest request);

    Flux<OllamaChatResponse> chatStream(OllamaChatRequest request);
}