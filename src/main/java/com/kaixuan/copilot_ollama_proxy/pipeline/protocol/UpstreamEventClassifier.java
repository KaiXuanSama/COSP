package com.kaixuan.copilot_ollama_proxy.pipeline.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.pipeline.after.send.responses.ResponsesStreamEvents;

/**
 * 把一帧原始报文的字符串<strong>归类</strong>为载荷或终止标记。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：主干 · 位置：{@code pipeline/protocol/}（层根）
 * 步骤「回程帧处理」—— 把一帧分成「载荷」与「终止标记」两态
 * <p>完整步骤树见 {@code pipeline/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 * <h2>为什么需要它</h2>
 * 「这一帧是不是说完了」此前是<strong>三处字符串模式匹配</strong>，且散在控制器里：
 * <ul>
 *   <li>{@code OpenAiController}：{@code "[DONE]".equals(chunk)}</li>
 *   <li>{@code AnthropicController}：{@code EVENT_MESSAGE_STOP.equals(extractEventType(event))}</li>
 *   <li>{@code ResponsesController}：{@code ResponsesStreamEvents.isTerminal(type)}</li>
 * </ul>
 * 三处都是「从报文里认出一个魔数」，而认出它的前提是<strong>知道这是哪个协议</strong>。
 * 控制器恰好知道（它服务哪个端点），但翻译路线下它看到的帧<strong>未必是它自己的协议</strong> ——
 * 这正是「谁生产帧，谁分类」的理由。
 *
 * <h2>按协议分派，不按内容猜</h2>
 * 调用方必须给出<strong>它正在生产哪种协议的帧</strong>：
 * <ul>
 *   <li>上游执行器给出<strong>上游协议</strong>（它发的就是上游的帧）；</li>
 *   <li>C2M 的响应翻译器给出<strong>下游协议</strong>（它发的是翻译后的 Chat 帧）。</li>
 * </ul>
 * 这样分类规则不会与帧内容打架 —— 一帧 Anthropic 事件不会被拿去比对 {@code [DONE]}。
 *
 * <h2>为什么不是各协议各写一份</h2>
 * 与内容判定器（{@code OpenAiContentDetector} 等）不同：那些的<strong>取值路径</strong>
 * 因协议而异，必须各自实现；而这里每个协议只有一条<strong>一行就能写完</strong>的规则，
 * 拆成三个类只会让「共有三种协议」这个事实消失在三份样板里。
 *
 * <p>它同时收掉了控制器里重复的 {@code extractEventType}（Anthropic 与 Responses 各一份）。
 */
public final class UpstreamEventClassifier {

    /** Chat 协议的流结束标记。它不承载内容，只是一个「说完了」的信号。 */
    private static final String CHAT_TERMINAL = "[DONE]";

    /** Anthropic 协议的流结束事件类型。等价于 Chat 的 {@code [DONE]}。 */
    private static final String MESSAGES_TERMINAL_TYPE = "message_stop";

    private UpstreamEventClassifier() {
    }

    /**
     * 按协议判定一帧是不是终止标记。
     *
     * @param objectMapper 解析事件类型用；{@link WireProtocol#CHAT} 不需要它，可传 null
     * @param protocol     这一帧所属的协议
     * @param data         原始报文
     * @return 终止标记用 {@link UpstreamEvent.Terminal}，其余一律 {@link UpstreamEvent.Body}
     */
    public static UpstreamEvent classify(ObjectMapper objectMapper, WireProtocol protocol, String data) {
        return isTerminal(objectMapper, protocol, data)
                ? UpstreamEvent.terminal(data)
                : UpstreamEvent.body(data);
    }

    /**
     * 判定一帧是否为终止标记。
     *
     * <p><strong>三个协议的终止形态互不相同</strong>，且都不可省略：
     * <ul>
     *   <li>{@code CHAT} —— 独立的 {@code [DONE]} 帧，与内容帧不同形；</li>
     *   <li>{@code MESSAGES} —— {@code message_stop} 事件，靠 {@code type} 字段区分；</li>
     *   <li>{@code RESPONSES} —— <strong>一组</strong>终态事件
     *       （{@code response.completed} / {@code .failed} / {@code .incomplete} / {@code .canceled}），
     *       不是单个标记。清单与空响应判定共用
     *       {@link ResponsesStreamEvents#isTerminal}，避免两处口径漂移。</li>
     * </ul>
     *
     * <p>解析失败一律判<strong>否</strong>：认不出的帧按载荷处理更安全 ——
     * 误判成终止会让下游提前收尾（丢掉后面的内容），误判成载荷最多是多下一帧。
     */
    public static boolean isTerminal(ObjectMapper objectMapper, WireProtocol protocol, String data) {
        if (data == null) {
            return false;
        }
        return switch (protocol) {
            case CHAT -> CHAT_TERMINAL.equals(data);
            case MESSAGES -> MESSAGES_TERMINAL_TYPE.equals(extractEventType(objectMapper, data));
            case RESPONSES -> ResponsesStreamEvents.isTerminal(extractEventType(objectMapper, data));
        };
    }

    /**
     * 从事件 JSON 取出 {@code type}。
     *
     * <p>收归自两个控制器里逐字相同的私有方法 —— 那次重复本身无害，
     * 但既然本类已经要按协议分派，提取类型就不再是「某个控制器的事」。
     *
     * @return type 字段的文本值；缺失、非文本或解析失败时返回 null
     */
    public static String extractEventType(ObjectMapper objectMapper, String event) {
        if (objectMapper == null || event == null) {
            return null;
        }
        try {
            var node = objectMapper.readTree(event);
            var type = node.get("type");
            return type != null && type.isTextual() ? type.asText() : null;
        } catch (Exception exception) {
            return null;
        }
    }
}
