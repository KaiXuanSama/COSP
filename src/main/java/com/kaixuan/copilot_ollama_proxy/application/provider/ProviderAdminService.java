package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderApiKeyRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderRequestTransformRow;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 管理后台供应商配置用例。 */
@Service
public class ProviderAdminService {

    /**
     * 展示名派生不出路由标识时的错误提示。
     *
     * {@link #toProviderKey} 只保留 ASCII 字母数字，所以纯中文或全角名称
     * （如「深度求索」「ＭｉＭｏ」）会得到空串。空串在 SQLite 里能通过
     * {@code NOT NULL} 约束照常入库，随后引发两个隐蔽故障：模型在 Copilot 侧
     * 失去 {@code [provider-key]} 前缀而无法精确路由；且 {@code UNIQUE} 约束
     * 使第二个这类供应商被拒绝时，报出与展示名不符的「名称已存在」。
     * 故必须在入库前拦掉。前端另有同源提示，此处是防止绕过界面直接调接口。
     */
    static final String EMPTY_PROVIDER_KEY_ERROR = "供应商名称需包含至少一个英文字母或数字，用于生成路由标识";

    private final ProviderConfigRepository providerConfigRepository;
    private final ProviderApiKeyRepository providerApiKeyRepository;
    private final ProviderRequestTransformRepository providerRequestTransformRepository;
    private final ProviderRequestTransformService providerRequestTransformService;
    private final ObjectMapper objectMapper;

    public ProviderAdminService(ProviderConfigRepository providerConfigRepository,
                                ProviderApiKeyRepository providerApiKeyRepository,
                                ProviderRequestTransformRepository providerRequestTransformRepository,
                                ProviderRequestTransformService providerRequestTransformService,
                                ObjectMapper objectMapper) {
        this.providerConfigRepository = providerConfigRepository;
        this.providerApiKeyRepository = providerApiKeyRepository;
        this.providerRequestTransformRepository = providerRequestTransformRepository;
        this.providerRequestTransformService = providerRequestTransformService;
        this.objectMapper = objectMapper;
    }

    public Mono<Map<String, Object>> listProviders() {
        return Mono.fromCallable(() -> {
            List<ProviderConfigRow> providers = providerConfigRepository.findAllWithModels();
            Map<Integer, ProviderRequestTransformRow> transforms = providerRequestTransformRepository
                    .findByProviderIds(providers.stream().map(ProviderConfigRow::id).toList());
            Map<String, Object> result = new LinkedHashMap<>();
            for (ProviderConfigRow provider : providers) {
                result.put(provider.providerKey(), buildProviderView(provider, transforms.get(provider.id())));
            }
            return result;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<List<Map<String, Object>>> listLegacyProviders() {
        return Mono.fromCallable(() -> {
            List<ProviderConfigRow> providers = providerConfigRepository.findAllWithModels();
            Map<Integer, ProviderRequestTransformRow> transforms = providerRequestTransformRepository
                    .findByProviderIds(providers.stream().map(ProviderConfigRow::id).toList());
            return providers.stream().map(provider -> buildProviderView(provider, transforms.get(provider.id()))).toList();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String, Object>> toggleProvider(String providerKey, boolean enabled) {
        return Mono.fromCallable(() -> {
            ProviderConfigRow provider = providerConfigRepository.findByKey(providerKey);
            String baseUrl = provider == null || provider.baseUrl() == null ? "" : provider.baseUrl();
            providerConfigRepository.saveProvider(providerKey, enabled, baseUrl);
            return Map.<String, Object>of("providerKey", providerKey, "enabled", enabled);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Outcome> saveProviderConfig(String providerKey, MultiValueMap<String, String> form) {
        return Mono.fromCallable(() -> {
            providerConfigRepository.saveProviderConfigWithModels(providerKey, value(form, "baseUrl", "").trim(),
                    parseApiKeyInputs(value(form, "apiKeys", "[]").trim(), value(form, "activeKeyUuid", "").trim()),
                    parseModels(form));
            return Outcome.ok(Map.of("ok", true));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Outcome> addProvider(MultiValueMap<String, String> form) {
        return Mono.fromCallable(() -> {
            String name = value(form, "displayName", "").trim();
            if (name.isEmpty()) return Outcome.badRequest("供应商名称不能为空");
            String providerKey = toProviderKey(name);
            if (providerKey.isEmpty()) return Outcome.badRequest(EMPTY_PROVIDER_KEY_ERROR);
            if (providerConfigRepository.findByKey(providerKey) != null) return Outcome.badRequest("该供应商名称已存在");
            try {
                providerRequestTransformService.createProvider(providerKey, name, value(form, "baseUrl", "").trim(),
                        defaultIfBlank(form.getFirst("headerRulesJson"), "[]"),
                        defaultIfBlank(form.getFirst("bodyTemplateKeysJson"), ProviderRequestTransformService.DEFAULT_TEMPLATE_KEYS_JSON),
                        defaultIfBlank(form.getFirst("bodyPreviewJson"), ProviderRequestTransformService.DEFAULT_BODY_PREVIEW_JSON),
                        defaultIfBlank(form.getFirst("bodyRulesJson"), ProviderRequestTransformService.EMPTY_BODY_RULES_JSON));
                return Outcome.ok(Map.of("ok", true, "providerKey", providerKey, "displayName", name));
            } catch (IllegalArgumentException exception) {
                return Outcome.badRequest(exception.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String, Object>> deleteProvider(String providerKey) {
        return Mono.fromCallable(() -> {
            providerConfigRepository.deleteByKey(providerKey);
            return Map.<String, Object>of("ok", true);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Outcome> updateProvider(String providerKey, MultiValueMap<String, String> form) {
        return Mono.fromCallable(() -> {
            String name = value(form, "displayName", "").trim();
            if (name.isEmpty()) return Outcome.badRequest("供应商名称不能为空");
            ProviderConfigRow existing = providerConfigRepository.findByKey(providerKey);
            if (existing == null) return Outcome.badRequest("供应商不存在");
            String newProviderKey = toProviderKey(name);
            if (newProviderKey.isEmpty()) return Outcome.badRequest(EMPTY_PROVIDER_KEY_ERROR);
            if (!newProviderKey.equals(providerKey) && providerConfigRepository.findByKey(newProviderKey) != null) {
                return Outcome.badRequest("该供应商名称已存在");
            }
            try {
                providerRequestTransformService.updateProvider(existing.id(), providerKey, newProviderKey, name,
                        value(form, "baseUrl", "").trim(), defaultIfBlank(form.getFirst("headerRulesJson"), "[]"),
                        defaultIfBlank(form.getFirst("bodyTemplateKeysJson"), ProviderRequestTransformService.DEFAULT_TEMPLATE_KEYS_JSON),
                        defaultIfBlank(form.getFirst("bodyPreviewJson"), ProviderRequestTransformService.DEFAULT_BODY_PREVIEW_JSON),
                        defaultIfBlank(form.getFirst("bodyRulesJson"), ProviderRequestTransformService.EMPTY_BODY_RULES_JSON));
                return Outcome.ok(Map.of("ok", true, "providerKey", newProviderKey, "displayName", name));
            } catch (IllegalArgumentException exception) {
                return Outcome.badRequest(exception.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> buildProviderView(ProviderConfigRow provider, ProviderRequestTransformRow transform) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", provider.id());
        view.put("providerKey", provider.providerKey());
        view.put("displayName", provider.displayName());
        view.put("enabled", provider.enabled());
        view.put("baseUrl", provider.baseUrl());
        view.put("updatedAt", provider.updatedAt());
        view.put("models", provider.models());
        view.put("apiKeys", buildMaskedApiKeys(provider.id()));
        Map<String, Object> transformView = new LinkedHashMap<>();
        transformView.put("headerRulesVersion", transform == null ? 1 : transform.headerRulesVersion());
        transformView.put("headerRulesJson", transform == null ? "[]" : transform.headerRulesJson());
        transformView.put("bodyTemplateKeysJson", transform == null ? ProviderRequestTransformService.DEFAULT_TEMPLATE_KEYS_JSON : transform.bodyTemplateKeysJson());
        transformView.put("bodyPreviewJson", transform == null ? ProviderRequestTransformService.DEFAULT_BODY_PREVIEW_JSON : transform.bodyPreviewJson());
        transformView.put("bodyRulesVersion", transform == null ? 1 : transform.bodyRulesVersion());
        transformView.put("bodyRulesJson", transform == null ? ProviderRequestTransformService.EMPTY_BODY_RULES_JSON : transform.bodyRulesJson());
        view.put("requestTransform", transformView);
        return view;
    }

    private List<Map<String, Object>> buildMaskedApiKeys(int providerId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProviderApiKeyRow row : providerApiKeyRepository.findByProviderId(providerId)) {
            Map<String, Object> key = new LinkedHashMap<>();
            key.put("keyUuid", row.keyUuid());
            key.put("name", row.keyName());
            key.put("masked", maskApiKey(providerApiKeyRepository.decrypt(row)));
            key.put("active", row.active());
            result.add(key);
        }
        return result;
    }

    private List<ProviderApiKeyRepository.ApiKeyInput> parseApiKeyInputs(String apiKeysJson, String activeKeyUuid) {
        List<ProviderApiKeyRepository.ApiKeyInput> inputs = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(apiKeysJson);
            if (!array.isArray()) return inputs;
            boolean activeFound = false;
            for (JsonNode node : array) {
                String uuid = node.hasNonNull("keyUuid") ? node.get("keyUuid").asText() : null;
                boolean active = uuid != null && !uuid.isBlank() && uuid.equals(activeKeyUuid);
                activeFound |= active;
                inputs.add(new ProviderApiKeyRepository.ApiKeyInput(uuid,
                        node.hasNonNull("name") ? node.get("name").asText() : "",
                        node.hasNonNull("apiKey") ? node.get("apiKey").asText() : null, active));
            }
            if (!activeFound && !inputs.isEmpty()) {
                ProviderApiKeyRepository.ApiKeyInput first = inputs.get(0);
                inputs.set(0, new ProviderApiKeyRepository.ApiKeyInput(first.keyUuid(), first.keyName(), first.plaintext(), true));
            }
            return inputs;
        } catch (Exception exception) {
            throw new IllegalArgumentException("API Key 列表格式错误: " + exception.getMessage(), exception);
        }
    }

    private List<Map<String, Object>> parseModels(MultiValueMap<String, String> form) {
        List<Map<String, Object>> models = new ArrayList<>();
        String prefix = "models[";
        Set<Integer> indices = new TreeSet<>();
        for (String key : form.keySet()) {
            if (key.startsWith(prefix) && key.contains("].")) {
                try {
                    indices.add(Integer.parseInt(key.substring(prefix.length(), key.indexOf(']', prefix.length()))));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (int index : indices) {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("modelName", value(form, prefix + index + "].name", "").trim());
            model.put("enabled", "on".equals(form.getFirst(prefix + index + "].enabled")));
            model.put("contextSize", value(form, prefix + index + "].contextSize", "0").trim());
            model.put("maxOutputTokens", value(form, prefix + index + "].maxOutputTokens", "128000").trim());
            model.put("capsTools", "on".equals(form.getFirst(prefix + index + "].capsTools")));
            model.put("capsVision", "on".equals(form.getFirst(prefix + index + "].capsVision")));
            model.put("reasoningEffort", value(form, prefix + index + "].reasoningEffort", "Medium").trim());
            models.add(model);
        }
        return models;
    }

    private String value(MultiValueMap<String, String> form, String key, String defaultValue) {
        String value = form.getFirst(key);
        return value == null ? defaultValue : value;
    }

    private String maskApiKey(String key) {
        if (key == null || key.isBlank()) return "";
        String trimmed = key.trim();
        return trimmed.length() <= 10 ? "****" : trimmed.substring(0, 6) + "****" + trimmed.substring(trimmed.length() - 4);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private String toProviderKey(String displayName) {
        return displayName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    public record Outcome(int status, Map<String, Object> body) {
        private static Outcome ok(Map<String, Object> body) { return new Outcome(200, body); }
        private static Outcome badRequest(String error) { return new Outcome(400, Map.of("ok", false, "error", error)); }
    }
}