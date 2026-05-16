package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ApiUsageRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 API 控制器 — 提供 SPA 路由回退和 JSON API 接口。
 * <p>
 * 前端由 Vue 3 SPA 完全接管，后端仅提供数据接口。
 */
@Controller
public class AdminPageController {

    private final JdbcUserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final ApiUsageRepository apiUsageRepository;
    private final ProviderConfigRepository providerConfigRepository;
    private final WebClient.Builder webClientBuilder;

    public AdminPageController(JdbcUserDetailsManager userDetailsManager, PasswordEncoder passwordEncoder, ApiUsageRepository apiUsageRepository, ProviderConfigRepository providerConfigRepository,
            WebClient.Builder webClientBuilder) {
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
        this.apiUsageRepository = apiUsageRepository;
        this.providerConfigRepository = providerConfigRepository;
        this.webClientBuilder = webClientBuilder;
    }

    // ==================== SPA 路由支持 ====================

    /**
     * 根路径重定向到登录页。
     */
    @GetMapping("/")
    public String rootRedirect() {
        return "redirect:/login";
    }

    /**
     * Vue Router 的 createWebHistory() 模式下，
     * 所有 SPA 路由需要返回 index.html 让 Vue 接管。
     * 静态资源（/assets/*, /img/*）由 Spring Boot 默认处理。
     */
    @GetMapping("/login")
    public String spaLogin() {
        return "forward:/index.html";
    }

    @GetMapping("/overview")
    public String spaOverview() {
        return "forward:/index.html";
    }

    @GetMapping("/settings")
    public String spaSettings() {
        return "forward:/index.html";
    }

    @GetMapping("/account")
    public String spaAccount() {
        return "forward:/index.html";
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
        List<Map<String, Object>> all = providerConfigRepository.findAllWithModels();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map<String, Object> p : all) {
            result.put((String) p.get("providerKey"), p);
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
        Map<String, Object> provider = providerConfigRepository.findByKey(providerKey);
        if (provider == null) {
            return ResponseEntity.notFound().build();
        }
        String baseUrl = (String) provider.getOrDefault("baseUrl", "");
        String apiKey = (String) provider.getOrDefault("apiKey", "");
        providerConfigRepository.saveProvider(providerKey, enabled, baseUrl, apiKey, "openai");
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
    public ResponseEntity<Map<String, Object>> saveProviderConfig(@PathVariable String providerKey, @RequestParam Map<String, String> params) {
        String baseUrl = params.getOrDefault("baseUrl", "").trim();
        String apiKey = params.getOrDefault("apiKey", "").trim();
        int providerId = providerConfigRepository.updateProviderConfig(providerKey, baseUrl, apiKey, "openai");
        List<Map<String, Object>> models = new ArrayList<>();
        String prefix = "models[";
        java.util.Set<Integer> indices = new java.util.TreeSet<>();
        for (String key : params.keySet()) {
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
            m.put("modelName", params.getOrDefault(prefix + i + "].name", "").trim());
            m.put("enabled", "on".equals(params.get(prefix + i + "].enabled")));
            m.put("contextSize", params.getOrDefault(prefix + i + "].contextSize", "0").trim());
            m.put("capsTools", "on".equals(params.get(prefix + i + "].capsTools")));
            m.put("capsVision", "on".equals(params.get(prefix + i + "].capsVision")));
            models.add(m);
        }
        providerConfigRepository.saveModels(providerId, models);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 使用当前表单中的 Base URL 与 API Key 从上游拉取模型列表。
     */
    @PostMapping("/config/api/providers/{providerKey}/pull-models") @ResponseBody
    public ResponseEntity<?> pullProviderModels(@PathVariable String providerKey, @RequestBody Map<String, String> body) {
        if (!supportsProviderKey(providerKey)) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "不支持的服务商。"));
        }

        String baseUrl = body.getOrDefault("baseUrl", "").trim();
        String apiKey = body.getOrDefault("apiKey", "").trim();
        String modelPullPath = body.getOrDefault("modelPullPath", "").trim();
        if (baseUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "请先填写 API 地址。"));
        }
        if (apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "请先填写 API Key。"));
        }

        return forwardModelsRequest(providerKey, baseUrl, apiKey, modelPullPath);
    }

    // ==================== 账号修改（JSON API） ====================

    /**
     * 获取当前登录用户信息。
     */
    @GetMapping("/config/api/me") @ResponseBody
    public ResponseEntity<Map<String, Object>> currentUser(Authentication authentication) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("username", authentication.getName());
        result.put("role", authentication.getAuthorities().stream().findFirst().map(Object::toString).orElse(""));
        return ResponseEntity.ok(result);
    }

    private boolean supportsProviderKey(String providerKey) {
        return switch (providerKey) {
        case "longcat", "mimo", "sensenova", "deepseek" -> true;
        default -> false;
        };
    }

    private ResponseEntity<String> forwardModelsRequest(String providerKey, String rawBaseUrl, String apiKey, String rawModelPullPath) {
        String requestUrl = rawBaseUrl.replaceAll("/+$", "") + normalizeModelPullPath(rawModelPullPath);
        try {
            return webClientBuilder.clone().defaultHeaders(headers -> {
                headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                applyModelDiscoveryAuthHeaders(providerKey, headers, apiKey);
            }).build().get().uri(requestUrl).exchangeToMono(response -> response.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
                ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.statusCode().value());
                response.headers().contentType().ifPresent(builder::contentType);
                return builder.body(body);
            })).onErrorResume(ex -> Mono.just(ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"连接上游服务失败，请检查 API 地址是否正确。\"}"))).blockOptional()
                    .orElseGet(() -> ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"连接上游服务失败，请检查 API 地址是否正确。\"}"));
        } catch (Exception ex) {
            return ResponseEntity.status(502).contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"连接上游服务失败，请检查 API 地址是否正确。\"}");
        }
    }

    private String normalizeModelPullPath(String rawModelPullPath) {
        String path = rawModelPullPath == null ? "" : rawModelPullPath.trim();
        if (path.isBlank()) {
            return "/v1/models";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private void applyModelDiscoveryAuthHeaders(String providerKey, HttpHeaders headers, String apiKey) {
        switch (providerKey) {
        case "mimo" -> {
            headers.set("api-key", apiKey);
            headers.set("x-api-key", apiKey);
        }
        case "longcat", "sensenova", "deepseek" -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        default -> {
        }
        }
    }

    @PostMapping("/config/api/account") @ResponseBody
    public ResponseEntity<Map<String, Object>> saveAccountApi(Authentication authentication, @RequestParam(value = "newUsername", required = false) String newUsername,
            @RequestParam(value = "currentPassword", required = false) String currentPassword, @RequestParam(value = "newPassword", required = false) String newPassword,
            @RequestParam(value = "confirmPassword", required = false) String confirmPassword) {

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
    }

}