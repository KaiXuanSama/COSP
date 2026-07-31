/**
 * 「近 7 日」折线与堆叠柱状图共用的按日归约。
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
import { DAY_MS, datePartOf, formatLocalDate, truncateToDay } from './localTime'
import type { TokenTotals, UsageBreakdownRow, UsageRecordDelta } from './types'

/** 柱状图与近 7 日折线的回看天数。 */
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
