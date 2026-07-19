package com.kaixuan.copilot_ollama_proxy.provider.generic.discovery;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.AbstractDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用模型发现服务 —— 处理所有数据库供应商配置。
 * 从数据库动态读取配置，提供模型发现和详情查询能力。
 */
@Service
public class GenericDiscoveryService extends AbstractDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(GenericDiscoveryService.class);
    private static final String PROVIDER_KEY = "generic";

    private final RuntimeProviderCatalog runtimeProviderCatalog;

    public GenericDiscoveryService(RuntimeProviderCatalog runtimeProviderCatalog) {
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
     */
    public boolean supports(String providerKey) {
        return providerKey != null && runtimeProviderCatalog.getActiveProvider(providerKey) != null;
    }

    @Override
    public OllamaShowResponse showModel(String modelName) {
        String providerKey = resolveProviderKey(modelName);
        String resolvedModel = resolveModelOrDefault(modelName);
        if (providerKey == null) {
            log.warn("通用服务无法找到模型 [{}] 对应的供应商", resolvedModel);
            return buildGenericShowResponse(resolvedModel, 8192, List.of("completion"), "generic");
        }
        ProviderRuntimeConfiguration config = runtimeProviderCatalog.getActiveProvider(providerKey);
        List<String> caps = new ArrayList<>();
        caps.add("completion");
        int contextLength = 8192;
        if (config != null) {
            for (var m : config.models()) {
                if (resolvedModel.equals(m.modelName())) {
                    contextLength = m.contextSize() >= 8192 ? m.contextSize() : 8192;
                    if (m.capsTools()) caps.add("tools");
                    if (m.capsVision()) caps.add("vision");
                    break;
                }
            }
        }
        return buildGenericShowResponse(resolvedModel, contextLength, caps, providerKey);
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
        }
        return findProviderKeyForModel(parsed.modelName());
    }

    private String findProviderKeyForModel(String modelName) {
        for (var provider : runtimeProviderCatalog.getActiveProviders()) {
            if (provider.supportsModel(modelName)) {
                return provider.providerKey();
            }
        }
        return null;
    }
}
