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
 * 「删除」不再占用档位取值，档位在三种模式下都保持有意义（`delete` 时它是备选值，
 * 用户切回其它模式时无需重新选）。
 */

/**
 * 思考深度的注入模式。
 *
 * - `override`：始终用配置的档位，覆盖下游请求携带的值
 * - `passthrough`：下游带了就用它的，没带才用配置的档位（后端当前行为）
 * - `delete`：始终不向上游发送该字段，由上游自己决定
 */
export type ReasoningOverwriteMode = 'override' | 'passthrough' | 'delete'

/** 轮转顺序。按「强度」递减排列，切换时的语义变化是连续的。 */
export const REASONING_OVERWRITE_MODES: readonly ReasoningOverwriteMode[] = [
  'override',
  'passthrough',
  'delete',
]

export const REASONING_OVERWRITE_MODE_LABELS: Record<ReasoningOverwriteMode, string> = {
  override: '覆写',
  passthrough: '透传',
  delete: '删除',
}

/** 悬停说明。三个模式的差别只在「下游带了值时怎么办」，文案必须点明这一点。 */
export const REASONING_OVERWRITE_MODE_HINTS: Record<ReasoningOverwriteMode, string> = {
  override: '始终使用此处配置的档位，忽略下游请求携带的值',
  passthrough: '下游请求带了就用它的值，没带才使用此处配置的档位',
  delete: '始终不向上游发送思考深度字段，由上游自行决定',
}

/**
 * 默认模式取 `passthrough`。
 *
 * 与后端当前行为一致（`AbstractUpstreamChatService` 只在下游未携带时才注入），
 * 因此存量模型在迁移后行为不变 —— 默认值的职责是保持现状，而非表达推荐做法。
 */
export const DEFAULT_REASONING_OVERWRITE_MODE: ReasoningOverwriteMode = 'passthrough'

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

function isOverwriteMode(value: unknown): value is ReasoningOverwriteMode {
  return typeof value === 'string'
    && (REASONING_OVERWRITE_MODES as readonly string[]).includes(value)
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
 * 解析持久化的思考深度配置，兼容全部历史形态。
 *
 * 依次尝试：
 * 1. **V2 JSON**：`{"reasoning_effort":"medium","overwrite_mode":"delete"}`
 * 2. **旧的纯档位**：`"Medium"`
 * 3. **更旧的逗号分隔多值**：`"Medium,High"` —— 只取第一项，表单不支持多选
 * 4. **旧的 `"None"`**：映射为 `mode: 'delete'`，档位取默认
 *
 * <p>第 4 条是这个函数存在的主要理由：`None` 表达的是「不发送」，
 * 若只按认不出的档位回退成 `Medium`，那些模型会突然开始向上游发送 `medium`——
 * 一次纯粹的读取行为改变了运行时行为，且用户无从察觉。
 *
 * <p>任何解析失败都回退到默认值而不抛错：这个字段来自数据库，
 * 一行脏数据不该让整个模型列表无法编辑。
 */
export function parseReasoningEffortConfig(raw: unknown): ReasoningEffortConfig {
  const fallback: ReasoningEffortConfig = {
    effort: DEFAULT_REASONING_EFFORT,
    mode: DEFAULT_REASONING_OVERWRITE_MODE,
  }
  if (typeof raw !== 'string' || !raw.trim()) {
    return fallback
  }
  const trimmed = raw.trim()

  if (trimmed.startsWith('{')) {
    try {
      const parsed = JSON.parse(trimmed) as Record<string, unknown>
      return {
        effort: canonicalizeEffort(parsed.reasoning_effort) ?? DEFAULT_REASONING_EFFORT,
        mode: isOverwriteMode(parsed.overwrite_mode)
          ? parsed.overwrite_mode
          : DEFAULT_REASONING_OVERWRITE_MODE,
      }
    } catch {
      return fallback
    }
  }

  const first = trimmed.split(',')[0].trim()
  if (first.toLowerCase() === 'none') {
    return { effort: DEFAULT_REASONING_EFFORT, mode: 'delete' }
  }
  return {
    effort: canonicalizeEffort(first) ?? DEFAULT_REASONING_EFFORT,
    mode: DEFAULT_REASONING_OVERWRITE_MODE,
  }
}

/**
 * 序列化成持久化用的 JSON 字符串。
 *
 * 档位转小写：上游协议里 `reasoning_effort` 的取值是小写的，存小写可以让后端
 * 直接取用而不必再转一次 —— 少一个转换点就少一处可能漏掉的地方。
 */
export function serializeReasoningEffortConfig(config: ReasoningEffortConfig): string {
  return JSON.stringify({
    reasoning_effort: config.effort.toLowerCase(),
    overwrite_mode: config.mode,
  })
}

/** 轮转到下一个模式，到末尾回到开头。 */
export function nextReasoningOverwriteMode(mode: ReasoningOverwriteMode): ReasoningOverwriteMode {
  const index = REASONING_OVERWRITE_MODES.indexOf(mode)
  // 认不出的值当作从头开始，而不是原地不动 —— 后者会让按钮看起来是坏的。
  if (index < 0) return REASONING_OVERWRITE_MODES[0]
  return REASONING_OVERWRITE_MODES[(index + 1) % REASONING_OVERWRITE_MODES.length]
}
