package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
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
     * Anthropic Messages 的深度字段容器（顶层）。
     *
     * <h2>为何 Anthropic 侧的深度不是 {@link #REQUEST_FIELD}</h2>
     * {@code reasoning_effort} 是 OpenAI 的名字，Anthropic 认的是顶层
     * {@code output_config.effort}（4.6+ 引入，五档 low/medium/high/xhigh/max）。
     * 名字不换上游认不出来，等于这一维没生效。
     *
     * <h2>为何不用 thinking.budget_tokens 表达深度</h2>
     * 那是 Anthropic 4.6 之前唯一能表达「想多深」的位置，但把档位换算成
     * token 预算需要一张查表，而三个参考项目的表互不相同、反向阈值也互不相同 ——
     * 请求侧契约第 4.4 节据此决定<strong>不做</strong>这个换算。既然换算被排除，
     * {@code output_config.effort} 就是这条线路上唯一自洽的落点。
     *
     * <p>代价是 4.5 及更早的模型不认识这个字段，会以错误码回答。这与
     * 「不做自动降级、不按模型名猜能力」一致：本服务发出用户配置的东西。
     */
    public static final String OUTPUT_CONFIG_FIELD = "output_config";

    /** {@link #OUTPUT_CONFIG_FIELD} 里承载档位的键名。 */
    public static final String OUTPUT_CONFIG_EFFORT_KEY = "effort";

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

    // ==================== Anthropic Messages 线路 ====================

    /**
     * 按当前模式把思考深度写入 <strong>Anthropic Messages</strong> 请求体。
     *
     * <h2>与 {@link #applyTo} 的关系：同一份配置，两种线格式</h2>
     * 持久化的 {@code {effort, mode}} 只有一份，两条线路的<strong>字段名与形态不同</strong>：
     * <pre>
     * OpenAI     深度 → reasoning_effort: "high"
     * Anthropic  深度 → output_config: {"effort": "high"}
     * </pre>
     * 模式语义（覆写 / 兜底 / 透传 / 删除）两侧完全一致，因此这里只重写映射、不重定义语义。
     *
     * <h2>与思考方式（{@link AnthropicThinkingSetting}）的分工</h2>
     * 这是本方法与 {@link #applyTo} 最大的行为差异。OpenAI 侧界面把「开关」与「深度」
     * 压成了一个档位选择器，所以那边必须成对操作 {@code thinking} 与
     * {@code reasoning_effort}。Anthropic 侧不是：思考<strong>方式</strong>
     * （adaptive / enabled+budget）是另一个独立控件、有自己的注入模式。
     *
     * <p>因此本方法<strong>只拥有</strong> {@code output_config.effort} 这一个字段，
     * 不越权改写 {@code thinking} —— 只有两个例外，两者都源于「五档里没有不思考」：
     * <ol>
     *   <li>{@code off} 档必须写 {@code thinking:{"type":"disabled"}}，
     *       因为 {@code output_config.effort} 没有表达「别思考」的取值。
     *       此时同步清掉档位 —— 「别思考」与「想这么深」并存是自相矛盾的请求体。</li>
     *   <li>覆写档遇到下游的 {@code thinking:{"type":"disabled"}} 时清掉它。
     *       只清这一个取值，不整字段清空 —— {@code adaptive} 与
     *       {@code enabled+budget_tokens} 与深度正交，清掉它们等于替另一个维度做决定。</li>
     * </ol>
     *
     * <p>返回值就是给调用方用来协调这两个维度的：写了 {@code disabled} 意味着
     * 「这次调用不思考」，思考方式那一组随之整体失去意义，调用方应跳过它。
     * 若不跳过，方式的覆写档会把 {@code disabled} 改写成 {@code adaptive}，
     * 用户配的「关闭思考」就被静默丢弃了 —— 前端
     * {@code anthropicThinkingLockedByEffort} 的置灰就是同一条规则的界面表达。
     *
     * @param body 请求体，原地修改
     * @return 是否写入了 {@code thinking:{"type":"disabled"}}
     */
    public boolean applyToAnthropic(Map<String, Object> body) {
        return switch (mode) {
            case OVERRIDE -> {
                // 下游明确的「别思考」与覆写档的档位直接冲突，先清掉再重建。
                // 尊重用户配置优先于下游意图，这正是覆写档的定义。
                removeDisabledThinking(body);
                removeAnthropicEffort(body);
                yield writeConfiguredAnthropicEffort(body);
            }
            case FALLBACK -> {
                if (downstreamHasAnthropicOpinion(body)) {
                    yield false;
                }
                yield writeConfiguredAnthropicEffort(body);
            }
            // 下游带什么就是什么，没带也不补。显式写出这个分支的理由同 applyTo。
            case PASSTHROUGH -> false;
            case DELETE -> {
                removeAnthropicEffort(body);
                // 兼容副本一并删掉。调用方稍后还会无条件剥一次，这里重复是为了让本方法
                // 自身的语义完整 —— 「删除档过后请求体里没有任何深度表达」。
                body.remove(REQUEST_FIELD);
                yield false;
            }
        };
    }

    /**
     * 下游是否已就「要不要思考 / 思考多深」表达过意见（Anthropic 形态）。
     *
     * <p>三个来源，任一存在即算表态：
     * <ul>
     *   <li>{@code thinking} —— 与 OpenAI 侧同一判据，含显式 {@code disabled}；</li>
     *   <li>{@code output_config.effort} —— Anthropic 的原生深度字段；</li>
     *   <li>{@code reasoning_effort} —— O2A 翻译器刻意保留的兼容副本。
     *       翻译路线上它与 {@code output_config.effort} 必然同时存在，所以这一条只在
     *       <strong>直连</strong>线路上才单独起作用：那时下游把 OpenAI 的字段发给了
     *       Anthropic 端点，属于畸形请求。仍按「表过态」处理 —— 兜底档的含义是
     *       「下游开口了就不插手」，而那个字段随后会被剥离，净效果是这次不发深度。
     *       这比替一个已经开口的下游改主意更保守。</li>
     * </ul>
     */
    private static boolean downstreamHasAnthropicOpinion(Map<String, Object> body) {
        if (body.containsKey(THINKING_FIELD) || body.containsKey(REQUEST_FIELD)) {
            return true;
        }
        if (!body.containsKey(OUTPUT_CONFIG_FIELD)) {
            return false;
        }
        // 显式 null 也算表态（与另外两个字段的 containsKey 判据一致，那个 null 由调用方
        // 末尾的清洗移除）；对象形态则要求真的带了 effort —— output_config 还承载其它设置，
        // 仅仅出现这个容器不代表下游对深度有意见。
        return !(body.get(OUTPUT_CONFIG_FIELD) instanceof Map<?, ?> outputConfig)
                || outputConfig.containsKey(OUTPUT_CONFIG_EFFORT_KEY);
    }

    /**
     * 把配置的档位写成 Anthropic 能懂的形态。
     *
     * <p>不校验档位是否落在 Anthropic 的五档内：{@code minimal} 之类在那边无对应档，
     * <strong>原样发出</strong>由上游用错误码回答（请求侧契约第 4.5 节）。
     *
     * @return 是否写入了 {@code thinking:{"type":"disabled"}}
     */
    private boolean writeConfiguredAnthropicEffort(Map<String, Object> body) {
        if (EFFORT_OFF.equals(effort)) {
            // 五档里没有「不思考」，只能靠 thinking 表达；同时确保没有残留的档位与它冲突。
            removeAnthropicEffort(body);
            body.put(THINKING_FIELD, Map.of(THINKING_TYPE_KEY, THINKING_DISABLED));
            return true;
        }
        Map<String, Object> outputConfig = mutableOutputConfig(body);
        outputConfig.put(OUTPUT_CONFIG_EFFORT_KEY, effort);
        body.put(OUTPUT_CONFIG_FIELD, outputConfig);
        return false;
    }

    /**
     * 只摘掉 {@code output_config.effort}，保留容器里的其它键。
     *
     * <p>摘完为空则连容器一起移除 —— 一个空的 {@code output_config} 是纯噪声，
     * 而某些上游对多余字段并不宽容。
     */
    private static void removeAnthropicEffort(Map<String, Object> body) {
        if (!(body.get(OUTPUT_CONFIG_FIELD) instanceof Map<?, ?> existing)) {
            return;
        }
        Map<String, Object> outputConfig = copyStringKeyed(existing);
        outputConfig.remove(OUTPUT_CONFIG_EFFORT_KEY);
        if (outputConfig.isEmpty()) {
            body.remove(OUTPUT_CONFIG_FIELD);
        } else {
            body.put(OUTPUT_CONFIG_FIELD, outputConfig);
        }
    }

    /**
     * 只在 {@code thinking.type} 确实是 {@code disabled} 时移除该字段。
     *
     * <p>{@code adaptive} 与 {@code enabled+budget_tokens} 属于思考<strong>方式</strong>
     * 那一维，与深度正交，不能顺手清掉。
     */
    private static void removeDisabledThinking(Map<String, Object> body) {
        if (body.get(THINKING_FIELD) instanceof Map<?, ?> thinking
                && THINKING_DISABLED.equals(thinking.get(THINKING_TYPE_KEY))) {
            body.remove(THINKING_FIELD);
        }
    }

    /** 取一份可写的 {@code output_config}，下游已有则保留其它键。 */
    private static Map<String, Object> mutableOutputConfig(Map<String, Object> body) {
        return body.get(OUTPUT_CONFIG_FIELD) instanceof Map<?, ?> existing
                ? copyStringKeyed(existing)
                : new LinkedHashMap<>();
    }

    /** 复制成 String 键的可变 Map。下游传来的嵌套对象键类型无法静态保证。 */
    private static Map<String, Object> copyStringKeyed(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }
}
