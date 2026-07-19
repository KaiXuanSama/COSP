package com.kaixuan.copilot_ollama_proxy.provider;

import com.kaixuan.copilot_ollama_proxy.application.ollama.OllamaService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.application.util.ModelNameUtil;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模型发现服务的运行时基类。
 *
 * 这个类把所有与具体 provider 无关的模型发现协议原语集中在一起：
 * 模型解析、showModel 的公共模板，以及 supportsModel 的默认实现。
 *
 * 子类只需要提供 provider 特化点（providerKey、family、format、license 等）。
 * showModel 已有默认实现（解析模型 → 读能力 → 读上下文长度 → 构建响应），
 * 只有 Generic 等需要特殊路由逻辑的子类才需要覆写。
 */
public abstract class AbstractDiscoveryService implements OllamaService {

    /** 运行时 provider 配置目录，统一暴露数据库中的 provider 配置与模型列表。 */
    private final RuntimeProviderCatalog runtimeProviderCatalog;

    /** 当用户未指定模型时，回退使用的默认模型名称。 */
    private final String fallbackDefaultModel;

    /**
     * @param runtimeProviderCatalog 运行时 provider 配置目录
     * @param fallbackDefaultModel 当请求中未指定模型时使用的默认模型名称
     */
    protected AbstractDiscoveryService(RuntimeProviderCatalog runtimeProviderCatalog, String fallbackDefaultModel) {
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.fallbackDefaultModel = fallbackDefaultModel;
    }

    @Override
    public boolean supportsModel(String modelName) {
        ProviderRuntimeConfiguration config = getProviderConfiguration();
        if (config == null) {
            return false;
        }

        // 解析模型名称，提取可能的供应商前缀
        ModelNameUtil.ParseResult parsed = ModelNameUtil.parse(modelName);

        // 如果带供应商前缀，必须精确匹配当前供应商
        if (parsed.hasProviderPrefix()) {
            if (!parsed.providerKey().equalsIgnoreCase(getProviderKey())) {
                return false;
            }
            // 前缀匹配后，检查实际模型名是否在配置中
            return config.supportsModel(parsed.modelName());
        }

        // 无前缀时，使用原有匹配逻辑
        return config.supportsModel(modelName);
    }

    /**
     * 获取当前服务商的运行时配置。
     * @return 如果未找到配置则返回 null，调用方需做好 null 安全检查。
     */
    protected ProviderRuntimeConfiguration getProviderConfiguration() {
        return runtimeProviderCatalog.getActiveProvider(getProviderKey());
    }

    /**
     * 解析模型名称，如果未指定则使用默认模型。
     * 支持带供应商前缀的模型名称，会自动去除前缀获取实际模型名。
     * @param modelName 模型名称，可能带前缀如 "[DeepSeek]deepseek-v4-flash"
     * @return 解析后的实际模型名称
     */
    protected String resolveModelOrDefault(String modelName) {
        // 先去除可能存在的供应商前缀
        String actualModelName = ModelNameUtil.stripPrefix(modelName);

        if (actualModelName != null && !actualModelName.isBlank()) {
            return actualModelName;
        }

        ProviderRuntimeConfiguration config = getProviderConfiguration();
        if (config != null) {
            return config.models().stream().map(ProviderRuntimeModel::modelName).filter(name -> !name.isBlank()).findFirst().orElse(fallbackDefaultModel);
        }
        return fallbackDefaultModel;
    }

    /**
     * 获取指定模型的配置，如果未找到则抛出异常。
     * @param resolvedModel 解析后的模型名称
     * @return 模型的运行时配置
     * @throws IllegalStateException 如果未找到模型配置
     */
    protected ProviderRuntimeModel requireModelConfiguration(String resolvedModel) {
        ProviderRuntimeConfiguration config = getProviderConfiguration();
        if (config != null) {
            return config.models().stream().filter(model -> resolvedModel.equals(model.modelName())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("在运行时配置中找不到模型 " + resolvedModel + " 的配置，请检查 provider_model 表"));
        }
        throw new IllegalStateException("在运行时配置中找不到模型 " + resolvedModel + " 的配置，请检查 provider_model 表");
    }

    /**
     * 获取指定模型的 context length，如果未找到或未配置则抛出异常。
     * @param resolvedModel 解析后的模型名称
     * @return 模型的 context length
     * @throws IllegalStateException 如果未找到模型配置或 context length 未配置
     */
    protected int requireContextLength(String resolvedModel) {
        ProviderRuntimeModel model = requireModelConfiguration(resolvedModel);
        if (model.contextSize() > 0) {
            return model.contextSize();
        }
        throw new IllegalStateException("模型 " + resolvedModel + " 的 context_size 未在运行时配置中配置或为 0，请先完成配置");
    }

    /**
     * 获取当前时间的 ISO 8601 格式字符串，用于发现响应中的时间戳字段。
     *
     * @return 当前时间的 ISO 8601 字符串
     */
    protected String currentTimestamp() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /**
     * 构建模型的详细信息响应对象。
     * 返回的模型名称会添加供应商前缀，格式为 [ProviderKey]modelName。
     * @param resolvedModel 实际模型名称（不带前缀）
     * @param contextLength 上下文长度
     * @param capabilities 模型能力列表
     * @return 模型的详细信息响应对象
     */
    protected OllamaShowResponse buildShowResponse(String resolvedModel, int contextLength, List<String> capabilities) {
        // 添加供应商前缀，保持与 listModels 返回的格式一致
        String prefixedModel = ModelNameUtil.buildPrefixedName(getProviderKey(), resolvedModel);

        OllamaShowResponse response = new OllamaShowResponse();
        response.setParameters("temperature 0.7\nnum_ctx " + contextLength);
        response.setLicense(providerLicense());
        response.setModifiedAt(currentTimestamp());
        response.setTemplate("{{ .System }}\n{{ .Prompt }}");
        response.setCapabilities(capabilities);

        OllamaShowResponse.ShowDetails details = new OllamaShowResponse.ShowDetails();
        details.setParentModel("");
        details.setFormat(providerFormat());
        details.setFamily(providerFamily());
        details.setFamilies(providerFamilies());
        details.setParameterSize(providerParameterSize());
        details.setQuantizationLevel(providerQuantizationLevel());
        response.setDetails(details);

        String architecture = providerArchitecture();
        response.setModelInfo(
                Map.of("general.architecture", architecture, "general.basename", prefixedModel, architecture + ".context_length", contextLength, architecture + ".embedding_length", 8192));
        return response;
    }

    /**
     * 默认的 showModel 实现 —— 解析模型名、读能力、读上下文长度、构建响应。
     *
    * 统一 Generic 发现服务可复用该实现，也可在需要时覆写路由逻辑。
     */
    @Override
    public OllamaShowResponse showModel(String modelName) {
        String resolvedModel = resolveModelOrDefault(modelName);
        List<String> capabilities = buildCapabilitiesFromDb(resolvedModel);
        int contextLength = requireContextLength(resolvedModel);
        return buildShowResponse(resolvedModel, contextLength, capabilities);
    }

    /** 获取 provider 的协议格式标识。 */
    protected abstract String providerFormat();

    /** 获取服务商所属的模型家族名称。 */
    protected abstract String providerFamily();

    /** 获取服务商所属的模型家族列表。 */
    protected abstract List<String> providerFamilies();

    /** 获取模型的参数规模描述。 */
    protected abstract String providerParameterSize();

    /** 获取模型的许可证类型。 */
    protected abstract String providerLicense();

    /** 获取模型的量化级别描述，默认 "none"。 */
    protected String providerQuantizationLevel() {
        return "none";
    }

    /** 获取模型的架构信息，默认返回 providerKey。 */
    protected String providerArchitecture() {
        return getProviderKey();
    }

    /**
     * 从数据库读取模型的能力标志（caps_tools / caps_vision），构建 capabilities 列表。
     * 始终包含 "completion"，根据 provider_model 表中的标志追加 "tools" 和 "vision"。
     * 如果读取失败，仅返回默认的 "completion" 能力。
     *
     * @param resolvedModel 解析后的模型名称
     * @return 模型能力列表
     */
    protected List<String> buildCapabilitiesFromDb(String resolvedModel) {
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
            // 能力读取失败时，仅使用默认 completion 能力
        }
        return caps;
    }
}
