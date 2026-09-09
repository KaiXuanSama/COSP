import type { ProviderModel } from '@/stores/providers'
import {
  parseAnthropicThinkingConfig,
  serializeAnthropicThinkingBudget,
  serializeAnthropicThinkingMode,
} from './anthropicThinking'
import {
  parseMaxOutputConfig,
  serializeMaxOutputConfig,
} from './maxOutput'
import {
  parseReasoningEffortConfig,
  serializeReasoningEffortConfig,
} from './reasoningEffort'

/**
 * 上游模型列表的解析与表单模型的构造。
 *
 * 上游 `/v1/models` 的响应形状在各供应商间差异很大，这里做宽松解析：
 * 既接受标准 OpenAI 的 `{ data: [{ id }] }`，也接受裸数组、`{ models: [...] }`、
 * 以及元素直接是字符串的形式。宽松是有意的 —— 拉取模型是辅助功能，
 * 能识别多少就用多少，好过因为形状不匹配整个失败。
 */

/** 表单中可编辑的模型行。字段为字符串，因为直接绑定到 n-input。 */
export interface EditableModel {
  modelName: string
  enabled: boolean
  contextSize: string
  maxOutputTokens: string
  capsTools: boolean
  capsVision: boolean
  reasoningEffort: string
  /**
   * Anthropic 思考方式（形态 + 注入模式）的 JSON 原文。
   *
   * 与 {@link reasoningEffort} 同理，存序列化后的 JSON 而不拆成两个字段 ——
   * 它要原样回传给后端的同一个表列。
   */
  thinkingMode: string
  /**
   * 思考预算，字符串形态以直接绑 `n-input`。
   *
   * 空串表示未设置，提交时由 `serializeAnthropicThinkingBudget` 折成哨兵。
   * 它不并进 {@link thinkingMode} 的 JSON：预算没有自己的注入模式，
   * 在库里就是独立的裸整数列。
   */
  thinkingBudgetTokens: string
  [key: string]: unknown
}

/** 新建模型行的上下文默认值。最大输出的默认值在 `maxOutput.ts` 里。 */
const DEFAULT_CONTEXT_SIZE = '128000'

/**
 * 归一化 `reasoningEffort` 为 V2 JSON 字符串。
 *
 * 表单里这个字段始终是序列化后的 JSON（`{reasoning_effort, overwrite_mode}`）,
 * 而非拆成两个字段：它要原样回传给后端的同一个表列，拆开后在提交前又得拼回去，
 * 多一道可能与解析侧不一致的工序。组件里需要分开编辑时再解一次。
 *
 * <p>历史形态（纯档位、逗号多值、`None`）全由 `parseReasoningEffortConfig` 处理。
 */
function normalizeReasoningEffort(value: unknown): string {
  return serializeReasoningEffortConfig(parseReasoningEffortConfig(value))
}

/**
 * 归一化 `maxOutputTokens` 为 V9 JSON 字符串，与思考深度同理。
 *
 * <p>历史形态（裸整数、空值、0）全由 `parseMaxOutputConfig` 处理。
 */
function normalizeMaxOutput(value: unknown): string {
  return serializeMaxOutputConfig(parseMaxOutputConfig(value))
}

/**
 * 归一化思考方式为 V10 JSON 字符串，与上两者同理。
 *
 * <p>预算不参与这一步 —— 它存在另一列，由 {@link normalizeThinkingBudget} 处理。
 */
function normalizeThinkingMode(value: unknown): string {
  return serializeAnthropicThinkingMode(parseAnthropicThinkingConfig(value, null))
}

/**
 * 归一化思考预算为输入框内容。
 *
 * <p>缺失与无法识别的值都渲染成哨兵 `-1` 而非空串：这两种情形提交后存进库里的
 * 就是 `-1`，显示空框会让对着数据库看的人以为两边不一致。新建模型行因此也直接
 * 展示 `-1`，与列默认值对得上。
 *
 * <p>用户在编辑过程中把框清空仍然保持空 —— 本函数只在加载 / 构造 / 提交时跑，
 * 不会在敲字时把光标位置顶掉。
 */
function normalizeThinkingBudget(value: unknown): string {
  const config = parseAnthropicThinkingConfig(null, value)
  return config.budgetTokens || String(serializeAnthropicThinkingBudget(config))
}

/**
 * 构造一行可编辑模型，未提供的字段取默认值。
 *
 * `source` 里的其余字段会被保留（展开在前），使拉取模型时能带回已有配置，
 * 用户不必对未变更的模型重新设置能力位。
 */
export function buildEditableModel(
  modelName = '',
  source: Record<string, unknown> = {},
): EditableModel {
  return {
    ...source,
    modelName,
    enabled: (source.enabled as boolean) ?? true,
    contextSize: String(source.contextSize ?? DEFAULT_CONTEXT_SIZE),
    maxOutputTokens: normalizeMaxOutput(source.maxOutputTokens),
    capsTools: (source.capsTools as boolean) ?? true,
    capsVision: (source.capsVision as boolean) ?? false,
    reasoningEffort: normalizeReasoningEffort(source.reasoningEffort),
    // 新建行的默认值就是两个归一化函数对 undefined 的产出：
    // adaptive + 兜底 + 预算空，与数据库列默认值逐一对应。
    thinkingMode: normalizeThinkingMode(source.thinkingMode),
    thinkingBudgetTokens: normalizeThinkingBudget(source.thinkingBudgetTokens),
  }
}

/**
 * 把后端返回的模型转成表单可编辑形态。
 *
 * 与 `buildEditableModel` 的差别是 `contextSize` 缺省值为 `'0'`：
 * 已保存的模型若真的是 0，应当原样呈现而非被悄悄改成 128K。
 */
export function toEditableModel(model: ProviderModel): EditableModel {
  return {
    ...model,
    contextSize: String(model.contextSize ?? '0'),
    maxOutputTokens: normalizeMaxOutput(model.maxOutputTokens),
    reasoningEffort: normalizeReasoningEffort(model.reasoningEffort),
    thinkingMode: normalizeThinkingMode(model.thinkingMode),
    thinkingBudgetTokens: normalizeThinkingBudget(model.thinkingBudgetTokens),
  }
}

/** 从单个元素里取模型名，依次尝试 id / model / name。 */
function pickModelName(item: Record<string, unknown>): string | null {
  for (const key of ['id', 'model', 'name']) {
    const value = item[key]
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  return null
}

/**
 * 从上游响应中提取模型名列表，去重且保持出现顺序。
 *
 * 接受的形状：
 * 1. JSON 字符串（会先尝试 parse，失败则返回空）
 * 2. 裸数组：`["a", "b"]` 或 `[{ id: "a" }]`
 * 3. 对象：`{ data: [...] }`（OpenAI 标准）或 `{ models: [...] }`（部分中转站）
 *
 * 无法识别时返回空数组而不抛错 —— 调用方据此提示「未拉取到模型」。
 */
export function extractModelNames(payload: unknown): string[] {
  let parsed = payload
  if (typeof parsed === 'string') {
    try {
      parsed = JSON.parse(parsed)
    } catch {
      return []
    }
  }

  const names = new Set<string>()

  const collect = (items: unknown) => {
    if (!Array.isArray(items)) return
    for (const item of items) {
      if (typeof item === 'string') {
        const value = item.trim()
        if (value) names.add(value)
        continue
      }
      if (!item || typeof item !== 'object') continue
      const name = pickModelName(item as Record<string, unknown>)
      if (name) names.add(name)
    }
  }

  if (Array.isArray(parsed)) {
    collect(parsed)
  } else if (parsed && typeof parsed === 'object') {
    const source = parsed as Record<string, unknown>
    collect(source.data)
    collect(source.models)
  }

  return Array.from(names)
}

/**
 * 序列化模型列表为后端 form 需要的索引式扁平键。
 *
 * 后端 `ProviderAdminService#parseModels` 靠扫 `models[N].` 前缀还原列表，
 * 布尔值约定为 `'on'` / `''` 而非 `true` / `false`。
 * `reasoningEffort` 为空时**不下发该键**，让后端用它自己的默认值。
 */
export function toModelFormParams(models: EditableModel[]): Record<string, string> {
  const params: Record<string, string> = {}
  models.forEach((model, index) => {
    const prefix = `models[${index}].`
    params[`${prefix}name`] = model.modelName
    params[`${prefix}enabled`] = model.enabled ? 'on' : ''
    params[`${prefix}contextSize`] = model.contextSize || '0'
    // 空值也走一遍归一化而非直接给字面默认值：后端 parseModels 会再 parse 一次，
    // 两侧的默认值定义在同一个常量上，不必在这里重复一份。
    params[`${prefix}maxOutputTokens`] = normalizeMaxOutput(model.maxOutputTokens)
    params[`${prefix}capsTools`] = model.capsTools ? 'on' : ''
    params[`${prefix}capsVision`] = model.capsVision ? 'on' : ''
    if (model.reasoningEffort) {
      params[`${prefix}reasoningEffort`] = model.reasoningEffort
    }
    // 与最大输出同理：空值也走一遗归一化而非省略键。预算必须始终下发 ——
    // 省略它会让后端拿不到「用户把预算清空了」这个意图，旧值会留在库里。
    params[`${prefix}thinkingMode`] = normalizeThinkingMode(model.thinkingMode)
    params[`${prefix}thinkingBudgetTokens`] = String(
      serializeAnthropicThinkingBudget(parseAnthropicThinkingConfig(
        model.thinkingMode, model.thinkingBudgetTokens)))
  })
  return params
}
