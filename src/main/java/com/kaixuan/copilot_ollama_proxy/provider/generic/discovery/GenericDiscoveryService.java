package com.kaixuan.copilot_ollama_proxy.provider.generic.discovery;

import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用模型发现服务 —— 处理所有数据库供应商配置。
 * 从数据库动态读取配置，提供模型发现和详情查询能力。
 */
@Service
public class GenericDiscoveryService {

    /**
     * 根据已解析路由构建模型详情响应。
     *
     * @param route 应用层解析出的供应商模型路由
     * @return Ollama 模型详情响应
     */
    public OllamaShowResponse showModel(ResolvedProviderRoute route) {
        ProviderRuntimeConfiguration config = route.provider();
        String providerKey = config.providerKey();
        String resolvedModel = route.model();
        List<String> caps = new ArrayList<>();
        caps.add("completion");
        int contextLength = 8192;
        for (var m : config.models()) {
            if (resolvedModel.equals(m.modelName())) {
                contextLength = m.contextSize() >= 8192 ? m.contextSize() : 8192;
                if (m.capsTools()) caps.add("tools");
                if (m.capsVision()) caps.add("vision");
                break;
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
        response.setModifiedAt(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
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

}
