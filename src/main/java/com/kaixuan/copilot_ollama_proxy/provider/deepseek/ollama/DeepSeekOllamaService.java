package com.kaixuan.copilot_ollama_proxy.provider.deepseek.ollama;

import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.provider.ollama.AbstractRuntimeCatalogOllamaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek Ollama 协议实现 —— 提供模型发现和详情查询能力。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class DeepSeekOllamaService extends AbstractRuntimeCatalogOllamaService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekOllamaService.class);

    public DeepSeekOllamaService(RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${deepseek.default-model:deepseek-v4-flash}") String fallbackDefaultModel) {
        super(runtimeProviderCatalog, fallbackDefaultModel);
    }

    // ========== 路由支持 ==========

    @Override
    public String getProviderKey() {
        return "deepseek";
    }

    @Override
    protected String providerFormat() {
        return "deepseek";
    }

    @Override
    protected String providerFamily() {
        return "DeepSeek";
    }

    @Override
    protected List<String> providerFamilies() {
        return List.of("DeepSeek");
    }

    @Override
    protected String providerParameterSize() {
        return "Flash";
    }

    @Override
    protected String providerLicense() {
        return "Proprietary";
    }

    @Override
    public OllamaShowResponse showModel(String modelName) {
        String resolvedModel = resolveModelOrDefault(modelName);
        List<String> capabilities = buildCapabilitiesFromDb(resolvedModel);
        int contextLength = requireContextLength(resolvedModel);
        return buildShowResponse(resolvedModel, contextLength, capabilities);
    }

    /**
     * 从数据库读取模型的能力标志（caps_tools / caps_vision），构建 capabilities 列表。
     */
    private List<String> buildCapabilitiesFromDb(String resolvedModel) {
        List<String> caps = new ArrayList<>();
        caps.add("completion");
        try {
            var model = requireModelConfiguration(resolvedModel);
            if (model.capsTools()) {
                caps.add("tools");
            }
            if (model.capsVision()) {
                caps.add("vision");
            }
        } catch (Exception e) {
            log.warn("DeepSeek 模型 [{}] 能力读取失败，仅使用默认 completion 能力: {}", resolvedModel, e.getMessage());
        }
        return caps;
    }
}
