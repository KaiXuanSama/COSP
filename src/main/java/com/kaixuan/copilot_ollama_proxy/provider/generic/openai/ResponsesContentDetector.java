package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 判定 OpenAI <strong>Responses</strong> 响应是否带实质载荷。
 *
 * <h2>与另两侧共享的是「类别定义」，不是取值路径</h2>
 * 三类实质载荷的划分与 {@code OpenAiContentDetector} / {@code AnthropicContentDetector}
 * 完全一致 —— 正文、思考链、工具调用。这个划分是<strong>策略</strong>，三侧必须同口径，
 * 否则会出现「切一下协议，同一个上游故障的结论就不同」。
 *
 * <p>但取值路径与两者都不同，故独立实现：
 * <ul>
 *   <li>Chat 的载荷在 {@code choices[].delta} 的同一个对象里按字段名找；</li>
 *   <li>Anthropic 在 {@code content[]} 数组里靠 {@code type} 区分；</li>
 *   <li>Responses 在 {@code output[]} 数组里靠 {@code type} 区分，但<strong>层级更深</strong>
 *       —— 正文在 {@code output[].content[]}、思考链在 {@code output[].content[]} 或
 *       {@code output[].summary[]}，工具调用则是 {@code output[]} 的<strong>兄弟项</strong>
 *       （{@code type=function_call}）而不是嵌在消息里。</li>
 * </ul>
 *
 * <h2>官方的「多路径」必须全部实现，只取一支会静默退化</h2>
 * 本类初版曾在两处只实现了官方定义的其中一支，两处都是 2026-09-12 对 MiMo 与 DeepSeek
 * 真实调用时才暴露（判定为空响应 → 按预算重发 5 次，一次正常回复变成 6 次上游调用、
 * 6 条日志、约 60 秒等待）。官方原文档的两处措辞是根因：
 * <ul>
 *   <li>{@code ResponseOutputMessage.content} 是
 *       {@code array of ResponseOutputText <b>or</b> ResponseOutputRefusal} ——
 *       <strong>联合类型</strong>。拒绝回答时元素只有 {@code refusal} 字段，没有 {@code text}。</li>
 *   <li>{@code Reasoning} 同时有 {@code summary}（摘要）与 {@code content}（思考正文，
 *       标为 optional）。当时的推断是「{@code summary} 无 optional 前缀、{@code content} 有，
 *       所以 content 是次要路径」—— <strong>这个推断是错的</strong>：
 *       {@code optional} 的含义是「可能不存在」，不是「少有人用」。
 *       实测 MiMo 与 DeepSeek <strong>两家都只走 content、summary 为空</strong>。</li>
 * </ul>
 * 写法上的判据：<strong>看到官方文档写 {@code A or B}，就必须两条都实现。</strong>
 * 这与本仓库另一条已记录的判据同源（协议集合的断言要问「对全集成立还是对某个集合成立」）——
 * 那次是集合只取一支，这次是字段只取一支，症状相同：加/遇到另一支时静默退化。
 *
 * <h2>内容可以出现在四个位置，都要认</h2>
 * 事件里的实质内容可能摊在顶层（{@code *.delta} 的 {@code delta}、{@code *.done} 的
 * {@code text} / {@code refusal}）、包在 {@code part} 里（{@code content_part.*}）、
 * 包在 {@code item} 里（{@code output_item.*}），或整批放在 {@code response.output[]}
 * （终态事件）。只认其中一两种的后果同上一节 —— 存在这样的上游：
 * 一个 delta 都不发，只在 {@code *.done} 或终态事件里给出全文。
 *
 * <h2>不用 {@code output_text} 便捷字段</h2>
 * 官方对该字段的原文是「<strong>SDK-only convenience property</strong> ... Supported in
 * the Python and JavaScript SDKs」—— 它不是服务端承诺返回的字段。实测 DeepSeek 返回
 * {@code output_text: ""} 而 message 项里的正文完好；若依赖它，那份完全正常的响应
 * 会被判成空并重试 5 次。官方定义见文档 {@code Response} 的 {@code output_text}
 * 条目，本服务因此只遍历 {@code output[]}。
 *
 * <h2>流式的判定单位是一整轮，不是单个事件</h2>
 * 与 Anthropic 侧同构：{@code response.created}、{@code response.output_item.added}
 * 这些事件本身不含内容（前者只有元信息、后者只声明 item 类型），内容分散在
 * {@code *.delta} 事件里。因此不能逐事件判「有没有内容」——
 * 那会把正常响应的头几个事件判成空。{@link #eventHasPayload} 的语义是
 * 「这个事件<em>贡献了</em>实质载荷吗」，由调用方在一轮内做逻辑或。
 *
 * <h2>解析失败一律保守放行</h2>
 * 与另两侧同一取向：宁可放行一个没见过的格式，也不要因为结构陌生就把正常响应
 * 判成空并重试。
 */
public final class ResponsesContentDetector {

    /** {@code output[]} 里承载助手正文的 item 类型。 */
    private static final String ITEM_MESSAGE = "message";
    /** {@code output[]} 里承载思考链的 item 类型。 */
    private static final String ITEM_REASONING = "reasoning";
    /** {@code output[]} 里承载工具调用的两种 item 类型。 */
    private static final String ITEM_FUNCTION_CALL = "function_call";
    private static final String ITEM_CUSTOM_TOOL_CALL = "custom_tool_call";

    /**
     * 定稿文本的字段名 —— 内容载体里承载完整文本的两个键。
     *
     * <p>两个都要认，因为官方把「正文」与「拒绝理由」分成了两种元素类型：
     * {@code ResponseOutputText} 用 {@code text}，
     * {@code ResponseOutputRefusal object { refusal, type }} 用 {@code refusal}。
     * 只认 {@code text} 时，一个「只含拒绝理由的 message」会被判成空响应
     * 并重试 5 次 —— 而模型拒绝回答是正常行为，不是上游故障。
     */
    private static final String[] FINALIZED_TEXT_FIELDS = {"text", "refusal"};

    private ResponsesContentDetector() {
    }

    /**
     * 判断<strong>非流式</strong>完整响应体是否带实质载荷。
     *
     * <p>Responses 的非流式响应把内容放在顶层 {@code output} 数组里。
     *
     * @param fullBody 上游完整响应体；null / 空白视为空响应
     * @return 含正文 / 思考链 / 工具调用之一返回 true；解析失败也返回 true（保守放行）
     */
    public static boolean hasMeaningfulPayload(ObjectMapper objectMapper, String fullBody) {
        if (fullBody == null || fullBody.isBlank()) {
            return false;
        }
        try {
            JsonNode output = objectMapper.readTree(fullBody).get("output");
            if (output == null || !output.isArray()) {
                // output 缺失或不是数组：整个响应没有可用的内容容器，判空。
                return false;
            }
            for (JsonNode item : output) {
                if (outputItemHasPayload(item)) {
                    return true;
                }
            }
            return false;
        } catch (Exception exception) {
            // 结构未知：保守认为有内容。
            return true;
        }
    }

    /**
     * 判断<strong>单个流式事件</strong>是否贡献了实质载荷。
     *
     * <p>返回 false 不代表这一轮是空响应 —— {@code response.created} 等控制事件本就不含内容。
     * 调用方需在一轮内对所有事件做逻辑或，整轮都为 false 才判空。
     *
     * <h2>内容可能出现在四个位置，逐个检查</h2>
     * <ol>
     *   <li><strong>顶层</strong> —— {@code *.delta} 的 {@code delta} 是增量文本；
     *       {@code output_text.done} / {@code reasoning_text.done} 把<strong>全文</strong>
     *       摊在顶层 {@code text}；{@code refusal.done} 摊在 {@code refusal}。</li>
     *   <li><strong>{@code part} 里</strong> —— {@code content_part.added} / {@code .done}
     *       把内容 part 包在这一层。</li>
     *   <li><strong>{@code item} 里</strong> —— {@code output_item.added} / {@code .done}
     *       携带完整 item，交给 {@link #outputItemHasPayload} 按 item 类型分派。</li>
     *   <li><strong>{@code response.output[]} 里</strong> —— 终态事件携带整份结果。</li>
     * </ol>
     *
     * <h2>为何 {@code *.done} 也要认</h2>
     * 存在这样的上游：一个 delta 都不发，只在定稿事件或终态事件里给出全文
     * （调研中 new-api 为此专门写了 {@code terminalOutputChunks} 来补发）。
     * 只认 {@code .delta} 后缀时，那种响应会被判成空并卷入重试 —— 而它其实是完整的。
     * 因此判据按<strong>字段名</strong>而非事件名：字段名在各类事件里含义稳定，
     * 而事件名会随协议演进增加（官方已有 62 个）。
     *
     * @param eventData SSE data 字段的原始内容
     * @return 该事件带来了正文 / 思考链 / 工具调用之一返回 true；解析失败返回 true（保守放行）
     */
    public static boolean eventHasPayload(ObjectMapper objectMapper, String eventData) {
        if (eventData == null || eventData.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(eventData);
            String type = text(root, "type");
            if (type == null) {
                return false;
            }
            // 位置一：内容直接摊在事件顶层。三类载荷的判据相同（内容字段非空），
            // 故不按事件名分派 —— 增量事件给 delta、定稿事件给 text/refusal。
            if (carrierHasContent(root)) {
                return true;
            }
            // 位置二：content_part.added / .done 把内容 part 包在 part 里。
            if (carrierHasContent(root.get("part"))) {
                return true;
            }
            // 位置三：output_item.added / .done 携带完整 item。工具调用在此处
            // name 已确定，算实质载荷 —— 与另两侧「tool_calls 含非空元素即算」同口径。
            if ("response.output_item.added".equals(type)
                    || "response.output_item.done".equals(type)) {
                return outputItemHasPayload(root.get("item"));
            }
            // 位置四：终态事件 —— response 里带完整 output[]，见方法注释。
            if (isTerminalEvent(type)) {
                JsonNode response = root.get("response");
                if (response == null || !response.isObject()) {
                    return false;
                }
                JsonNode output = response.get("output");
                if (output == null || !output.isArray()) {
                    return false;
                }
                for (JsonNode item : output) {
                    if (outputItemHasPayload(item)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception exception) {
            return true;
        }
    }

    /**
     * 一个内容载体是否带实质内容（增量或定稿）。
     *
     * <p>载体可以是事件对象本身、{@code part} 对象，或 {@code content[]} / {@code summary[]}
     * 里的元素 —— 它们在「内容存在哪个字段」这件事上结构相同，故共用一份判定。
     *
     * <p>不校验载体的 {@code type}（{@code output_text} / {@code summary_text} /
     * {@code reasoning_text} / {@code refusal} 等）：各家兼容端点的 type 取值有出入，
     * 而「有非空文本」本身已经证明有内容。多认一种形态的风险远小于把正常响应判成空。
     */
    private static boolean carrierHasContent(JsonNode carrier) {
        if (carrier == null || !carrier.isObject()) {
            return false;
        }
        return hasDeltaContent(carrier) || hasFinalizedText(carrier);
    }

    /**
     * 载体是否带非空的 {@code delta}（增量形态）。
     *
     * <p>{@code delta} 必须非空：一个空字符串的 delta 不贡献任何内容，
     * 而某些上游会在流首发一个空 delta 做预热。
     *
     * <p>这里用「非空」而非「非空白」：单个空格对 Markdown 是有意义的，
     * 而 delta 只是整体的一小片，不能按「这一片单独看像不像内容」来判 ——
     * 与 {@code OpenAiContentDetector} 里那条同口径的注释一致。
     */
    private static boolean hasDeltaContent(JsonNode carrier) {
        JsonNode delta = carrier.get("delta");
        if (delta == null) {
            return false;
        }
        if (delta.isTextual()) {
            return !delta.asText().isEmpty();
        }
        // 少数上游把 delta 建成对象（如 {"text": "..."}），非空对象即算贡献。
        return delta.isObject() && !delta.isEmpty();
    }

    /** 载体是否带非空的定稿文本（{@link #FINALIZED_TEXT_FIELDS} 任一）。 */
    private static boolean hasFinalizedText(JsonNode carrier) {
        if (carrier == null || !carrier.isObject()) {
            return false;
        }
        for (String field : FINALIZED_TEXT_FIELDS) {
            if (isNonBlank(text(carrier, field))) {
                return true;
            }
        }
        return false;
    }

    /** 是否是流的终态事件。取值与 {@code ResponsesStreamEvents} 的清单同源。 */
    private static boolean isTerminalEvent(String type) {
        return ResponsesStreamEvents.isTerminal(type);
    }

    /**
     * 判断一个 {@code output[]} 元素是否带实质载荷。
     *
     * <p>非流式的 {@code output[]} 元素与流式 {@code output_item.added} 的 {@code item}
     * 是同一种结构，故共用此方法。
     */
    private static boolean outputItemHasPayload(JsonNode item) {
        if (item == null || !item.isObject()) {
            return false;
        }
        String type = text(item, "type");
        if (type == null) {
            return false;
        }
        return switch (type) {
            // 正文：content[] 里的元素带非空文本。
            //
            // 官方 content 是联合类型 ——
            // `array of ResponseOutputText or ResponseOutputRefusal`，
            // 拒绝回答时元素是 `{type:"refusal", refusal:"..."}`，<strong>没有 text 字段</strong>。
            // 只认 text 时，一个「只含拒绝理由的 message」会被判成空响应并重试 5 次，
            // 而模型拒绝回答是正常行为。两个字段由 carrierHasContent 一并覆盖。
            case ITEM_MESSAGE -> arrayHasContent(item.get("content"));
            // 思考链：官方有两条路径，都要认。
            //   summary[] —— 思考摘要（SummaryTextContent，官方无 optional 前缀）
            //   content[] —— 思考正文（ReasoningText，官方标为 optional）
            //
            // optional 的含义是「可能不存在」，不是「少有人用」。2026-09-12 实测
            // MiMo 与 DeepSeek **两家都只走 content、summary 为空**，只认 summary
            // 会让「思考是唯一内容」的响应被判空（思考吃完全部预算、正文未生成）。
            //
            // 不看 encrypted_content：那是不透明令牌，它单独存在（远程压缩后的常见形态）
            // 时并不构成用户可见的思考内容，算作实质载荷会让一个「只有加密块、
            // 没有任何输出」的响应被判成正常。
            case ITEM_REASONING -> arrayHasContent(item.get("summary"))
                    || arrayHasContent(item.get("content"));
            // 工具调用：name 或 arguments 任一非空即算。
            //
            // arguments 也算是必要的：存在只发 arguments 增量而 item 声明里 name 为空的
            // 上游（调研中 new-api 为此写了 pendingArgs 兜底），只认 name 会漏判。
            case ITEM_FUNCTION_CALL, ITEM_CUSTOM_TOOL_CALL ->
                    isNonBlank(text(item, "name")) || isNonBlank(text(item, "arguments"));
            // 其余 item 类型（web_search_call、file_search_call 等）是工具执行的痕迹，
            // 其结果已并入文本输出，本身不算独立载荷 —— 与 sub2api 的处理一致。
            default -> false;
        };
    }

    /**
     * 数组里是否有元素带实质内容。
     *
     * <p>用于 {@code content[]} / {@code summary[]} 这类内容数组。判定委托给
     * {@link #carrierHasContent}，因此正文与拒绝理由、摘要与思考正文
     * 都走同一份口径，不会出现「某一支又被漏掉」。
     */
    private static boolean arrayHasContent(JsonNode array) {
        if (array == null || !array.isArray()) {
            return false;
        }
        for (JsonNode element : array) {
            if (carrierHasContent(element)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
