/**
 * 思考深度的注入模式与持久化形态。
 *
 * <h2>为何需要「模式」这一维度</h2>
 * 思考深度与上下文、工具、视觉不同：后三者只向 Ollama 发现接口声明能力，
 * 而思考深度会**真正进入发往上游的请求体**。一旦如此，「下游自己带了这个字段怎么办」
 * 就成了一个必须回答的问题，而单个档位值无法表达答案。
 *
 * <p>旧形态是纯档位字符串（如 `"Medium"`），其中 `"None"` 被借用来表达「不发送」——
 * 一个取值同时承担了「档位」与「是否发送」两件事。拆成 `{effort, mode}` 后，
 * 档位在四种模式下都保持有意义（`delete` 时它是备选值，用户切回其它模式时无需重新选）。
 *
 * <h2>与基座的分工</h2>
 * 模式清单、轮转、解析与序列化的骨架在 `overwriteMode.ts`；
 * 这里只留思考深度真正独有的东西：档位清单、大小写归一化、
 * `"None"` 与逗号分隔多值那两个历史包袍，以及出站时转小写。
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
 * 思考深度的注入模式。
 *
 * 四者的区别只在「下游带了值时用谁的」与「下游没带时是否补」这两问上：
 *
 * |             | 下游带了值   | 下游没带     |
 * |-------------|-------------|-------------|
 * | override    | 用配置的档位 | 用配置的档位 |
 * | fallback    | 用下游的值   | 用配置的档位 |
 * | passthrough | 用下游的值   | 不发送       |
 * | delete      | 移除         | 不发送       |
 *
 * `passthrough` 与 `delete` 只在下游带了值时不同：前者尊重它，后者连它一起丢掉。
 * 后者是必要的 —— 某些上游收到不认识的 `reasoning_effort` 会直接 400，
 * 那时候必须能强制剥离，而不是指望下游不发。
 */
export type ReasoningOverwriteMode = OverwriteMode

/**
 * 轮转顺序。
 *
 * 按「代理干预程度」递减排列：覆写完全接管、兜底只补缺、透传完全不管、删除强制剥离。
 * 前三档的语义变化因此是连续的；delete 排在末尾，因为它不是「更不干预」而是另一种干预。
 *
 * <p>这是唯一用满四档的字段 —— 最大输出只有前两档，见 `maxOutput.ts`。
 */
export const REASONING_OVERWRITE_MODES: readonly ReasoningOverwriteMode[] = [
  'override',
  'fallback',
  'passthrough',
  'delete',
]

/** 标签全局统一，直接取基座的那一份。 */
export const REASONING_OVERWRITE_MODE_LABELS = OVERWRITE_MODE_LABELS

/** 悬停说明。四个模式的差别只在两问上，文案必须同时点明。 */
export const REASONING_OVERWRITE_MODE_HINTS = buildOverwriteModeHints('档位')

/**
 * 默认模式取 `fallback`。
 *
 * 与后端 V2 之前的行为一致（`AbstractUpstreamChatService` 只在下游未携带时才注入），
 * 因此存量模型在迁移后行为不变 —— 默认值的职责是保持现状，而非表达推荐做法。
 */
export const DEFAULT_REASONING_OVERWRITE_MODE: ReasoningOverwriteMode = 'fallback'

/** 可选档位。不含 `None` —— 它的语义已由 `delete` 模式承担。 */
export const REASONING_EFFORT_OPTIONS: readonly string[] = [
  'Low',
  'Medium',
  'High',
  'Xhigh',
  'Max',
]

export const DEFAULT_REASONING_EFFORT = 'Medium'

/** 思考深度的完整配置。 */
export interface ReasoningEffortConfig {
  /** 档位，取值来自 {@link REASONING_EFFORT_OPTIONS}。 */
  effort: string
  mode: ReasoningOverwriteMode
}

/**
 * 把任意大小写的档位映射回选项形态。
 *
 * 数据库里存的是首字母大写（`"Medium"`），而 JSON 形态按小写存、后端也按小写发给上游；
 * 归一化集中在这里，避免各处各写一遍大小写判断。认不出的值回退默认档位而非原样保留：
 * 下拉框只认选项里的值，塞一个陌生值进去会显示成空白。
 */
function canonicalizeEffort(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  if (!trimmed) return null
  const matched = REASONING_EFFORT_OPTIONS.find(
    option => option.toLowerCase() === trimmed.toLowerCase(),
  )
  return matched ?? null
}

/**
 * 交给基座的字段特有规则。
 *
 * `parseLegacy` 处理这一列的两个历史包袱：逗号分隔多值（只取第一项，表单不支持多选）
 * 与 `"None"`（映射为 `delete`）。后者是必须的 —— 详见 {@link parseReasoningEffortConfig}。
 */
const CODEC: ModeScopedCodec<string, ReasoningOverwriteMode> = {
  modes: REASONING_OVERWRITE_MODES,
  defaultMode: DEFAULT_REASONING_OVERWRITE_MODE,
  defaultValue: DEFAULT_REASONING_EFFORT,
  valueKey: 'reasoning_effort',
  canonicalizeValue: canonicalizeEffort,
  parseLegacy: raw => {
    const first = raw.split(',')[0].trim()
    if (first.toLowerCase() === 'none') {
      return { value: DEFAULT_REASONING_EFFORT, mode: 'delete' }
    }
    return {
      value: canonicalizeEffort(first) ?? DEFAULT_REASONING_EFFORT,
      mode: DEFAULT_REASONING_OVERWRITE_MODE,
    }
  },
}

/**
 * 解析持久化的思考深度配置，兼容全部历史形态。
 *
 * 依次尝试：
 * 1. **V2 JSON**：`{"reasoning_effort":"medium","overwrite_mode":"fallback"}`
 * 2. **旧的纯档位**：`"Medium"` → 档位 + 兜底
 * 3. **更旧的逗号分隔多值**：`"Medium,High"` —— 只取第一项，表单不支持多选
 * 4. **旧的 `"None"`**：映射为 `mode: 'delete'`，档位取默认
 *
 * <p>第 4 条是这个函数存在的主要理由：旧的 `None` 表达的是「不向上游发送」，
 * 对应 `delete`。若把它当作认不出的档位回退成默认（`fallback`），
 * 那些模型会突然开始向上游发送 `medium` —— 一次纯粹的读取行为改变了运行时行为，
 * 且用户无从察觉。
 *
 * <p>任何解析失败都回退到默认值而不抛错：这个字段来自数据库，
 * 一行脏数据不该让整个模型列表无法编辑。
 */
export function parseReasoningEffortConfig(raw: unknown): ReasoningEffortConfig {
  const { value, mode } = parseModeScopedValue(raw, CODEC)
  return { effort: value, mode }
}

/**
 * 序列化成持久化用的 JSON 字符串。
 *
 * 档位转小写：上游协议里 `reasoning_effort` 的取值是小写的，存小写可以让后端
 * 直接取用而不必再转一次 —— 少一个转换点就少一处可能漏掉的地方。
 */
export function serializeReasoningEffortConfig(config: ReasoningEffortConfig): string {
  return serializeModeScopedValue(
    { value: config.effort, mode: config.mode },
    CODEC,
    effort => effort.toLowerCase(),
  )
}

/** 轮转到下一个模式，到末尾回到开头。 */
export function nextReasoningOverwriteMode(mode: ReasoningOverwriteMode): ReasoningOverwriteMode {
  return nextOverwriteMode(mode, REASONING_OVERWRITE_MODES)
}
