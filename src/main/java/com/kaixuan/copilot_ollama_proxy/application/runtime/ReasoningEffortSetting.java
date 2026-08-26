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
     * 按当前模式把思考深度写入（或移出、或保留原样）请求体。
     *
     * <p>原地修改传入的 Map 而非返回新 Map：调用方
     * （{@code AbstractUpstreamChatService.prepareRequestBody}）已经持有一份可变副本，
     * 再造一个只会让「哪一份才是最终请求体」变得不明确。
     *
     * <p>{@link Mode#FALLBACK} 用 {@code containsKey} 而非判空：下游显式传
     * {@code "reasoning_effort": null} 时应视为「它表达过意见」，不该被配置值顶掉；
     * 那个 null 随后由 {@code removeIf(Objects::isNull)} 清掉，最终等效于不发送。
     *
     * <p>{@link Mode#PASSTHROUGH} 不做任何事 —— 它的语义正是「不干预」。
     * 显式写出这个空分支而不让它落到 default，是为了让四种模式在代码里一一对应，
     * 将来新增模式时不会有一个分支被静默吞掉。
     */
    public void applyTo(Map<String, Object> body) {
        switch (mode) {
            case OVERRIDE -> body.put(REQUEST_FIELD, effort);
            case FALLBACK -> {
                if (!body.containsKey(REQUEST_FIELD)) {
                    body.put(REQUEST_FIELD, effort);
                }
            }
            case PASSTHROUGH -> {
                // 下游带什么就是什么，没带也不补。
            }
            case DELETE -> body.remove(REQUEST_FIELD);
        }
    }
}
