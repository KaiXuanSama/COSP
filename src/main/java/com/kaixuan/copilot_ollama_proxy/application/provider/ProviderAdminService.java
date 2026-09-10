package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AnthropicThinkingSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.MaxOutputTokensSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ReasoningEffortSetting;
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

    /**
     * 可声明的线路协议白名单。
     *
     * <p>与 {@link ProviderRequestTransformService} 里规则组的同名白名单同理：校验的是
     * <strong>外部输入的字符串</strong>，用 {@code WireProtocol.valueOf} 会把非法值变成异常控制流，
     * 而这里要的是「集合包含判断 + 统一错误消息」。
     */
    private static final Set<String> SUPPORTED_PROTOCOLS = Set.of("OPENAI", "ANTHROPIC");

    private final ProviderConfigRepository providerConfigRepository;
    private final ProviderApiKeyRepository providerApiKeyRepository;
    private final ProviderRequestTransformRepository providerRequestTransformRepository;
    private final ProviderRequestTransformService providerRequestTransformService;
    private final OutboundProxyTargetProjector proxyTargetProjector;
    private final ObjectMapper objectMapper;

    public ProviderAdminService(ProviderConfigRepository providerConfigRepository,
                                ProviderApiKeyRepository providerApiKeyRepository,
                                ProviderRequestTransformRepository providerRequestTransformRepository,
                                ProviderRequestTransformService providerRequestTransformService,
                                OutboundProxyTargetProjector proxyTargetProjector,
                                ObjectMapper objectMapper) {
        this.providerConfigRepository = providerConfigRepository;
        this.providerApiKeyRepository = providerApiKeyRepository;
        this.providerRequestTransformRepository = providerRequestTransformRepository;
        this.providerRequestTransformService = providerRequestTransformService;
        this.proxyTargetProjector = proxyTargetProjector;
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
            // enabled 变化不影响代理目标归属（目标按 host 投影，与启停无关），此处无需重投影。
            return Map.<String, Object>of("providerKey", providerKey, "enabled", enabled);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 保存供应商配置与模型列表（编辑抽屉路径）。
     */
    public Mono<Outcome> saveProviderConfig(String providerKey, MultiValueMap<String, String> form) {
        return Mono.fromCallable(() -> {
            providerConfigRepository.saveProviderConfigWithModels(providerKey, value(form, "baseUrl", "").trim(),
                    parseApiKeyInputs(value(form, "apiKeys", "[]").trim(), value(form, "activeKeyUuid", "").trim(),
                            parseOptionalIndex(value(form, "activeKeyIndex", "").trim())),
                    parseModels(form));
            try {
                saveProtocolsFromForm(providerKey, form);
            } catch (IllegalArgumentException exception) {
                return Outcome.badRequest(exception.getMessage());
            }
            // 该路径（编辑抽屉）当前不提交 useProxy，因此这一步通常什么都不做；
            // 保留调用是为了让三条保存路径对这个字段的语义一致 —— 谁带了就写，没带就保留。
            saveProxyFromForm(providerKey, form);
            // base_url / anthropic_base_url 可能在此变更，端点变了就得重投影，
            // 否则代理仍指向旧 host:port。use_proxy 没变也要投——投影读的是当前全表，幂等。
            proxyTargetProjector.reprojectProxiedTargets();
            return Outcome.ok(Map.of("ok", true));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 从表单写入代理开关；字段未出现时保持原值。
     *
     * <p>与 {@link #saveProtocolsFromForm} 同一路子，并入新建 / 修改两条保存路径。
     * 曾经这一步由前端在保存完成<strong>之后</strong>另调一次专项接口，
     * 那样两次写入不在同一个请求里：第二次失败会留下「供应商已保存但代理开关未生效」
     * 的中间状态，而前端已经弹过成功提示。
     *
     * <h2>为何用 null 表达「未提供」</h2>
     * 布尔字段比字符串更容易出错：表单里一个没勾上的复选框可能根本不发该字段，
     * 也可能发个空串。若把两者都当成 {@code false}，那么任何一条不带该字段的保存路径
     * （比如编辑抽屉里只改模型）都会把用户已开的代理静默关掉。
     * 因此只有显式的 {@code true} / {@code false} 才算声明，其余一律保留。
     *
     * @return true 表示确实写入了（调用方据此决定要不要重投影）
     */
    private boolean saveProxyFromForm(String providerKey, MultiValueMap<String, String> form) {
        Boolean useProxy = parseUseProxy(form.getFirst("useProxy"));
        if (useProxy == null) {
            return false;
        }
        providerConfigRepository.updateProviderProxy(providerKey, useProxy);
        return true;
    }

    /**
     * 解析代理开关表单值。
     *
     * <p>{@code null}（含空串与空白）表示未提供，由调用方保留原值。
     * 只认 {@code true} / {@code false} 两个字面量（大小写不敏感），
     * 其余取值同样当成未提供 —— 看不懂的值宁可不改，也不要猜成 false。
     */
    private static Boolean parseUseProxy(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        if ("true".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 从表单写入协议配置；字段未出现时保持原值。
     *
     * <p>三条保存路径（新建弹窗、改名弹窗、编辑抽屉）共用这一份，因为「未提供即保留」
     * 这个语义在任何一条路径上被写错，后果都是同一个：一次无关的保存把协议支持抹平。
     *
     * @throws IllegalArgumentException 协议集合不是合法的协议名数组
     */
    private void saveProtocolsFromForm(String providerKey, MultiValueMap<String, String> form) {
        String rawAnthropicBaseUrl = form.getFirst("anthropicBaseUrl");
        providerConfigRepository.updateProviderProtocols(providerKey,
                parseSupportedProtocols(form.getFirst("supportedProtocolsJson")),
                rawAnthropicBaseUrl == null ? null : rawAnthropicBaseUrl.trim());
    }

    /**
     * 校验并规范化协议集合表单值。
     *
     * <p>返回 {@code null} 表示<strong>表单没带这个字段</strong>，交由仓储保留原值；
     * 而非「清成空集」。当前管理后台前端尚未提交该字段，若把缺失当清空，
     * 任何一次普通的供应商编辑都会把协议支持抹平，而空集会让该供应商的全部调用被拒。
     *
     * <p>空串与空白同样视为「未提供」—— 表单里一个未填的隐藏域发出来就是空串，
     * 把它读成「用户声明了什么」是错的。确实要表达空集就传字面的 {@code []}。
     *
     * @throws IllegalArgumentException 不是 JSON 字符串数组，或含未知协议名
     */
    private String parseSupportedProtocols(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(rawJson);
        } catch (Exception exception) {
            throw new IllegalArgumentException("协议支持配置不是合法 JSON");
        }
        if (!parsed.isArray()) {
            throw new IllegalArgumentException("协议支持配置必须是数组");
        }
        Set<String> normalized = new TreeSet<>();
        for (JsonNode element : parsed) {
            String name = element.isTextual() ? element.asText().trim().toUpperCase() : "";
            if (!SUPPORTED_PROTOCOLS.contains(name)) {
                throw new IllegalArgumentException("不支持的线路协议: " + element.asText());
            }
            normalized.add(name);
        }
        try {
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception exception) {
            throw new IllegalArgumentException("协议支持配置序列化失败");
        }
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
                saveProtocolsFromForm(providerKey, form);
                // 代理开关与供应商本体同一次写入，不再由前端保存后补一次专项请求。
                saveProxyFromForm(providerKey, form);
                // 新建默认 use_proxy=0，但表单可能已经带了 true；无论哪种都重投影一次
                // 保持集合与全表一致（投影读的是当前全表，幂等）。
                proxyTargetProjector.reprojectProxiedTargets();
                return Outcome.ok(Map.of("ok", true, "providerKey", providerKey, "displayName", name));
            } catch (IllegalArgumentException exception) {
                return Outcome.badRequest(exception.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Map<String, Object>> deleteProvider(String providerKey) {
        return Mono.fromCallable(() -> {
            providerConfigRepository.deleteByKey(providerKey);
            // 删除也是一种撤销路径：被删供应商若曾开代理，它的端点必须从集合里移走，否则成陈旧目标。
            proxyTargetProjector.reprojectProxiedTargets();
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
                // 用改名后的 key 定位：上一行可能刚把 provider_key 改掉，用旧 key 会匹配不到任何行
                // 而 UPDATE 不报错，表现为协议配置静默丢失。
                saveProtocolsFromForm(newProviderKey, form);
                // 同样用改名后的 key：旧 key 已不存在，UPDATE 会匹配 0 行且不报错，
                // 表现为代理开关静默丢失。
                saveProxyFromForm(newProviderKey, form);
                // 改名会换掉 provider_key、base_url 可能也变，两者都影响代理目标归属，重投影。
                proxyTargetProjector.reprojectProxiedTargets();
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
        view.put("useProxy", provider.useProxy());
        view.put("baseUrl", provider.baseUrl());
        // 协议支持以数组而非 JSON 字符串形式返回：前端拿到就能直接绑多选控件，
        // 不必再做一次 JSON.parse 并处理它可能失败。规则集那几个字段保持字符串是因为它们
        // 在前端也以字符串形式回传，而协议集合没有这个对称需求。
        view.put("supportedProtocols", parseProtocolsForView(provider.supportedProtocolsJson()));
        view.put("anthropicBaseUrl", provider.anthropicBaseUrl() == null ? "" : provider.anthropicBaseUrl());
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

    /**
     * 把协议集合 JSON 解成供前端直接使用的列表。
     *
     * <p>解不开时回退到两种协议都有，与
     * {@code ProviderProtocolSupport} 的宽容口径保持一致 —— 否则会出现「界面上看不到勾选，
     * 实际却两条线路都能跑」这种说不通的状态。显式的空数组仍如实返回空列表。
     */
    private List<String> parseProtocolsForView(String supportedProtocolsJson) {
        List<String> fallback = List.of("OPENAI", "ANTHROPIC");
        if (supportedProtocolsJson == null || supportedProtocolsJson.isBlank()) {
            return fallback;
        }
        try {
            JsonNode parsed = objectMapper.readTree(supportedProtocolsJson);
            if (!parsed.isArray()) {
                return fallback;
            }
            List<String> protocols = new ArrayList<>();
            for (JsonNode element : parsed) {
                String name = element.isTextual() ? element.asText().trim().toUpperCase() : "";
                if (SUPPORTED_PROTOCOLS.contains(name) && !protocols.contains(name)) {
                    protocols.add(name);
                }
            }
            return protocols;
        } catch (Exception exception) {
            return fallback;
        }
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

    /**
     * 解析 API Key 输入列表并确定激活项。
     *
     * <h2>激活项的三级判定</h2>
     * <ol>
     *   <li>按 {@code activeKeyUuid} 精确匹配 —— 已保存的 Key 用它标识；</li>
     *   <li>都匹配不上时按 {@code activeKeyIndex} 命中对应下标 —— 覆盖「激活项是一条
     *       尚未落库的新增条目」这个场景：它此刻没有 {@code keyUuid}，无法用 uuid 表达，
     *       前端改用它在提交数组中的下标传达意图；</li>
     *   <li>仍确定不了才兜底第一条。</li>
     * </ol>
     *
     * <p>第二级是「新增并选中它、保存成功却回退到旧 Key」这个 bug 的修复点：过去只有
     * uuid 一条路，新增条目的 uuid 为空，于是永远落到兜底把第一条（通常是旧 Key）设为
     * 激活。下标由前端 {@code resolveActiveKeyIndex} 从临时 value 解析而来，与本方法
     * 遍历的数组同序。
     *
     * @param apiKeysJson    Key 列表 JSON（数组）
     * @param activeKeyUuid  激活项的 keyUuid；新增未保存项为空串
     * @param activeKeyIndex 激活项在数组中的下标；{@code -1} 表示前端未指定（靠 uuid 即可）
     */
    private List<ProviderApiKeyRepository.ApiKeyInput> parseApiKeyInputs(String apiKeysJson, String activeKeyUuid,
                                                                         int activeKeyIndex) {
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
            // uuid 没命中任何项：先试下标（新增未保存项走这条），再兜底第一条。
            if (!activeFound && !inputs.isEmpty()) {
                int target = (activeKeyIndex >= 0 && activeKeyIndex < inputs.size()) ? activeKeyIndex : 0;
                ProviderApiKeyRepository.ApiKeyInput chosen = inputs.get(target);
                inputs.set(target, new ProviderApiKeyRepository.ApiKeyInput(
                        chosen.keyUuid(), chosen.keyName(), chosen.plaintext(), true));
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
            // 与思考深度同样收敛成 V9 JSON：表单可能提交 V9 JSON，也可能是旧前端的裸整数。
            model.put("maxOutputTokens", MaxOutputTokensSetting
                    .parse(value(form, prefix + index + "].maxOutputTokens", "").trim(), objectMapper)
                    .serialize());
            model.put("capsTools", "on".equals(form.getFirst(prefix + index + "].capsTools")));
            model.put("capsVision", "on".equals(form.getFirst(prefix + index + "].capsVision")));
            // 收敛成规范的 V2 JSON：表单提交的可能是 V2 JSON、旧的裸档位、甚至遗留的 None，
            // 在入库前统一形态，读取侧才不必长期兼容三种写法。
            model.put("reasoningEffort", ReasoningEffortSetting
                    .parse(value(form, prefix + index + "].reasoningEffort", "").trim(), objectMapper)
                    .serialize());
            // 思考方式与预算（V10）。两者分属两列，但要一起 parse —— record 的构造器
            // 把非正预算归一为哨兵，分开处理就得在这里再写一遗那个规则。
            AnthropicThinkingSetting thinking = AnthropicThinkingSetting.parse(
                    value(form, prefix + index + "].thinkingMode", "").trim(),
                    parsePositiveInt(value(form, prefix + index + "].thinkingBudgetTokens", "")),
                    objectMapper);
            model.put("thinkingMode", thinking.serialize());
            model.put("thinkingBudgetTokens", thinking.budgetTokens());
            models.add(model);
        }
        return models;
    }

    private String value(MultiValueMap<String, String> form, String key, String defaultValue) {
        String value = form.getFirst(key);
        return value == null ? defaultValue : value;
    }

    /**
     * 解析一个可选的非负下标表单值。
     *
     * <p>空值、非数字或负数一律返回 {@code -1}（表示「未指定」），
     * 让 {@link #parseApiKeyInputs} 走 uuid 优先、否则兜底第一条的原有路径。
     * 越界下标不在这里拦 —— 那里已按 {@code inputs.size()} 判定，避免两处各写一遍边界。
     */
    private static int parseOptionalIndex(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        try {
            int index = Integer.parseInt(raw.trim());
            return index >= 0 ? index : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /**
     * 解析一个可选的正整数表单值，空值与非数字返回 0。
     *
     * <p>返回 0 而不是哨兵：归一成哨兵是
     * {@link AnthropicThinkingSetting} 构造器的职责，这里只负责把表单字符串
     * 变成一个数，不重复一遗那个规则。
     */
    private static int parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
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