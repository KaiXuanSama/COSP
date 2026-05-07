package com.kaixuan.copilot_ollama_proxy.provider.mimo.ollama;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.protocol.anthropic.AnthropicStreamEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.ollama.OllamaChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MiMo Anthropic 流式事件到 Ollama chunk 的翻译器。
 * <p>
 * 与 LongCat 的 SSE translator 不同，这里处理的是 Anthropic event 流：
 * text_delta 直接转成增量文本，tool_use 需要跨多个 input_json_delta 片段做状态累计，
 * 最后在 message_stop 时统一收束成 Ollama 的完成包。
 */
final class MimoOllamaStreamTranslator {

    /** 用于创建普通文本增量 chunk 的工厂。 */
    @FunctionalInterface
    interface AssistantChunkFactory {
        OllamaChatResponse create(String modelName, String content);
    }

    /** 用于创建最终完成包的工厂。 */
    @FunctionalInterface
    interface AssistantCompletionFactory {
        OllamaChatResponse create(String modelName, String content, List<OllamaChatResponse.ToolCallResult> toolCalls);
    }

    /**
     * translator 的输出工厂集合。
     */
    record Support(AssistantChunkFactory assistantChunkFactory, AssistantCompletionFactory assistantCompletionFactory) {

        Support {
            Objects.requireNonNull(assistantChunkFactory, "assistantChunkFactory");
            Objects.requireNonNull(assistantCompletionFactory, "assistantCompletionFactory");
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MimoOllamaStreamTranslator.class);

    private final ObjectMapper objectMapper;
    private final Support support;
    private final StringBuilder textBuffer = new StringBuilder();
    private final List<OllamaChatResponse.ToolCallResult> toolCalls = new ArrayList<>();

    private Map<String, Object> currentToolInput;
    private String currentToolName;
    private StringBuilder currentToolJsonBuffer;

    /**
     * @param objectMapper 用于把 partial_json 片段累积后解析成工具参数 Map
     * @param support 用于创建中间 chunk 和最终完成包的工厂集合
     */
    MimoOllamaStreamTranslator(ObjectMapper objectMapper, Support support) {
        this.objectMapper = objectMapper;
        this.support = support;
    }

    /**
     * 翻译单个 Anthropic 流式事件。
     * <p>
     * 这个方法只处理事件状态机，不负责任何 HTTP、SSE 或 provider 路由逻辑。
     * 因此 service 层现在只需要把事件流交给 translator，再把输出结果透传给控制器即可。
     *
     * @param event MiMo 上游返回的单个 Anthropic 流式事件
     * @param modelName 当前请求的模型名称
     * @return 由该事件生成的 0 到多个 Ollama chunk
     */
    List<OllamaChatResponse> translate(AnthropicStreamEvent event, String modelName) {
        List<OllamaChatResponse> results = new ArrayList<>();

        switch (event.getType()) {
        case "message_start", "message_delta" -> {
        }
        case "content_block_start" -> handleContentBlockStart(event);
        case "content_block_delta" -> handleContentBlockDelta(event, modelName, results);
        case "content_block_stop" -> handleContentBlockStop();
        case "message_stop" -> results.add(support.assistantCompletionFactory().create(modelName, textBuffer.toString(), List.copyOf(toolCalls)));
        default -> {
        }
        }

        return results;
    }

    /**
     * 在 tool_use block 开始时初始化当前工具调用的累计状态。
     */
    private void handleContentBlockStart(AnthropicStreamEvent event) {
        Map<String, Object> block = event.getContentBlock();
        if (block != null && "tool_use".equals(block.get("type"))) {
            currentToolName = (String) block.get("name");
            currentToolInput = new LinkedHashMap<>();
            currentToolJsonBuffer = new StringBuilder();
        }
    }

    /**
     * 处理 text_delta 与 input_json_delta 两类内容增量。
     * <p>
     * 这里保留了一个关键修复：tool 参数不再按“单片 JSON 直接解析”的方式处理，
     * 而是先把 partial_json 原始片段拼接起来，再尝试整体解析，避免分片 JSON 时丢失工具参数。
     */
    private void handleContentBlockDelta(AnthropicStreamEvent event, String modelName, List<OllamaChatResponse> results) {
        Map<String, Object> delta = event.getDelta();
        if (delta == null) {
            return;
        }

        String deltaType = (String) delta.get("type");
        if ("text_delta".equals(deltaType)) {
            String text = (String) delta.get("text");
            if (text != null) {
                textBuffer.append(text);
                results.add(support.assistantChunkFactory().create(modelName, text));
            }
            return;
        }

        if ("input_json_delta".equals(deltaType)) {
            String json = (String) delta.get("partial_json");
            if (json != null && currentToolInput != null && currentToolJsonBuffer != null) {
                currentToolJsonBuffer.append(json);
                try {
                    Map<String, Object> parsed = objectMapper.readValue(currentToolJsonBuffer.toString(), new TypeReference<>() {
                    });
                    currentToolInput.clear();
                    currentToolInput.putAll(parsed);
                } catch (Exception ignored) {
                    log.debug("Partial JSON parse, accumulating: {}", currentToolJsonBuffer);
                }
            }
        }
    }

    /**
     * 在 tool_use block 结束时，把当前累计的工具调用压入最终结果列表。
     */
    private void handleContentBlockStop() {
        if (currentToolName != null) {
            var toolCall = new OllamaChatResponse.ToolCallResult();
            var function = new OllamaChatResponse.ToolCallFunction();
            function.setName(currentToolName);
            function.setArguments(currentToolInput != null ? currentToolInput : Map.of());
            toolCall.setFunction(function);
            toolCalls.add(toolCall);
            currentToolName = null;
            currentToolInput = null;
            currentToolJsonBuffer = null;
        }
    }
}