import type { ProviderModel } from '@/stores/providers'

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
  [key: string]: unknown
}

/** 新建模型行的默认值。上下文与最大输出都按 128K 起步。 */
const DEFAULT_CONTEXT_SIZE = '128000'
const DEFAULT_MAX_OUTPUT_TOKENS = '128000'
const DEFAULT_REASONING_EFFORT = 'Medium'

/**
 * 归一化 `reasoningEffort`。
 *
 * 历史数据里该字段可能是逗号分隔的多值（如 `"Medium,High"`），表单只取第一项。
 * 空值或非字符串回退默认档位。
 */
function normalizeReasoningEffort(value: unknown): string {
  if (typeof value === 'string' && value.trim()) {
    return value.split(',')[0].trim()
  }
  return DEFAULT_REASONING_EFFORT
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
    maxOutputTokens: String(source.maxOutputTokens ?? DEFAULT_MAX_OUTPUT_TOKENS),
    capsTools: (source.capsTools as boolean) ?? true,
    capsVision: (source.capsVision as boolean) ?? false,
    reasoningEffort: normalizeReasoningEffort(source.reasoningEffort),
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
    maxOutputTokens: String(model.maxOutputTokens ?? DEFAULT_MAX_OUTPUT_TOKENS),
    reasoningEffort: normalizeReasoningEffort(model.reasoningEffort),
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
    params[`${prefix}maxOutputTokens`] = model.maxOutputTokens || DEFAULT_MAX_OUTPUT_TOKENS
    params[`${prefix}capsTools`] = model.capsTools ? 'on' : ''
    params[`${prefix}capsVision`] = model.capsVision ? 'on' : ''
    if (model.reasoningEffort) {
      params[`${prefix}reasoningEffort`] = model.reasoningEffort
    }
  })
  return params
}
