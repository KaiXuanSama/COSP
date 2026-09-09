package com.kaixuan.copilot_ollama_proxy.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;

/**
 * 模型的最大输出配置：token 上限 + 注入模式。
 *
 * <h2>为何也需要「模式」这一维度</h2>
 * 与思考深度同理：这个值会<strong>进入发往上游的请求体</strong>（Anthropic 的
 * {@code max_tokens} 必填，OpenAI 的同名字段可选），于是「下游自己带了怎么办」
 * 必须有个答案，而单个数字无法表达答案。
 *
 * <p>但模式只有两档，比思考深度少两个：
 * <ul>
 *   <li>没有 {@code PASSTHROUGH} —— Anthropic 侧 {@code max_tokens} 缺失会直接 400，
 *       「下游没带也不补」在那条线路上等于必然失败。真要不干预，把值调大即可。</li>
 *   <li>没有 {@code DELETE} —— 同样的理由，剥掉必填字段没有任何合理用途。</li>
 * </ul>
 * 少两档不是简化，而是这两档在本字段上没有对应的真实意图。
 *
 * <h2>持久化形态</h2>
 * 存在 {@code provider_model.max_output_tokens} 一列里，V9 起是 JSON：
 * <pre>{@code {"max_output_tokens":4000,"overwrite_mode":"fallback"}}</pre>
 * 该列在 V9 之前是 INTEGER，V9 重建表把它换成带 {@code json_valid} 约束的 TEXT。
 *
 * <h2>接线范围</h2>
 * 目前只有 <strong>Anthropic</strong> 线路消费本设置
 * （{@code GenericAnthropicChatService.ensureMaxTokens}）—— 那条线路上
 * {@code max_tokens} 必填，不补就发不出去。
 *
 * <p>OpenAI 线路<strong>刻意不接</strong>：那边该字段可选，接上去会改变所有现存
 * OpenAI 供应商的出站请求体（默认兜底档会给「下游没带」的调用凭空补一个上限，
 * 而 Copilot 通常就是不带）。要接需要单独评估影响面。
 */
public record MaxOutputTokensSetting(int maxOutputTokens, Mode mode) {

    /**
     * 最大输出的注入模式。
     *
     * <pre>
     *            下游带了值        下游没带
     * OVERRIDE   用配置的上限      用配置的上限
     * FALLBACK   用下游的值        用配置的上限
     * </pre>
     *
     * <p>{@code FALLBACK} 是升级后的默认：V9 之前这个值压根没进过请求体，
     * 因此「下游带了就听它的」最接近「什么都没变」。
     */
    public enum Mode {
        /** 覆写：无论下游有没有带，都用配置的上限。 */
        OVERRIDE,
        /** 兜底：下游带了就用它的，没带才用配置的上限。 */
        FALLBACK
    }

    /** 上游请求体里最大输出的字段名（OpenAI 与 Anthropic 同名）。 */
    public static final String REQUEST_FIELD = "max_tokens";

    /** 持久化 JSON 里承载 token 上限的键名，与数据库列同名。 */
    public static final String TOKENS_KEY = "max_output_tokens";

    /**
     * 默认上限取 4000。
     *
     * <p>与 V9 迁移把存量 128K 一律下调为 4K 保持一致 —— 若默认值仍留在 128000，
     * 新建模型会拿到一个迁移刚刚判定为「过大」的值。
     */
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4000;

    private static final Mode DEFAULT_MODE = Mode.FALLBACK;

    /** 配置缺失时的兜底：4K + 兜底模式。 */
    public static MaxOutputTokensSetting defaults() {
        return new MaxOutputTokensSetting(DEFAULT_MAX_OUTPUT_TOKENS, DEFAULT_MODE);
    }

    /**
     * 非正数一律回退默认值。
     *
     * <p>0 在旧形态里是合法的（列约束只要求 {@code >= 0}），语义上表示「未配置」。
     * 到了 V9 这个语义由模式承担，于是 0 不再需要单独表达 —— 归一化成默认值即可。
     */
    public MaxOutputTokensSetting {
        maxOutputTokens = maxOutputTokens > 0 ? maxOutputTokens : DEFAULT_MAX_OUTPUT_TOKENS;
        mode = mode == null ? DEFAULT_MODE : mode;
    }

    /**
     * 解析持久化的配置字符串，兼容 V9 之前的裸整数形态。
     *
     * <p>依次尝试：
     * <ol>
     *   <li><strong>V9 JSON</strong>：{@code {"max_output_tokens":4000,"overwrite_mode":"fallback"}}</li>
     *   <li><strong>旧的裸整数</strong>：{@code "128000"} → 该值 + 兜底模式</li>
     * </ol>
     *
     * <p>注意这里<strong>不做</strong>「128K 降为 4K」那类档位重映射：那是 V9 迁移的一次性
     * 语义调整，只该发生在迁移里。读取路径若也做同样的映射，任何一个手工设成 128000 的值
     * 都会在每次读取时变成 4000，而用户看到的界面值与自己填的不一致。
     *
     * <p>解析失败回退默认值而不抛异常 —— 值来自数据库，一行脏数据不该让聊天链路失败。
     *
     * @param raw          持久化的原始字符串，可为 null
     * @param objectMapper JSON 解析器；为 null 时只处理旧的裸整数形态
     */
    public static MaxOutputTokensSetting parse(String raw, ObjectMapper objectMapper) {
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
                return new MaxOutputTokensSetting(
                        parsed.path(TOKENS_KEY).asInt(0),
                        parseMode(parsed.path("overwrite_mode").asText(null)));
            } catch (Exception exception) {
                return defaults();
            }
        }

        try {
            return new MaxOutputTokensSetting(Integer.parseInt(trimmed), DEFAULT_MODE);
        } catch (NumberFormatException exception) {
            return defaults();
        }
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
     * 序列化为持久化用的 V9 JSON。
     *
     * <p>手工拼接而不用 {@code ObjectMapper}：两个字段都已在构造器里归一化过
     * （一个是正整数、一个是枚举），不存在需要转义的内容。
     */
    public String serialize() {
        return "{\"" + TOKENS_KEY + "\":" + maxOutputTokens
                + ",\"overwrite_mode\":\"" + mode.name().toLowerCase(Locale.ROOT) + "\"}";
    }

    /**
     * 按当前模式把最大输出写入请求体。
     *
     * <p>{@link Mode#FALLBACK} 用 {@code containsKey} 而非判空，与思考深度一致：
     * 下游显式传 {@code null} 视为「它表达过意见」，那个 null 随后由调用方的
     * null 清洗移除。
     *
     * <p><strong>当前无调用方</strong>，见类注释。
     */
    public void applyTo(Map<String, Object> body) {
        switch (mode) {
            case OVERRIDE -> body.put(REQUEST_FIELD, maxOutputTokens);
            case FALLBACK -> {
                if (!body.containsKey(REQUEST_FIELD)) {
                    body.put(REQUEST_FIELD, maxOutputTokens);
                }
            }
        }
    }
}
