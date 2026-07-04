package com.kaixuan.copilot_ollama_proxy.provider.mimo.ollama;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.AbstractRuntimeCatalogOllamaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * MiMo Ollama 协议实现 —— 提供模型发现和详情查询能力。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class MimoOllamaService extends AbstractRuntimeCatalogOllamaService {

    public MimoOllamaService(RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${mimo.default-model:mimo-v2.5-pro}") String fallbackDefaultModel) {
        super(runtimeProviderCatalog, fallbackDefaultModel);
    }

    @Override
    public String getProviderKey() {
        return "mimo";
    }

    @Override
    protected String providerFormat() {
        return "mimo";
    }

    @Override
    protected String providerFamily() {
        return "Mimo";
    }

    @Override
    protected List<String> providerFamilies() {
        return List.of("Mimo");
    }

    @Override
    protected String providerParameterSize() {
        return "42B";
    }

    @Override
    protected String providerLicense() {
        return "Apache 2.0";
    }

    private List<String> buildCapabilitiesFromDb(String resolvedModel) {
        List<String> caps = new ArrayList<>();
        caps.add("completion");
        ProviderRuntimeModel model = requireModelConfiguration(resolvedModel);
        if (model.capsTools()) {
            caps.add("tools");
        }
        if (model.capsVision()) {
            caps.add("vision");
        }
        return caps;
    }

    @Override
    public OllamaShowResponse showModel(String modelName) {
        String resolvedModel = resolveModelOrDefault(modelName);
        int contextLength = requireContextLength(resolvedModel);
        List<String> capabilities = buildCapabilitiesFromDb(resolvedModel);
        return buildShowResponse(resolvedModel, contextLength, capabilities);
    }
}
