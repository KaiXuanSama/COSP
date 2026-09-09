/**
 * 「注入模式 + 值」这类字段的共享基座。
 *
 * <h2>为什么会有这一层</h2>
 * 凡是**会进入发往上游请求体**的模型参数（思考深度、最大输出，将来的思考预算），
 * 都绕不开同一个问题：「下游自己带了这个字段怎么办」。单个值无法表达答案，
 * 于是每个这类字段都变成 `{值, 模式}` 的二元组，并且都要：
 *
 * - 有一份模式清单、中文标签与悬停说明
 * - 能在清单里轮转到下一档
 * - 从数据库列解析出二元组（还要兼容该列的历史裸值形态）
 * - 序列化回 JSON 存库
 *
 * <p>`reasoningEffort.ts` 与 `maxOutput.ts` 各自实现过一遍，两份代码逐行同构 ——
 * 连 `canonicalizeMode` 都完全一样。这里把共享部分提出来，
 * 各字段模块只保留自己**真正独有**的东西：模式清单、值的归一化规则、JSON 字段名。
 *
 * <h2>这一层不做什么</h2>
 * 不统一模式清单。思考深度有四档、最大输出只有两档 —— 后者少两档不是简化，
 * 而是 `passthrough` / `delete` 在 `max_tokens` 上没有对应的真实意图
 * （Anthropic 侧该字段缺失会直接 400）。清单是各字段的领域事实，不该被基座拉平。
 *
 * <p>也不统一默认模式与默认值：它们的取值理由各不相同（都是「保持迁移前的行为」，
 * 但迁移前的行为本身不同）。基座只提供机制。
 */

/** 所有已知的注入模式。各字段从中取自己支持的子集。 */
export type OverwriteMode = 'override' | 'fallback' | 'passthrough' | 'delete'

/**
 * 模式的中文标签与悬停说明。
 *
 * 标签全局统一：同一个词在不同字段上表示同一种干预方式，
 * 若「兜底」在一栏叫兜底、在另一栏叫别的，用户得分别记两套。
 *
 * <p>说明文案不放在这里 —— 它必须点明「使用此处配置的<b>什么</b>」，
 * 而那个词随字段变化（档位 / 上限 / 预算）。见 {@link buildOverwriteModeHints}。
 */
export const OVERWRITE_MODE_LABELS: Record<OverwriteMode, string> = {
  override: '覆写',
  fallback: '兜底',
  passthrough: '透传',
  delete: '删除',
}

/**
 * 按字段的值名生成悬停说明。
 *
 * 四档的差别只在「下游带了值时用谁的」与「下游没带时是否补」两问上，文案必须同时点明
 * —— 只说「覆写下游的值」无法区分 override 与 fallback。
 *
 * @param noun 该字段的值叫什么，用于填进文案，如「档位」「上限」「预算」
 */
export function buildOverwriteModeHints(noun: string): Record<OverwriteMode, string> {
  return {
    override: `无论下游是否携带，都使用此处配置的${noun}`,
    fallback: `下游携带就用它的值，未携带才使用此处配置的${noun}`,
    passthrough: '下游携带就用它的值，未携带也不添加该字段',
    delete: '始终不向上游发送该字段，连下游自带的也一并移除',
  }
}

/**
 * 该模式下，此处配置的值是否真的会被发往上游。
 *
 * `passthrough` 与 `delete` 都**不使用**配置的值：前者完全不干预、后者直接剔除字段，
 * 值只作为界面上的记忆值存在（用户切回其它模式时无需重新选）。
 * 界面据此降低那一栏的存在感 —— 否则一个永不生效的值会与已生效的配置长得一模一样。
 */
export function modeUsesConfiguredValue(mode: OverwriteMode): boolean {
  return mode === 'override' || mode === 'fallback'
}

/**
 * 取轮转顺序里的下一档，到末尾回到开头。
 *
 * 认不出的值从头开始而非原地不动 —— 后者会让按钮看起来是坏的。
 */
export function nextOverwriteMode<M extends OverwriteMode>(
  current: M,
  modes: readonly M[],
): M {
  const index = modes.indexOf(current)
  if (index < 0) return modes[0]
  return modes[(index + 1) % modes.length]
}

/**
 * 归一化模式名，大小写与两侧空白不敏感。
 *
 * 只认 `modes` 里的值：各字段支持的子集不同，把 `passthrough` 解析进只有两档的
 * 最大输出会得到一个界面无法显示、轮转也碰不到的状态。
 */
export function canonicalizeOverwriteMode<M extends OverwriteMode>(
  value: unknown,
  modes: readonly M[],
): M | null {
  if (typeof value !== 'string') return null
  const trimmed = value.trim().toLowerCase()
  return (modes as readonly string[]).includes(trimmed) ? trimmed as M : null
}

/** `{值, 模式}` 二元组。值的类型由各字段决定（档位是字符串、上限是数字）。 */
export interface ModeScopedValue<V, M extends OverwriteMode = OverwriteMode> {
  value: V
  mode: M
}

/** 解析一个「值 + 模式」字段所需的字段特有规则。 */
export interface ModeScopedCodec<V, M extends OverwriteMode> {
  /** 该字段支持的模式子集，顺序即轮转顺序。 */
  modes: readonly M[]
  /** 解析不出模式时用哪一档。 */
  defaultMode: M
  /** 解析不出值时用什么值。 */
  defaultValue: V
  /** JSON 里存值的键名，如 `reasoning_effort`、`max_output_tokens`。 */
  valueKey: string
  /**
   * 归一化值。认不出返回 `null`，由调用方回退到 `defaultValue`。
   *
   * 这是各字段差异最大的地方：档位要做大小写匹配、上限要做正整数判定。
   */
  canonicalizeValue: (value: unknown) => V | null
  /**
   * 解析非 JSON 的历史形态。
   *
   * 每个字段的列都有自己的历史包袱（思考深度有 `"None"` 与逗号分隔多值、
   * 最大输出有裸整数），无法归一。返回 `null` 表示交给默认值。
   */
  parseLegacy?: (raw: string) => ModeScopedValue<V, M> | null
}

/**
 * 解析持久化的「值 + 模式」字段。
 *
 * 先试 JSON 形态（以 `{` 开头），失败或不是 JSON 就交给 `parseLegacy`，
 * 最后回退默认值。
 *
 * <p>任何解析失败都回退而不抛错：这些字段来自数据库，
 * 一行脏数据不该让整个模型列表无法编辑。
 */
export function parseModeScopedValue<V, M extends OverwriteMode>(
  raw: unknown,
  codec: ModeScopedCodec<V, M>,
): ModeScopedValue<V, M> {
  const fallback: ModeScopedValue<V, M> = {
    value: codec.defaultValue,
    mode: codec.defaultMode,
  }

  // 数字入参走值归一化：最大输出那一列在 V9 之前是 INTEGER，
  // 后端有可能把它当数字发过来而非字符串。
  if (typeof raw === 'number') {
    return { value: codec.canonicalizeValue(raw) ?? codec.defaultValue, mode: codec.defaultMode }
  }
  if (typeof raw !== 'string' || !raw.trim()) {
    return fallback
  }
  const trimmed = raw.trim()

  if (trimmed.startsWith('{')) {
    try {
      const parsed = JSON.parse(trimmed) as Record<string, unknown>
      return {
        value: codec.canonicalizeValue(parsed[codec.valueKey]) ?? codec.defaultValue,
        mode: canonicalizeOverwriteMode(parsed.overwrite_mode, codec.modes) ?? codec.defaultMode,
      }
    } catch {
      return fallback
    }
  }

  return codec.parseLegacy?.(trimmed) ?? fallback
}

/**
 * 序列化成持久化用的 JSON 字符串。
 *
 * 值先过一遍归一化：界面可能把一个空输入框的内容传进来，
 * 而列上带着 `json_valid` 与取值约束，不该写进一个数据库不接受的形态。
 *
 * @param serializeValue 可选的出站转换，如档位要转小写以匹配上游协议
 */
export function serializeModeScopedValue<V, M extends OverwriteMode>(
  config: ModeScopedValue<V, M>,
  codec: ModeScopedCodec<V, M>,
  serializeValue?: (value: V) => unknown,
): string {
  const canonical = codec.canonicalizeValue(config.value) ?? codec.defaultValue
  return JSON.stringify({
    [codec.valueKey]: serializeValue ? serializeValue(canonical) : canonical,
    overwrite_mode: config.mode,
  })
}
