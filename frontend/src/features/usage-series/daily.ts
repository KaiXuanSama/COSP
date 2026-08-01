/**
 * 「近 N 日」折线与堆叠柱状图共用的按日归约。
 *
 * 两者读同一份明细快照（`event: breakdown`）：柱状图按 (日期, 供应商, 模型) 取次数，
 * 折线按日期求 token。因此不必为折线单独下发一帧。
 *
 * ## 与今日时段的窗口差异
 * 这里的「今天」是<strong>自然日</strong>（`substr(created_at, 1, 10)` 的口径），
 * 而今日时段折线的一天从 5 点起算。两者对同一条增量帧可能给出不同判定 ——
 * 例如明天凌晨 02:00 的调用，对本模块属于「窗口外的新一天」应丢弃，
 * 对今日时段却仍在当前窗口内应接受。故两处各有独立的窗口过滤，不共用判定函数。
 */
import { DAY_MS, datePartOf, formatLocalDate, parseLocalDay, truncateToDay } from './localTime'
import type { TokenTotals, UsageBreakdownRow, UsageRecordDelta } from './types'

/** 柱状图与近 N 日折线的默认回看天数。 */
export const BREAKDOWN_DAYS = 7

/** 折线图消费的一个日期点位。 */
export interface DailyPoint {
  /** 桶键：`yyyy-MM-dd`。 */
  bucket: string
  inputTokens: number
  outputTokens: number
}

/**
 * 窗口内的日期序列（升序，含今天）。
 *
 * @param now 当前时刻
 * @param days 天数
 */
export function windowDates(now: Date, days: number = BREAKDOWN_DAYS): string[] {
  const today = truncateToDay(now)
  const dates: string[] = []
  for (let offset = days - 1; offset >= 0; offset -= 1) {
    dates.push(formatLocalDate(new Date(today.getTime() - offset * DAY_MS)))
  }
  return dates
}

/**
 * 由两个端点日期生成升序日期序列（含两端）。
 *
 * <p>供历史窗口的展示轴使用 —— 实时窗口用 {@link windowDates}，
 * 历史窗口只知道起止两天，需要逐日展开。日期串非法时返回空数组，
 * 调用方拿到空数组应回退到实时窗口。
 */
export function windowDatesBetween(start: string, end: string): string[] {
  const cursor = parseLocalDay(start)
  const last = parseLocalDay(end)
  if (!cursor || !last || cursor.getTime() > last.getTime()) return []

  const dates: string[] = []
  const at = cursor.getTime()
  const until = last.getTime()
  for (let t = at; t <= until; t += DAY_MS) {
    dates.push(formatLocalDate(new Date(t)))
  }
  return dates
}

/**
 * 窗口的统一标识 —— `start~end`。
 *
 * <p>历史缓存、实时判定、显示滞后全都用它作键。含端点的日期串天然唯一，
 * 同一窗口无论宽度怎么表达（`size`/`offset` 或起止日期）都收敛到同一个键。
 */
export function windowKeyOf(start: string, end: string): string {
  return `${start}~${end}`
}

/**
 * 由绝对索引（今天为 0、往前为负）换算成后端分页参数。
 *
 * <p>与后端 `SlidingDateWindow` 的口径一致：`offset = 0` 时窗口右端为今天，
 * 宽度为两端索引之差加一。前端选择器工作在绝对索引轴上，落到 HTTP 前
 * 必须经这里换一次。
 *
 * @param startIdx 窗口左端的绝对索引
 * @param endIdx   窗口右端的绝对索引（≤ 0）
 */
export function windowParamsOf(startIdx: number, endIdx: number): { size: number; offset: number } {
  // 写成 `0 - endIdx` 而非 `-endIdx`：后者在 endIdx 为 0 时得到 `-0`，
  // `Object.is(-0, 0)` 为 false，会让 `toEqual` 失配。
  return { size: endIdx - startIdx + 1, offset: 0 - endIdx }
}

/**
 * 把明细行按日期归约成折线点位，缺失的日期补零。
 *
 * ## 为什么要补零
 * 折线按点位等距绘制，若跳过无数据的日期，横轴就不再是等距时间轴，
 * 「隔了几天」这个信息会丢失。这与「今日时段不补未来」并不矛盾 ——
 * 过去的空日是确定的零，未来的时段则是尚未发生。
 *
 * @param rows 明细行（快照 + 已累加的增量）
 * @param dates 窗口内的日期序列
 */
export function buildDailyPoints(rows: UsageBreakdownRow[], dates: string[]): DailyPoint[] {
  const totals = new Map<string, TokenTotals>()
  for (const row of rows) {
    const existing = totals.get(row.date)
    if (existing) {
      existing.inputTokens += row.inputTokens
      existing.outputTokens += row.outputTokens
      continue
    }
    totals.set(row.date, { inputTokens: row.inputTokens, outputTokens: row.outputTokens })
  }

  return dates.map((bucket) => ({
    bucket,
    inputTokens: totals.get(bucket)?.inputTokens ?? 0,
    outputTokens: totals.get(bucket)?.outputTokens ?? 0,
  }))
}

/**
 * 把一条增量帧累加进明细行数组（原地修改）。
 *
 * ## 判定依据是「这一天是否已在窗口里」
 * 而非「这个组合是否已存在」：某供应商今天首次被调用时，它的行本来就不存在，
 * 那种情况要<strong>插入</strong>而非丢弃。反之，跨过午夜后的新数据属于「明天」，
 * 而用户此刻看的窗口不含明天 —— 直接丢弃，不擅自加一根柱子（那会让横轴凭空变长）。
 * 刷新后窗口自然重算，新的一天才出现。
 *
 * @param rows 明细行数组，会被原地修改
 * @param delta 增量帧
 * @param dates 窗口内的日期序列
 * @returns 是否被接受
 */
export function mergeBreakdownDelta(
  rows: UsageBreakdownRow[],
  delta: UsageRecordDelta,
  dates: string[],
): boolean {
  const date = datePartOf(delta.createdAt)
  if (!dates.includes(date)) return false

  const hit = rows.find(
    (row) =>
      row.date === date &&
      row.providerKey === delta.providerKey &&
      row.modelName === delta.modelName,
  )
  if (hit) {
    hit.callCount += 1
    hit.inputTokens += delta.inputTokens
    hit.outputTokens += delta.outputTokens
    return true
  }

  rows.push({
    date,
    providerKey: delta.providerKey,
    modelName: delta.modelName,
    callCount: 1,
    inputTokens: delta.inputTokens,
    outputTokens: delta.outputTokens,
  })
  return true
}
