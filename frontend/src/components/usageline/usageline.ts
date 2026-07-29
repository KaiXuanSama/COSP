/**
 * token 用量折线图的类型契约。
 *
 * <h2>为什么与柱状图分开</h2>
 * 折线的横轴是<strong>时间</strong>（日期或时段），柱状图的横轴在下钻后变成
 * 供应商 / 模型这类<strong>类别</strong>。把类别之间连成线会暗示一种不存在的顺序关系，
 * 属于图表语法错误。两者表达的问题也不同：折线看趋势（涨还是跌），
 * 柱状图看构成（谁占多少），故各自成图、各用一套契约。
 *
 * <h2>与后端的对应</h2>
 * {@link UsageTimelinePoint} 与 protocol 层的同名 record 字段一一对应，
 * 前端不做字段映射。总量不在其中 —— 它恒等于输入加输出，由 {@link SERIES} 现算。
 */

/** 后端 `/config/api/usage-timeline` 返回的一个点。 */
export interface UsageTimelinePoint {
  /** 时间桶标识：近 7 日为 `yyyy-MM-dd`，今日时段为起始时刻 `HH:mm`。 */
  bucket: string
  /** 该桶内的输入 token 总量。 */
  inputTokens: number
  /** 该桶内的输出 token 总量。 */
  outputTokens: number
}

/**
 * 时间范围。
 *
 * - `7d` —— 近 7 日，一天一个点；
 * - `1d` —— 今日 05:00 至次日 05:00，按 {@link BucketHours} 分桶。
 */
export type TimelineRange = '7d' | '1d'

/**
 * 时段颗粒度（小时）。
 *
 * 只允许能整除 24 的档位，这样一天恰好被分成等长的若干段、末段不会被截短
 * （3 小时档下 24/3=8 也成立，但 5 小时档会余 4 小时，最后一段长度不一致，
 * 折线的点距就不再代表相同的时间跨度）。
 */
export type BucketHours = 1 | 2 | 4

/** 可选颗粒度，顺序即控件中的展示顺序。 */
export const BUCKET_OPTIONS: BucketHours[] = [1, 2, 4]

/** 默认颗粒度。2 小时下一天 12 个点，折线足够平滑又不失细节。 */
export const DEFAULT_BUCKET_HOURS: BucketHours = 2

/** 今日窗口的起始小时，须与后端 `UsageQueryService.DAY_START_HOUR` 一致。 */
export const DAY_START_HOUR = 5

/**
 * 一条折线的配置。
 *
 * 三条线共用一个纵轴 —— 总量恒等于输入加输出，三者同量纲同数量级，
 * 分轴反而会破坏「总量 = 两者之和」这个可以直接读出的关系。
 * 因此区分靠颜色与线型，而非各自的坐标系。
 */
export interface SeriesConfig {
  key: 'total' | 'input' | 'output'
  /** 图例与 tooltip 中的名称。 */
  label: string
  /** 从点位取值。总量在此现算，不占用传输字段。 */
  valueOf: (point: UsageTimelinePoint) => number
  /** 线条颜色的 CSS 变量表达式。 */
  color: string
  /** SVG `stroke-dasharray`；实线传 `undefined`。 */
  dash?: string
}

/**
 * 三条线的定义，顺序即绘制与图例顺序。
 *
 * 视觉层次的分配依据「谁是主角」：
 * - 总量用强调色实线，它是这张图要回答的主要问题；
 * - 输入量最大但属背景信息，用灰实线；
 * - 输出占比很小、线条贴近横轴，用灰虚线 —— 虚线在密集区比实线更不易与轴线混淆。
 */
export const SERIES: SeriesConfig[] = [
  {
    key: 'total',
    label: '总量',
    valueOf: (point) => point.inputTokens + point.outputTokens,
    color: 'var(--usage-line-accent, #c27a3e)',
  },
  {
    key: 'input',
    label: '输入',
    valueOf: (point) => point.inputTokens,
    color: 'var(--usage-line-text-muted, #9a9590)',
  },
  {
    key: 'output',
    label: '输出',
    valueOf: (point) => point.outputTokens,
    color: 'var(--usage-line-text-muted, #9a9590)',
    dash: '4 3',
  },
]

/** 折线上的一个已换算坐标点。 */
export interface SeriesPoint {
  /** 横向位置占绘图区宽度的比例（0 ~ 1）。 */
  x: number
  /** 纵向位置占绘图区高度的比例（0 ~ 1），自下而上。 */
  y: number
  /** 原始数值，供 tooltip 显示。 */
  value: number
}

/** 一条已换算好的折线。 */
export interface RenderedSeries {
  config: SeriesConfig
  points: SeriesPoint[]
}

/**
 * 横轴第二行的日期分段 —— 仅今日时段范围有。
 *
 * 单看时刻行无法判断 02:00 属于哪一天，故用第二行把跨夜语义显式标出：
 * 05:00–00:00 段标当日，00:00–05:00 段标次日。
 */
export interface AxisDateSegment {
  /** 分段标签，如 `7/26`。 */
  label: string
  /** 起始位置占绘图区宽度的比例。 */
  start: number
  /** 宽度占绘图区宽度的比例。 */
  width: number
}
