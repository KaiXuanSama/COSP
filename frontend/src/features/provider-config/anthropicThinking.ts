/**
 * Anthropic 思考方式的前端编辑模型。
 *
 * <h2>与思考深度是两个正交维度</h2>
 * 深度（`reasoningEffort.ts`）回答「想多深」，本字段回答「预算怎么算」：
 *
 * - `adaptive`：上游自行决定思考预算；不接受手动预算。
 * - `enabled`：手动预算形态，出站为 `thinking: {type:"enabled", budget_tokens:N}`。
 *
 * 两者可以并存 —— DeepSeek 官方示例就把 `thinking` 与 `reasoning_effort` 并列给出。
 * 所以「深度=High」配「方式=adaptive」不矛盾：前者说想多想，后者说预算交给上游定。
 *
 * <h2>为何没有 `disabled`</h2>
 * 关闭思考已经由思考深度的 `Off` 档表达，且那一档在两条线路上都成立。
 * 如果这里再给一个 `disabled`，同一个意图就有两个入口，而且两个入口各自带
 * 注入模式 —— 「思考深度=High 覆写」配「思考方式=disabled 覆写」会产出自相矛盾的请求体。
 * 思考开关因此只留在思考深度一处，本字段只回答「开了之后预算怎么定」。
 *
 * <h2>形态与模式合存一列，预算另存一列</h2>
 * `thinking_mode` 存 `{thinking_type, overwrite_mode}`（走 `overwriteMode.ts` 的基座），
 * `thinking_budget_tokens` 是裸整数。预算**没有自己的注入模式** —— 它是 `enabled`
 * 形态的附属参数，跟着形态的模式走；给它单独一档模式会允许表达
 * 「方式覆写但预算兜底」这种没有真实意图的组合。
 *
 * <p>因此本模块的编辑模型（{@link AnthropicThinkingConfig}）有三个字段，
 * 但序列化成**两个**出站值，见 {@link serializeAnthropicThinkingMode} 与
 * {@link serializeAnthropicThinkingBudget}。
 *
 * <p>这里不按模型名判断哪个 type 能用，也不自动改写用户选择，也不校验预算是否落在
 * Anthropic 要求的区间 —— 越界让上游用错误码回答，与「不自动降级」的原则一致。
 */
import {
  OVERWRITE_MODE_LABELS,
  buildOverwriteModeHints,
  modeUsesConfiguredValue,
  nextOverwriteMode,
  parseModeScopedValue,
  serializeModeScopedValue,
  type ModeScopedCodec,
  type OverwriteMode,
} from './overwriteMode'
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

/** 模式的标签与悬停说明，与另外两个字段共用同一套词。 */
export const ANTHROPIC_THINKING_OVERWRITE_MODE_LABELS = OVERWRITE_MODE_LABELS

export const ANTHROPIC_THINKING_OVERWRITE_MODE_HINTS = buildOverwriteModeHints('方式')

/** 默认形态：交给上游决定预算。 */
export const DEFAULT_ANTHROPIC_THINKING_TYPE: AnthropicThinkingType = 'adaptive'

/**
 * 默认模式取 `fallback`。
 *
 * 这正是 V10 之前后端那段硬编码的行为（下游没带 `thinking` 就补一个 `adaptive`），
 * 选它作默认使升级前后的出站请求体完全一致。
 */
export const DEFAULT_ANTHROPIC_THINKING_OVERWRITE_MODE: AnthropicThinkingOverwriteMode = 'fallback'

/**
 * 预算的「未设置」哨兵，与后端 `AnthropicThinkingSetting.UNSET_BUDGET_TOKENS` 一致。
 *
 * 取 -1 而非 0 是因为 0 看着像个真实值，而负数一眼就是哨兵。
 *
 * <p>这个值在输入框里是**可见且可输入**的。曾经把它渲染成空串，理由是
 * 「显示 -1 会让用户以为那是个需要理解的配置」—— 但它确实是库里真实存着的值，
 * 隐起来反而让对着数据库看的人以为“填了存不上”。现在原样展示，含义由提示文案解释。
 */
export const UNSET_THINKING_BUDGET_TOKENS = -1

/**
 * 输入框允许的字符：可选的单个前导负号加十进制数字。
 *
 * 空串必须放行，否则用户无法删到空；单独一个 `-` 也放行，否则没法输入负数的
 * 第一个字符。两者都不是合法的最终值，由序列化折成哨兵。
 */
const BUDGET_INPUT_PATTERN = /^-?\d*$/

/**
 * 输入框的字符过滤（给 `n-input` 的 `allow-input`）。
 *
 * 只限这一个框：旁边的「最大输出」框只接受正整数，且带预设下拉与滚轮步进，
 * 不存在要手输负号的情形；本框的哨兵是个要能手输的负数，所以口径不同。
 *
 * <p>过滤而非提交时默默修正：`abc` 敲下去看着像没反应、存下去又变成了另一个值，
 * 直接不接受输入是更诚实的反馈。
 */
export function isAnthropicThinkingBudgetInputAllowed(value: string): boolean {
  return BUDGET_INPUT_PATTERN.test(value)
}

/**
 * 编辑模型：三个字段，序列化成两个出站值。
 *
 * `budgetTokens` 是字符串而非数字，因为它直接绑 `n-input` —— 用户清空输入框时
 * 那是空串而非 0，两者语义不同（前者是「没填」，后者是「填了个 0」）。
 */
export interface AnthropicThinkingConfig {
  type: AnthropicThinkingType
  mode: AnthropicThinkingOverwriteMode
  budgetTokens: string
}

export const DEFAULT_ANTHROPIC_THINKING_CONFIG: Readonly<AnthropicThinkingConfig> = {
  type: DEFAULT_ANTHROPIC_THINKING_TYPE,
  mode: DEFAULT_ANTHROPIC_THINKING_OVERWRITE_MODE,
  budgetTokens: '',
}

/** 只有 enabled 的手动预算形态允许编辑预算。 */
export function anthropicThinkingUsesBudget(type: AnthropicThinkingType): boolean {
  return type === 'enabled'
}

/** 认不出的形态回退默认，而不是让整条配置失效。 */
function canonicalizeType(value: unknown): AnthropicThinkingType | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim().toLowerCase()
  return ANTHROPIC_THINKING_TYPE_OPTIONS.find(option => option === normalized) ?? null
}

/**
 * 交给基座的字段特有规则。
 *
 * 没有 `parseLegacy`：这一列是 V10 才新增的，不存在历史裸值形态 ——
 * 与背着 `"None"` 和逗号多值的思考深度不同。
 */
const CODEC: ModeScopedCodec<AnthropicThinkingType, AnthropicThinkingOverwriteMode> = {
  modes: ANTHROPIC_THINKING_OVERWRITE_MODES,
  defaultMode: DEFAULT_ANTHROPIC_THINKING_OVERWRITE_MODE,
  defaultValue: DEFAULT_ANTHROPIC_THINKING_TYPE,
  valueKey: 'thinking_type',
  canonicalizeValue: canonicalizeType,
}

/**
 * 解析持久化的思考方式配置。
 *
 * @param rawMode   `thinking_mode` 列的原文
 * @param rawBudget `thinking_budget_tokens` 列的值；哨兵与非正数都渲染成空输入框
 */
export function parseAnthropicThinkingConfig(
  rawMode: unknown,
  rawBudget: unknown,
): AnthropicThinkingConfig {
  const { value, mode } = parseModeScopedValue(rawMode, CODEC)
  return { type: value, mode, budgetTokens: formatBudgetForInput(rawBudget) }
}

/**
 * 把持久化的预算渲染成输入框内容。
 *
 * 三类取值：
 * - **正整数**：原样展示。
 * - **哨兵 -1**：也原样展示。它是库里真实存着的值，隐成空框会让对着
 *   数据库看的人以为保存失败了。
 * - **其余**（`null`、`0`、其他负数、非数字）：空串。这些都不是列约束放行的
 *   形态，展示它们等于把脏数据当成配置；空框提交后会被归一到哨兵。
 */
function formatBudgetForInput(raw: unknown): string {
  const parsed = typeof raw === 'number' ? raw : Number.parseInt(String(raw ?? '').trim(), 10)
  if (!Number.isFinite(parsed)) return ''
  const truncated = Math.trunc(parsed)
  if (truncated > 0) return String(truncated)
  return truncated === UNSET_THINKING_BUDGET_TOKENS ? String(UNSET_THINKING_BUDGET_TOKENS) : ''
}

/** 序列化形态与模式为 `thinking_mode` 列的 JSON。**不含预算**。 */
export function serializeAnthropicThinkingMode(config: AnthropicThinkingConfig): string {
  return serializeModeScopedValue({ value: config.type, mode: config.mode }, CODEC)
}

/**
 * 序列化预算为 `thinking_budget_tokens` 列的整数。
 *
 * 空输入框、孤立的 `-`、`0`、其他负数与非数字都折成哨兵：列约束只放行正数与 -1。
 * 手输 `-1` 与清空因此得到同一个结果 —— 两者表达的本来就是同一个意图（未设置），
 * 只是输入方式不同。
 */
export function serializeAnthropicThinkingBudget(config: AnthropicThinkingConfig): number {
  const parsed = Number.parseInt(config.budgetTokens.trim(), 10)
  return Number.isFinite(parsed) && parsed > 0
    ? Math.trunc(parsed)
    : UNSET_THINKING_BUDGET_TOKENS
}

/**
 * 取轮转顺序里的下一个模式。
 *
 * 认不出的值从头开始而非原地不动 —— 后者会让按钮看起来是坏的。
 */
export function nextAnthropicThinkingOverwriteMode(
  current: AnthropicThinkingOverwriteMode,
): AnthropicThinkingOverwriteMode {
  return nextOverwriteMode(current, ANTHROPIC_THINKING_OVERWRITE_MODES)
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
