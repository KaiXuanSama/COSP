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
  /**
   * 该时段是否尚未到来。
   *
   * 今日时段范围会返回完整的 24 小时，因此横轴不随时间伸缩；但未来段的 0
   * 是「还没发生」而非「没有用量」，照常连线会让折线贴底延伸到轴末，
   * 看起来像用量已归零。折线因此只画到最后一个已发生的点为止。
   */
  future?: boolean
}

/**
 * 时间范围。
 *
 * - `7d` —— 近 7 日，一天一个点；
 * - `1d` —— 今日 05:00 至次日 05:00，每小时一个点（共 25 个，首尾都是 05:00）。
 */
export type TimelineRange = '7d' | '1d'

/**
 * 今日窗口的起始小时，须与后端 `UsageQueryService.DAY_START_HOUR` 一致。
 *
 * 同时决定横轴第二行「当日 / 次日」分段线的位置。
 */
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
