package com.kaixuan.copilot_ollama_proxy.provider.mimo.discovery;

import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.provider.AbstractDiscoveryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MiMo 模型发现服务 —— 提供模型发现和详情查询能力。
 * 所有运行时配置（API Key、Base URL、模型列表）均从数据库读取。
 */
@Service
public class MimoDiscoveryService extends AbstractDiscoveryService {

    public MimoDiscoveryService(RuntimeProviderCatalog runtimeProviderCatalog,
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
}
