package com.kaixuan.copilot_ollama_proxy.pipeline.protocol.translate;

import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslatedRequest;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI Chat Completions → OpenAI Responses 的<strong>请求体</strong>翻译器（C2R 去程）。
 *
 * <h2>在链条中的位置</h2>
 * 与 {@code ChatToMessagesRequestTranslator}（C2M）同位：下游请求进来后的
 * <strong>第一层</strong>，在设置层之前。翻译后 body 已是 Responses 形态，
 * {@code bodyProtocol} 随之切到 RESPONSES —— 后续的 system role 改写支线
 * （{@code ResponsesSystemPromptStage}，见 PLAN 阶段二）与思考注入支线
 * （{@code ResponsesThinkingStage}，已就位）按这个键查表命中。
 *
 * <h2>本类不做什么</h2>
 * <ul>
 *   <li><strong>不改写 system role</strong>。{@code system} → {@code developer}
 *       是 {@code ResponsesSystemPromptStage} 的职责（对齐 C2M 分工先例：
 *       翻译器不管 system 语义归一化）。本类把 system 消息原样保留为
 *       input 的 message item。</li>
 *   <li><strong>不补 max_output_tokens 默认值</strong>。那是
 *       {@code MaxOutputTokensSetting} 的职责（当前 RESPONSES 侧无支线，
 *       见 PLAN §6.4 的 TODO；本类只做字段搬运）。</li>
 *   <li><strong>不写 reasoning.summary</strong>。summary 是输出的显式 opt-in，
 *       不该由 effort 推导（采纳 CPA 注释的理由，决策 #6）。</li>
 *   <li><strong>不合成 Responses 特有形态</strong>。{@code namespace} /
 *       {@code web_search} 工具、{@code prompt_cache_key} 等 Codex 专有字段
 *       在 Chat 里没有对应物，不凭空造（决策 #4：忠实搬运）。</li>
 * </ul>
 *
 * <h2>白名单制（与 C2M 同一条纪律）</h2>
 * 链条末尾只有 {@code removeIf(Objects::isNull)}，非 null 的多余字段会原样
 * 发给上游然后 400 —— 因此顶层字段必须显式处置，没被搬过来的就是丢弃。
 * 完整丢弃清单见 PLAN §2.1，单测逐字段钉住。
 *
 * <h2>与 C2M 的关键差异：reasoning_effort 改名后删原字段</h2>
 * C2M 保留一份 {@code reasoning_effort} 是因为 Anthropic 侧的表态判定
 * （{@code ReasoningEffortSetting.downstreamHasAnthropicOpinion}）兼看它。
 * Responses 侧的判定（{@code downstreamHasResponsesOpinion}）<strong>只看
 * {@code reasoning.effort}</strong>——改名后设置层自然看到表态，保留反而有害：
 * {@code reasoning_effort} 是 Chat 字段，Responses 上游可能拒收多余字段，
 * 且这条线路没有 MESSAGES 侧那个 {@code body.remove} 收尾方。
 *
 * @see <a href="file:../../../../../../../../../docs/features/protocol-translation/chat-responses/PLAN.md">
 *      C2R 去程翻译实施计划</a>
 */
@Component
public class ChatToResponsesRequestTranslator implements RequestProtocolTranslator {

    /** 直接搬运的顶层标量（值域两侧相同，无需缩放或剥离）。 */
    private static final Set<String> PASSTHROUGH_SCALARS = Set.of(
            "temperature", "top_p", "parallel_tool_calls");

    private static final String FIELD_REASONING_EFFORT = "reasoning_effort";
    private static final String FIELD_REASONING = "reasoning";
    private static final String FIELD_TOOLS = "tools";
    private static final String FIELD_TOOL_CHOICE = "tool_choice";

    @Override
    public WireProtocol downstreamProtocol() {
        return WireProtocol.CHAT;
    }

    @Override
    public WireProtocol upstreamProtocol() {
        return WireProtocol.RESPONSES;
    }

    /**
     * 翻译请求体。
     *
     * @param downstreamBody 下游的 OpenAI Chat 请求体，不会被修改
     * @return 翻译产物，含供响应侧（R2C，未实现）使用的上下文
     * @throws RequestTranslationException 请求内容无法表达成 Responses 协议
     */
    @Override
    public TranslatedRequest translateRequest(Map<String, Object> downstreamBody) {
        Map<String, Object> source = downstreamBody == null ? Map.of() : downstreamBody;
        Map<String, Object> target = new LinkedHashMap<>();

        rejectLegacyFunctionCall(source);
        rejectMultipleChoices(source);

        copyIfPresent(source, target, "model");
        copyIfPresent(source, target, "stream");

        translateMaxOutputTokens(source, target);
        for (String field : PASSTHROUGH_SCALARS) {
            copyIfPresent(source, target, field);
        }
        translateThinking(source, target);
        translateResponseFormat(source, target);
        translateMessages(source, target);
        translateTools(source, target);

        // store / include 是 Responses 侧无状态代理的正确姿态，强制注入而非透传：
        // Chat 里没有这两个字段，不存在「下游表态」可尊重；索要密文是给回程翻译器
        // （R2C）铺路的基础设施字段。决策 #2，理由沿用 sub2api 的成文注释。
        target.put("store", Boolean.FALSE);
        target.put("include", List.of("reasoning.encrypted_content"));

        // 其余顶层字段一律丢弃（白名单制，见类注释）。
        return new TranslatedRequest(target, TranslationContext.fromDownstreamBody(source));
    }

    /**
     * legacy {@code function_call} / {@code functions} 不支持。
     *
     * <p>它们与 {@code tool_choice} 并存时是可检测的矛盾（new-api 报错先例）；
     * 单独出现时属于「翻译 legacy 形态」这个新功能，不在本次范围。
     * 两者都报错而非静默丢弃 —— 静默丢掉函数调用声明会产出一个语义完全
     * 不同的请求（模型以为没有工具可用）。
     */
    private void rejectLegacyFunctionCall(Map<String, Object> source) {
        if (source.containsKey("function_call")) {
            throw new RequestTranslationException("function_call",
                    "legacy function_call 不被 C2R 翻译支持，请改用 tools + tool_choice");
        }
        if (source.containsKey("functions")) {
            throw new RequestTranslationException("functions",
                    "legacy functions 不被 C2R 翻译支持，请改用 tools");
        }
    }

    /**
     * {@code n > 1} 报错。
     *
     * <p>Responses 的 output 数组没有「n 份候选」的概念，静默降级到 1 会
     * 让下游拿到与请求不符的结果数量 —— 属「继续下去必然产出语义错误请求」
     * 的情形，契约口径要求报错。
     */
    private void rejectMultipleChoices(Map<String, Object> source) {
        Object n = source.get("n");
        if (n instanceof Number number && number.intValue() > 1) {
            throw new RequestTranslationException("n",
                    "Responses 协议不支持 n>1 的多候选生成");
        }
    }

    /**
     * {@code max_completion_tokens ?? max_tokens} → {@code max_output_tokens}。
     *
     * <p>只搬运，不 clamp（上游对 &lt;16 自己报错，与「报错而非静默修正」一致）、
     * 不补默认（那是 {@code MaxOutputTokensSetting} 的职责）。
     */
    private void translateMaxOutputTokens(Map<String, Object> source, Map<String, Object> target) {
        Object value = source.containsKey("max_completion_tokens")
                ? source.get("max_completion_tokens")
                : source.get("max_tokens");
        if (value != null) {
            target.put("max_output_tokens", value);
        }
    }

    /**
     * {@code reasoning_effort} → {@code reasoning.effort}，改名后<strong>删原字段</strong>。
     *
     * <p>与 C2M 相反（那边保留原字段），理由见类注释：Responses 侧的表态判定
     * 只看 {@code reasoning.effort}，保留原字段反而可能被上游拒收。
     *
     * <p>{@code thinking} 不搬运：它是 Chat 侧的开关字段，Responses 的开关
     * 就在 {@code reasoning.effort} 的取值里（{@code "none"} 档），
     * 再塞一个 {@code thinking} 会与 effort 矛盾。
     */
    private void translateThinking(Map<String, Object> source, Map<String, Object> target) {
        Object effort = source.get(FIELD_REASONING_EFFORT);
        if (effort == null) {
            return;
        }
        Map<String, Object> reasoning = target.get(FIELD_REASONING) instanceof Map<?, ?> existing
                ? new LinkedHashMap<>(asStringKeyMap(existing))
                : new LinkedHashMap<>();
        reasoning.put("effort", effort);
        target.put(FIELD_REASONING, reasoning);
    }

    /**
     * {@code response_format} → {@code text.format}。
     *
     * <p>{@code json_schema} 形态（OpenAI 把 schema 放在
     * {@code response_format.json_schema} 嵌套层）展开为 Responses 的
     * {@code text.format}（扁平）；其余类型只搬 {@code type} 值。
     * 两侧语义相同，只是层级不同。
     */
    private void translateResponseFormat(Map<String, Object> source, Map<String, Object> target) {
        if (!(source.get("response_format") instanceof Map<?, ?> rawFormat)) {
            return;
        }
        Map<String, Object> format = asStringKeyMap(rawFormat);
        Map<String, Object> textFormat = new LinkedHashMap<>();
        Object type = format.get("type");
        if ("json_schema".equals(type) && format.get("json_schema") instanceof Map<?, ?> rawSchema) {
            // 展开：json_schema 的内层键（name/schema/strict）整体搬到 text.format 顶层。
            textFormat.putAll(asStringKeyMap(rawSchema));
            textFormat.put("type", "json_schema");
        } else if (type instanceof String typeValue) {
            textFormat.put("type", typeValue);
        } else {
            return;
        }
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("format", textFormat);
        target.put("text", text);
    }

    /**
     * {@code messages} → {@code input}。
     *
     * <p>非数组形态原样交给上游报错（与 C2M 同口径：契约只规定合法请求的映射，
     * 畸形请求的错误信息由上游给更准）。
     */
    private void translateMessages(Map<String, Object> source, Map<String, Object> target) {
        if (!source.containsKey("messages")) {
            return;
        }
        Object rawMessages = source.get("messages");
        if (!(rawMessages instanceof List<?> messages)) {
            target.put("input", rawMessages);
            return;
        }
        List<Object> input = new ArrayList<>(messages.size());
        Set<Object> emittedCallIds = new LinkedHashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            Object item = messages.get(i);
            if (!(item instanceof Map<?, ?> raw)) {
                input.add(item);
                continue;
            }
            translateOneMessage(asStringKeyMap(raw), "messages[" + i + "]", input, emittedCallIds);
        }
        target.put("input", input);
    }

    /**
     * 翻译单条消息，产出一个或多个 input item（assistant 带 tool_calls 时
     * 正文与每个工具调用各成一个 item）。
     *
     * <p>配对修复：{@code role:tool} 消息的 {@code tool_call_id} 找不到前置
     * {@code function_call.call_id} 时<strong>丢弃并留痕</strong>，不凭空造
     * {@code function_call} 去配（决策 #5：只填有来源的字段）。
     */
    private void translateOneMessage(Map<String, Object> message, String path,
                                     List<Object> sink, Set<Object> emittedCallIds) {
        String role = message.get("role") instanceof String value ? value : null;
        if (role == null || role.isBlank()) {
            throw new RequestTranslationException(path + ".role", "缺失或为空");
        }
        switch (role) {
            case "system", "developer" -> translateRoleMessage(message, "system", sink);
            case "user" -> translateRoleMessage(message, "user", sink);
            case "assistant" -> translateAssistant(message, path, sink, emittedCallIds);
            case "tool", "function" -> translateToolOutput(message, path, sink, emittedCallIds);
            default -> throw new RequestTranslationException(path + ".role",
                    "是未知角色 " + role + "，无法翻译到 Responses 协议");
        }
    }

    /**
     * system / developer / user 消息 → message item。
     *
     * <p>role 统一归一：{@code developer} → {@code system}（Responses 的
     * message item 官方只认 user / assistant / developer / system；
     * developer 的官方改写留给支线，本类先把 Chat 侧的 developer 归到
     * system 这个「与支线约定好的中间形态」——支线只处理 system 一种）。
     *
     * <p>content 一律升格为块数组（Responses 的 message item 只接受数组形态，
     * 与 Chat 的「字符串或数组皆可」不同，这里必须升格）。
     */
    private void translateRoleMessage(Map<String, Object> message, String role, List<Object> sink) {
        List<Object> content = translateContent(message.get("content"), role);
        if (content.isEmpty()) {
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("role", role);
        item.put("content", content);
        sink.add(item);
    }

    /**
     * assistant 消息 → （若有思考）reasoning item + message item（正文，若有）+
     * 每个工具调用一个 function_call item。
     *
     * <h2>{@code reasoning_content} 翻译为 reasoning item（明文形态）</h2>
     *
     * <p>曾经这里直接丢弃思考——依据是「Chat 客户端本就不回放思考」（BYOK 调查，
     * Copilot 确实会回放，见该调查的修正）。2026-10-04 deepseek 官方实测推翻了
     * 丢弃路线：思考模式的对话式上游要求历史里 assistant 消息<strong>必须携带
     * 思考</strong>，缺失直接 400（REQUEST-CONTRACT §4.8 硬约束③，sub2api 记录的
     * 同一错误："The reasoning_content in the thinking mode must be passed back"）。
     * 是否校验因供应商而异（mimo/stepfun 不校验，deepseek 官方校验），
     * 不能假定任何一种。
     *
     * <p>产出的形态是<strong>明文 reasoning item</strong>（{@code content[]} 带
     * {@code reasoning_text}、{@code encrypted_content: null}）——契约 §4.8 记录的
     * 「上游不签发密文」填充方式，也是唯一可由 Chat 明文构造的形态。它与 R2C
     * 响应侧构成<strong>往返闭环</strong>：R2C 把上游 reasoning 转成
     * {@code reasoning_content} 给下游 → 下轮回传 → 本方法转回 reasoning item。
     * 思考在两侧都不断链，且<strong>零缓存</strong>（透传的是明文，与 §4.8
     * sub2api 的「缓存明文」路线不同——那仅在「上游签发密文 + 下游只有密文」
     * 时才需要，而本线路下游手上本来就有明文）。
     *
     * <p>item 顺序：reasoning 在 message 之前——与上游产出顺序一致
     * （实测形态 B/C 里 reasoning 占 output_index 0、message 在后），回放时保持
     * 同序最不容易踩上游的顺序校验。
     */
    private void translateAssistant(Map<String, Object> message, String path,
                                    List<Object> sink, Set<Object> emittedCallIds) {
        Object reasoning = message.get("reasoning_content");
        if (reasoning instanceof String text && !text.isBlank()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "reasoning");
            item.put("summary", List.of());
            item.put("content", List.of(Map.of("type", "reasoning_text", "text", text)));
            item.put("encrypted_content", null);
            sink.add(item);
        }
        List<Object> content = translateContent(message.get("content"), "assistant");
        if (!content.isEmpty()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "message");
            item.put("role", "assistant");
            item.put("content", content);
            sink.add(item);
        }
        if (message.get("tool_calls") instanceof List<?> toolCalls) {
            for (int i = 0; i < toolCalls.size(); i++) {
                if (toolCalls.get(i) instanceof Map<?, ?> raw) {
                    translateToolCall(asStringKeyMap(raw), path + ".tool_calls[" + i + "]",
                            sink, emittedCallIds);
                }
            }
        }
    }

    /**
     * 单个 {@code tool_calls} 元素 → {@code function_call} item。
     *
     * <p>{@code tool_calls[].id} → {@code call_id}（配对键，与
     * {@code function_call_output.call_id} 对齐）；item 级 {@code id} 不造
     * （决策 #5：实测一半样本不造照样被接受）。
     *
     * <p>{@code arguments} 保持字符串形态 —— Responses 的
     * {@code function_call.arguments} 就是 JSON 字符串，与 Chat 相同，无需解析。
     */
    private void translateToolCall(Map<String, Object> toolCall, String path,
                                   List<Object> sink, Set<Object> emittedCallIds) {
        Map<String, Object> rawFunction = toolCall.get("function") instanceof Map<?, ?> function
                ? asStringKeyMap(function)
                : null;
        if (rawFunction == null || !(rawFunction.get("name") instanceof String name) || name.isBlank()) {
            throw new RequestTranslationException(path + ".function",
                    "缺失或无名，无法构造 function_call item");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function_call");
        item.put("call_id", toolCall.get("id"));
        item.put("name", name);
        item.put("arguments", argumentsAsString(rawFunction.get("arguments")));
        sink.add(item);
        if (toolCall.get("id") != null) {
            emittedCallIds.add(toolCall.get("id"));
        }
    }

    /**
     * {@code role:tool} 消息 → {@code function_call_output} item。
     *
     * <p>配对失败（call_id 无前置 function_call）时丢弃该条 —— 造一个假
     * function_call 去配会污染模型上下文（决策 #5）。
     *
     * <p>output 压平成字符串：Responses 的 {@code function_call_output.output}
     * 是字符串形态（抓包样本里全部如此），块数组原样搬会被拒。
     */
    private void translateToolOutput(Map<String, Object> message, String path,
                                     List<Object> sink, Set<Object> emittedCallIds) {
        Object callId = message.get("tool_call_id");
        if (callId == null || !emittedCallIds.contains(callId)) {
            // 孤儿结果：配不上对。丢弃（不报错）—— 与 C2M ToolPairingNormalizer
            // 丢孤儿 tool_result 同口径，属「靠丢弃能得到合法请求」的情形。
            return;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "function_call_output");
        item.put("call_id", callId);
        item.put("output", stringifyOutput(message.get("content"), path));
        sink.add(item);
    }

    /**
     * content（字符串或块数组）→ Responses 块数组。
     *
     * <p>文本块按角色定形态：user/system 用 {@code input_text}、
     * assistant 用 {@code output_text}（RESEARCH §4.1：两家独立实现一致，
     * 且与 Responses 官方语义贴 —— input 是给模型看的，output 是模型产的）。
     * 图片块解包为 {@code input_image}（丢 {@code detail}，三家共识）。
     * 未知块类型<strong>报错</strong>（与 C2M 的「丢弃」不同：Responses
     * 的块类型空间更小，静默丢弃会让多模态请求无声降级为纯文本，
     * 下游无法察觉 —— 报错让下游知道改哪里）。
     */
    private List<Object> translateContent(Object content, String role) {
        List<Object> result = new ArrayList<>();
        if (content instanceof String text) {
            if (!text.isBlank()) {
                result.add(textBlock(text, role));
            }
            return result;
        }
        if (!(content instanceof List<?> parts)) {
            return result;
        }
        boolean assistant = "assistant".equals(role);
        for (Object part : parts) {
            if (!(part instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> block = asStringKeyMap(raw);
            String type = block.get("type") instanceof String value ? value : null;
            switch (type == null ? "" : type) {
                case "text", "input_text", "output_text" -> {
                    if (block.get("text") instanceof String text && !text.isBlank()) {
                        result.add(textBlock(text, role));
                    }
                }
                case "image_url" -> {
                    if (!assistant) {
                        String url = extractImageUrl(block);
                        if (url != null && !url.isBlank()) {
                            Map<String, Object> image = new LinkedHashMap<>();
                            image.put("type", "input_image");
                            image.put("image_url", url);
                            result.add(image);
                        }
                    }
                }
                default -> throw new RequestTranslationException("content",
                        "含未知块类型 " + type + "，无法翻译到 Responses 协议");
            }
        }
        return result;
    }

    private Map<String, Object> textBlock(String text, String role) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "assistant".equals(role) ? "output_text" : "input_text");
        block.put("text", text);
        return block;
    }

    private String extractImageUrl(Map<String, Object> block) {
        Object imageUrl = block.get("image_url");
        if (imageUrl instanceof String direct) {
            return direct;
        }
        if (imageUrl instanceof Map<?, ?> map && map.get("url") instanceof String url) {
            return url;
        }
        return null;
    }

    /**
     * tool 输出压平成字符串。字符串直通；块数组取全部文本块拼接
     * （非文本块无字符串形态可表达，丢弃）；null 归空串（Responses
     * 要求 output 为字符串，缺失会被拒）。
     */
    private String stringifyOutput(Object content, String path) {
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> raw
                        && "text".equals(raw.get("type"))
                        && raw.get("text") instanceof String text) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        if (content == null) {
            return "";
        }
        throw new RequestTranslationException(path + ".content", "既不是字符串也不是块数组");
    }

    private String argumentsAsString(Object arguments) {
        if (arguments instanceof String text) {
            return text;
        }
        // 非字符串（畸形或已解析的 Map）—— 空串兜底而非报错：
        // arguments 缺失在无参调用里是合法形态，与 C2M parseArguments 同口径。
        return arguments == null ? "" : String.valueOf(arguments);
    }

    /**
     * {@code tools}（嵌套）→ Responses 扁平形态；{@code tool_choice} 展平。
     *
     * <p>{@code strict} 缺省时<strong>显式写 false</strong> —— 两侧默认值相反
     * （Chat 默认 false、Responses 默认 true），省略不写等于悄悄改语义。
     * 这是 CPA 注释给出的唯一因果说明，实测 10/10 工具全带 strict:false 佐证。
     */
    private void translateTools(Map<String, Object> source, Map<String, Object> target) {
        if (!(source.get(FIELD_TOOLS) instanceof List<?> tools)) {
            return;
        }
        List<Object> translated = new ArrayList<>(tools.size());
        for (Object item : tools) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> tool = translateTool(asStringKeyMap(raw));
                if (tool != null) {
                    translated.add(tool);
                }
            }
        }
        if (!translated.isEmpty()) {
            target.put(FIELD_TOOLS, translated);
        }
        Object toolChoice = translateToolChoice(source.get(FIELD_TOOL_CHOICE), translated);
        // 只有声明了工具时才发 tool_choice —— 没有工具的选择策略无意义，
        // 部分上游还会因此报错（与 C2M 同判）。
        if (toolChoice != null && !translated.isEmpty()) {
            target.put(FIELD_TOOL_CHOICE, toolChoice);
        }
    }

    /**
     * 单个工具定义：{@code {"type":"function","function":{…}}} → 扁平。
     *
     * <p>非 function 类型（hosted tool）丢弃：Chat 侧不会合法出现，
     * 硬映射得不到等价语义（与 C2M 同判）。
     */
    private Map<String, Object> translateTool(Map<String, Object> tool) {
        Object type = tool.get("type");
        if (type != null && !"function".equals(type)) {
            return null;
        }
        if (!(tool.get("function") instanceof Map<?, ?> rawFunction)) {
            return null;
        }
        Map<String, Object> function = asStringKeyMap(rawFunction);
        if (!(function.get("name") instanceof String name) || name.isBlank()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "function");
        result.put("name", name);
        if (function.get("description") instanceof String description) {
            result.put("description", description);
        }
        if (function.get("parameters") instanceof Map<?, ?> parameters) {
            result.put("parameters", parameters);
        }
        // strict 显式落值：缺省写 false（见方法注释的两侧默认值差异）。
        result.put("strict", function.get("strict") instanceof Boolean strict ? strict : Boolean.FALSE);
        return result;
    }

    /**
     * {@code tool_choice} 展平：具名形态的 {@code function.name} 提到顶层；
     * 字符串形态原样。指向未声明工具时整个字段丢弃（与 C2M 同判：
     * 下游请求自身的不一致，代理无立场修正）。
     */
    private Object translateToolChoice(Object rawToolChoice, List<Object> translatedTools) {
        if (rawToolChoice == null) {
            return null;
        }
        if (rawToolChoice instanceof String choice) {
            return switch (choice) {
                case "auto", "none", "required" -> choice;
                default -> null;
            };
        }
        if (!(rawToolChoice instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, Object> choice = asStringKeyMap(raw);
        String name = null;
        if (choice.get("function") instanceof Map<?, ?> functionRaw
                && asStringKeyMap(functionRaw).get("name") instanceof String value) {
            name = value;
        } else if (choice.get("name") instanceof String value) {
            name = value;
        }
        if (name == null || name.isBlank() || !declaredNames(translatedTools).contains(name)) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "function");
        result.put("name", name);
        return result;
    }

    private Set<String> declaredNames(List<Object> translatedTools) {
        Set<String> names = new LinkedHashSet<>();
        for (Object item : translatedTools) {
            if (item instanceof Map<?, ?> raw && raw.get("name") instanceof String name) {
                names.add(name);
            }
        }
        return names;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String field) {
        if (source.containsKey(field)) {
            target.put(field, source.get(field));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyMap(Map<?, ?> raw) {
        return (Map<String, Object>) raw;
    }
}
