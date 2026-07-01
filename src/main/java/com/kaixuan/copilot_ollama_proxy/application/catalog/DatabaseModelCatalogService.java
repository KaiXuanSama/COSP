package com.kaixuan.copilot_ollama_proxy.application.catalog;

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
 * 遍历已启用供应商、去除自定义供应商的 "custom-" 前缀、拼接展示前缀、读取能力标记。
 */
@Service
public class DatabaseModelCatalogService implements ModelCatalogService {

    private final ProviderConfigRepository providerConfigRepository;

    public DatabaseModelCatalogService(ProviderConfigRepository providerConfigRepository) {
        this.providerConfigRepository = providerConfigRepository;
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
            // 自定义供应商展示时去除 custom- 前缀
            String displayKey = providerKey.startsWith("custom-") ? providerKey.substring(7) : providerKey;
            for (ProviderModelRow m : models) {
                String modelName = m.modelName();
                if (modelName.isEmpty()) {
                    continue;
                }
                String prefixedName = ModelNameUtil.buildPrefixedName(displayKey, modelName);
                boolean capsTools = m.capsTools();
                boolean capsVision = m.capsVision();
                result.add(new AvailableModel(providerKey, displayKey, modelName, prefixedName, capsTools, capsVision));
            }
        }
        return result;
    }
}
