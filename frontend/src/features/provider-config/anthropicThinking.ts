/**
 * Anthropic 思考形态的前端编辑模型。
 *
 * <h2>为何现在单独建这个纯模块</h2>
 * 第二层目前只做 UI，后端尚没有对应字段，因此这里的状态暂不序列化进模型提交体。
 * 但若直接在组件里散落三个字符串（模式 / type / budget），后续接数据库时会重新设计一次。
 * 先把形状、默认值和可编辑条件收敛在纯模块，后续只需补 parse / serialize 与后端 DTO。
 *
 * <h2>两个 type 的语义</h2>
 * - {@code adaptive}：上游自行决定思考预算；不接受手动预算。
 * - {@code enabled}：旧的手动预算形态，出站为 {@code thinking: {type:"enabled", budget_tokens:N}}。
 *
 * <h2>为何没有 {@code disabled}</h2>
 * 关闭思考已经由思考深度的 {@code Off} 档表达，且那一档在两条线路上都成立。
 * 如果这里再给一个 {@code disabled}，同一个意图就有两个入口，而且两个入口各自带
 * 注入模式 —— 「思考深度=High 覆写」配「思考方式=disabled 覆写」会产出自相矛盾的请求体。
 * 思考开关因此只留在思考深度一处，本字段只回答「开了之后预算怎么定」。
 *
 * <p>这里不按模型名判断哪个 type 能用，也不自动改写用户选择。模型版本、
 * 中转站兼容性与最终出站翻译属于后续后端工作；UI 只如实表达用户配置。
 */
import { modeUsesConfiguredValue, type OverwriteMode } from './overwriteMode'
import { EFFORT_OFF } from './reasoningEffort'

/** Anthropic Messages thinking.type 的可选形态。关闭思考由思考深度的 Off 档负责。 */
export type AnthropicThinkingType = 'adaptive' | 'enabled'

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

/**
 * 思考深度已经表达「不思考」时，本字段整组失去意义。
 *
 * <h2>为何要连模式一起判</h2>
 * 只看档位会误锁：透传与删除两档不使用此处配置的值，那时候的 {@code Off}
 * 只是一个备选值、不会出站，据此锁掉思考方式就把一个并未生效的配置当成了事实。
 * 因此仅在覆写 / 兜底两档下生效 —— 与 {@code ModeScopedField} 的置灰口径一致。
 *
 * <p>兜底档下是否真的发出 {@code Off} 取决于下游有没有表态，这在编辑时无法得知。
 * 这里按「配置意图」而非「运行时结果」锁定：用户既然已经把默认意图定为不思考，
 * 再让他在旁边配一个预算只会产生误解。
 */
export function anthropicThinkingLockedByEffort(
  effort: string,
  effortMode: OverwriteMode,
): boolean {
  if (!modeUsesConfiguredValue(effortMode)) return false
  return effort.trim().toLowerCase() === EFFORT_OFF
}
