package com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;

/**
 * Anthropic Messages API 的 usage 解析。
 *
 * <h2>为何独立于 {@code OpenAiUsageParser} 而非扩展它</h2>
 * 字段名全然不同（{@code input_tokens} / {@code output_tokens} /
 * {@code cache_read_input_tokens} / {@code cache_creation_input_tokens}），
 * 且 OpenAI 侧那份还带着「8+1 个供应商实证得出的 cached_tokens fallback 链」，
 * 那条链是 OpenAI 兼容生态混乱的产物，套到 Anthropic 上没有意义。
 *
 * <p><strong>但输出契约共享</strong>：两侧都产出 {@link UsageTokens}，因此落库无需分支 ——
 * 这正是「解析各写一份、结构归一化」的分界。
 *
 * <h2>口径也归一：{@code promptTokens} 是总输入（含缓存）</h2>
 * {@code api_call_usage} 的三个 token 列是跨协议共用的度量列，口径必须固定：
 * <pre>
 * prompt_tokens = 本次调用的总输入 token，<strong>包含</strong>缓存命中部分
 * </pre>
 * OpenAI 的 {@code prompt_tokens} 本来就是这个形态，而 Anthropic 把缓存读取排在
 * {@code input_tokens} <strong>之外</strong>单独计量，所以本类在 {@link #toTokens} 里把
 * {@code cache_read_input_tokens} 加回去。缓存命中的 token 确实是真实输入 ——
 * 模型每轮都要处理它们，缓存只是让它便宜，不是让它不存在。
 *
 * <h2>为何换算在解析层而不在落库层</h2>
 * 这个换算<strong>只依赖上游协议</strong>，与下游是谁无关：无论这条请求是
 * Anthropic 直连还是 A2O 翻译，上游都是 Anthropic、都差那一份缓存。既然如此，
 * 它就属于「如实解析 Anthropic 报文」这个职责的一部分。
 *
 * <p>早期的做法是只在 A2O 路线上换算（{@code DownstreamLogView.usageRewriter} +
 * {@code AnthropicToOpenAiResponseTranslator.translateUsageForLog}），前提是「Anthropic
 * 直连要的就是不含缓存的 {@code input_tokens}」。那个前提已被推翻：一列承载两种
 * 口径让每个消费方都得先知道该行的协议，而汇总查询根本做不到这一点
 * （SQL 里一 {@code SUM} 就把两种定义混在一起了）。
 *
 * <p>换算上提后两个副作用：那条落库侧的换算管道整体删除（留着只会诱人
 * 再加一遍缓存）；O2A 也不再需要任何 usage 接线 —— 那条线路上游是 OpenAI，
 * 本来就产出归一口径。
 *
 * <h2>{@code cache_creation_input_tokens} 不计入，这是可接受的取舍</h2>
 * Anthropic 的输入侧有三个量，后两个都在 {@code input_tokens} 之外：
 * <pre>
 * input_tokens                 本次新增输入
 * cache_read_input_tokens      命中缓存读出的（便宜，按折扣价计费）
 * cache_creation_input_tokens  写入缓存的（比普通输入更贵）
 * </pre>
 * 本类只把前两项相加，因此发生缓存写入时 {@code promptTokens} 略低于真实总输入，
 * 缓存占比也随之略偏高。
 *
 * <p><strong>不把它算进去是因为 {@link UsageTokens} 没有它的位置</strong>，而那个 record
 * 是多协议共用的输出契约 —— 为 Anthropic 的私有字段加成员会把协议细节漏给
 * OpenAI 侧与 Ollama 侧（那两边永远是 null）。补它的正确做法是给
 * {@code api_call_usage} 加一列，属于后续版本。
 *
 * <p>现阶段按「已知精度损失」处理而非缺陷：差额只在真的发生缓存写入那一轮出现
 * （多轮对话里通常只有首轮），且同一行的 {@code usage_raw} 保留了上游原文，
 * 随时可查。出站报文侧（{@code AnthropicUsageAccumulator}）则<strong>已经</strong>把三项
 * 都算进去了，因此下游客户端看到的数字是完整的。详见
 * {@code docs/PROTOCOL_TRANSLATION_RESPONSE_CONTRACT.md} 第 9.4 节。
 *
 * <h2>null 与 0 的区分必须保留</h2>
 * 与 OpenAI 侧同一约束：{@code null} 表示上游未提供该字段，{@code 0} 表示上游
 * 报告了真实零值。一律按<strong>字段存在性</strong>提取，绝不用 {@code asInt(0)}
 * 兜底，否则会把两者抹平，毁掉缓存命中率的可解读性。
 *
 * <h2>流式下 usage 分散在两个事件里</h2>
 * {@code message_start} 携带 {@code input_tokens}（此时 {@code output_tokens}
 * 通常为 0 或缺失），{@code message_delta} 携带最终的 {@code output_tokens}。
 * 因此流式链路不能只取一个事件就完事，需要跨事件合并 —— 见 {@link #merge}。
 *
 * <h2>与 OpenAI 侧对称</h2>
 * 本类与 {@code provider.generic.openai.OpenAiUsageParser} 是<strong>同一职责的两个协议实现</strong>，
 * 位于同一层级、命名风格一致。
 *
 * <p>{@link UsageTokens} 不在此列 —— 它是<strong>共享的输出契约</strong>
 * （两侧都产出它，落库与前端因此无需分支），留在 {@code application.usage} 是正确的归属。
 */
public final class AnthropicUsageParser {

    private AnthropicUsageParser() {
    }

    /**
     * 从一个 Anthropic 事件或完整响应体中提取 usage 对象的原始 JSON。
     *
     * <p>{@code message_start} 的 usage 嵌在 {@code message.usage} 下，
     * 而 {@code message_delta} 与非流式响应的 usage 在顶层 —— 两处都要看。
     *
     * @return usage 对象的原始 JSON；无 usage 或解析失败返回 null
     */
    public static String extractUsageRawJson(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode usage = locateUsage(root);
            return usage == null ? null : objectMapper.writeValueAsString(usage);
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 解析一个 Anthropic usage 对象的 JSON 为归一化的 token 指标。
     *
     * @param usageObjectJson usage 对象原始 JSON（非事件整体）
     */
    public static UsageTokens parseUsageObject(ObjectMapper objectMapper, String usageObjectJson) {
        if (usageObjectJson == null || usageObjectJson.isBlank()) {
            return UsageTokens.EMPTY;
        }
        try {
            return toTokens(objectMapper.readTree(usageObjectJson));
        } catch (Exception exception) {
            return UsageTokens.EMPTY;
        }
    }

    /**
     * 合并两轮 usage —— 流式下把 {@code message_start} 的输入与
     * {@code message_delta} 的输出拼成完整一份。
     *
     * <h2>覆盖而非相加</h2>
     * Anthropic 的 {@code message_delta} 报告的 {@code output_tokens} 是
     * <strong>累计值</strong>而非增量，相加会翻倍。
     *
     * <h2>只有正数才覆盖：0 不得抹掉已知值</h2>
     * 存在这样的上游：<strong>每一个</strong>事件都带完整的 usage 对象，
     * 但只有少数几个带真实数字，其余全是 {@code 0}（包括最后的
     * {@code message_stop}）。若按「非 null 就覆盖」，那个全零尾事件会把
     * {@code message_delta} 里的真实数字全部抹成 0 —— 实测到过的缺陷。
     *
     * <p>反向风险不存在：上游不会先报一个正数再改成 0，那在 token 计数上
     * 没有意义。因此「保留已知正数」是安全的。
     *
     * <p>这与 {@code AnthropicUsageAccumulator.mergeField}（出站报文侧）是<strong>同一条
     * 规则</strong>，两处必须一致。那边从一开始就只让正数覆盖，所以同一次调用里
     * 出站 usage 正确、落库却全零 —— 两套规则不一致正是那个缺陷的成因。
     *
     * <p>{@code null} 与 {@code 0} 的区分仍然保留：两边都没给过正数时，
     * 结果取 {@code update} 的值，因此「上游报告了 0」能落到 0 而不是变回 null。
     *
     * @param base   先前累积的指标；可为 null
     * @param update 新一轮解析出的指标；可为 null
     */
    public static UsageTokens merge(UsageTokens base, UsageTokens update) {
        if (base == null || base.isEmpty()) {
            return update == null ? UsageTokens.EMPTY : update;
        }
        if (update == null || update.isEmpty()) {
            return base;
        }
        return new UsageTokens(
                mergeCount(base.promptTokens(), update.promptTokens()),
                mergeCount(base.completionTokens(), update.completionTokens()),
                mergeCount(base.cachedTokens(), update.cachedTokens()));
    }

    /**
     * 单个计数的合并：新值为正数才覆盖，否则保留已知的正数。
     *
     * <p>两边都不是正数时取 {@code update}（可能是 {@code 0} 也可能是 {@code null}），
     * 但 {@code update} 为 null 而 {@code base} 有值时仍保留 {@code base} ——
     * 后续事件不带某个字段是常态，不能因此丢掉已收到的数。
     */
    private static Integer mergeCount(Integer base, Integer update) {
        if (update != null && update > 0) {
            return update;
        }
        if (base != null && base > 0) {
            return base;
        }
        return update != null ? update : base;
    }

    /**
     * 定位 usage 节点：先看顶层，再看 {@code message.usage}。
     *
     * <p>顺序不能反 —— {@code message_delta} 事件的顶层就有 usage，
     * 而它没有 {@code message} 字段；{@code message_start} 反之。
     */
    private static JsonNode locateUsage(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode topLevel = root.get("usage");
        if (topLevel != null && topLevel.isObject()) {
            return topLevel;
        }
        JsonNode message = root.get("message");
        if (message != null && message.isObject()) {
            JsonNode nested = message.get("usage");
            if (nested != null && nested.isObject()) {
                return nested;
            }
        }
        return null;
    }

    /**
     * 把 Anthropic usage 对象映射为归一口径的指标。
     *
     * <h2>{@code promptTokens} 是 {@code input_tokens + cache_read_input_tokens}</h2>
     * Anthropic 把缓存读取排在 {@code input_tokens} 之外，而本列的口径是「总输入含缓存」，
     * 所以这里加回去。理由与不在落库层做这件事的原因见类注释。
     *
     * <h2>缓存 token 只取 cache_read，不取 cache_creation</h2>
     * 后者是「本次写入缓存的量」，属于成本项而非命中项，混入会让缓存命中率虚高。
     * 它同样不计入 {@code promptTokens} —— 那是个已知精度损失，见类注释。
     */
    private static UsageTokens toTokens(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return UsageTokens.EMPTY;
        }
        Integer cacheRead = intIfPresent(usage, "cache_read_input_tokens");
        return new UsageTokens(
                sumOrNull(intIfPresent(usage, "input_tokens"), cacheRead),
                intIfPresent(usage, "output_tokens"),
                cacheRead);
    }

    /**
     * 两个可空计数相加，两者均缺失时保持 {@code null}。
     *
     * <p>{@code null} 不能当 0 参与相加：那会把「上游未提供」造成「上游报告了 0」，
     * 而缓存占比靠这个区分区分「—」与「0.0%」。但只有一方缺失时可以按 0 参与 ——
     * 那时另一方已经证明了「上游报告了输入侧数据」，和值仍然是个真实量。
     */
    private static Integer sumOrNull(Integer left, Integer right) {
        if (left == null && right == null) {
            return null;
        }
        return (left == null ? 0 : left) + (right == null ? 0 : right);
    }

    /** 仅当字段存在且为整数时返回值，否则 null —— 保住 null 与 0 的区分。 */
    private static Integer intIfPresent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asInt() : null;
    }
}
