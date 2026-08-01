/**
 * 「今日时段」折线按日期回看时的<strong>双栈</strong>模型。
 *
 * ## 为什么必须双栈
 * 实时窗口（当前那一天）的数据只从 SSE 来，历史日期的数据只从 HTTP 来，
 * 两者存在<strong>各自独立</strong>的桶表里，互不覆盖。
 *
 * <p>如果只用一份桶表、切回今天时重新 HTTP 拉一遍，就会有一个无法消除的缝隙：
 * 请求发出到响应到达之间可能有新调用产生，那些 SSE 帧要么被随后到达的快照覆盖
 * （少算），要么在快照之上重复累加（多算）—— 取决于两者的到达顺序，而那个顺序
 * 不可控。这类偏差不会报错，只表现为「数字对不上」。
 *
 * <p>双栈把两个来源彻底分开：实时栈从建流那一刻起连续累加，永远不被 HTTP 触碰；
 * 切回今天只是<strong>换一个读取源</strong>，不触发任何请求，故不存在缝隙。
 * 代价是实时栈在后台一直维护着（即使用户正在看历史日期），
 * 但那只是往 Map 里累加几个数，比一次网络往返便宜得多。
 *
 * ## 实时窗口的锚定日不一定是今天
 * 一天从 05:00 起算，故凌晨 5 点前的实时窗口锚定在<strong>昨天</strong>。
 * 这意味着 03:00 时「今天」这个日期对应的窗口 `[今天 05:00, 明天 05:00)`
 * 整个都在未来 —— 选它只会得到一张全零的图。
 *
 * <p>因此时段视图的可选范围右端是{@link liveAnchorOffset 实时窗口的锚定日}
 * 而非日历今天。这与柱状图不同：那个按日历日聚合，今天是有意义的。
 */
import {
  DAY_START_HOUR,
  HOURLY_POINT_COUNT,
  resolveWindowStart,
} from './hourly'
import {
  DAY_MS,
  HOUR_MS,
  formatLocalDate,
  parseLocalDay,
  truncateToDay,
} from './localTime'
import type { TokenTotals, UsageHourlyPoint } from './types'

/**
 * `GET /config/api/usage-hourly/series` 的响应体 —— 与后端 `UsageHourlySeries` 一一对应。
 *
 * <p>`points` 已由后端补零、长度恒为 25。仍会经 {@link collectHourlySeriesResponse}
 * 过滤一遍窗口，理由见那里。
 */
export interface UsageHourlySeriesResponse {
  /**
   * 窗口锚定日，`yyyy-MM-dd`。
   *
   * <p>是<strong>实际生效</strong>的日期：请求越界或畸形时后端会收敛，
   * 故它可能不等于请求参数。缓存必须以这个值为键，否则会把 A 天的数据
   * 存到 B 天名下，而图照样能画。
   */
  date: string
  /** 窗口起点 `date 05:00`，`yyyy-MM-ddTHH:mm:ss`。 */
  windowStart: string
  /** 窗口末<strong>点位</strong> `date+1 05:00`，不是查询的右开边界。 */
  windowEnd: string
  /** 该窗口是否包含当前时刻。为 true 时该由实时栈负责，不进历史缓存。 */
  isCurrentWindow: boolean
  /** 25 个整点点位，已补零。 */
  points: UsageHourlyPoint[]
}

/**
 * 实时窗口的锚定日 —— 时段视图「最新可看的那一天」。
 *
 * <p>凌晨 5 点前是昨天。这是 {@link resolveWindowStart} 的日期部分，
 * 单独给出是为了让「可选范围的右端在哪」有一个可测试的名字。
 */
export function liveAnchorDate(now: Date): string {
  return formatLocalDate(resolveWindowStart(now))
}

/**
 * 实时窗口锚定日相对<strong>日历今天</strong>的偏移 —— 0 或 −1。
 *
 * <p>供选择器的硬墙使用：时段视图不该让用户选到一个整段都在未来的窗口。
 */
export function liveAnchorOffset(now: Date): number {
  const today = truncateToDay(now).getTime()
  const anchor = truncateToDay(resolveWindowStart(now)).getTime()
  return Math.round((anchor - today) / DAY_MS)
}

/**
 * 绝对索引（今天为 0，往前为负）→ 日期串。
 *
 * <p>与选择器刻度用同一套换算，故两者不会错位。
 */
export function dateAtDayOffset(now: Date, offset: number): string {
  const day = truncateToDay(now)
  day.setDate(day.getDate() + offset)
  return formatLocalDate(day)
}

/** 某个锚定日的窗口起点 `date 05:00`。日期串非法时返回 null。 */
export function hourlyWindowStartOf(date: string): Date | null {
  const day = parseLocalDay(date)
  if (!day) return null
  day.setHours(DAY_START_HOUR, 0, 0, 0)
  return day
}

/** 选中的那一天该从哪个栈读。 */
export interface HourlyDayTarget {
  /** 锚定日，`yyyy-MM-dd`。 */
  date: string
  /** 窗口起点 `date 05:00`。 */
  windowStart: Date
  /**
   * 是否为实时窗口。
   *
   * <p>true 时从实时栈读、<strong>不发请求</strong>；false 时从历史缓存读，
   * 未命中才发请求。
   */
  live: boolean
}

/**
 * 由选择器的绝对索引解析出读取目标 —— 日期、窗口起点与栈归属一次算齐。
 *
 * <p>三者必须同源：日期用来当缓存键与分流依据，窗口起点用来画横轴，
 * 若分开计算（比如日期取自刻度、窗口起点另用 `now` 算）就会在跨天那一刻错位一天。
 *
 * @param offset 绝对索引，0 为今天、−1 为昨天
 * @param now    当前时刻
 */
export function resolveHourlyDayTarget(offset: number, now: Date): HourlyDayTarget {
  const date = dateAtDayOffset(now, offset)
  // 非法日期在这里不可能出现（date 由 now 算出），但类型上仍需兜底。
  const windowStart = hourlyWindowStartOf(date) ?? resolveWindowStart(now)
  return { date, windowStart, live: date === liveAnchorDate(now) }
}

/**
 * 历史日期的桶表缓存 —— 日期 → 桶表。
 *
 * <p>只缓存<strong>历史</strong>窗口：那些数据已固化，缓存永不失效，
 * 于是来回滑动时同一天只请求一次。实时窗口不进这里（它在实时栈里，且一直在变）。
 */
export type HourlyHistoryCache = Map<string, Map<string, TokenTotals>>

/** {@link selectHourlyTotals} 的结果。 */
export interface HourlyReadResult {
  /** 该读哪一份桶表。缺失时为空 Map，故调用方可无条件使用。 */
  totals: Map<string, TokenTotals>
  /** 读的是不是实时栈。为 true 时<strong>绝不能</strong>发请求。 */
  live: boolean
  /**
   * 数据尚未就绪 —— 既不是实时栈，历史缓存里也没有。
   *
   * <p>此时应发请求（或正在等停留计时）。实时栈永远不算缺失：
   * 它从建流起就在累加，空 Map 表示「这一天确实还没有调用」而非「还没加载」。
   */
  missing: boolean
}

/**
 * 决定选中的那一天该从哪一栈读 —— 双栈的分流点。
 *
 * <h2>实时栈优先，且不可绕过</h2>
 * 选中日等于实时锚定日时一律读实时栈，即使历史缓存里恰好也有那一天
 * （后端把越界日期收敛到当前窗口时会出现）。缓存的那份是某一刻的快照，
 * 而实时栈一直在累加 —— 读错的表现是「今天的数字停在某个时刻不再涨」。
 *
 * @param date     选中的锚定日，`yyyy-MM-dd`
 * @param liveDate 实时窗口的锚定日
 * @param live     实时栈
 * @param history  历史缓存
 */
export function selectHourlyTotals(
  date: string,
  liveDate: string,
  live: Map<string, TokenTotals>,
  history: HourlyHistoryCache,
): HourlyReadResult {
  if (date === liveDate) {
    return { totals: live, live: true, missing: false }
  }
  const cached = history.get(date)
  if (cached) {
    return { totals: cached, live: false, missing: false }
  }
  return { totals: new Map(), live: false, missing: true }
}

/**
 * 把后端响应收进桶表。
 *
 * <h2>为何仍要过滤窗口</h2>
 * `points` 已由后端补零且长度恒为 25，直接搬进 Map 也能用。过滤的意义在于
 * 以响应<strong>自带的</strong> `windowStart` 为准划定边界 —— 请求越界时后端会
 * 收敛日期，此时点位属于另一天，而调用方手上那个「请求时算的窗口起点」是错的。
 * 让数据自己说明归属，比让调用方同步两个值更可靠。
 *
 * <p>畸形响应（`windowStart` 无法解析、`points` 不是数组）返回空表而非抛错：
 * 调用方拿到空表会渲染一整天的零，比整页报错更接近「这一天没数据」的实情。
 */
export function collectHourlySeriesResponse(
  response: UsageHourlySeriesResponse,
): Map<string, TokenTotals> {
  const totals = new Map<string, TokenTotals>()
  const start = new Date(response?.windowStart ?? '')
  if (Number.isNaN(start.getTime()) || !Array.isArray(response.points)) return totals

  const windowEnd = start.getTime() + (HOURLY_POINT_COUNT - 1) * HOUR_MS
  for (const point of response.points) {
    if (!point?.bucket) continue
    const at = new Date(point.bucket).getTime()
    if (Number.isNaN(at) || at < start.getTime() || at > windowEnd) continue
    const existing = totals.get(point.bucket)
    if (existing) {
      existing.inputTokens += point.inputTokens
      existing.outputTokens += point.outputTokens
      continue
    }
    totals.set(point.bucket, {
      inputTokens: point.inputTokens,
      outputTokens: point.outputTokens,
    })
  }
  return totals
}
