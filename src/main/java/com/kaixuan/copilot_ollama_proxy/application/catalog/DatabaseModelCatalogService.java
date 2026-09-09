package com.kaixuan.copilot_ollama_proxy.application.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.MaxOutputTokensSetting;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderModelRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ModelCatalogService} 的默认实现 —— 基于 {@link ProviderConfigRepository} 聚合可用模型。
 *
 * 封装了原先散落在 OllamaApiController 与 OpenAiController 中的模型聚合逻辑：
 * 遍历已启用供应商、拼接展示前缀、读取能力标记。
 */
@Service
public class DatabaseModelCatalogService implements ModelCatalogService {

    private final ProviderConfigRepository providerConfigRepository;
    private final ObjectMapper objectMapper;

    public DatabaseModelCatalogService(ProviderConfigRepository providerConfigRepository,
                                      ObjectMapper objectMapper) {
        this.providerConfigRepository = providerConfigRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AvailableModel> listAvailableModels() {
        List<AvailableModel> result = new ArrayList<>();
        List<ProviderConfigRow> activeProviders = providerConfigRepository.findAllActiveProvidersWithEnabledModels();
        for (ProviderConfigRow provider : activeProviders) {
            String providerKey = provider.providerKey();
            List<ProviderModelRow> models = provider.models();
            if (models == null) {
                continue;
            }
            String displayKey = providerKey;
            for (ProviderModelRow m : models) {
                String modelName = m.modelName();
                if (modelName.isEmpty()) {
                    continue;
                }
                String prefixedName = ModelNameUtil.buildPrefixedName(displayKey, modelName);
                boolean capsTools = m.capsTools();
                boolean capsVision = m.capsVision();
                int contextSize = m.contextSize();
                // 行里存的是 V9 JSON（或未迁移库的裸整数），而发现接口只需要数值上限；
                // 注入模式在这里无意义 —— 它只影响发往上游的请求体。
                int maxOutputTokens = MaxOutputTokensSetting
                        .parse(m.maxOutputTokens(), objectMapper).maxOutputTokens();
                result.add(new AvailableModel(providerKey, displayKey, modelName, prefixedName, capsTools, capsVision, contextSize, maxOutputTokens));
            }
        }
        return result;
    }
}
