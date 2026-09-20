package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AuthHeaderSetting;
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
                : forwardModelsRequest(command, prepared.get()));
    }

    /**
     * 汇总一次拉取需要的持久化状态：凭据明文、请求头规则、出站鉴权头装配方式。
     *
     * <h2>三者都取自数据库，与表单里的地址不同源</h2>
     * 地址和协议由 {@link ModelPullCommand} 从表单带来（用户可能正在改还没保存），
     * 而这三项一律读库。这个不对称是既有约定，本次沿用而非引入：
     * 请求头规则从来如此，鉴权头方式跟它同源才不会出现「拉取用旧规则配新鉴权头」。
     *
     * <p>对鉴权头来说这还恰好是<strong>严格正确</strong>的：该设置的入口在新增/修改
     * 供应商弹窗，而拉取按钮在编辑抽屉里 —— 抽屉根本不存在「未保存的鉴权头」这种状态，
     * 因此读库读到的就是用户看到的。
     *
     * @return 准备好的请求材料；指定的 keyUuid 查不到凭据时返回 {@code null}
     */
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
        // 供应商查不到时传 null 而不是在这里写默认值：缺省语义只存在于
        // AuthHeaderSetting.parse 一处，两边各写一份就会在将来改默认值时漏掉一处。
        return new ModelPullRequest(apiKey, transform == null ? "[]" : transform.headerRulesJson(),
                provider == null ? null : provider.authHeaderJson());
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

    /**
     * 把两个 record 整体传进来，而不是摊开成 6 个参数。
     *
     * <p>摊开后会是 3 个来自 {@link ModelPullCommand}、3 个来自 {@link ModelPullRequest}
     * 的裸值，其中 {@code headerRulesJson} 与 {@code authHeaderJson} 都是 {@code String} ——
     * 调换顺序<strong>能编译</strong>，症状却是「规则集被当鉴权配置解析（静默回默认）、
     * 鉴权配置被当规则集解析（打条 warn 然后无规则）」。传 record 让类型挡住这种写法。
     */
    private Mono<ResponseEntity<Object>> forwardModelsRequest(ModelPullCommand command, ModelPullRequest prepared) {
        String requestUrl = providerRequestHeaderService.buildRequestUrl(
                command.baseUrl(), normalizeModelPullPath(command.modelPullPath()));
        return webClientBuilder.clone().defaultHeaders(headers -> {
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            // 与聊天链路读同一列：拉取能通而聊天 401（或反之）这类「只能靠对比两处代码才能
            // 解释」的现象因此不可能发生。无下游请求头意味着两项探测皆为假，于是两种模式
            // 都落到配置的头名 —— 「取下游」在这条路径上等价于「取设置」，语义正确。
            providerRequestHeaderService.applyHeaders(headers, prepared.apiKey(), prepared.headerRulesJson(),
                    AuthHeaderSetting.parse(prepared.authHeaderJson(), objectMapper));
            applyProtocolHeaders(headers, command.protocol());
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
     * 补上目标协议特有的非鉴权请求头。
     *
     * <h2>为何拉取模型也要分协议</h2>
     * 两个协议的模型列表端点<strong>路径完全相同</strong>（都是 {@code GET /v1/models}），
     * 只有请求头不同：Anthropic 把 {@code anthropic-version} 列为必需头，缺失时官方 API 返回 400。
     * 若不分协议，从一个只有 Anthropic 端点的供应商拉模型就会稳定得到 400，
     * 而错误消息会指向「地址不对」—— 地址其实是对的。
     *
     * <h2>鉴权头不在这里</h2>
     * 它由 {@code ProviderRequestHeaderService} 按<strong>供应商级配置</strong>装配
     * （取下游 / 取设置 × {@code Authorization} / {@code x-api-key}），本方法只补版本头。
     * 这一处曾有一份「双认证头」副本，与聊天链路各写一遍；收归一处后，模型拉取与聊天用的是
     * 同一套鉴权口径 —— 拉取能通而聊天 401（或反之）这类只能靠对比两处代码才能解释的现象
     * 因此不再可能。
     *
     * <p>注意<strong>协议仍然决定版本头</strong>，只是不再决定鉴权头。两件事的判据不同：
     * {@code anthropic-version} 是那条线路的协议要求（缺失即 400），而头名取决于用户配了什么。
     *
     * <p>版本头只在缺失时设置，因此供应商自定义头规则（已在 {@code applyHeaders} 里生效）
     * 仍能覆盖它 —— 某些中转站要求特定版本号。
     *
     * <h2>判断写成「排除 MESSAGES」而非「列举需要版本头的协议」</h2>
     * {@link WireProtocol#RESPONSES} 与 {@link WireProtocol#CHAT} 同为 OpenAI 系接口：
     * 同一个 {@code GET /v1/models}，且都<strong>不能</strong>带 {@code anthropic-version}。
     * 现在这个否定式判断让新加入的 OpenAI 系协议自动落到正确的一侧，而改成逐协议
     * {@code switch} 就得记着为每个新协议补一条 —— 漏掉的症状是给一个 OpenAI 端点发了
     * Anthropic 的版本头，多数中转站会忽略它，于是错误要等到某个严格上游才暴露。
     *
     * <p>这条理由里曾还有一句「同一个 {@code Authorization} 头」，现已删除：
     * 头名由供应商配置决定，与协议无关，两个 OpenAI 系协议在这一维上不再有共同点。
     */
    private void applyProtocolHeaders(HttpHeaders headers, WireProtocol protocol) {
        if (protocol != WireProtocol.MESSAGES) {
            return;
        }
        if (!headers.containsKey(ANTHROPIC_VERSION_HEADER)) {
            headers.set(ANTHROPIC_VERSION_HEADER, ANTHROPIC_VERSION_VALUE);
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
            protocol = protocol == null ? WireProtocol.CHAT : protocol;
        }

        /**
         * 从请求体的字符串解出协议，认不得一律按 OpenAI。
         *
         * <p>不报错而静默回退：拉取模型是一个辅助操作，因一个认不得的协议名就拒绝整个请求
         * 比「按默认协议试一下」更让人因惑 —— 且 OpenAI 是绝大多数供应商的形态。
         */
        public static WireProtocol parseProtocol(String raw) {
            if (raw == null || raw.isBlank()) {
                return WireProtocol.CHAT;
            }
            for (WireProtocol candidate : WireProtocol.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
            return WireProtocol.CHAT;
        }
    }

    /**
     * 一次拉取所需的持久化状态，全部取自数据库（见 {@link #prepareModelPullRequest}）。
     *
     * @param apiKey          解密后的凭据明文
     * @param headerRulesJson 供应商请求头规则原文；无规则时为 {@code "[]"}
     * @param authHeaderJson  出站鉴权头装配方式原文；供应商查不到时为 {@code null}，
     *                        由 {@link AuthHeaderSetting#parse} 兜底
     */
    private record ModelPullRequest(String apiKey, String headerRulesJson, String authHeaderJson) {
    }
}