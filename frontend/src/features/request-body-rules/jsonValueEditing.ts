/**
 * JSON 值的类型化编辑。
 *
 * 单个自由文本输入框无法表达 JSON 的类型意图：`null` 到底是 null 还是字符串 "null"，
 * `123` 是数字还是字符串，都取决于用户想要什么而非他打了什么。因此界面上把类型
 * 显式提出来选，本模块负责「类型 + 文本」与「JSON 值」之间的双向映射。
 *
 * <h2>两处消费者共用同一套映射</h2>
 * 「设置字段值」决定发给上游的内容，「条件 · 等于」决定规则是否命中 —— 两者面对的
 * 都是「用户想表达哪个 JSON 值」这同一个问题，因此共用一份映射而非各写一套。
 * 条件侧原先用宽松解析（能当 JSON 就当 JSON，否则当字符串），结果是「等于 null」
 * 只能靠留空试出来、且无法表达「等于字符串 "10"」—— 语义靠猜是这类界面最难用的地方。
 *
 * <h2>为何不把类型存进规则</h2>
 * JSON 值本身就是自描述的 —— 存下来的 `"abc"` / `12.38` / `[1,2]` / `true` / `null`
 * 各自唯一对应一种类型，回显时用 {@link inferJsonValueType} 反推即可。
 * 给规则加一个 `valueType` 字段则要动数据契约、后端白名单与迁移，
 * 换来的只是一份可以从值本身算出来的冗余信息，而冗余信息会有和值不一致的可能。
 */

/** 可选的值类型。 */
export type JsonValueType = 'string' | 'number' | 'list' | 'boolean' | 'null'

/** 下拉选项。 */
export const JSON_VALUE_TYPE_OPTIONS: Array<{ label: string; value: JsonValueType }> = [
  { label: '字符串', value: 'string' },
  { label: '数值', value: 'number' },
  { label: '列表', value: 'list' },
  { label: '布尔', value: 'boolean' },
  { label: 'null', value: 'null' },
]

/** 布尔类型的取值下拉选项。 */
export const BOOLEAN_VALUE_OPTIONS = [
  { label: 'true', value: 'true' },
  { label: 'false', value: 'false' },
]

/**
 * 从已保存的 JSON 值反推类型。
 *
 * <p>对象值没有对应的类型档位 —— 界面上「调整对象内容」是另一个操作。
 * 真遇到对象（手写 JSON 规则时可能出现）按列表处理，用户至少能看到并编辑原文。
 */
export function inferJsonValueType(value: unknown): JsonValueType {
  if (value === null) return 'null'
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'number') return 'number'
  if (Array.isArray(value) || typeof value === 'object') return 'list'
  return 'string'
}

/**
 * 把已保存的值渲染成输入框文本。
 *
 * <p>字符串类型直接返回原文而不加引号：用户在「字符串」档位下看到的应当就是他输入的内容。
 * 其余类型走 JSON 序列化，于是列表能看到完整字面量、数字与布尔看到裸值。
 */
export function formatJsonValueText(value: unknown, type: JsonValueType): string {
  if (type === 'null') return 'null'
  if (type === 'string') return typeof value === 'string' ? value : String(value ?? '')
  if (type === 'boolean') return value === false ? 'false' : 'true'
  if (type === 'number') return typeof value === 'number' ? String(value) : ''
  return value === undefined ? '[]' : JSON.stringify(value)
}

/** 切换类型时的默认文本。 */
export function defaultJsonValueText(type: JsonValueType): string {
  switch (type) {
    case 'list':
      return '[]'
    case 'boolean':
      return 'true'
    case 'null':
      return 'null'
    default:
      return ''
  }
}

/**
 * 过滤数值输入。
 *
 * <p>只保留阿拉伯数字、小数点与**开头的负号**。负号不在最初的白名单里，但 OpenAI 的
 * `presence_penalty` / `frequency_penalty` 合法取值含负数（-2.0 ~ 2.0），
 * 少了它这两个字段就没法用类型化输入表达 —— 而它们正是请求体规则的常见目标。
 *
 * <p>多余的小数点被丢弃而非报错：用户按住 `.` 不该得到 `1...2` 这种既非法又需要他自己清理的中间态。
 */
export function sanitizeNumberInput(text: string): string {
  const negative = text.startsWith('-')
  const digitsAndDots = text.replace(/[^0-9.]/g, '')
  const firstDot = digitsAndDots.indexOf('.')
  const normalized = firstDot < 0
    ? digitsAndDots
    : digitsAndDots.slice(0, firstDot + 1) + digitsAndDots.slice(firstDot + 1).replace(/\./g, '')
  return negative ? `-${normalized}` : normalized
}

/** 解析结果。`value` 在 `error` 非空时无意义。 */
export interface JsonValueParseResult {
  value: unknown
  error: string
}

/**
 * 按类型把输入文本解析为 JSON 值。
 *
 * <p>各档位的语义严格按类型走，不做任何「猜用户想要什么」的修正：
 * <ul>
 *   <li><strong>字符串</strong>：原文即值。输入 {@code null} 得到 {@code "null"}，
 *       输入 {@code "null"}（含引号）得到 {@code "\"null\""}。尊重输入内容。</li>
 *   <li><strong>数值</strong>：空文本视为未填而非 0 —— 0 是一个有意义的值，
 *       不该由「还没输入」变出来。</li>
 *   <li><strong>列表</strong>：必须是合法 JSON 数组，中括号由用户输入。</li>
 *   <li><strong>布尔</strong>：只有 {@code true} / {@code false}。</li>
 *   <li><strong>null</strong>：恒为 null，忽略文本。</li>
 * </ul>
 */
export function parseJsonValue(text: string, type: JsonValueType): JsonValueParseResult {
  switch (type) {
    case 'null':
      return { value: null, error: '' }
    case 'boolean':
      return { value: text !== 'false', error: '' }
    case 'string':
      return { value: text, error: '' }
    case 'number': {
      const trimmed = text.trim()
      if (!trimmed || trimmed === '-' || trimmed === '.' || trimmed === '-.') {
        return { value: null, error: '请输入数值' }
      }
      const parsed = Number(trimmed)
      if (!Number.isFinite(parsed)) return { value: null, error: `不是合法数值：${trimmed}` }
      return { value: parsed, error: '' }
    }
    case 'list': {
      const trimmed = text.trim()
      if (!trimmed) return { value: null, error: '请输入列表，如 [1, "a", true]' }
      let parsed: unknown
      try {
        parsed = JSON.parse(trimmed)
      } catch (cause) {
        const message = cause instanceof Error ? cause.message : '未知语法错误'
        return { value: null, error: `列表 JSON 语法错误：${message}` }
      }
      if (!Array.isArray(parsed)) return { value: null, error: '必须是 JSON 数组，以 [ 开头、] 结尾' }
      return { value: parsed, error: '' }
    }
  }
}
