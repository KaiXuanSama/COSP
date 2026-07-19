package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestTransformService;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallLogRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.AppConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRow;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 API 控制器 — 提供管理后台的 JSON API 接口。
 * <p>
 * 前端由 Vue 3 SPA 完全接管，SPA 路由回退由 {@code SpaRoutingConfig} 处理，
 * 本控制器仅提供数据接口。
 */
@RestController
public class AdminPageController {

    private final JdbcUserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final ApiUsageRepository apiUsageRepository;
    private final ProviderConfigRepository providerConfigRepository;
    private final ProviderRequestTransformRepository providerRequestTransformRepository;
    private final ProviderRequestTransformService providerRequestTransformService;
    private final ProviderRequestHeaderService providerRequestHeaderService;
    private final ProviderApiKeyRepository providerApiKeyRepository;
    private final AppConfigRepository appConfigRepository;
    private final ApiCallLogRepository apiCallLogRepository;
    private final WebClient.Builder webClientBuilder;

        public AdminPageController(JdbcUserDetailsManager userDetailsManager, PasswordEncoder passwordEncoder, ApiUsageRepository apiUsageRepository, ProviderConfigRepository providerConfigRepository,
            ProviderApiKeyRepository providerApiKeyRepository,
            ProviderRequestTransformRepository providerRequestTransformRepository,
            ProviderRequestTransformService providerRequestTransformService,
            ProviderRequestHeaderService providerRequestHeaderService,
            AppConfigRepository appConfigRepository, ApiCallLogRepository apiCallLogRepository, WebClient.Builder webClientBuilder) {
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
        this.apiUsageRepository = apiUsageRepository;
        this.providerConfigRepository = providerConfigRepository;
        this.providerApiKeyRepository = providerApiKeyRepository;
        this.providerRequestTransformRepository = providerRequestTransformRepository;
        this.providerRequestTransformService = providerRequestTransformService;
        this.providerRequestHeaderService = providerRequestHeaderService;
        this.appConfigRepository = appConfigRepository;
        this.apiCallLogRepository = apiCallLogRepository;
        this.webClientBuilder = webClientBuilder;
    }

    // ==================== API 统计接口（JSON） ====================

    @GetMapping("/config/api/stats") @ResponseBody
    public ResponseEntity<Map<String, Object>> apiStats() {
        int[] tokens = apiUsageRepository.sumTokensToday();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalApiCalls", apiUsageRepository.countTotal());
        stats.put("todayApiCalls", apiUsageRepository.countToday());
        stats.put("todayInputTokens", tokens[0]);
        stats.put("todayOutputTokens", tokens[1]);
        return ResponseEntity.ok(stats);
    }

    // ==================== 服务商配置 JSON 接口 ====================

    @GetMapping("/config/api/providers") @ResponseBody
    public ResponseEntity<Map<String, Object>> listProviders() {
        List<ProviderConfigRow> all = providerConfigRepository.findAllWithModels();
        Map<Integer, ProviderRequestTransformRow> transforms = providerRequestTransformRepository
                .findByProviderIds(all.stream().map(ProviderConfigRow::id).toList());
        Map<String, Object> result = new LinkedHashMap<>();
        for (ProviderConfigRow p : all) {
            result.put(p.providerKey(), buildProviderView(p, transforms.get(p.id())));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 构建供应商视图，附带脱敏后的 API Key 列表。
     * 明文永不返回前端，仅返回 keyUuid、名称、脱敏值和激活标记。
     */
    private Map<String, Object> buildProviderView(ProviderConfigRow p, ProviderRequestTransformRow transform) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", p.id());
        view.put("providerKey", p.providerKey());
        view.put("displayName", p.displayName());
        view.put("enabled", p.enabled());
        view.put("baseUrl", p.baseUrl());
        view.put("updatedAt", p.updatedAt());
        view.put("models", p.models());
        view.put("apiKeys", buildMaskedApiKeys(p.id()));
        Map<String, Object> requestTransform = new LinkedHashMap<>();
        requestTransform.put("headerRulesVersion", transform != null ? transform.headerRulesVersion() : 1);
        requestTransform.put("headerRulesJson", transform != null ? transform.headerRulesJson() : "[]");
        requestTransform.put("bodyTemplateKeysJson", transform != null
            ? transform.bodyTemplateKeysJson() : ProviderRequestTransformService.DEFAULT_TEMPLATE_KEYS_JSON);
        requestTransform.put("bodyPreviewJson", transform != null
            ? transform.bodyPreviewJson() : ProviderRequestTransformService.DEFAULT_BODY_PREVIEW_JSON);
        requestTransform.put("bodyRulesVersion", transform != null ? transform.bodyRulesVersion() : 1);
        requestTransform.put("bodyRulesJson", transform != null
            ? transform.bodyRulesJson() : ProviderRequestTransformService.EMPTY_BODY_RULES_JSON);
        view.put("requestTransform", requestTransform);
        return view;
    }

    /**
     * 读取某个供应商的 API Key 列表，解密后脱敏返回。
     */
    private List<Map<String, Object>> buildMaskedApiKeys(int providerId) {
        List<Map<String, Object>> masked = new ArrayList<>();
        for (ProviderApiKeyRow row : providerApiKeyRepository.findByProviderId(providerId)) {
            String plaintext = providerApiKeyRepository.decrypt(row);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("keyUuid", row.keyUuid());
            entry.put("name", row.keyName());
            entry.put("masked", maskApiKey(plaintext));
            entry.put("active", row.active());
            masked.add(entry);
        }
        return masked;
    }

    /**
     * 脱敏 API Key，仅保留首尾少量字符。
     */
    private String maskApiKey(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String trimmed = key.trim();
        if (trimmed.length() <= 10) {
            return "****";
        }
        return trimmed.substring(0, 6) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    /**
     * 热力图数据 — 返回最近 N 天每天的 API 调用次数。
     */
    @GetMapping("/config/api/heatmap") @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> heatmapData(@RequestParam(defaultValue = "360") int days) {
        days = Math.max(7, Math.min(365, days));
        List<Map<String, Object>> data = apiUsageRepository.listRecentDays(days);
        Map<String, Map<String, Object>> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : data) {
            byDate.put((String) row.get("usageDate"), row);
        }
        List<Map<String, Object>> full = new ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        for (int i = 364; i >= 0; i--) {
            java.time.LocalDate d = today.minusDays(i);
            String key = d.toString();
            Map<String, Object> row = byDate.get(key);
            if (row != null) {
                full.add(row);
            } else {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("usageDate", key);
                empty.put("callCount", 0);
                empty.put("inputTokens", 0);
                empty.put("outputTokens", 0);
                full.add(empty);
            }
        }
        return ResponseEntity.ok(full);
    }

    // ==================== 服务商快速启用/禁用 ====================

    @PostMapping("/config/api/providers/{providerKey}/toggle") @ResponseBody
    public ResponseEntity<Map<String, Object>> toggleProvider(@PathVariable String providerKey, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        ProviderConfigRow provider = providerConfigRepository.findByKey(providerKey);
        String baseUrl = "";
        if (provider != null) {
            baseUrl = provider.baseUrl() != null ? provider.baseUrl() : "";
        }
        // saveProvider 在记录不存在时会自动插入（首次启用场景）；API Key 独立管理，不受启停影响
        providerConfigRepository.saveProvider(providerKey, enabled, baseUrl);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerKey", providerKey);
        result.put("enabled", enabled);
        return ResponseEntity.ok(result);
    }

    // ==================== 运行配置 ====================

    @GetMapping("/config/api/fake-version") @ResponseBody
    public ResponseEntity<Map<String, Object>> getFakeVersion() {
        String version = appConfigRepository.findConfigValue("fake_version");
        return ResponseEntity.ok(Map.of("fakeVersion", version != null ? version : ""));
    }

    @PostMapping("/config/api/fake-version") @ResponseBody
    public ResponseEntity<Map<String, Object>> saveFakeVersion(@RequestParam String fakeVersion) {
        appConfigRepository.saveConfig("fake_version", fakeVersion.trim());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ==================== 服务商编辑保存 ====================

    @PostMapping("/config/api/providers/{providerKey}/config") @ResponseBody
    public Mono<ResponseEntity<Map<String, Object>>> saveProviderConfig(@PathVariable String providerKey, ServerWebExchange exchange) {
        // WebFlux 读取 application/x-www-form-urlencoded 表单的标准方式：exchange.getFormData()
        return exchange.getFormData().map(form -> {
            java.util.function.BiFunction<String, String, String> getParam = (key, def) -> {
                String v = form.getFirst(key);
                return v != null ? v : def;
            };
            String baseUrl = getParam.apply("baseUrl", "").trim();
            String apiKeysJson = getParam.apply("apiKeys", "[]").trim();
            String activeKeyUuid = getParam.apply("activeKeyUuid", "").trim();
            List<ProviderApiKeyRepository.ApiKeyInput> apiKeyInputs = parseApiKeyInputs(apiKeysJson, activeKeyUuid);
            List<Map<String, Object>> models = new ArrayList<>();
            String prefix = "models[";
            java.util.Set<Integer> indices = new java.util.TreeSet<>();
            for (String key : form.keySet()) {
                if (key.startsWith(prefix) && key.contains("].")) {
                    try {
                        int start = prefix.length();
                        int end = key.indexOf(']', start);
                        indices.add(Integer.parseInt(key.substring(start, end)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            for (int i : indices) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("modelName", getParam.apply(prefix + i + "].name", "").trim());
                m.put("enabled", "on".equals(form.getFirst(prefix + i + "].enabled")));
                m.put("contextSize", getParam.apply(prefix + i + "].contextSize", "0").trim());
                m.put("maxOutputTokens", getParam.apply(prefix + i + "].maxOutputTokens", "128000").trim());
                m.put("capsTools", "on".equals(form.getFirst(prefix + i + "].capsTools")));
                m.put("capsVision", "on".equals(form.getFirst(prefix + i + "].capsVision")));
                m.put("reasoningEffort", getParam.apply(prefix + i + "].reasoningEffort", "Medium").trim());
                models.add(m);
            }
            providerConfigRepository.saveProviderConfigWithModels(
                    providerKey, baseUrl, apiKeyInputs, models);
            return ResponseEntity.ok(Map.<String, Object>of("ok", true));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 解析前端提交的 API Key JSON 列表为仓储输入。
     *
     * 每项结构为 keyUuid（可选）、name、apiKey（可选，未修改时缺省）。
     * activeKeyUuid 指向激活项；若无法匹配则默认激活第一项。
     */
    private List<ProviderApiKeyRepository.ApiKeyInput> parseApiKeyInputs(String apiKeysJson, String activeKeyUuid) {
        List<ProviderApiKeyRepository.ApiKeyInput> inputs = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode array =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(apiKeysJson);
            if (!array.isArray()) {
                return inputs;
            }
            boolean anyActiveMatched = false;
            for (com.fasterxml.jackson.databind.JsonNode node : array) {
                String uuid = node.hasNonNull("keyUuid") ? node.get("keyUuid").asText() : null;
                String name = node.hasNonNull("name") ? node.get("name").asText() : "";
                String plaintext = node.hasNonNull("apiKey") ? node.get("apiKey").asText() : null;
                boolean active = uuid != null && !uuid.isBlank() && uuid.equals(activeKeyUuid);
                if (active) {
                    anyActiveMatched = true;
                }
                inputs.add(new ProviderApiKeyRepository.ApiKeyInput(uuid, name, plaintext, active));
            }
            // 无匹配激活项时默认激活第一项
            if (!anyActiveMatched && !inputs.isEmpty()) {
                ProviderApiKeyRepository.ApiKeyInput first = inputs.get(0);
                inputs.set(0, new ProviderApiKeyRepository.ApiKeyInput(
                        first.keyUuid(), first.keyName(), first.plaintext(), true));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("API Key 列表格式错误: " + e.getMessage(), e);
        }
        return inputs;
    }

    /**
     * 使用当前表单中的 Base URL 与 API Key 从上游拉取模型列表。
     *
     * 支持两种 Key 来源：
     * 1. 前端传入明文 apiKey（新增未保存 / 已保存但重新输入了明文）
     * 2. 前端传入 keyUuid（已保存的 Key，由后端从数据库解密得到明文）
     */
    @PostMapping("/config/api/providers/{providerKey}/pull-models") @ResponseBody
    public Mono<ResponseEntity<Object>> pullProviderModels(@PathVariable String providerKey, @RequestBody Map<String, String> body) {
        String baseUrl = body.getOrDefault("baseUrl", "").trim();
        String submittedApiKey = body.getOrDefault("apiKey", "").trim();
        String keyUuid = body.getOrDefault("keyUuid", "").trim();
        String modelPullPath = body.getOrDefault("modelPullPath", "").trim();
        if (baseUrl.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body((Object) Map.of("ok", false, "error", "请先填写 API 地址。")));
        }

        if (submittedApiKey.isBlank() && keyUuid.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body((Object) Map.of("ok", false, "error", "请先填写 API Key。")));
        }

        return Mono.fromCallable(() -> prepareModelPullRequest(providerKey, submittedApiKey, keyUuid))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(prepared -> {
                    if (prepared == null) {
                        return Mono.just(ResponseEntity.badRequest().body((Object) Map.of(
                                "ok", false, "error", "指定的 API Key 不存在或已被删除，请重新选择。")));
                    }
                    return forwardModelsRequest(baseUrl, prepared.apiKey(), modelPullPath, prepared.headerRulesJson());
                });
    }

    /**
     * 读取已保存 Key 和请求头规则；该方法只在 boundedElastic 调度器调用。
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
        return new ModelPullRequest(apiKey, transform != null ? transform.headerRulesJson() : "[]");
    }

    /**
     * 根据 keyUuid 从数据库查找并解密 API Key。
     *
     * @return 明文 API Key，找不到时返回 null
     */
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
        }).build().get().uri(requestUrl).exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("").map(respBody -> {
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.statusCode().value());
            response.headers().contentType().ifPresent(builder::contentType);
            return builder.body((Object) respBody);
        })).onErrorResume(ex -> {
            String errorMsg = resolvePullModelsErrorMessage(ex);
            return Mono.just(ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON).body((Object) ("{\"error\":\"" + errorMsg.replace("\"", "'") + "\"}")));
        });
    }

    /**
     * 将上游模型拉取的异常转换为用户友好的错误信息。
     */
    private String resolvePullModelsErrorMessage(Throwable ex) {
        // 尝试从 WebClientResponseException 中提取上游返回的错误信息
        if (ex instanceof org.springframework.web.reactive.function.client.WebClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            String body = responseException.getResponseBodyAsString();
            // 尝试解析上游返回的 JSON 错误体
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> errorBody = new com.fasterxml.jackson.databind.ObjectMapper().readValue(body, Map.class);
                Object errorObj = errorBody.get("error");
                if (errorObj instanceof Map<?, ?> errorMap) {
                    String msg = (String) errorMap.get("message");
                    if (msg != null && !msg.isBlank())
                        return msg;
                } else if (errorObj instanceof String msg && !msg.isBlank()) {
                    return msg;
                }
            } catch (Exception ignored) {
                // JSON 解析失败，使用 body 原文
                if (body != null && !body.isBlank() && body.length() < 500) {
                    return body;
                }
            }
            // 按状态码给出友好提示
            return switch (status) {
            case 401, 403 -> "API Key 无效或无权限";
            case 404 -> "模型列表端点不存在，请检查 API 地址";
            case 429 -> "上游服务限流，请稍后重试";
            default -> "上游返回错误 (" + status + ")";
            };
        }
        // 网络层异常
        if (ex instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
            return "无法连接到上游服务，请检查 API 地址是否正确";
        }
        // 其他异常
        String msg = ex.getMessage();
        return (msg != null && !msg.isBlank()) ? "连接上游服务失败: " + msg : "连接上游服务失败";
    }

    private String normalizeModelPullPath(String rawModelPullPath) {
        String path = rawModelPullPath == null ? "" : rawModelPullPath.trim();
        if (path.isBlank()) {
            return "/models";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private record ModelPullRequest(String apiKey, String headerRulesJson) {
    }


    // ==================== 供应商创建与重命名 API ====================

    // TODO: 下一个 API major 移除 /custom-providers 兼容路径，前端改用 /providers。

    /**
     * 返回旧版自定义供应商接口格式。
     *
     * 该兼容端点将在下一个 API 主版本移除，新客户端应使用 {@code /config/api/providers}。
     *
     * @return 所有供应商的列表视图
     */
    @Deprecated
    @GetMapping("/config/api/custom-providers") @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> listLegacyCustomProviders() {
        List<ProviderConfigRow> all = providerConfigRepository.findAllWithModels();
        Map<Integer, ProviderRequestTransformRow> transforms = providerRequestTransformRepository
            .findByProviderIds(all.stream().map(ProviderConfigRow::id).toList());
        return ResponseEntity.ok(all.stream().map(p -> buildProviderView(p, transforms.get(p.id()))).toList());
    }

    @PostMapping({"/config/api/providers", "/config/api/custom-providers"}) @ResponseBody
    public Mono<ResponseEntity<Map<String, Object>>> addProvider(ServerWebExchange exchange) {
        return exchange.getFormData().map(form -> {
            String displayName = form.getFirst("displayName");
            String headerRulesJson = form.getFirst("headerRulesJson");
            String baseUrl = form.getFirst("baseUrl");
            String bodyTemplateKeysJson = form.getFirst("bodyTemplateKeysJson");
            String bodyPreviewJson = form.getFirst("bodyPreviewJson");
            String bodyRulesJson = form.getFirst("bodyRulesJson");
            String name = displayName == null ? "" : displayName.trim();
            if (name.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", "供应商名称不能为空"));
            }
            String providerKey = toProviderKey(name);
            // 检查是否已存在
            if (providerConfigRepository.findByKey(providerKey) != null) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", "该供应商名称已存在"));
            }
            String headers = defaultIfBlank(headerRulesJson, "[]");
            String url = baseUrl == null ? "" : baseUrl.trim();
            try {
                providerRequestTransformService.createProvider(
                    providerKey, name, url, headers,
                        defaultIfBlank(bodyTemplateKeysJson, ProviderRequestTransformService.DEFAULT_TEMPLATE_KEYS_JSON),
                        defaultIfBlank(bodyPreviewJson, ProviderRequestTransformService.DEFAULT_BODY_PREVIEW_JSON),
                        defaultIfBlank(bodyRulesJson, ProviderRequestTransformService.EMPTY_BODY_RULES_JSON));
                return ResponseEntity.ok(Map.<String, Object>of(
                        "ok", true, "providerKey", providerKey, "displayName", name));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping({"/config/api/providers/{providerKey}", "/config/api/custom-providers/{providerKey}"}) @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteProvider(@PathVariable String providerKey) {
        providerConfigRepository.deleteByKey(providerKey);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping({"/config/api/providers/{providerKey}", "/config/api/custom-providers/{providerKey}"}) @ResponseBody
    public Mono<ResponseEntity<Map<String, Object>>> updateProvider(@PathVariable String providerKey, ServerWebExchange exchange) {
        return exchange.getFormData().map(form -> {
            String displayName = form.getFirst("displayName");
            String headerRulesJson = form.getFirst("headerRulesJson");
            String baseUrl = form.getFirst("baseUrl");
            String bodyTemplateKeysJson = form.getFirst("bodyTemplateKeysJson");
            String bodyPreviewJson = form.getFirst("bodyPreviewJson");
            String bodyRulesJson = form.getFirst("bodyRulesJson");
            String name = displayName == null ? "" : displayName.trim();
            if (name.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", "供应商名称不能为空"));
            }
            // 检查原供应商是否存在
            ProviderConfigRow existing = providerConfigRepository.findByKey(providerKey);
            if (existing == null) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", "供应商不存在"));
            }
            String newProviderKey = toProviderKey(name);
            // 如果名称改变了，检查新 key 是否冲突
            if (!newProviderKey.equals(providerKey)) {
                if (providerConfigRepository.findByKey(newProviderKey) != null) {
                    return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", "该供应商名称已存在"));
                }
            }
            String headers = defaultIfBlank(headerRulesJson, "[]");
            String url = baseUrl == null ? "" : baseUrl.trim();
            try {
                providerRequestTransformService.updateProvider(
                    existing.id(), providerKey, newProviderKey, name, url, headers,
                        defaultIfBlank(bodyTemplateKeysJson, ProviderRequestTransformService.DEFAULT_TEMPLATE_KEYS_JSON),
                        defaultIfBlank(bodyPreviewJson, ProviderRequestTransformService.DEFAULT_BODY_PREVIEW_JSON),
                        defaultIfBlank(bodyRulesJson, ProviderRequestTransformService.EMPTY_BODY_RULES_JSON));
                return ResponseEntity.ok(Map.<String, Object>of(
                        "ok", true, "providerKey", newProviderKey, "displayName", name));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String toProviderKey(String displayName) {
        return displayName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    @PostMapping("/config/api/account") @ResponseBody
    public Mono<ResponseEntity<Map<String, Object>>> saveAccountApi(Mono<Authentication> authenticationMono, ServerWebExchange exchange) {
        return Mono.zip(authenticationMono, exchange.getFormData()).map(tuple -> {
            Authentication authentication = tuple.getT1();
            MultiValueMap<String, String> form = tuple.getT2();
            String newUsername = form.getFirst("newUsername");
            String currentPassword = form.getFirst("currentPassword");
            String newPassword = form.getFirst("newPassword");
            String confirmPassword = form.getFirst("confirmPassword");

            String currentUsername = authentication.getName();
            Map<String, Object> result = new LinkedHashMap<>();

            // 验证当前密码
            UserDetails currentUser = userDetailsManager.loadUserByUsername(currentUsername);
            if (currentPassword == null || !passwordEncoder.matches(currentPassword, currentUser.getPassword())) {
                result.put("ok", false);
                result.put("error", "当前密码不正确。");
                return ResponseEntity.ok(result);
            }

            final String finalUsername = (newUsername != null && !newUsername.isBlank() && !newUsername.equals(currentUsername)) ? newUsername.trim() : currentUsername;
            final String finalPassword;
            boolean passwordChanged = false;

            if (newPassword != null && !newPassword.isBlank()) {
                if (newPassword.length() < 4) {
                    result.put("ok", false);
                    result.put("error", "新密码长度至少 4 位。");
                    return ResponseEntity.ok(result);
                }
                if (!newPassword.equals(confirmPassword)) {
                    result.put("ok", false);
                    result.put("error", "两次输入的新密码不一致。");
                    return ResponseEntity.ok(result);
                }
                finalPassword = passwordEncoder.encode(newPassword);
                passwordChanged = true;
            } else {
                finalPassword = null;
            }

            if (!finalUsername.equals(currentUsername) || passwordChanged) {
                userDetailsManager.deleteUser(currentUsername);
                String passwordToUse = passwordChanged ? finalPassword : currentUser.getPassword();
                UserDetails newUser = User.withUsername(finalUsername).password(passwordToUse).roles("ADMIN").build();
                userDetailsManager.createUser(newUser);
                result.put("ok", true);
                result.put("message", "账号信息已修改，请使用新账号重新登录。");
                result.put("usernameChanged", !finalUsername.equals(currentUsername));
            } else {
                result.put("ok", true);
                result.put("message", "未做任何修改。");
            }

            return ResponseEntity.ok(result);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ==================== API 调用日志接口 ====================

    /**
     * 基于游标分页查询 API 调用日志。
     *
     * @param cursor 上一页最后一条记录的 ID，首屏传 null
     * @param pageSize 每页条数（默认 20，最大 100）
     * @return 游标分页结果：items、nextCursor、hasMore、pageSize
     */
    @GetMapping("/config/api/logs") @ResponseBody
    public ResponseEntity<Map<String, Object>> listLogs(@RequestParam(value = "cursor", required = false) Long cursor,
                                                        @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        Map<String, Object> result = apiCallLogRepository.findLogs(cursor, pageSize);
        return ResponseEntity.ok(result);
    }

    /**
     * 查询单条日志详情（含完整请求/响应）。
     *
     * @param id 日志 ID
     * @return 日志详情，不存在时返回 404
     */
    @GetMapping("/config/api/logs/{id}") @ResponseBody
    public ResponseEntity<Map<String, Object>> getLogDetail(@PathVariable long id) {
        Map<String, Object> log = apiCallLogRepository.findLogById(id);
        if (log == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(log);
    }

}