/**
 * 日期选择器可达区间的数据源 —— 把后端的日期上下限换算成绝对索引轴上的软墙位置。
 *
 * ## 为什么需要这一层
 * 选择器工作在「距今天多少天」这个绝对索引轴上（今天为 0，往前为负），而后端给的是
 * 日期串。换算本身只有两行，但**取哪个字段、哪些字段刻意不取**是有讲究的，
 * 值得单独成模块并锁进测试 —— 写在组件里就会变成一句没人敢动的表达式。
 *
 * ## 只取下界，上界恒为今天
 * 后端返回上下限两端，前端**只用下界**。
 *
 * <p>「今天没有数据」与「今天不可达」是两件事：今天随时可能产生第一条调用，
 * 且它是默认视图的右端。若拿 `latestDate` 当上界，空闲一天后打开页面，
 * 今天就会被画成灰点、选择器整体退到昨天 —— 而下一次调用又让它突然可选。
 * 只有**未来**才是不可达的，故上界恒为 0（今天），不读 `latestDate` /
 * `latestSelectable`。后者按后端约定必然等于今天，读它只是多一个失配来源。
 *
 * ## 可选天数不足时收缩最小跨度
 * 区间块的跨度下限默认是 7 天（与后端 `SlidingDateWindow.MIN_SIZE` 一致），
 * 但全新部署可能只有两三天数据。此时「至少选 7 天」与「只有 3 天可达」互相矛盾，
 * 故最小跨度收缩为实际可选天数。
 *
 * <p>`resolvePagedConfig` 本身也会把 `minSpan` 钳到 `min(可达宽度, 池宽)`，
 * 因此不写这一步也不会出错。仍显式写出来是因为这个收缩是**业务决策**
 * （「数据不够 7 天时就按实际天数算」），而那个钳制是**防御**；
 * 依赖防御来实现决策，改防御的人不会知道自己动了业务语义。
 */
import { DAY_MS, formatLocalDate, parseLocalDay, truncateToDay } from './localTime'

/** `GET /config/api/usage-date-range` 的响应体 —— 与后端 `UsageDateRange` 一一对应。 */
export interface UsageDateRangeMeta {
  /** 最早一条用量记录的日期，`yyyy-MM-dd`；库为空时为 null。 */
  earliestDate: string | null
  /** 最晚一条用量记录的日期；库为空时为 null。 */
  latestDate: string | null
  /** 服务端本地时钟的今天，`yyyy-MM-dd`。 */
  today: string
  /** 是否存在至少一条记录。 */
  hasData: boolean
  /** 可选的最早日期（含）—— 数据下界与后端回看深度的交集，永不为 null。 */
  earliestSelectable: string
  /** 可选的最晚日期（含）—— 后端约定恒等于 `today`。本模块刻意不使用它。 */
  latestSelectable: string
}

/** 选择器的轴约束 —— 绝对索引，今天为 0。 */
export interface SelectableAxis {
  /**
   * 软墙位置：可选的最早一天，绝对索引（≤ 0）。
   *
   * <p>更早的位置仍会作为刻度渲染，但标成不可达（空心点）——
   * 从刻度里删掉会让用户以为轴就这么长，看不出「更早的数据查不了」这个事实。
   */
  reachableStart: number
  /** 可选天数（含今天），≥ 1。等于 `1 - reachableStart`。 */
  selectableDays: number
  /**
   * 区间块的跨度下限。
   *
   * <p>正常为 `preferredSpan`（7 天）；可选天数不足时收缩为可选天数本身。
   */
  minSpan: number
  /** 库里是否有数据。用于文案，不参与轴运算。 */
  hasData: boolean
}

/** {@link resolveSelectableAxis} 的约束参数。 */
export interface SelectableAxisOptions {
  /** 期望的跨度下限（天），通常为 7。可选天数不足时会被收缩。 */
  preferredSpan: number
  /** 池宽（可见刻度数），同时也是软墙能退到的最远距离。 */
  poolDays: number
}

/**
 * 两个日期相差多少天 —— `target` 在 `origin` 之后为正。
 *
 * <p>先各自截断到零点再相减，故不受时刻影响。用 {@link DAY_MS} 相除后取整
 * 而非按日历逐日累加：夏令时切换那天只有 23 或 25 小时，`Math.round`
 * 足以吸收这 ±1 小时的偏差。
 */export function dayOffsetBetween(origin: Date, target: Date): number {
  const from = truncateToDay(origin).getTime()
  const to = truncateToDay(target).getTime()
  return Math.round((to - from) / DAY_MS)
}

/**
 * 把后端的日期范围换算成选择器的轴约束。
 *
 * <h2>meta 为 null 时放开整个池</h2>
 * 加载中与加载失败都走这条路。刻意**不**收缩成「只有今天可选」：
 * 那会让选择器先塌成一个点、拿到响应后再展开，闪一下比慢一点更刺眼；
 * 失败时放开也与接线前的行为一致，用户至少还能拖 —— 拖到没数据的日期
 * 只是看到空图，比整个控件锁死要好。
 *
 * <h2>为什么用浏览器时钟做原点</h2>
 * 刻度（`dateAtOffset`）由浏览器时钟生成，软墙必须落在同一套坐标里，
 * 否则两者错位一天而页面照样能渲染 —— 只是软墙位置悄悄偏了一格。
 * `meta.today` 因此不参与换算，它的用途是{@link isClockAligned 校验两端时钟是否一致}。
 *
 * @param meta    后端响应；null 表示尚未加载或加载失败
 * @param today   浏览器时钟的当前时刻，作为绝对索引的原点
 * @param options 期望跨度与池宽
 */
export function resolveSelectableAxis(
  meta: UsageDateRangeMeta | null,
  today: Date,
  options: SelectableAxisOptions,
): SelectableAxis {
  const poolDays = Math.max(1, Math.floor(options.poolDays) || 1)
  const preferredSpan = Math.max(1, Math.floor(options.preferredSpan) || 1)
  // 池左端能露出的最远位置。软墙比它更早没有意义 —— 那些刻度根本不在池内，
  // 且后端也把可查窗口限制在这个深度内。
  //
  // 写成 `1 - poolDays` 而非 `-(poolDays - 1)`：两者数值相同，但后者在
  // poolDays 为 1 时得到 `-0`，而 `Object.is(-0, 0)` 为 false ——
  // 那会让 `toEqual` 之类的严格比较失配，且这种差异在运算中毫无痕迹。
  const floor = 1 - poolDays

  const fallback: SelectableAxis = {
    reachableStart: floor,
    selectableDays: poolDays,
    minSpan: Math.min(preferredSpan, poolDays),
    hasData: false,
  }
  if (!meta) return fallback

  const earliest = parseLocalDay(meta.earliestSelectable)
  // 畸形的边界串按「未加载」处理而非塌成今天：脏数据不该把控件锁死。
  if (!earliest) return { ...fallback, hasData: Boolean(meta.hasData) }

  let reachableStart = dayOffsetBetween(today, earliest)
  // 服务端日期晚于浏览器今天（时区差或时钟不同步）时轴会反向，钳回今天。
  if (reachableStart > 0) reachableStart = 0
  if (reachableStart < floor) reachableStart = floor

  const selectableDays = 1 - reachableStart
  return {
    reachableStart,
    selectableDays,
    // 只有数据不足时才收缩 —— 数据充足时保持 7 天，与后端的窗口下限一致。
    minSpan: Math.min(preferredSpan, selectableDays),
    hasData: Boolean(meta.hasData),
  }
}

/**
 * 服务端与浏览器是否认同同一个「今天」。
 *
 * <p>不一致时整条时间轴会整体错位一天，而图表照样能画 —— 只是每根柱子都贴错日期。
 * 这类问题不会自己暴露，故留一个显式判定供调用方告警。
 *
 * @param meta  后端响应；null 视为无法判定（返回 true，不误报）
 * @param today 浏览器时钟的当前时刻
 */
export function isClockAligned(meta: UsageDateRangeMeta | null, today: Date): boolean {
  if (!meta) return true
  return meta.today === formatLocalDate(today)
}
