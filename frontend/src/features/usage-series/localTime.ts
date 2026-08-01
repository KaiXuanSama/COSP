/**
 * 本地时间戳的解析与格式化。
 *
 * 后端的 `created_at` 与整点桶都是 `yyyy-MM-ddTHH:mm:ss` —— 无时区后缀的本地时间。
 * 这类字符串按 ES 规范当作本地时间解析（带 `Z` 或 `+08:00` 才是绝对时刻），
 * 与后端 `date('now','localtime')` 的口径一致，故两端谈论的是同一个「今天」。
 *
 * 这里刻意不引第三方日期库：需要的只有「截断到整点」「加减小时」「取日期部分」
 * 三种运算，原生 `Date` 足够，且少一个依赖。
 */

/** 一小时的毫秒数。 */
export const HOUR_MS = 3_600_000

/** 半小时的毫秒数 —— 「四舍五入到最近整点」的偏移量。 */
export const HALF_HOUR_MS = 1_800_000

/** 一天的毫秒数。 */
export const DAY_MS = 86_400_000

/**
 * 解析后端的本地时间戳。
 *
 * @param value `yyyy-MM-ddTHH:mm:ss`
 */
export function parseLocalTimestamp(value: string): Date {
  return new Date(value)
}

/** 两位补零。 */
function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

/**
 * 格式化为与后端完全一致的本地时间戳。
 *
 * 不用 `toISOString()` —— 那会转成 UTC，东八区的 `14:00` 会变成 `06:00Z`，
 * 桶键随即对不上后端下发的值。
 */
export function formatLocalTimestamp(date: Date): string {
  return (
    `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}` +
    `T${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`
  )
}

/** 格式化为 `yyyy-MM-dd`（本地时区），与后端 `substr(created_at, 1, 10)` 同口径。 */
export function formatLocalDate(date: Date): string {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

/** 时间戳字符串的日期部分 —— 前 10 位即可，无需解析成 Date。 */
export function datePartOf(timestamp: string): string {
  return timestamp.slice(0, 10)
}

/**
 * 严格解析 `yyyy-MM-dd` 为本地零点。格式不符或日历越界返回 null。
 *
 * <h2>为何不直接 `new Date(text)`</h2>
 * 那个写法对纯日期串按 <strong>UTC</strong> 解析（与带时刻的串相反），
 * 东八区下 `2026-07-28` 会得到本地 08:00，而西半球会退到前一天。
 *
 * <h2>为何要回读校验</h2>
 * `new Date(2026, 1, 31)` 会静默滚到 3 月 3 日，正则拦不住这种越界值。
 * 那种偏差不会报错，只会让日期轴或查询窗口静默错位几天。
 */
export function parseLocalDay(text: string | null | undefined): Date | null {
  if (typeof text !== 'string') return null
  const matched = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text.trim())
  if (!matched) return null
  const year = Number(matched[1])
  const month = Number(matched[2])
  const day = Number(matched[3])
  const parsed = new Date(year, month - 1, day)
  if (
    parsed.getFullYear() !== year ||
    parsed.getMonth() !== month - 1 ||
    parsed.getDate() !== day
  ) {
    return null
  }
  return parsed
}

/** 取该时刻所在整点（分秒归零）。 */
export function truncateToHour(date: Date): Date {
  const result = new Date(date)
  result.setMinutes(0, 0, 0)
  return result
}

/** 取该时刻所在自然日的零点。 */
export function truncateToDay(date: Date): Date {
  const result = new Date(date)
  result.setHours(0, 0, 0, 0)
  return result
}
