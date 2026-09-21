package com.kaixuan.copilot_ollama_proxy.provider.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.runtime.AnthropicThinkingSetting;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ReasoningEffortSetting;

import java.util.Map;

/**
 * Anthropic 线路思考注入的<strong>纯逻辑</strong> —— 被 {@link ThinkingInjectStage} 的
 * MESSAGES 实现与 Anthropic 执行器共同调用。
 *
 * <p>为何是静态工具（而不是直接把逻辑放进实现类）见 {@link SystemPromptNormalizer} 的类注释：
 * 支线接线（3.3d-2）之前执行器仍需工作，两份等价逻辑必然静默分叉。
 *
 * <h2>两个维度的施加顺序不可交换</h2>
 * <strong>深度先、方式后</strong>，且深度写了
 * {@code thinking:{"type":"disabled"}} 时<strong>跳过方式</strong>。
 * 两者都会写 {@code thinking}，而它们对那个字段的权限不对等：
 * 深度只在 {@code off} 档动它（五档里没有「不思考」，只能借这个字段表达），
 * 方式则把它当作自己的主场。
 *
 * <p>先方式后深度会让深度的兜底档把方式刚写的 {@code thinking} 误认为
 * 「下游已表态」，于是自己不再注入 —— 一个下游从未发过的字段反而封住了
 * 用户配的档位。不跳过方式则相反：方式的覆写档会把 {@code disabled} 改写成
 * {@code adaptive}，用户配的「关闭思考」被静默丢弃。前端的
 * {@code anthropicThinkingLockedByEffort} 置灰就是同一条规则的界面表达。
 */
public final class AnthropicThinkingNormalizer {

    /** OpenAI 的深度字段名；C2M 翻译器留了一份兼容副本供判定「下游已表态」。 */
    private static final String ALIAS_FIELD = "reasoning_effort";

    private AnthropicThinkingNormalizer() {
    }

    /**
     * 就地把思考的两个维度写进请求体，并剥掉兼容副本。
     *
     * <p>三步的顺序不可交换，理由见类注释与本步骤各行的行内说明。
     *
     * @param body          请求体，会被原地修改
     * @param resolvedModel 已剥供应商前缀的真实模型名
     * @param provider      本次调用的供应商运行时配置
     * @param objectMapper  用于解析模型配置 JSON
     */
    public static void applyThinkingDimensions(Map<String, Object> body, String resolvedModel,
                                               ProviderRuntimeConfiguration provider,
                                               ObjectMapper objectMapper) {
        // 深度先。
        boolean thinkingDisabled = resolveReasoningEffort(resolvedModel, provider, objectMapper)
                .applyToAnthropic(body);
        // 方式后，且深度明确要求关闭时跳过 —— 否则会把 disabled 改写成 adaptive，
        // 用户配的「关闭思考」被静默丢弃。
        if (!thinkingDisabled) {
            resolveThinking(resolvedModel, provider, objectMapper).applyTo(body);
        }
        // 剥兼容副本：必须在上面两步之后 —— 它正是供那两步判定「下游已表态」的。
        // 提前剥会让兜底档把一个已表态的请求当成未表态，静默退化成覆写档。
        body.remove(ALIAS_FIELD);
    }

    /**
     * 从运行时模型配置中读取思考方式设置。
     *
     * <p>找不到匹配的模型时返回 {@link AnthropicThinkingSetting#defaults()}
     * （adaptive + 兜底 + 未设置预算）。这个默认值<strong>就是</strong> V10 之前
     * 那段硬编码的行为，因此升级前后的出站请求体完全一致。
     *
     * @param resolvedModel 已剥供应商前缀的真实模型名
     * @param provider      本次调用的供应商运行时配置
     * @param objectMapper  用于解析配置 JSON
     * @return 该模型的思考方式设置；模型名查不到时为默认值
     */
    public static AnthropicThinkingSetting resolveThinking(String resolvedModel,
                                                          ProviderRuntimeConfiguration provider,
                                                          ObjectMapper objectMapper) {
        for (var model : provider.models()) {
            if (resolvedModel.equals(model.modelName())) {
                return AnthropicThinkingSetting.parse(
                        model.thinkingMode(), model.thinkingBudgetTokens(), objectMapper);
            }
        }
        return AnthropicThinkingSetting.defaults();
    }

    /**
     * 从运行时模型配置中读取思考深度设置。
     *
     * <p>与 OpenAI 侧读的是<strong>同一列</strong>（{@code provider_model.reasoning_effort}）、
     * 同一份解析与同一套四档语义，只有出站的字段名与形态不同。因此此处不引入
     * 第二份配置 —— 用户在界面上看到的就是一个模型一个档位，无论它走哪条线路。
     *
     * <p>模型名查不到时用 {@link ReasoningEffortSetting#defaults()}（medium + 兜底），
     * 与 OpenAI 侧 {@code resolveReasoningEffort} 同一形状。
     *
     * <p><strong>本方法在三个执行器各有一份逐字相同的副本</strong>
     * （{@code AbstractUpstreamChatService} / {@code GenericResponsesChatService} 也有）。
     * 本步只把 Anthropic 那份搬来此处，另两份的合流是后续步骤的事 —— 现在动它们
     * 会超出「协议特定步骤支线化」的范围。
     *
     * @param resolvedModel 已剥供应商前缀的真实模型名
     * @param provider      本次调用的供应商运行时配置
     * @param objectMapper  用于解析配置 JSON
     * @return 该模型的思考深度设置；模型名查不到时为默认值
     */
    public static ReasoningEffortSetting resolveReasoningEffort(String resolvedModel,
                                                               ProviderRuntimeConfiguration provider,
                                                               ObjectMapper objectMapper) {
        for (var model : provider.models()) {
            if (resolvedModel.equals(model.modelName())) {
                return ReasoningEffortSetting.parse(model.reasoningEffort(), objectMapper);
            }
        }
        return ReasoningEffortSetting.defaults();
    }
}
