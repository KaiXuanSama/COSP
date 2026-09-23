package com.kaixuan.copilot_ollama_proxy.upstream.requestbody.maxtokens;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.MaxOutputTokensSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;

import java.util.Map;

/**
 * {@code max_tokens} 落定的<strong>纯逻辑</strong> —— 被 {@link MaxTokensNormalizeStage} 的
 * MESSAGES 实现与 Anthropic 执行器共同调用。
 *
 * <p>为何是静态工具（而不是直接把逻辑放进实现类）见 {@link SystemPromptNormalizer} 的类注释：
 * 支线接线（3.3d-2）之前执行器仍需工作，两份等价逻辑必然静默分叉。
 *
 * <h2>它在管道中的位置</h2>
 * 形态：支线<strong>实现体</strong>（静态工具） · 位置：{@code upstream/requestbody/maxtokens/}
 * 步骤「max_tokens 补齐」的纯逻辑
 * <p>完整步骤树见 {@code upstream/README.md}；<strong>那里有编号，本处刻意不写</strong> ——
 * 编号是全局坐标、会随插入而漂，故类注释只写步骤的<strong>基名</strong>。
 */
public final class MaxTokensNormalizer {

    /** 下游可能用的 OpenAI 别名，需求一在此归一。 */
    private static final String ALIAS_FIELD = "max_completion_tokens";
    /** Anthropic 的正名字段。 */
    private static final String FIELD = "max_tokens";

    private MaxTokensNormalizer() {
    }

    /**
     * 按模型配置的注入模式落定 {@code max_tokens}。
     *
     * <h2>为何这一步不能省</h2>
     * Anthropic 把 {@code max_tokens} 列为<strong>必填</strong>，缺失时上游直接 400。
     * 而 OpenAI 侧它是可选的，Copilot 之类的下游通常不带 —— 于是必须在这里补齐。
     *
     * <h2>三个步骤的顺序有讲究</h2>
     * <ol>
     *   <li><strong>别名归一化</strong>：下游可能用 OpenAI 的 {@code max_completion_tokens}。
     *       必须在注入之前搬到正名上，否则 {@code OVERRIDE} 模式写好 {@code max_tokens} 后，
     *       那个别名字段仍会留在请求体里一起发给上游。</li>
     *   <li><strong>清掉非法值</strong>：{@code 0}、负数、非数字都视为「没带」。
     *       {@link MaxOutputTokensSetting#applyTo} 的兜底档只看 {@code containsKey}，
     *       留着一个 {@code "max_tokens": 0} 会让它认为下游表达过意见而放行 —— 然后上游 400。</li>
     *   <li><strong>按模式注入</strong>：交给 {@code applyTo}，覆写档无条件写、兜底档只补缺。</li>
     * </ol>
     *
     * <h2>兜底档在这条线路上的实际作用</h2>
     * Anthropic 客户端直连时几乎总会自带 {@code max_tokens}（协议必填），因此兜底档很少触发；
     * 真正有用的是<strong>覆写档</strong> —— 它能把下游请求的上限统一压到这里配置的值。
     * 但兜底档仍不能省：跨协议来的请求（OpenAI 形态的下游打到 Anthropic 供应商）就是靠它补齐的。
     *
     * @param body          请求体，会被原地修改
     * @param resolvedModel 已剥供应商前缀的真实模型名
     * @param provider      本次调用的供应商运行时配置
     * @param objectMapper  用于解析模型配置里的 {@code max_output_tokens} JSON
     */
    public static void ensureMaxTokens(Map<String, Object> body, String resolvedModel,
                                       ProviderRuntimeConfiguration provider, ObjectMapper objectMapper) {
        normalizeMaxTokensAlias(body);
        resolveMaxOutputTokens(resolvedModel, provider, objectMapper).applyTo(body);
    }

    /**
     * 把 OpenAI 的 {@code max_completion_tokens} 搬到 Anthropic 的正名上，并清掉非法值。
     *
     * <p>别名无论合法与否都会被移除：它不是 Anthropic 协议的字段，留着只会让上游困惑。
     *
     * @param body 请求体，会被原地修改
     */
    public static void normalizeMaxTokensAlias(Map<String, Object> body) {
        Object alias = body.remove(ALIAS_FIELD);
        if (!(body.get(FIELD) instanceof Number existing) || existing.intValue() <= 0) {
            body.remove(FIELD);
            if (alias instanceof Number aliasValue && aliasValue.intValue() > 0) {
                body.put(FIELD, aliasValue.intValue());
            }
        }
    }

    /**
     * 从运行时模型配置中读取最大输出设置。
     *
     * <p>找不到匹配的模型时返回 {@link MaxOutputTokensSetting#defaults()}（64K + 兜底），
     * 与 OpenAI 侧 {@code resolveReasoningEffort} 同一形状。这里的兜底比思考深度那个安全得多 ——
     * 给一个未配置的模型注入 {@code max_tokens} 不会改变语义，而缺了它这条线路根本发不出去。
     *
     * <p>线性查找而非建 Map：模型数量是个位到几十的量级，且这个方法每轮请求只调一次。
     *
     * @param resolvedModel 已剥供应商前缀的真实模型名
     * @param provider      本次调用的供应商运行时配置
     * @param objectMapper  用于解析配置 JSON
     * @return 该模型的设置；模型名查不到时为默认值
     */
    public static MaxOutputTokensSetting resolveMaxOutputTokens(String resolvedModel,
                                                               ProviderRuntimeConfiguration provider,
                                                               ObjectMapper objectMapper) {
        for (var model : provider.models()) {
            if (resolvedModel.equals(model.modelName())) {
                return MaxOutputTokensSetting.parse(model.maxOutputTokens(), objectMapper);
            }
        }
        return MaxOutputTokensSetting.defaults();
    }
}
