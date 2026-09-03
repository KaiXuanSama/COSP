/**
 * Anthropic 思考形态的前端编辑模型。
 *
 * <h2>为何现在单独建这个纯模块</h2>
 * 第二层目前只做 UI，后端尚没有对应字段，因此这里的状态暂不序列化进模型提交体。
 * 但若直接在组件里散落三个字符串（模式 / type / budget），后续接数据库时会重新设计一次。
 * 先把形状、默认值和可编辑条件收敛在纯模块，后续只需补 parse / serialize 与后端 DTO。
 *
 * <h2>三个 type 的语义</h2>
 * - {@code adaptive}：上游自行决定思考预算；不接受手动预算。
 * - {@code enabled}：旧的手动预算形态，出站为 {@code thinking: {type:"enabled", budget_tokens:N}}。
 * - {@code disabled}：明确关闭思考；不接受手动预算。
 *
 * <p>这里不按模型名判断哪个 type 能用，也不自动改写用户选择。模型版本、
 * 中转站兼容性与最终出站翻译属于后续后端工作；UI 只如实表达用户配置。
 */
import type { OverwriteMode } from './overwriteMode'

/** Anthropic Messages thinking.type 的三种可选形态。 */
export type AnthropicThinkingType = 'adaptive' | 'enabled' | 'disabled'

/**
 * 思考形态支持的注入模式。
 *
 * 没有 delete：删除是「强制剥离 thinking」的另一类高级行为，目前不放进这个
 * 基础 UI；用户可继续用仅适用于 ANTHROPIC 的请求体规则实现。透传 / 兜底 / 覆写
 * 已覆盖绝大多数直连场景。
 */
export type AnthropicThinkingOverwriteMode = Extract<
  OverwriteMode,
  'passthrough' | 'fallback' | 'override'
>

/** 点击轮转与滚轮步进的顺序。 */
export const ANTHROPIC_THINKING_OVERWRITE_MODES: readonly AnthropicThinkingOverwriteMode[] = [
  'override',
  'fallback',
  'passthrough',
]

/** 下拉里的 type 值。标签故意与协议字面量一致，避免用户误以为是本服务自定义概念。 */
export const ANTHROPIC_THINKING_TYPE_OPTIONS: readonly AnthropicThinkingType[] = [
  'adaptive',
  'enabled',
  'disabled',
]

/**
 * 尚未有后端默认值；预算默认留空。
 *
 * enabled 的预算必须最终满足 Anthropic 的约束（旧形态为 >=1024 且 < max_tokens），
 * 但本轮只实现 UI，校验与最终注入应由后端统一处理，不能在前端复制一份规则引擎。
 */
export interface AnthropicThinkingConfig {
  type: AnthropicThinkingType
  mode: AnthropicThinkingOverwriteMode
  budgetTokens: string
}

export const DEFAULT_ANTHROPIC_THINKING_CONFIG: Readonly<AnthropicThinkingConfig> = {
  type: 'adaptive',
  mode: 'fallback',
  budgetTokens: '',
}

/** 只有 enabled 的手动预算形态允许编辑预算。 */
export function anthropicThinkingUsesBudget(type: AnthropicThinkingType): boolean {
  return type === 'enabled'
}
