package com.kaixuan.copilot_ollama_proxy.provider.generic.ollama;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.AbstractRuntimeCatalogOllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用 Ollama 协议服务 —— 处理所有 custom-* 前缀的自定义供应商。
 * 从数据库动态读取配置，提供模型发现和详情查询能力。
 */
@Service
public class GenericOllamaService extends AbstractRuntimeCatalogOllamaService {

    private static final Logger log = LoggerFactory.getLogger(GenericOllamaService.class);
    private static final String PROVIDER_KEY = "__generic__";

    private final RuntimeProviderCatalog runtimeProviderCatalog;

    public GenericOllamaService(RuntimeProviderCatalog runtimeProviderCatalog) {
        super(runtimeProviderCatalog, "");
        this.runtimeProviderCatalog = runtimeProviderCatalog;
    }

    @Override
    public String getProviderKey() {
        return PROVIDER_KEY;
    }

    @Override
    protected String providerFormat() {
        return "generic";
    }

    @Override
    protected String providerFamily() {
        return "Generic";
    }

    @Override
    protected List<String> providerFamilies() {
        return List.of("Generic");
    }

    @Override
    protected String providerParameterSize() {
        return "Unknown";
    }

    @Override
    protected String providerLicense() {
        return "Proprietary";
    }

    /**
     * 判断此服务是否能处理给定的 providerKey。
     * 支持所有 custom- 前缀的供应商。
     */
    public boolean supports(String providerKey) {
        return providerKey != null && providerKey.startsWith("custom-");
    }

    @Override
    public OllamaShowResponse showModel(String modelName) {
        String providerKey = resolveProviderKey(modelName);
        String resolvedModel = resolveModelOrDefault(modelName);
        if (providerKey == null) {
            log.warn("通用服务无法找到模型 [{}] 对应的供应商", resolvedModel);
            return buildGenericShowResponse(resolvedModel, 4096, List.of("completion"), "generic");
        }
        ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(providerKey);
        List<String> caps = new ArrayList<>();
        caps.add("completion");
        int contextLength = 4096;
        if (config != null) {
            for (var m : config.models()) {
                if (resolvedModel.equals(m.modelName())) {
                    contextLength = m.contextSize() > 0 ? m.contextSize() : 4096;
                    if (m.capsTools()) caps.add("tools");
                    if (m.capsVision()) caps.add("vision");
                    break;
                }
            }
        }
        String displayKey = providerKey.startsWith("custom-") ? providerKey.substring(7) : providerKey;
        return buildGenericShowResponse(resolvedModel, contextLength, caps, displayKey);
    }

    /**
     * 构建通用的 show 响应，使用指定的 displayKey 作为架构名。
     */
    private OllamaShowResponse buildGenericShowResponse(String model, int contextLength, List<String> capabilities, String displayKey) {
        String prefixedModel = "[" + displayKey + "] " + model;
        OllamaShowResponse response = new OllamaShowResponse();
        response.setParameters("temperature 0.7\nnum_ctx " + contextLength);
        response.setLicense("Proprietary");
        response.setModifiedAt(currentTimestamp());
        response.setTemplate("{{ .System }}\n{{ .Prompt }}");
        response.setCapabilities(capabilities);

        OllamaShowResponse.ShowDetails details = new OllamaShowResponse.ShowDetails();
        details.setParentModel("");
        details.setFormat(displayKey);
        details.setFamily(capitalize(displayKey));
        details.setFamilies(List.of(capitalize(displayKey)));
        details.setParameterSize("Unknown");
        details.setQuantizationLevel("none");
        response.setDetails(details);

        response.setModelInfo(Map.of(
                "general.architecture", displayKey,
                "general.basename", prefixedModel,
                displayKey + ".context_length", contextLength,
                displayKey + ".embedding_length", 8192));
        return response;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    private String resolveProviderKey(String modelName) {
        var parsed = com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil.parse(modelName);
        if (parsed.hasProviderPrefix()) {
            String key = parsed.providerKey().toLowerCase();
            if (runtimeProviderCatalog.getActiveProvider(key) != null) {
                return key;
            }
            String customKey = "custom-" + key;
            if (runtimeProviderCatalog.getActiveProvider(customKey) != null) {
                return customKey;
            }
        }
        return findProviderKeyForModel(parsed.modelName());
    }

    private String findProviderKeyForModel(String modelName) {
        for (var provider : runtimeProviderCatalog.getActiveProviders()) {
            if (provider.providerKey().startsWith("custom-") && provider.supportsModel(modelName)) {
                return provider.providerKey();
            }
        }
        return null;
    }
}
