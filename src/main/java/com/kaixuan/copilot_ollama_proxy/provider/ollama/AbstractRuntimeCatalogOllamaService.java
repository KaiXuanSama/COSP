package com.kaixuan.copilot_ollama_proxy.provider.ollama;

import com.kaixuan.copilot_ollama_proxy.application.ollama.OllamaService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeModel;
import com.kaixuan.copilot_ollama_proxy.application.runtime.RuntimeProviderCatalog;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaShowResponse;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaTagsResponse;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ollama provider 的运行时基类。
 *
 * 这个类把所有与具体 provider 无关的 Ollama 协议原语集中在一起：
 * 模型解析、max_tokens 解析、文本内容提取、assistant chunk/完成包组装、
 * tags 列表构造、showModel 的公共模板，以及 supportsModel 的默认实现。
 *
 * 子类只需要提供 provider 特化点（providerKey、family、format、license 等），
 * 以及各自的非流式/流式编排逻辑。协议转换和流式状态机则进一步下沉到
 * 各 provider 的 converter 和 translator 中。
 */
public abstract class AbstractRuntimeCatalogOllamaService implements OllamaService {

    /** 运行时 provider 配置目录，统一暴露数据库中的 provider 配置与模型列表。 */
    private final RuntimeProviderCatalog runtimeProviderCatalog;

    /** 当用户未指定模型时，回退使用的默认模型名称。 */
    private final String fallbackDefaultModel;

    /**
     * @param runtimeProviderCatalog 运行时 provider 配置目录
     * @param fallbackDefaultModel 当请求中未指定模型时使用的默认模型名称
     */
    protected AbstractRuntimeCatalogOllamaService(RuntimeProviderCatalog runtimeProviderCatalog, String fallbackDefaultModel) {
        this.runtimeProviderCatalog = runtimeProviderCatalog;
        this.fallbackDefaultModel = fallbackDefaultModel;
    }

    @Override
    public boolean supportsModel(String modelName) {
        ProviderRuntimeConfiguration config = getProviderConfiguration();
        return config != null && config.supportsModel(modelName);
    }

    @Override
    public Mono<OllamaTagsResponse> listModels() {
        OllamaTagsResponse response = new OllamaTagsResponse();
        ProviderRuntimeConfiguration config = getProviderConfiguration();

        if (config == null) {
            response.setModels(List.of());
            return Mono.just(response);
        }

        response.setModels(config.models().stream().map(this::createModelInfo).toList());
        return Mono.just(response);
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
     * @param modelName 模型名称
     * @return 解析后的模型名称，如果未指定则返回默认模型名称
     */
    protected String resolveModelOrDefault(String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            return modelName;
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
     * 解析请求中的模型名称，如果未指定则使用默认模型。
     * @param modelName 模型名称
     * @return 解析后的模型名称，如果未指定则返回默认模型名称
     */
    protected String resolveRequestModel(String modelName) {
        return resolveModelOrDefault(modelName);
    }

    /**
     * 解析请求中的最大 token 数量，如果未指定则使用默认值。
     * @param options 请求选项
     * @return 解析后的最大 token 数量，如果未指定则返回默认值
     */
    protected int resolveMaxTokens(Map<String, Object> options) {
        if (options != null && options.containsKey("num_predict")) {
            Object value = options.get("num_predict");
            if (value instanceof Number number) {
                int resolved = number.intValue();
                return resolved > 0 ? resolved : 8192;
            }
        }
        return 8192;
    }

    /**
     * 从请求内容中提取字符串内容，支持字符串或列表格式。
     *
     * @param content 请求内容，可能是字符串或列表
     * @return 提取后的字符串内容，如果无法提取则返回空字符串
     */
    protected String extractStringContent(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String stringContent) {
            return stringContent;
        }
        if (content instanceof List<?> listContent) {
            StringBuilder builder = new StringBuilder();
            for (Object item : listContent) {
                if (item instanceof Map<?, ?> mapContent) {
                    Object text = mapContent.get("text");
                    if (text != null) {
                        builder.append(text);
                    }
                }
            }
            return builder.toString();
        }
        return content.toString();
    }

    /**
     * 创建一个助手消息的聊天响应，通常用于流式响应中的增量更新。
     * @param modelName 模型名称
     * @param text 消息内容
     * @param done 是否完成
     * @return 聊天响应对象
     */
    protected OllamaChatResponse createAssistantChunk(String modelName, String text, boolean done) {
        return createResponse(modelName, done, null, createMessage("assistant", text));
    }

    /**
     * 创建一个助手消息的聊天响应，通常用于完成时的最终响应。
     * @param modelName 模型名称
     * @param doneReason 完成原因，如 "stop"、"tool_calls" 等
     * @param content 消息内容
     * @param toolCalls 工具调用结果列表，如果有工具调用则传入，否则传 null 或空列表
     * @return 聊天响应对象
     */
    protected OllamaChatResponse createAssistantCompletion(String modelName, String doneReason, String content, List<OllamaChatResponse.ToolCallResult> toolCalls) {
        var message = createMessage("assistant", toolCalls != null && !toolCalls.isEmpty() ? "" : content);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            message.setToolCalls(toolCalls);
        }
        return createResponse(modelName, true, doneReason, message);
    }

    /**
     * 创建一个通用的聊天响应对象。
     * @param modelName 模型名称
     * @param done 是否完成
     * @param doneReason 完成原因
     * @param message 消息对象
     * @return 聊天响应对象
     */
    protected OllamaChatResponse createResponse(String modelName, boolean done, String doneReason, OllamaChatResponse.ResponseMessage message) {
        var response = new OllamaChatResponse();
        response.setModel(modelName);
        response.setCreatedAt(currentTimestamp());
        response.setDone(done);
        if (doneReason != null && !doneReason.isBlank()) {
            response.setDoneReason(doneReason);
        }
        response.setMessage(message);
        return response;
    }

    /**
     * 创建一个聊天消息对象。
     * @param role 消息角色，如 "assistant"、"user"
     * @param content 消息内容
     * @return 聊天消息对象
     */
    protected OllamaChatResponse.ResponseMessage createMessage(String role, String content) {
        var message = new OllamaChatResponse.ResponseMessage();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    /**
     * 获取当前时间的 ISO 8601 格式字符串，通常用于响应中的时间戳字段。
     * @return 当前时间的 ISO 8601 格式字符串
     */
    protected String currentTimestamp() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    /**
     * 构建模型的详细信息响应对象。
     * @param resolvedModel 模型名称
     * @param contextLength 上下文长度
     * @param capabilities 模型能力列表
     * @return 模型的详细信息响应对象
     */
    protected OllamaShowResponse buildShowResponse(String resolvedModel, int contextLength, List<String> capabilities) {
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
                Map.of("general.architecture", architecture, "general.basename", resolvedModel, architecture + ".context_length", contextLength, architecture + ".embedding_length", 8192));
        return response;
    }

    /**
     * 创建模型信息对象。
     * @param model 模型对象
     * @return 模型信息对象
     */
    private OllamaTagsResponse.ModelInfo createModelInfo(ProviderRuntimeModel model) {
        OllamaTagsResponse.ModelInfo info = new OllamaTagsResponse.ModelInfo();
        info.setName(model.modelName());
        info.setModel(model.modelName());
        info.setModifiedAt(currentTimestamp());
        info.setSize(0);
        info.setDigest("sha256:" + UUID.randomUUID().toString().replace("-", ""));
        info.setDetails(buildTagDetails(model));
        return info;
    }

    /**
     * 从模型配置中构建标签详情对象。
     * @param model 模型对象
     * @return 标签详情对象
     */
    protected OllamaTagsResponse.ModelDetails buildTagDetails(ProviderRuntimeModel model) {
        OllamaTagsResponse.ModelDetails details = new OllamaTagsResponse.ModelDetails();
        details.setFormat(providerFormat());
        details.setFamily(providerFamily());
        details.setFamilies(providerFamilies());
        details.setParameterSize(providerParameterSize());
        details.setQuantizationLevel(providerQuantizationLevel());
        return details;
    }

    /**
     * 获取 provider 的协议格式标识，如 "openai"、"mimo" 等。
     *
     * 该值会出现在 Ollama tags/show 响应的 details.format 字段中，
     * 供客户端识别模型背后的协议类型。
     *
     * @return 协议格式标识字符串
     */
    protected abstract String providerFormat();

    /**
     * 获取服务商所属的模型家族名称，如 "LongCat"、"MiMo" 等。
     * @return 模型家族名称字符串
     */
    protected abstract String providerFamily();

    /**
     * 获取服务商所属的模型家族列表，通常包含一个元素，但也可能包含多个相关家族。
     * @return 模型家族名称列表
     */
    protected abstract List<String> providerFamilies();

    /**
     * 获取模型的参数规模描述，如 "7B"、"13B"、"Flash" 等。
     * @return 模型参数规模描述字符串
     */
    protected abstract String providerParameterSize();

    /**
     * 获取模型的许可证类型，如 "Proprietary"、"MIT"、"Apache-2.0" 等。
     * @return 模型许可证类型字符串
     */
    protected abstract String providerLicense();

    /**
     * 获取模型的量化级别描述，如 "none"、"int8"、"int4" 等。
     * @return 模型量化级别描述字符串
     */
    protected String providerQuantizationLevel() {
        return "none";
    }

    /**
     * 获取模型的架构信息，如 "Transformer"、"LSTM" 等。
     * @return 模型架构信息字符串
     */
    protected String providerArchitecture() {
        return getProviderKey();
    }
}