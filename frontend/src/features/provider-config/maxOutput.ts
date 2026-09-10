/**
 * 最大输出的注入模式与持久化形态。
 *
 * <h2>为何也需要「模式」这一维度</h2>
 * 与思考深度同理：这个值会**进入发往上游的请求体**（Anthropic 的 `max_tokens` 必填，
 * OpenAI 的同名字段可选），于是「下游自己带了怎么办」必须有个答案，而单个数字无法表达答案。
 *
 * <p>但模式只有两档，比思考深度少两个 —— 见 {@link MAX_OUTPUT_OVERWRITE_MODES}。
 *
 * <h2>持久化形态</h2>
 * 存在 `provider_model.max_output_tokens` 一列里，V9 起是 JSON：
 * `{"max_output_tokens":4000,"overwrite_mode":"fallback"}`。
 * 该列在 V9 之前是 INTEGER，V9 重建表把它换成带 `json_valid` 约束的 TEXT。
 *
 * <h2>与基座的分工</h2>
 * 模式转转、解析与序列化的骨架在 `overwriteMode.ts`；这里只留本字段独有的：
 * 只支持前两档模式、正整数归一化、预设清单。
 */

import {
  OVERWRITE_MODE_LABELS,
  buildOverwriteModeHints,
  nextOverwriteMode,
  parseModeScopedValue,
  serializeModeScopedValue,
  type ModeScopedCodec,
  type OverwriteMode,
} from './overwriteMode'

/**
 * 最大输出的注入模式。
 *
 * |          | 下游带了值   | 下游没带     |
 * |----------|-------------|-------------|
 * | override | 用配置的上限 | 用配置的上限 |
 * | fallback | 用下游的值   | 用配置的上限 |
 *
 * 没有 `passthrough` 与 `delete`：Anthropic 侧 `max_tokens` 缺失会直接 400，
 * 「下游没带也不补」或「强制剥离」在那条线路上等于必然失败。
 * 少两档不是简化，而是这两档在本字段上没有对应的真实意图。
 */
export type MaxOutputOverwriteMode = Extract<OverwriteMode, 'override' | 'fallback'>

/** 转转顺序，按代理干预程度递减 —— 与思考深度的前两档一致。 */
export const MAX_OUTPUT_OVERWRITE_MODES: readonly MaxOutputOverwriteMode[] = [
  'override',
  'fallback',
]

/**
 * 标签取基座的全集。
 *
 * 不裁成两档：`Record` 多两个用不到的键无害，而这两档的文案必须与思考深度
 * 完全一致 —— 分两份维护只会让它们漂移。
 */
export const MAX_OUTPUT_OVERWRITE_MODE_LABELS = OVERWRITE_MODE_LABELS

export const MAX_OUTPUT_OVERWRITE_MODE_HINTS = buildOverwriteModeHints('上限')

/**
 * 默认模式取 `fallback`。
 *
 * V9 之前这个值压根没进过上游请求体，因此「下游带了就听它的」最接近「什么都没变」。
 */
export const DEFAULT_MAX_OUTPUT_OVERWRITE_MODE: MaxOutputOverwriteMode = 'fallback'

/**
 * 默认上限取 64000，对齐 Claude CLI 的默认请求上限。
 *
 * 必须与后端 `MaxOutputTokensSetting.DEFAULT_MAX_OUTPUT_TOKENS` 保持一致：
 * 两侧不一致时新建模型会出现「界面显示一个值、存进去又是另一个」的错位。
 */
export const DEFAULT_MAX_OUTPUT_TOKENS = 64000

/**
 * 可选预设。
 *
 * K 一律按十进制换算（`4K = 4000`），与上下文预设的既有口径一致。
 * 上游文档里的 4096、8192 那类二进制值不在预设里，但用户可以手填 ——
 * 迁移也把它们当作「非标值」原样保留。
 */
export const MAX_OUTPUT_PRESETS: readonly { label: string, value: number }[] = [
  { label: '128K', value: 128000 },
  { label: '64K', value: 64000 },
  { label: '32K', value: 32000 },
  { label: '16K', value: 16000 },
  { label: '8K', value: 8000 },
  { label: '4K', value: 4000 },
]

/** 最大输出的完整配置。 */
export interface MaxOutputConfig {
  maxOutputTokens: number
  mode: MaxOutputOverwriteMode
}

/**
 * 归一化 token 上限。
 *
 * 非正数一律回退默认值：0 在 V9 之前是合法的（列约束只要求 `>= 0`），语义上表示
 * 「未配置」。到了 V9 这个语义由模式承担，于是 0 不再需要单独表达。
 */
function canonicalizeTokens(value: unknown): number | null {
  const parsed = typeof value === 'number' ? value : Number.parseInt(String(value ?? '').trim(), 10)
  return Number.isFinite(parsed) && parsed > 0 ? Math.trunc(parsed) : null
}

/**
 * 交给基座的字段特有规则。
 *
 * `parseLegacy` 只需处理裸整数（V9 之前该列是 INTEGER）—— 比思考深度简单得多，
 * 那一列还背着 `"None"` 与逗号分隔多值两个包袱。
 */
const CODEC: ModeScopedCodec<number, MaxOutputOverwriteMode> = {
  modes: MAX_OUTPUT_OVERWRITE_MODES,
  defaultMode: DEFAULT_MAX_OUTPUT_OVERWRITE_MODE,
  defaultValue: DEFAULT_MAX_OUTPUT_TOKENS,
  valueKey: 'max_output_tokens',
  canonicalizeValue: canonicalizeTokens,
  parseLegacy: raw => ({
    value: canonicalizeTokens(raw) ?? DEFAULT_MAX_OUTPUT_TOKENS,
    mode: DEFAULT_MAX_OUTPUT_OVERWRITE_MODE,
  }),
}

/**
 * 解析持久化的最大输出配置，兼容 V9 之前的裸整数形态。
 *
 * 依次尝试：
 * 1. **V9 JSON**：`{"max_output_tokens":4000,"overwrite_mode":"fallback"}`
 * 2. **旧的裸整数**：`"128000"` → 该值 + 兜底模式
 *
 * <p>这里**不做**「128K 降为 4K」那类档位重映射：那是 V9 迁移的一次性语义调整，
 * 只该发生在迁移里。读取路径若也做同样的映射，任何一个手工设成 128000 的值都会在
 * 每次读取时变成 4000，而用户看到的界面值与自己填的不一致。
 *
 * <p>任何解析失败都回退到默认值而不抛错：这个字段来自数据库，
 * 一行脏数据不该让整个模型列表无法编辑。
 */
export function parseMaxOutputConfig(raw: unknown): MaxOutputConfig {
  const { value, mode } = parseModeScopedValue(raw, CODEC)
  return { maxOutputTokens: value, mode }
}

/**
 * 序列化成持久化用的 JSON 字符串。
 *
 * token 上限写成 JSON 数字而非字符串：迁移与界面都用 `json_extract` 直接取整数。
 */
export function serializeMaxOutputConfig(config: MaxOutputConfig): string {
  return serializeModeScopedValue({ value: config.maxOutputTokens, mode: config.mode }, CODEC)
}

/**
 * 取轮转顺序里的下一个模式。
 *
 * 认不出的值从头开始而非原地不动 —— 后者会让按钮看起来是坏的。
 */
export function nextMaxOutputOverwriteMode(current: MaxOutputOverwriteMode): MaxOutputOverwriteMode {
  return nextOverwriteMode(current, MAX_OUTPUT_OVERWRITE_MODES)
}
