/**
 * 「今日时段」折线的归桶策略 —— 从后端搬来的那套规则，现在只有这一份实现。
 *
 * ## 窗口从 5 点起算
 * 跨夜编码是常态。按自然日切分会把一次连续的工作截成两段，看起来像两个互不相关的
 * 低谷；凌晨 5 点基本落在活动的最低谷，以它为界，一天的活动曲线才是完整的一条。
 * 因此凌晨 5 点之前仍算作「昨天」那一轮，窗口起点要回退一天。
 *
 * ## 点以整点为中心
 * `14:00` 这个点覆盖 `[13:30, 14:30)`，等价于「把时刻四舍五入到最近的整点」。
 * 若改成「该整点起的一小时」，25 个点的标签就是 05:00 到 04:00，
 * 横轴两端一个 05:00 一个 04:00，看不出这是完整的一圈。居中之后首尾都是 05:00，
 * 前后对称、一圈闭合的语义直接可见；代价是首尾各只覆盖半小时，两者相加恰好一小时，
 * 总量不重不漏 —— 这不是特例处理，而是窗口边界与居中聚合共同作用的自然结果。
 *
 * ## 窗口冻结
 * 窗口在建流时算定，此后<strong>不随时钟跨过 5 点而重基</strong>：
 * 那是刷新后的行为。窗口内点位的「尚未到来」状态则会随时间推进（见 {@link isFuture}），
 * 两者是不同的事 —— 一个换掉整批点，一个只是把已发生的点逐个放出来。
 */
import {
  DAY_MS,
  HALF_HOUR_MS,
  HOUR_MS,
  formatLocalTimestamp,
  parseLocalTimestamp,
  truncateToHour,
} from './localTime'
import type { TokenTotals, UsageHourlyPoint, UsageRecordDelta } from './types'

/**
 * 今日窗口的起始小时。
 *
 * 与后端 `UsageQueryService.DAY_START_HOUR` 一致 —— 后端用它决定查询窗口，
 * 前端用它决定展示窗口。两者必须相同，否则会出现「后端只给了 5 点后的数据、
 * 前端却按 0 点画轴」这类静默错位。
 */
export const DAY_START_HOUR = 5

/** 窗口内的点位数：05:00 到次日 05:00，首尾同为 05:00 故有 25 个。 */
export const HOURLY_POINT_COUNT = 25

/**
 * 确定今日窗口的起点。
 *
 * @param now 当前时刻
 * @returns 当日或前一日的 `DAY_START_HOUR` 整点
 */
export function resolveWindowStart(now: Date): Date {
  const start = truncateToHour(now)
  start.setHours(DAY_START_HOUR)
  if (now.getHours() < DAY_START_HOUR) {
    start.setTime(start.getTime() - DAY_MS)
  }
  return start
}

/**
 * 把任意时刻归入它所属的整点桶 —— 即「四舍五入到最近的整点」。
 *
 * 实现上是「加 30 分钟后截断到整点」，与后端 SQL 的
 * `strftime('%Y-%m-%dT%H:00:00', created_at, '+30 minutes')` 是同一个式子。
 *
 * @param moment 调用时刻
 * @returns 该时刻所属的整点
 */
export function bucketOf(moment: Date): Date {
  return truncateToHour(new Date(moment.getTime() + HALF_HOUR_MS))
}

/** 时间戳字符串 → 整点桶键（同格式的时间戳）。 */
export function bucketKeyOf(timestamp: string): string {
  return formatLocalTimestamp(bucketOf(parseLocalTimestamp(timestamp)))
}

/**
 * 判断某个点位是否尚未到来。
 *
 * 判定口径必须与聚合口径一致：点在整点<strong>前半小时</strong>就已开始收数据，
 * 13:45 的调用属于 14:00 那个点。若按「整点是否已过」判断，13:50 时 14:00 会被
 * 判成未来，刚刚发生的调用随即被从折线上剪掉，直到 14:00 整才突然出现。
 * 故比较的是点位的<strong>覆盖起点</strong>（整点减半小时）而非整点本身。
 *
 * @param pointTime 点位对应的整点
 * @param now 当前时刻
 */
export function isFuture(pointTime: Date, now: Date): boolean {
  return pointTime.getTime() - HALF_HOUR_MS > now.getTime()
}

/**
 * 下一次「已发生点位集合」发生变化的时刻。
 *
 * 点位在整点前半小时开始收数据，故集合只在时钟跨过每个 `HH:30` 时变化 ——
 * 这不是把定时器周期放宽的近似，而是精确命中唯一会让图变化的时刻。
 * 中间任何时刻重绘都是纯浪费。
 *
 * @param now 当前时刻
 * @returns 下一个半点的时刻
 */
export function nextFutureBoundary(now: Date): Date {
  const boundary = truncateToHour(now)
  boundary.setMinutes(30)
  if (boundary.getTime() <= now.getTime()) {
    boundary.setTime(boundary.getTime() + HOUR_MS)
  }
  return boundary
}

/** 折线图消费的一个点位。 */
export interface HourlyPoint {
  /** 桶键：完整时间戳，供增量帧精确匹配。 */
  bucket: string
  /** 展示用时刻标签 `HH:mm`。 */
  label: string
  inputTokens: number
  outputTokens: number
  /** 该时段是否尚未到来。 */
  future: boolean
}

/**
 * 把整点快照与增量帧归约成完整一天的 25 个点位。
 *
 * ## 为什么返回完整一天而非截断到当前
 * 横轴始终覆盖整个 24 小时，一天之内不再随时间推移而伸缩 —— 使用者能一眼看出
 * 「今天还剩多少时间」，各时段的横向位置也不会在刷新时移动。代价是必须区分
 * 「用量为 0」与「尚未到来」：两者的 token 都是 0，若不加区分，折线会一路贴底
 * 延伸到轴末，读起来像用量已归零。故未来时段标记 `future`，由展示侧决定断开还是淡化。
 *
 * @param totals 桶键 → token 累计量（快照与增量已合并）
 * @param windowStart 窗口起点
 * @param now 当前时刻，用于判定「尚未到来」
 */
export function buildHourlyPoints(
  totals: Map<string, TokenTotals>,
  windowStart: Date,
  now: Date,
): HourlyPoint[] {
  const points: HourlyPoint[] = []
  for (let index = 0; index < HOURLY_POINT_COUNT; index += 1) {
    const pointTime = new Date(windowStart.getTime() + index * HOUR_MS)
    const bucket = formatLocalTimestamp(pointTime)
    const totalsAt = totals.get(bucket)
    points.push({
      bucket,
      label: `${String(pointTime.getHours()).padStart(2, '0')}:00`,
      inputTokens: totalsAt?.inputTokens ?? 0,
      outputTokens: totalsAt?.outputTokens ?? 0,
      future: isFuture(pointTime, now),
    })
  }
  return points
}

/**
 * 把整点快照收进桶表。
 *
 * 窗口外的点被丢弃 —— 后端下发的窗口与前端展示窗口理应一致，但两者各自计算，
 * 跨过 5 点的瞬间可能相差一格；以前端窗口为准可保证轴不会凭空多出一个点。
 *
 * @param snapshot 后端整点快照
 * @param windowStart 窗口起点
 */
export function collectHourlySnapshot(
  snapshot: UsageHourlyPoint[],
  windowStart: Date,
): Map<string, TokenTotals> {
  const totals = new Map<string, TokenTotals>()
  const windowEnd = windowStart.getTime() + (HOURLY_POINT_COUNT - 1) * HOUR_MS
  for (const point of snapshot) {
    const at = parseLocalTimestamp(point.bucket).getTime()
    if (at < windowStart.getTime() || at > windowEnd) continue
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

/**
 * 把一条增量帧累加进桶表。
 *
 * 落在窗口外的帧被丢弃 —— 窗口冻结，跨过 5 点后的新调用属于「下一轮」，
 * 不该挤进用户此刻看的这一天（那会让轴凭空变长）。刷新后窗口重算，新的一轮才出现。
 *
 * @returns 是否被接受（用于测试与调试，调用方通常不关心）
 */
export function mergeHourlyDelta(
  totals: Map<string, TokenTotals>,
  delta: UsageRecordDelta,
  windowStart: Date,
): boolean {
  const bucketTime = bucketOf(parseLocalTimestamp(delta.createdAt))
  const at = bucketTime.getTime()
  const windowEnd = windowStart.getTime() + (HOURLY_POINT_COUNT - 1) * HOUR_MS
  if (at < windowStart.getTime() || at > windowEnd) return false

  const bucket = formatLocalTimestamp(bucketTime)
  const existing = totals.get(bucket)
  if (existing) {
    existing.inputTokens += delta.inputTokens
    existing.outputTokens += delta.outputTokens
    return true
  }
  totals.set(bucket, {
    inputTokens: delta.inputTokens,
    outputTokens: delta.outputTokens,
  })
  return true
}
