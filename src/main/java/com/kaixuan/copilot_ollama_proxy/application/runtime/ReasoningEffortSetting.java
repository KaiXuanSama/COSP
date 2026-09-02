package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;

/**
 * 模型的思考深度配置：档位 + 注入模式。
 *
 * <h2>为何需要「模式」这一维度</h2>
 * 思考深度与上下文、工具、视觉不同：后三者只向 Ollama 发现接口声明能力，
 * 而思考深度会<strong>真正进入发往上游的请求体</strong>。一旦如此，
 * 「下游自己带了这个字段怎么办」就成了必须回答的问题，而单个档位值无法表达答案。
 *
 * <p>旧形态是纯档位字符串（如 {@code "Medium"}），其中 {@code "None"} 被借用来表达
 * 「不发送」—— 一个取值同时承担了「档位」与「是否发送」两件事。拆成
 * {@code {effort, mode}} 后，档位在四种模式下都保持有意义（切换模式时无需重新选）。
 *
 * <h2>持久化形态</h2>
 * 存在 {@code provider_model.reasoning_effort} 一列里，V2 形态是 JSON：
 * <pre>{@code {"reasoning_effort":"medium","overwrite_mode":"fallback"}}</pre>
 * 不拆成两列是因为这是一次纯粹的<strong>内容形态</strong>变更 —— 拆列要改 schema、
 * 迁移、行记录与三处读写，而收益只是省掉一次 JSON 解析。
 */
public record ReasoningEffortSetting(String effort, Mode mode) {

    /**
     * 思考深度的注入模式。
     *
     * <p>四者的区别只在「下游带了值时用谁的」与「下游没带时是否补」这两问上：
     * <pre>
     *              下游带了值        下游没带
     * OVERRIDE     用配置的档位      用配置的档位
     * FALLBACK     用下游的值        用配置的档位
     * PASSTHROUGH  用下游的值        不发送
     * DELETE       移除              不发送
     * </pre>
     *
     * <p>{@link #PASSTHROUGH} 与 {@link #DELETE} 的差别只在下游带了值时：前者尊重它，
     * 后者连它一起丢掉。看似接近，但对应两种不同的意图 —— 「我不干预」与
     * 「这个上游不接受这个字段」。后者是必要的：某些上游收到不认识的
     * {@code reasoning_effort} 会直接 400，那时候必须能强制剥离，而不是指望下游不发。
     */
    public enum Mode {
        /** 覆写：无论下游有没有带，都用配置的档位。 */
        OVERRIDE,
        /** 兜底：下游带了就用它的，没带才用配置的档位。这是 V2 之前的默认行为。 */
        FALLBACK,
        /** 透传：下游带了就用它的，没带也不擅自添加。 */
        PASSTHROUGH,
        /** 删除：始终不向上游发送该字段，连下游自带的也一并移除。 */
        DELETE
    }

    /** 上游请求体里思考深度的字段名（OpenAI 协议）。 */
    public static final String REQUEST_FIELD = "reasoning_effort";

    /**
     * 思考开关字段名。
     *
     * <h2>它属于 OpenAI 协议，不是 Anthropic 独有</h2>
     * 这一点容易搞反。实测两家 OpenAI 兼容上游都用它，且与
     * {@link #REQUEST_FIELD} <strong>并列存在</strong>而非二选一：
     * <ul>
     *   <li>DeepSeek {@code /chat/completions} 的官方示例同时给出
     *       {@code "thinking": {"type": "enabled"}} 与 {@code "reasoning_effort": "low"}。</li>
     *   <li>小米 MiMo {@code /v1/chat/completions} 把 {@code thinking.type} 列为必选，
     *       取值 {@code enabled} / {@code disabled}，旗舰模型默认 {@code enabled}。</li>
     * </ul>
     *
     * <p>两个字段是<strong>正交</strong>的：本字段管「开不开思考」，
     * {@link #REQUEST_FIELD} 管「思考多深」。
     *
     * <p>Anthropic 侧也有同名字段但形态不同（带 {@code budget_tokens} 或
     * {@code type: "adaptive"}），那条线路的处理见 {@code GenericAnthropicChatService}。
     */
    public static final String THINKING_FIELD = "thinking";

    /** {@code thinking} 对象里表示开关状态的键名。 */
    public static final String THINKING_TYPE_KEY = "type";

    /** {@code thinking.type} 的关闭值。 */
    public static final String THINKING_DISABLED = "disabled";

    /**
     * 「不思考」档位的界面标识。
     *
     * <h2>它不是一个 {@code reasoning_effort} 取值</h2>
     * OpenAI Chat Completions 协议里 {@code reasoning_effort} <strong>没有</strong>
     * {@code none} 这一档（DeepSeek 只认 {@code low}/{@code high}/{@code max}）。
     * {@code none} 属于 <strong>Responses</strong> 协议，形态是
     * {@code reasoning: {effort: "none"}} —— 不同的协议、不同的字段。
     *
     * <p>所以本档位出站时写的是 {@link #THINKING_FIELD}
     * （{@code {"type": "disabled"}}）而非一个档位值。见 {@link #applyTo}。
     *
     * <h2>它与 {@link Mode#DELETE} 的区别</h2>
     * <ul>
     *   <li>本档位 = <strong>发送</strong> {@code thinking: {"type": "disabled"}}，
     *       明确要求上游不要思考。对「默认开启思考」的模型才有意义 ——
     *       什么都不发它就会自己思考。</li>
     *   <li>{@link Mode#DELETE} = <strong>两个字段都不发</strong>，
     *       上游按自己的默认行为走。用于收到这些字段会 400 的上游。</li>
     * </ul>
     *
     * <p>本类不校验档位取值 —— 用户配了什么就发什么，包括上游可能不认识的档位。
     * 这是刻意的：本服务不做自动降级，「上游认不认」由上游用错误码回答。
     */
    public static final String EFFORT_OFF = "off";

    private static final String DEFAULT_EFFORT = "medium";

    /**
     * 默认模式取 {@link Mode#FALLBACK}。
     *
     * <p>与 V2 之前的行为一致（只在下游未携带时才注入），因此存量模型在升级后行为不变 ——
     * 默认值的职责是保持现状，而非表达推荐做法。
     */
    private static final Mode DEFAULT_MODE = Mode.FALLBACK;

    /** 配置缺失时的兜底：中等档位 + 兜底模式。 */
    public static ReasoningEffortSetting defaults() {
        return new ReasoningEffortSetting(DEFAULT_EFFORT, DEFAULT_MODE);
    }

    public ReasoningEffortSetting {
        effort = effort == null || effort.isBlank()
                ? DEFAULT_EFFORT : effort.trim().toLowerCase(Locale.ROOT);
        mode = mode == null ? DEFAULT_MODE : mode;
    }

    /**
     * 解析持久化的配置字符串，兼容全部历史形态。
     *
     * <p>依次尝试：
     * <ol>
     *   <li><strong>V2 JSON</strong>：{@code {"reasoning_effort":"high","overwrite_mode":"override"}}</li>
     *   <li><strong>旧的纯档位</strong>：{@code "Medium"} → 档位 + 兜底</li>
     *   <li><strong>更旧的逗号分隔多值</strong>：{@code "Medium,High"} → 只取第一项</li>
     *   <li><strong>旧的 {@code "None"}</strong>：映射为 {@link Mode#DELETE}</li>
     * </ol>
     *
     * <p>第 4 条是这个方法存在的主要理由：旧的 {@code None} 表达的是「不向上游发送」，
     * 对应 {@link Mode#DELETE}。若把它当作认不出的档位回退成 {@code medium} + 兜底，
     * 那些模型会突然开始向上游发送 {@code medium} —— 一次纯粹的读取行为改变了运行时行为，
     * 且用户无从察觉。
     *
     * <p>任何解析失败都回退到默认值而不抛异常：这个值来自数据库，
     * 一行脏数据不该让整条聊天链路失败。真正的校验在保存路径上。
     *
     * @param raw          持久化的原始字符串，可为 null
     * @param objectMapper JSON 解析器；为 null 时只处理旧的非 JSON 形态
     */
    public static ReasoningEffortSetting parse(String raw, ObjectMapper objectMapper) {
        if (raw == null || raw.isBlank()) {
            return defaults();
        }
        String trimmed = raw.trim();

        if (trimmed.startsWith("{")) {
            if (objectMapper == null) {
                return defaults();
            }
            try {
                JsonNode parsed = objectMapper.readTree(trimmed);
                return new ReasoningEffortSetting(
                        parsed.path(REQUEST_FIELD).asText(null),
                        parseMode(parsed.path("overwrite_mode").asText(null)));
            } catch (Exception exception) {
                return defaults();
            }
        }

        String first = trimmed.split(",")[0].trim();
        // 只有**旧的裸字符串**形态里的 None 才映射为 DELETE，V2 JSON 走上面那个分支。
        // 新增的 off 档序列化为 {"reasoning_effort":"off",...}，因此不会撞上这条规则 ——
        // 两者语义不同：旧 None 是「不发送任何字段」，off 档是「发送 thinking:disabled
        // 明确要求不思考」。混淆会让一个明确的要求退化成沉默。
        if ("none".equalsIgnoreCase(first)) {
            return new ReasoningEffortSetting(DEFAULT_EFFORT, Mode.DELETE);
        }
        return new ReasoningEffortSetting(first, DEFAULT_MODE);
    }

    /** 认不出的模式名回退为默认模式，而不是让整条配置失效。 */
    private static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MODE;
        }
        for (Mode candidate : Mode.values()) {
            if (candidate.name().equalsIgnoreCase(raw.trim())) {
                return candidate;
            }
        }
        return DEFAULT_MODE;
    }

    /**
     * 序列化为持久化用的 V2 JSON。
     *
     * <p>手工拼接而不用 {@code ObjectMapper}：两个字段都已在构造器里归一化过
     * （档位转小写去空白、模式是枚举），不存在需要转义的内容，
     * 而引入序列化器会让这个纯值对象多一个依赖。
     *
     * <p>保存路径用它把表单提交的任意字符串收敛成规范形态 —— 于是数据库里
     * 只会出现两种形态：本方法产出的 V2 JSON，或升级前遗留的旧字符串。
     */
    public String serialize() {
        return "{\"" + REQUEST_FIELD + "\":\"" + effort
                + "\",\"overwrite_mode\":\"" + mode.name().toLowerCase(Locale.ROOT) + "\"}";
    }

    /**
     * 按当前模式把思考配置写入（或移出、或保留原样）请求体。
     *
     * <h2>为何要同时操作两个字段</h2>
     * {@link #THINKING_FIELD} 与 {@link #REQUEST_FIELD} 在 OpenAI 协议里是<strong>正交</strong>的
     * 两个字段（前者管开关、后者管深度），而界面把它们压成了一个档位选择器 ——
     * {@code off} 档对应「关」、其余档位对应「开 + 该深度」。
     *
     * <p>于是每个模式都必须成对处理，否则会产出自相矛盾的请求体。最典型的错误是
     * 只写 {@code reasoning_effort} 而不清掉下游的 {@code thinking:{"type":"disabled"}}：
     * 上游收到「不要思考」和「思考要多深」两个矛盾指令。
     *
     * <h2>「下游已表态」的判据是两个字段的并集</h2>
     * {@link Mode#FALLBACK} 只在下游<strong>两个字段都没带</strong>时才注入。
     * 下游只发了 {@code thinking:{"type":"disabled"}} 也算表态过 ——
     * 那时再补一个思考深度，等于无视它明确的「别思考」。
     *
     * <p>判据用 {@code containsKey} 而非判空：下游显式传 {@code null} 也算表达过意见；
     * 那个 null 随后由调用方的 {@code removeIf(Objects::isNull)} 清掉，等效于不发送。
     *
     * <p>原地修改传入的 Map 而非返回新 Map：调用方
     * （{@code AbstractUpstreamChatService.prepareRequestBody}）已经持有一份可变副本，
     * 再造一个只会让「哪一份才是最终请求体」变得不明确。
     */
    public void applyTo(Map<String, Object> body) {
        switch (mode) {
            case OVERRIDE -> {
                // 覆写：下游说什么都不算，两个字段都按配置重建。
                // 先清干净再写，避免遗留一个与新配置矛盾的字段。
                body.remove(REQUEST_FIELD);
                body.remove(THINKING_FIELD);
                writeConfiguredThinking(body);
            }
            case FALLBACK -> {
                if (!downstreamHasOpinion(body)) {
                    writeConfiguredThinking(body);
                }
            }
            case PASSTHROUGH -> {
                // 下游带什么就是什么，没带也不补。显式写出这个空分支而不让它落到
                // default，是为了让四种模式一一对应，将来新增模式时不会被静默吞掉。
            }
            case DELETE -> {
                // 检测到哪个删哪个 —— 用于收到任一字段就 400 的上游。
                body.remove(REQUEST_FIELD);
                body.remove(THINKING_FIELD);
            }
        }
    }

    /**
     * 下游是否已就「要不要思考 / 思考多深」表达过意见。
     *
     * <p>两个字段任一存在即算表态。少看一个会让兜底档在下游明确要求关闭思考时
     * 仍去补一个深度值。
     */
    private static boolean downstreamHasOpinion(Map<String, Object> body) {
        return body.containsKey(REQUEST_FIELD) || body.containsKey(THINKING_FIELD);
    }

    /**
     * 把配置的档位写成上游能懂的形态。
     *
     * <p>{@code off} 档写 {@code thinking:{"type":"disabled"}} —— 那是这个协议里
     * 表达「不要思考」的方式；{@code reasoning_effort} 压根没有 {@code none} 这一档
     * （见 {@link #EFFORT_OFF}）。
     *
     * <p>其余档位只写 {@code reasoning_effort}，<strong>不</strong>额外补一个
     * {@code thinking:{"type":"enabled"}}：那两个字段虽可共存，但只发深度已足够表达意图，
     * 而多发一个字段会让只认识其中一个的上游收到意外内容。本服务的原则是发出用户
     * 配置的东西，不替他添。
     */
    private void writeConfiguredThinking(Map<String, Object> body) {
        if (EFFORT_OFF.equals(effort)) {
            body.put(THINKING_FIELD, Map.of(THINKING_TYPE_KEY, THINKING_DISABLED));
            return;
        }
        body.put(REQUEST_FIELD, effort);
    }
}
