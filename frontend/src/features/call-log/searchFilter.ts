/**
 * 消费记录检索条件：表单状态、选项常量与查询参数转换。
 *
 * 后端语义见 `CallLogFilter.java` 与 ADR-0020。这里只负责把表单值翻译成查询参数，
 * 不在前端重写匹配逻辑 —— 匹配结果一律以后端为准。
 */

/** 文本匹配方式，取值与后端 `providerMatch` / `modelMatch` 参数逐字一致。 */
export type MatchMode = 'exact' | 'contains'

/** 检索表单的状态。 */
export interface LogSearchFilter {
  /**
   * 时间范围，NDatePicker `datetimerange` 的原生值（毫秒时间戳，闭区间）。
   * null 表示不限。
   */
  timeRange: [number, number] | null
  /** 状态取值：`2xx` / `4xx` / `5xx` 或状态码字符串（含 `-1`）。多个之间取 OR。 */
  statuses: string[]
  provider: string
  providerMatch: MatchMode
  model: string
  modelMatch: MatchMode
}

/** 发给 `/config/api/logs` 的检索参数。`status` 序列化为重复参数。 */
export interface LogQueryParams {
  from?: string
  to?: string
  status?: string[]
  provider?: string
  providerMatch?: MatchMode
  model?: string
  modelMatch?: MatchMode
}

export interface StatusOption {
  label: string
  value: string
}

/**
 * 状态类选项。`-1` 不属于任何状态类（它表示没有拿到 HTTP 响应），
 * 所以单独列出，选中 2xx/4xx/5xx 时不会带上它。
 */
export const STATUS_CLASS_OPTIONS: readonly StatusOption[] = [
  { label: '2xx 成功', value: '2xx' },
  { label: '4xx 客户端错误', value: '4xx' },
  { label: '5xx 服务端错误', value: '5xx' },
  { label: '非 HTTP 异常（-1）', value: '-1' },
]

/** 排查时常单独看的状态码。与状态类同时选中不冲突，后端取 OR。 */
export const STATUS_CODE_OPTIONS: readonly StatusOption[] = [
  '400', '401', '403', '429', '500', '502', '503', '504',
].map((code) => ({ label: code, value: code }))

export function createEmptyFilter(): LogSearchFilter {
  return {
    timeRange: null,
    statuses: [],
    provider: '',
    providerMatch: 'exact',
    model: '',
    modelMatch: 'exact',
  }
}

/** 是否没有任何实际条件。只改了匹配方式而没填值不算条件，与后端一致。 */
export function isFilterEmpty(filter: LogSearchFilter): boolean {
  return toQueryParams(filter) === undefined
}

function pad(value: number, width = 2): string {
  return String(value).padStart(width, '0')
}

/**
 * 毫秒时间戳 → `yyyy-MM-ddTHH:mm:ss` 本地时间，与 `api_call_log.created_at` 的存储格式一致。
 *
 * 不能用 `toISOString()`：它输出 UTC，而库里存的是服务器本地时间，
 * 东八区下会整整差 8 小时。秒以下直接舍去，后端只接受秒精度。
 */
export function formatLocalDateTime(ms: number): string {
  const d = new Date(ms)
  return (
    `${pad(d.getFullYear(), 4)}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
    `T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  )
}

/**
 * 表单 → 查询参数。没有任何条件时返回 undefined，调用方据此走实时模式，
 * 发出的请求与引入检索前完全相同。
 *
 * 匹配方式只在对应文本非空时才发送，避免产生「有参数、无条件」的请求。
 */
export function toQueryParams(filter: LogSearchFilter): LogQueryParams | undefined {
  const params: LogQueryParams = {}
  if (filter.timeRange) {
    params.from = formatLocalDateTime(filter.timeRange[0])
    params.to = formatLocalDateTime(filter.timeRange[1])
  }
  const statuses = [...new Set(filter.statuses.map((s) => s.trim()).filter(Boolean))]
  if (statuses.length) params.status = statuses
  const provider = filter.provider.trim()
  if (provider) {
    params.provider = provider
    params.providerMatch = filter.providerMatch
  }
  const model = filter.model.trim()
  if (model) {
    params.model = model
    params.modelMatch = filter.modelMatch
  }
  return Object.keys(params).length ? params : undefined
}
