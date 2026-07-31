/**
 * token 用量折线图的类型契约。
 *
 * <h2>为什么与柱状图分开</h2>
 * 折线的横轴是<strong>时间</strong>（日期或时段），柱状图的横轴在下钻后变成
 * 供应商 / 模型这类<strong>类别</strong>。把类别之间连成线会暗示一种不存在的顺序关系，
 * 属于图表语法错误。两者表达的问题也不同：折线看趋势（涨还是跌），
 * 柱状图看构成（谁占多少），故各自成图、各用一套契约。
 *
 * <h2>数据从哪来</h2>
 * 点位<strong>不再由后端算好</strong>：三张图表共用一条 SSE，后端只下发足够细的
 * 聚合数据与单条增量，归桶策略在 `@/features/usage-series`。本文件的
 * {@link UsageTimelinePoint} 是那层归约的产物（{@code HourlyPoint} / {@code DailyPoint}
 * 都能直接充当），只服务渲染。总量不在其中 —— 它恒等于输入加输出，由 {@link SERIES} 现算。
 */

/** 折线图消费的一个点位，由 `@/features/usage-series` 归约得出。 */
export interface UsageTimelinePoint {
  /**
   * 时间桶标识：近 7 日为 `yyyy-MM-dd`，今日时段为完整时间戳
   * `yyyy-MM-ddTHH:mm:ss`。
   *
   * 今日窗口跨午夜，故时刻桶必须带日期 —— 只给 `HH:mm` 则 `01:00` 分不清属于
   * 当天还是次日。展示用的短标签见 {@link label}。
   */
  bucket: string
  /**
   * 展示用标签，缺省时由 {@link bucketLabel} 从 {@link bucket} 推导。
   *
   * 时刻桶的桶键是完整时间戳，直接显示会撑爆横轴，故由归约层给出 `HH:mm`。
   */
  label?: string
  /** 该桶内的输入 token 总量。 */
  inputTokens: number
  /** 该桶内的输出 token 总量。 */
  outputTokens: number
  /**
   * 该时段是否尚未到来。
   *
   * 今日时段范围恒为完整的 24 小时，因此横轴不随时间伸缩；但未来段的 0
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
 * 今日窗口的起始小时。
 *
 * 从归约层重导出而非另立一份 —— 归桶与展示必须用同一个值，各定义一份会在
 * 不一致时表现为「数据按 5 点切、分界线画在别处」，且无编译错误。
 * 这里只用于横轴第二行「当日 / 次日」分段线的位置。
 */
export { DAY_START_HOUR } from '@/features/usage-series'

/**
 * 一个数据系列的配置。
 *
 * 三个系列共用一个纵轴 —— 总量恒等于输入加输出，三者同量纲。
 * 但只有总量画成折线，见 {@link SeriesConfig.drawn}。
 */
export interface SeriesConfig {
  key: 'total' | 'input' | 'output'
  /** 图例与浮框中的名称。 */
  label: string
  /** 从点位取值。总量在此现算，不占用传输字段。 */
  valueOf: (point: UsageTimelinePoint) => number
  /** 线条颜色的 CSS 变量表达式。 */
  color: string
  /** SVG `stroke-dasharray`；实线传 `undefined`。 */
  dash?: string
  /**
   * 是否绘制成折线。
   *
   * 输入 token 占总量的绝大部分（实测常在 99% 以上），输出则贴着横轴 ——
   * 三条线画出来是「总量与输入几乎重合、输出压成一条直线」，
   * 既看不出输入的独立走势，也看不出输出的起伏，反而让图变脏。
   *
   * 故只画总量一条线表达趋势，输入与输出的绝对值改由悬停浮框给出：
   * 需要看构成时它们精确可读，不需要时不占用视觉带宽。
   *
   * <p>目前渲染层只对<strong>唯一</strong>被绘制的系列建形变实例，因此把本字段
   * 改成 true 并不足以让第二条线动起来 —— 还须让形变按系列分别建实例。
   */
  drawn: boolean
}

/**
 * 三个系列的定义，顺序即浮框中的列出顺序。
 *
 * 总量用强调色实线；输入与输出不绘制，只在浮框里出现，
 * 颜色与线型仍保留 —— 浮框的行首标记要与「若将来画出来会是什么样」保持一致。
 */
export const SERIES: SeriesConfig[] = [
  {
    key: 'total',
    label: '总量',
    valueOf: (point) => point.inputTokens + point.outputTokens,
    color: 'var(--usage-line-accent, #c27a3e)',
    drawn: true,
  },
  {
    key: 'input',
    label: '输入',
    valueOf: (point) => point.inputTokens,
    color: 'var(--usage-line-text-muted, #9a9590)',
    drawn: false,
  },
  {
    key: 'output',
    label: '输出',
    valueOf: (point) => point.outputTokens,
    color: 'var(--usage-line-text-muted, #9a9590)',
    dash: '4 3',
    drawn: false,
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
 * 横轴上的一个时刻 / 日期标签。
 *
 * 字段形状刻意与 {@link SeriesPoint} 对齐（都带 {@code x} / {@code y} / {@code opacity}），
 * 因此能与端点共用同一套形变模型：范围切换时标签跟着刻度一起滑动、淡入淡出，
 * 而不是当帧换掉一行文字。
 */
export interface AxisLabel {
  /** 横向位置占绘图区宽度的比例。 */
  x: number
  /**
   * 纵向位置 —— 标签排在单独一行，不参与纵向排布，恒为 0。
   *
   * 保留此字段是形变模型的要求：它对「位置」不作二维/一维的区分，
   * 少一个维度就得为标签另开一套换算。
   */
  y: number
  /**
   * 目标不透明度。
   *
   * 稀疏（{@code 0}）与未来时段（淡显）都由它表达，故这两类状态的变化也走同一条
   * 淡入淡出 —— 若改用 CSS 类名切换，标签会在滑动途中突然显影或消失。
   */
  opacity: number
  /** 标签文字。 */
  text: string
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
