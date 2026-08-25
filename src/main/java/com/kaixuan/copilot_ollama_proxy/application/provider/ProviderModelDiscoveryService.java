package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRow;
import org.springframework.http.HttpHeaders;
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

    /** Anthropic 必需的版本头，与上游聊天链路取同一个值。 */
    private static final String ANTHROPIC_VERSION_HEADER = "anthropic-version";
    private static final String ANTHROPIC_VERSION_VALUE = "2023-06-01";

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
                    prepared.get().headerRulesJson(), command.protocol()));
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
                                                               String headerRulesJson, WireProtocol protocol) {
        String requestUrl = providerRequestHeaderService.buildRequestUrl(rawBaseUrl, normalizeModelPullPath(rawModelPullPath));
        return webClientBuilder.clone().defaultHeaders(headers -> {
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            providerRequestHeaderService.applyHeaders(headers, apiKey, headerRulesJson);
            applyProtocolHeaders(headers, apiKey, protocol);
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

    /**
     * 补上目标协议特有的请求头。
     *
     * <h2>为何拉取模型也要分协议</h2>
     * 两个协议的模型列表端点<strong>路径完全相同</strong>（都是 {@code GET /v1/models}），
     * 只有请求头不同：Anthropic 把 {@code anthropic-version} 列为必需头，缺失时官方 API 返回 400。
     * 若不分协议，从一个只有 Anthropic 端点的供应商拉模型就会稳定得到 400，
     * 而错误消息会指向「地址不对」—— 地址其实是对的。
     *
     * <p>{@code x-api-key} 与上游聊天链路保持同一口径：官方用它，而多数 OpenAI 兼容中转站
     * 沿用 {@code Authorization: Bearer}，两个都给以兼容两类上游。
     *
     * <p>两个头都只在缺失时设置，因此供应商自定义头规则（已在 {@code applyHeaders} 里生效）
     * 仍能覆盖它们 —— 某些中转站要求特定版本号。
     */
    private void applyProtocolHeaders(HttpHeaders headers, String apiKey, WireProtocol protocol) {
        if (protocol != WireProtocol.ANTHROPIC) {
            return;
        }
        if (!headers.containsKey(ANTHROPIC_VERSION_HEADER)) {
            headers.set(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION_VALUE);
        }
        if (!headers.containsKey("x-api-key") && apiKey != null && !apiKey.isBlank()) {
            headers.set("x-api-key", apiKey);
        }
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

    /**
     * @param protocol 拉取走哪条线路协议；缺失或认不得时按 OpenAI 处理
     */
    public record ModelPullCommand(String baseUrl, String apiKey, String keyUuid, String modelPullPath,
                                   WireProtocol protocol) {
        public ModelPullCommand {
            baseUrl = baseUrl == null ? "" : baseUrl.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            keyUuid = keyUuid == null ? "" : keyUuid.trim();
            modelPullPath = modelPullPath == null ? "" : modelPullPath.trim();
            protocol = protocol == null ? WireProtocol.OPENAI : protocol;
        }

        /**
         * 从请求体的字符串解出协议，认不得一律按 OpenAI。
         *
         * <p>不报错而静默回退：拉取模型是一个辅助操作，因一个认不得的协议名就拒绝整个请求
         * 比「按默认协议试一下」更让人因惑 —— 且 OpenAI 是绝大多数供应商的形态。
         */
        public static WireProtocol parseProtocol(String raw) {
            if (raw == null || raw.isBlank()) {
                return WireProtocol.OPENAI;
            }
            for (WireProtocol candidate : WireProtocol.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
            return WireProtocol.OPENAI;
        }
    }

    private record ModelPullRequest(String apiKey, String headerRulesJson) {
    }
}