package com.kaixuan.copilot_ollama_proxy.provider.deepseek.discovery;

import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.AbstractDiscoveryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DeepSeek 模型发现服务 —— 提供模型发现和详情查询能力。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class DeepSeekDiscoveryService extends AbstractDiscoveryService {

    public DeepSeekDiscoveryService(RuntimeProviderCatalog runtimeProviderCatalog,
            @Value("${deepseek.default-model:deepseek-v4-flash}") String fallbackDefaultModel) {
        super(runtimeProviderCatalog, fallbackDefaultModel);
    }

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
}
