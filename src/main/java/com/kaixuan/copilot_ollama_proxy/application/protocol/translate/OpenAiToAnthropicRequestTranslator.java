package com.kaixuan.copilot_ollama_proxy.application.protocol.translate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslator;
import com.kaixuan.copilot_ollama_proxy.application.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslatedRequest;
import com.kaixuan.copilot_ollama_proxy.application.protocol.TranslationContext;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI Chat Completions → Anthropic Messages 的<strong>请求体</strong>翻译器。
 *
 * <h2>在链条中的位置</h2>
 * 本翻译器是下游请求进来后的<strong>第一层</strong>，在设置层之前：
 * <pre>
 * 下游 OpenAI 请求
 *   → ★ 本类（纯字段翻译）
 *   → 协议归一化（system 提取 / max_tokens 别名）
 *   → 设置层（思考深度、最大输出的注入模式）
 *   → 请求体与请求头规则
 *   → 以 Anthropic 协议发出
 * </pre>
 * 这个顺序让两条路（直连与翻译）走到设置层时形态一致，四档语义因此
 * <strong>不需要为翻译单开一套</strong>。
 *
 * <h2>本类不做什么</h2>
 * <ul>
 *   <li><strong>不推导下游没说的值</strong>。OpenAI 请求里不可能出现
 *       {@code thinking: {"type":"adaptive"}}，翻译后该字段就是缺失的，
 *       由兜底档决定补什么。</li>
 *   <li><strong>不提取 system 到顶层</strong>。那是
 *       {@code GenericAnthropicChatService.extractSystemPrompt} 的职责，
 *       在本类之后执行。重复实现两份迟早分叉。</li>
 *   <li><strong>不补 max_tokens 默认值</strong>。{@code MaxOutputTokensSetting}
 *       已经管这件事，且它的两档语义在翻译路线上同样适用。</li>
 *   <li><strong>不做自动降级</strong>。{@code Minimal} 在 Anthropic 侧无对应档，
 *       原样发出让上游用错误码回答。理由见契约第 4.5 节。</li>
 * </ul>
 *
 * <h2>最容易踩的坑：必须无损搬运「下游已表态」</h2>
 * 设置层 {@code fallback} 的判据是在<strong>翻译后</strong>的形态上算的。
 * 若翻译丢掉了 {@code reasoning_effort}，一个明确要求 {@code low} 的请求
 * 到设置层会被判成「没表态」，兜底档就把配置值补上去 ——
 * <strong>{@code fallback} 静默退化成 {@code override}</strong>。
 * 现象只在下游带了字段时出现，很难被发现。契约第 2 节，已有单测钉住。
 *
 * @see <a href="file:../../../../../../../../../docs/PROTOCOL_TRANSLATION_CONTRACT.md">
 *      协议翻译契约</a>
 */
@Component
public class OpenAiToAnthropicRequestTranslator implements ProtocolTranslator {

    /**
     * 直接搬运的顶层标量字段。
     *
     * <h2>为何 temperature / top_p 不缩放</h2>
     * 两侧值域相同。调研的三个参考项目也都没有做任何缩放。
     *
     * <h2>为何不因思考已开启而清空它们</h2>
     * 参考项目里 new-api 会在开思考时清掉采样参数，且在「没要求思考」的分支
     * 也生效 —— 用户只是换了个模型名，采样参数就凭空消失。这是明确的反面案例，
     * 本服务的原则是尊重用户配置。
     */
    private static final Set<String> PASSTHROUGH_SCALARS = Set.of(
            "temperature", "top_p", "top_k");

    /**
     * 思考相关字段，必须无损搬运（见类注释）。
     *
     * <p>{@code thinking} 在两侧都存在且都表达开关，形态也兼容
     * （{@code {"type":"disabled"}}），因此可以直接搬。
     * {@code reasoning_effort} 则要换字段名，见 {@link #translateThinking}。
     */
    private static final String FIELD_REASONING_EFFORT = "reasoning_effort";
    private static final String FIELD_THINKING = "thinking";
    private static final String FIELD_OUTPUT_CONFIG = "output_config";

    private final MessageTranslator messageTranslator;
    private final ToolTranslator toolTranslator;
    private final ToolPairingNormalizer toolPairingNormalizer;

    public OpenAiToAnthropicRequestTranslator(ObjectMapper objectMapper) {
        ContentBlockTranslator contentBlockTranslator = new ContentBlockTranslator();
        this.messageTranslator = new MessageTranslator(objectMapper, contentBlockTranslator);
        this.toolTranslator = new ToolTranslator();
        this.toolPairingNormalizer = new ToolPairingNormalizer();
    }

    @Override
    public WireProtocol downstreamProtocol() {
        return WireProtocol.OPENAI;
    }

    @Override
    public WireProtocol upstreamProtocol() {
        return WireProtocol.ANTHROPIC;
    }

    /**
     * 翻译请求体。
     *
     * @param downstreamBody 下游的 OpenAI 请求体，不会被修改
     * @return 翻译产物，含供响应侧使用的上下文
     * @throws RequestTranslationException 请求内容无法表达成 Anthropic 协议
     */
    public TranslatedRequest translateRequest(Map<String, Object> downstreamBody) {
        Map<String, Object> source = downstreamBody == null ? Map.of() : downstreamBody;
        Map<String, Object> target = new LinkedHashMap<>();

        // model / stream 原样。路由已在上游完成，这里不改写模型名。
        copyIfPresent(source, target, "model");
        copyIfPresent(source, target, "stream");

        // max_tokens 只搬运，不补默认值 —— 别名归一化与两档注入都在设置层。
        copyIfPresent(source, target, "max_tokens");
        copyIfPresent(source, target, "max_completion_tokens");

        for (String field : PASSTHROUGH_SCALARS) {
            copyIfPresent(source, target, field);
        }

        translateStop(source, target);
        translateThinking(source, target);
        translateMessages(source, target);
        translateTools(source, target);

        // 其余字段一律丢弃（无对应物）。必须显式丢而非留着：链条末尾只有
        // removeIf(Objects::isNull)，非 null 的多余字段会原样发给上游然后 400。
        // 完整清单见契约第 5.1 节，这里靠白名单实现 —— 没被上面搬过来的就没了。

        return new TranslatedRequest(target, TranslationContext.fromDownstreamBody(source));
    }

    /**
     * {@code stop} → {@code stop_sequences}。
     *
     * <p>字符串包成单元素数组；数组要求每个元素都是字符串。
     * 非字符串元素<strong>报错</strong>而非跳过：参考项目里用裸类型断言，
     * {@code {"stop":[1,2]}} 会 panic 而不是 400。跳过也不对 ——
     * 那会静默改变停止条件。
     */
    private void translateStop(Map<String, Object> source, Map<String, Object> target) {
        Object stop = source.get("stop");
        if (stop == null) {
            return;
        }
        if (stop instanceof String single) {
            if (!single.isEmpty()) {
                target.put("stop_sequences", List.of(single));
            }
            return;
        }
        if (!(stop instanceof List<?> values)) {
            throw new RequestTranslationException("stop", "既不是字符串也不是数组");
        }
        List<String> sequences = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (!(value instanceof String text)) {
                throw new RequestTranslationException("stop[" + i + "]",
                        "不是字符串，无法翻译成 stop_sequences");
            }
            if (!text.isEmpty()) {
                sequences.add(text);
            }
        }
        if (!sequences.isEmpty()) {
            target.put("stop_sequences", sequences);
        }
    }

    /**
     * 思考字段的无损搬运。
     *
     * <table>
     *   <caption>映射</caption>
     *   <tr><th>下游 OpenAI</th><th>Anthropic</th></tr>
     *   <tr><td>{@code thinking:{"type":"disabled"}}</td><td>原样</td></tr>
     *   <tr><td>{@code reasoning_effort: <档位>}</td><td>{@code output_config.effort}</td></tr>
     *   <tr><td>两者都缺失</td><td>两者都缺失，交给设置层</td></tr>
     * </table>
     *
     * <h2>为何 reasoning_effort 要换成 output_config.effort</h2>
     * Anthropic 的深度字段是顶层的 {@code output_config.effort}，
     * 而 {@code reasoning_effort} 是 OpenAI 的名字。名字不换上游认不出来，
     * 等于把「下游表过态」这个信息丢了。
     *
     * <h2>为何同时保留 reasoning_effort</h2>
     * 这是最微妙的一点：设置层 {@code ReasoningEffortSetting} 判定「下游已表态」
     * 时看的是 {@code reasoning_effort} 与 {@code thinking} 两个字段
     * （它是为 OpenAI 侧写的）。若翻译把 {@code reasoning_effort} 改名后删掉，
     * 设置层就看不到表态、兜底档会再注入一次。
     *
     * <p>留着的那份由设置层之后的 {@code body.remove("reasoning_effort")}
     * 负责清掉 —— 那行在 Anthropic 服务里本来就存在，正好承担这个收尾。
     * 注意这与契约第 2.1 节说的「翻译落地后要删掉那行」并不矛盾：
     * 要删的是它<strong>在设置层之前</strong>执行这件事，收尾清理仍需保留。
     */
    private void translateThinking(Map<String, Object> source, Map<String, Object> target) {
        // thinking 两侧形态兼容，直接搬。
        copyIfPresent(source, target, FIELD_THINKING);

        Object effort = source.get(FIELD_REASONING_EFFORT);
        if (effort == null) {
            return;
        }
        // 换名到 Anthropic 的深度字段。
        Map<String, Object> outputConfig = target.get(FIELD_OUTPUT_CONFIG) instanceof Map<?, ?> existing
                ? new LinkedHashMap<>(asStringKeyMap(existing))
                : new LinkedHashMap<>();
        outputConfig.put("effort", effort);
        target.put(FIELD_OUTPUT_CONFIG, outputConfig);

        // 同时保留原字段供设置层判定「下游已表态」，见方法注释。
        target.put(FIELD_REASONING_EFFORT, effort);
    }

    /**
     * 消息数组：逐条翻译后修复配对。
     */
    private void translateMessages(Map<String, Object> source, Map<String, Object> target) {
        if (!source.containsKey("messages")) {
            return;
        }
        List<Object> translated = messageTranslator.translate(source.get("messages"));
        if (translated == null) {
            // 非数组形态原样交给上游报错。
            target.put("messages", source.get("messages"));
            return;
        }
        target.put("messages", toolPairingNormalizer.normalize(translated));
    }

    /**
     * 工具定义与选择。
     *
     * <p>{@code tool_choice} 依赖已翻译的工具列表做具名校验，因此必须在
     * {@code tools} 之后处理。
     */
    private void translateTools(Map<String, Object> source, Map<String, Object> target) {
        List<Object> tools = toolTranslator.translateTools(source.get("tools"));
        if (!tools.isEmpty()) {
            target.put("tools", tools);
        }
        Object toolChoice = toolTranslator.translateToolChoice(source.get("tool_choice"), tools);
        // 只有声明了工具时才发 tool_choice —— 没有工具的选择策略是无意义的，
        // 部分上游还会因此报错。
        if (toolChoice != null && !tools.isEmpty()) {
            target.put("tool_choice", toolChoice);
        }
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
