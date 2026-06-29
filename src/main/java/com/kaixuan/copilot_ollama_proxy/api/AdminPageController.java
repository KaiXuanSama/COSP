package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiCallLogRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.provider.generic.openai.RequestTransformEngine;
import org.springframework.http.HttpHeaders;
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
    private final ApiCallLogRepository apiCallLogRepository;
    private final WebClient.Builder webClientBuilder;

    public AdminPageController(JdbcUserDetailsManager userDetailsManager, PasswordEncoder passwordEncoder, ApiUsageRepository apiUsageRepository, ProviderConfigRepository providerConfigRepository,
            ApiCallLogRepository apiCallLogRepository, WebClient.Builder webClientBuilder) {
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
        this.apiUsageRepository = apiUsageRepository;
        this.providerConfigRepository = providerConfigRepository;
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
        Map<String, Object> result = new LinkedHashMap<>();
        for (ProviderConfigRow p : all) {
            result.put(p.providerKey(), p);
        }
        return ResponseEntity.ok(result);
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
        String apiKey = "";
        String customTransforms = "{}";
        if (provider != null) {
            baseUrl = provider.baseUrl() != null ? provider.baseUrl() : "";
            apiKey = provider.apiKey() != null ? provider.apiKey() : "";
            customTransforms = provider.customTransforms() != null ? provider.customTransforms() : "{}";
        }
        // saveProvider 在记录不存在时会自动插入（首次启用场景）
        providerConfigRepository.saveProvider(providerKey, enabled, baseUrl, apiKey, "openai", customTransforms);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("providerKey", providerKey);
        result.put("enabled", enabled);
        return ResponseEntity.ok(result);
    }

    // ==================== 运行配置 ====================

    @GetMapping("/config/api/fake-version") @ResponseBody
    public ResponseEntity<Map<String, Object>> getFakeVersion() {
        String version = providerConfigRepository.findConfigValue("fake_version");
        return ResponseEntity.ok(Map.of("fakeVersion", version != null ? version : ""));
    }

    @PostMapping("/config/api/fake-version") @ResponseBody
    public ResponseEntity<Map<String, Object>> saveFakeVersion(@RequestParam String fakeVersion) {
        providerConfigRepository.saveConfig("fake_version", fakeVersion.trim());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ==================== 服务商编辑保存 ====================

    @PostMapping("/config/api/providers/{providerKey}/config") @ResponseBody
    public Mono<ResponseEntity<Map<String, Object>>> saveProviderConfig(@PathVariable String providerKey, ServerWebExchange exchange) {
        // WebFlux 读取 application/x-www-form-urlencoded 表单的标准方式：exchange.getFormData()
        return exchange.getFormData().map(form -> {
            java.util.function.BiFunction<String, String, String> getParam =
                    (key, def) -> { String v = form.getFirst(key); return v != null ? v : def; };
            String baseUrl = getParam.apply("baseUrl", "").trim();
            String apiKey = getParam.apply("apiKey", "").trim();
            int providerId = providerConfigRepository.updateProviderConfig(providerKey, baseUrl, apiKey, "openai");
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
                m.put("capsTools", "on".equals(form.getFirst(prefix + i + "].capsTools")));
                m.put("capsVision", "on".equals(form.getFirst(prefix + i + "].capsVision")));
                m.put("reasoningEffort", getParam.apply(prefix + i + "].reasoningEffort", "Medium").trim());
                models.add(m);
            }
            providerConfigRepository.saveModels(providerId, models);
            return ResponseEntity.ok(Map.<String, Object>of("ok", true));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 使用当前表单中的 Base URL 与 API Key 从上游拉取模型列表。
     */
    @PostMapping("/config/api/providers/{providerKey}/pull-models") @ResponseBody
    public Mono<ResponseEntity<Object>> pullProviderModels(@PathVariable String providerKey, @RequestBody Map<String, String> body) {
        if (!supportsProviderKey(providerKey)) {
            return Mono.just(ResponseEntity.badRequest().body((Object) Map.of("ok", false, "error", "不支持的服务商。")));
        }

        String baseUrl = body.getOrDefault("baseUrl", "").trim();
        String apiKey = body.getOrDefault("apiKey", "").trim();
        String modelPullPath = body.getOrDefault("modelPullPath", "").trim();
        if (baseUrl.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body((Object) Map.of("ok", false, "error", "请先填写 API 地址。")));
        }
        if (apiKey.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body((Object) Map.of("ok", false, "error", "请先填写 API Key。")));
        }

        return forwardModelsRequest(providerKey, baseUrl, apiKey, modelPullPath);
    }

    // ==================== 账号修改（JSON API） ====================

    /**
     * 获取当前登录用户信息。
     */
    @GetMapping("/config/api/me") @ResponseBody
    public Mono<ResponseEntity<Map<String, Object>>> currentUser(Mono<Authentication> authentication) {
        return authentication.map(auth -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("username", auth.getName());
            result.put("role", auth.getAuthorities().stream().findFirst().map(Object::toString).orElse(""));
            return ResponseEntity.ok(result);
        });
    }

    private boolean supportsProviderKey(String providerKey) {
        return switch (providerKey) {
        case "mimo", "deepseek" -> true;
        default -> providerKey.startsWith("custom-");
        };
    }

    private Mono<ResponseEntity<Object>> forwardModelsRequest(String providerKey, String rawBaseUrl, String apiKey, String rawModelPullPath) {
        String requestUrl = rawBaseUrl.replaceAll("/+$", "") + normalizeModelPullPath(rawModelPullPath);
        return webClientBuilder.clone().defaultHeaders(headers -> {
            headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
            applyModelDiscoveryAuthHeaders(providerKey, headers, apiKey);
            // 自定义供应商：应用 custom_transforms 中的自定义请求头
            if (providerKey.startsWith("custom-")) {
                ProviderConfigRow provider = providerConfigRepository.findByKey(providerKey);
                if (provider != null) {
                    String customTransforms = provider.customTransforms() != null ? provider.customTransforms() : "{}";
                    RequestTransformEngine.applyCustomHeaders(headers, apiKey, customTransforms, new com.fasterxml.jackson.databind.ObjectMapper());
                }
            }
        }).build().get().uri(requestUrl).exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("").map(respBody -> {
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.statusCode().value());
            response.headers().contentType().ifPresent(builder::contentType);
            return builder.body((Object) respBody);
        })).onErrorResume(ex -> {
            String errorMsg = resolvePullModelsErrorMessage(ex);
            return Mono.just(ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON)
                    .body((Object) ("{\"error\":\"" + errorMsg.replace("\"", "'") + "\"}")));
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
                    if (msg != null && !msg.isBlank()) return msg;
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

    private void applyModelDiscoveryAuthHeaders(String providerKey, HttpHeaders headers, String apiKey) {
        if ("mimo".equals(providerKey)) {
            headers.set("api-key", apiKey);
            headers.set("x-api-key", apiKey);
        } else {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
    }

    // ==================== 自定义供应商 API ====================

    @GetMapping("/config/api/custom-providers") @ResponseBody
    public ResponseEntity<List<ProviderConfigRow>> listCustomProviders() {
        // 从 provider_config 中筛选 custom- 前缀的供应商
        List<ProviderConfigRow> all = providerConfigRepository.findAllWithModels();
        List<ProviderConfigRow> custom = all.stream()
                .filter(p -> p.providerKey().startsWith("custom-"))
                .toList();
        return ResponseEntity.ok(custom);
    }

    @PostMapping("/config/api/custom-providers") @ResponseBody
    public Mono<ResponseEntity<Map<String, Object>>> addCustomProvider(ServerWebExchange exchange) {
        return exchange.getFormData().map(form -> {
            String displayName = form.getFirst("displayName");
            String customTransforms = form.getFirst("customTransforms");
            String baseUrl = form.getFirst("baseUrl");
            String name = displayName == null ? "" : displayName.trim();
            if (name.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", "供应商名称不能为空"));
            }
            String providerKey = "custom-" + name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            // 检查是否已存在
            if (providerConfigRepository.findByKey(providerKey) != null) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", "该供应商名称已存在"));
            }
            // 验证 customTransforms 格式
            String transforms = customTransforms == null || customTransforms.isBlank() ? "{}" : customTransforms.trim();
            String url = baseUrl == null ? "" : baseUrl.trim();
            // 在 provider_config 中创建记录（默认启用）
            providerConfigRepository.saveProvider(providerKey, true, url, "", "openai", transforms);
            return ResponseEntity.ok(Map.<String, Object>of("ok", true, "providerKey", providerKey, "displayName", name));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/config/api/custom-providers/{providerKey}") @ResponseBody
    public ResponseEntity<Map<String, Object>> deleteCustomProvider(@PathVariable String providerKey) {
        providerConfigRepository.deleteByKey(providerKey);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PutMapping("/config/api/custom-providers/{providerKey}") @ResponseBody
    public Mono<ResponseEntity<Map<String, Object>>> updateCustomProvider(@PathVariable String providerKey,
                                                                    ServerWebExchange exchange) {
        return exchange.getFormData().map(form -> {
            String displayName = form.getFirst("displayName");
            String customTransforms = form.getFirst("customTransforms");
            String baseUrl = form.getFirst("baseUrl");
            String name = displayName == null ? "" : displayName.trim();
            if (name.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", "供应商名称不能为空"));
            }
            // 检查原供应商是否存在
            ProviderConfigRow existing = providerConfigRepository.findByKey(providerKey);
            if (existing == null) {
                return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", "供应商不存在"));
            }
            // 生成新的 providerKey
            String newProviderKey = "custom-" + name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            // 如果名称改变了，检查新 key 是否冲突
            if (!newProviderKey.equals(providerKey)) {
                if (providerConfigRepository.findByKey(newProviderKey) != null) {
                    return ResponseEntity.badRequest().body(Map.<String, Object>of("ok", false, "error", "该供应商名称已存在"));
                }
            }
            String transforms = customTransforms == null || customTransforms.isBlank() ? "{}" : customTransforms.trim();
            String url = baseUrl == null ? "" : baseUrl.trim();
            // 直接更新 provider_key、custom_transforms 和 base_url，保留关联的模型配置
            providerConfigRepository.updateProviderKeyAndTransforms(providerKey, newProviderKey, transforms, url);
            return ResponseEntity.ok(Map.<String, Object>of("ok", true, "providerKey", newProviderKey, "displayName", name));
        }).subscribeOn(Schedulers.boundedElastic());
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
     * 分页查询 API 调用日志。
     *
     * @param pageNum  页码（从 1 开始，默认 1）
     * @param pageSize 每页条数（默认 20，最大 100）
     * @return 分页结果：currentPage, totalPages, pageSize, totalItems, items
     */
    @GetMapping("/config/api/logs") @ResponseBody
    public ResponseEntity<Map<String, Object>> listLogs(
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        Map<String, Object> result = apiCallLogRepository.findLogs(pageNum, pageSize);
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