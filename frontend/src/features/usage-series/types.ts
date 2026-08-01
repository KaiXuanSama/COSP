/**
 * 图表流（`/config/api/usage/stream`）的三种帧。
 *
 * 三张图表（堆叠柱状图、近 N 日折线、今日时段折线）共用这一条流：
 * 两帧全量快照打底，随后每次调用推一帧增量。
 *
 * | 帧 | event | 消费方 |
 * |---|---|---|
 * | 明细快照 | `breakdown` | 柱状图 + 近 N 日折线 |
 * | 整点快照 | `hourly` | 今日时段折线 |
 * | 单条增量 | `usage-delta` | 三者 |
 *
 * ## 归桶策略在前端
 * 后端只负责「聚合到足够细的粒度」，不做任何展示决策 —— 窗口起点（5 点分界）、
 * 「以整点为中心」的点位构成、「尚未到来」的判定全部在
 * {@link ./hourly} 与 {@link ./daily} 中实现。这样增量帧与快照帧
 * 走的是同一套归桶规则，不存在两份实现需要人工同步。
 */

/**
 * 明细快照的一行 —— 与后端 `UsageBreakdownRow` 一一对应。
 *
 * 带 token 是为了让近 N 日折线复用这份数据：它按 `date` 求和即可，
 * 不必为它单独下发一帧。
 */
export interface UsageBreakdownRow {
  /** 调用日期，`yyyy-MM-dd`（本地时区）。 */
  date: string
  providerKey: string
  modelName: string
  callCount: number
  inputTokens: number
  outputTokens: number
}

/**
 * 整点快照的一个点 —— 与后端 `UsageHourlyPoint` 一一对应。
 *
 * `bucket` 是<strong>完整时间戳</strong>而非 `HH:mm`：今日窗口跨午夜，
 * 只给时刻则 `01:00` 分不清属于当天还是次日，消费侧就得靠「序号为负则加一天」
 * 之类的补偿来还原，而那种补偿依赖于「窗口从几点开始」这一展示口径。
 */
export interface UsageHourlyPoint {
  /** 整点时刻，`yyyy-MM-ddTHH:mm:ss`（本地时区）。 */
  bucket: string
  inputTokens: number
  outputTokens: number
}

/**
 * 单条用量增量帧 —— 与后端 `UsageRecordDelta` 一一对应。
 *
 * 不带日期字段：日期是 `createdAt` 的前 10 位，派生字段会给「两者不一致」留下可能。
 * 同理不带 `callCount`（一帧即一次调用）。
 *
 * ## 不可丢
 * 每帧是一次状态转移，丢一帧就永久少算一次调用。故流在订阅建立时先发两帧全量快照
 * 作为基准，断线重连时同理 —— 重连间隙的遗漏由新快照补齐。
 */
export interface UsageRecordDelta {
  /** 调用时刻，`yyyy-MM-ddTHH:mm:ss`（本地时区），与落库值同源。 */
  createdAt: string
  providerKey: string
  modelName: string
  inputTokens: number
  outputTokens: number
}

/** 一个时间桶内的 token 累计量。 */
export interface TokenTotals {
  inputTokens: number
  outputTokens: number
}

/**
 * `GET /config/api/usage-breakdown/page` 的响应体 —— 与后端 `UsageBreakdownPage` 一一对应。
 *
 * <p>历史窗口的数据载体：不含今天时数据已固化，一次拉取即为终态，可缓存。
 *
 * <p>注意 `rows` 只含有数据的日期组合，<strong>不补零</strong> —— 补零由展示层
 * 按显示窗口完成（柱状图缺日不画柱、折线按窗口补零）。且后端会钳制 `size`/`offset`，
 * 实际窗口可能与请求参数不同，故回带 `startDate`/`endDate`。
 */
export interface UsageBreakdownPage {
  /** 实际生效的窗口起始日期（含），`yyyy-MM-dd`。 */
  startDate: string
  /** 实际生效的窗口结束日期（含），`yyyy-MM-dd`。 */
  endDate: string
  /** 实际窗口宽度（天），已钳制。 */
  size: number
  /** 实际向过去偏移的天数，已钳制。 */
  offset: number
  /** 窗口是否包含今天。为 true 时该由 SSE 实时栈负责，不应进历史缓存。 */
  includesToday: boolean
  /** 是否还能往「更近」的方向滑。 */
  hasNewer: boolean
  /** 是否还能往「更早」的方向滑。 */
  hasOlder: boolean
  /** 窗口内按 日期 × 供应商 × 模型 聚合的明细行。 */
  rows: UsageBreakdownRow[]
}
