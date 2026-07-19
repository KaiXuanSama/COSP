package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRow;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 使用供应商当前表单配置发现上游模型的应用用例。
 */
@Service
public class ProviderModelDiscoveryService {

    private final ProviderConfigRepository providerConfigRepository;
    private final ProviderApiKeyRepository providerApiKeyRepository;
    private final ProviderRequestTransformRepository providerRequestTransformRepository;
    private final ProviderRequestHeaderService providerRequestHeaderService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public ProviderModelDiscoveryService(ProviderConfigRepository providerConfigRepository,
                                         ProviderApiKeyRepository providerApiKeyRepository,
                                         ProviderRequestTransformRepository providerRequestTransformRepository,
                                         ProviderRequestHeaderService providerRequestHeaderService,
                                         WebClient.Builder webClientBuilder,
                                         ObjectMapper objectMapper) {
        this.providerConfigRepository = providerConfigRepository;
        this.providerApiKeyRepository = providerApiKeyRepository;
        this.providerRequestTransformRepository = providerRequestTransformRepository;
        this.providerRequestHeaderService = providerRequestHeaderService;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    public Mono<ResponseEntity<Object>> pullModels(String providerKey, ModelPullCommand command) {
        if (command.baseUrl().isBlank()) {
            return Mono.just(badRequest("请先填写 API 地址。"));
        }
        if (command.apiKey().isBlank() && command.keyUuid().isBlank()) {
            return Mono.just(badRequest("请先填写 API Key。"));
        }

        return Mono.fromCallable(() -> Optional.ofNullable(
                prepareModelPullRequest(providerKey, command.apiKey(), command.keyUuid())))
                .subscribeOn(Schedulers.boundedElastic())
            .flatMap(prepared -> prepared.isEmpty()
                        ? Mono.just(badRequest("指定的 API Key 不存在或已被删除，请重新选择。"))
                : forwardModelsRequest(command.baseUrl(), prepared.get().apiKey(), command.modelPullPath(),
                    prepared.get().headerRulesJson()));
    }

    private ModelPullRequest prepareModelPullRequest(String providerKey, String submittedApiKey, String keyUuid) {
        String apiKey = submittedApiKey;
        if (apiKey.isBlank()) {
            apiKey = resolveApiKeyByUuid(providerKey, keyUuid);
            if (apiKey == null) {
                return null;
            }
        }
        ProviderConfigRow provider = providerConfigRepository.findByKey(providerKey);
        ProviderRequestTransformRow transform = provider == null ? null
                : providerRequestTransformRepository.findByProviderId(provider.id());
        return new ModelPullRequest(apiKey, transform == null ? "[]" : transform.headerRulesJson());
    }

    private String resolveApiKeyByUuid(String providerKey, String keyUuid) {
        ProviderConfigRow provider = providerConfigRepository.findByKey(providerKey);
        if (provider == null) {
            return null;
        }
        for (ProviderApiKeyRow row : providerApiKeyRepository.findByProviderId(provider.id())) {
            if (keyUuid.equals(row.keyUuid())) {
                return providerApiKeyRepository.decrypt(row);
            }
        }
        return null;
    }

    private Mono<ResponseEntity<Object>> forwardModelsRequest(String rawBaseUrl, String apiKey, String rawModelPullPath,
                                                               String headerRulesJson) {
        String requestUrl = providerRequestHeaderService.buildRequestUrl(rawBaseUrl, normalizeModelPullPath(rawModelPullPath));
        return webClientBuilder.clone().defaultHeaders(headers -> {
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            providerRequestHeaderService.applyHeaders(headers, apiKey, headerRulesJson);
        }).build().get().uri(requestUrl).exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("")
                .map(responseBody -> {
                    ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.statusCode().value());
                    response.headers().contentType().ifPresent(builder::contentType);
                    return builder.body((Object) responseBody);
                })).onErrorResume(exception -> Mono.just(ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                .body((Object) ("{\"error\":\"" + resolvePullModelsErrorMessage(exception).replace("\"", "'") + "\"}"))));
    }

    private String resolvePullModelsErrorMessage(Throwable exception) {
        if (exception instanceof org.springframework.web.reactive.function.client.WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            String body = responseException.getResponseBodyAsString();
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> errorBody = objectMapper.readValue(body, Map.class);
                Object error = errorBody.get("error");
                if (error instanceof Map<?, ?> errorMap) {
                    Object message = errorMap.get("message");
                    if (message instanceof String text && !text.isBlank()) {
                        return text;
                    }
                } else if (error instanceof String text && !text.isBlank()) {
                    return text;
                }
            } catch (Exception ignored) {
                if (body != null && !body.isBlank() && body.length() < 500) {
                    return body;
                }
            }
            return switch (status) {
                case 401, 403 -> "API Key 无效或无权限";
                case 404 -> "模型列表端点不存在，请检查 API 地址";
                case 429 -> "上游服务限流，请稍后重试";
                default -> "上游返回错误 (" + status + ")";
            };
        }
        if (exception instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
            return "无法连接到上游服务，请检查 API 地址是否正确";
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "连接上游服务失败" : "连接上游服务失败: " + message;
    }

    private String normalizeModelPullPath(String rawModelPullPath) {
        String path = rawModelPullPath == null ? "" : rawModelPullPath.trim();
        if (path.isBlank()) {
            return "/models";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "error", message));
    }

    public record ModelPullCommand(String baseUrl, String apiKey, String keyUuid, String modelPullPath) {
        public ModelPullCommand {
            baseUrl = baseUrl == null ? "" : baseUrl.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            keyUuid = keyUuid == null ? "" : keyUuid.trim();
            modelPullPath = modelPullPath == null ? "" : modelPullPath.trim();
        }
    }

    private record ModelPullRequest(String apiKey, String headerRulesJson) {
    }
}