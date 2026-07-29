import { computed, type Ref } from 'vue'
import { buildAxisTicks } from '../usagechart/axisTicks'
import {
  DAY_START_HOUR,
  SERIES,
  type AxisDateSegment,
  type BucketHours,
  type RenderedSeries,
  type TimelineRange,
  type UsageTimelinePoint,
} from './usageline'

/**
 * 把后端点位换算成可直接绘制的三条折线与两行横轴标签。
 *
 * <h2>为什么在数据层做换算</h2>
 * SVG 折线需要的是坐标序列，而后端给的是数值序列。这层换算涉及轴上限取整、
 * 空数据兜底、跨夜日期归属等一堆边界，全都是纯计算 —— 放在组件里会让模板
 * 难以测试，抽到这里则每条规则都能单测覆盖。
 *
 * <h2>横轴为何用比例而非像素</h2>
 * 卡片宽度随窗口变化，若在 JS 里算像素就得监听 resize 并重算。
 * 输出 0~1 的比例、由 SVG 的 `viewBox` 或 CSS 百分比负责映射到实际尺寸，
 * 宽度变化便完全交给浏览器处理。
 */

/** 单点时的横向位置 —— 居中，避免孤立点贴在左边缘看起来像被截断。 */
const SINGLE_POINT_X = 0.5

/**
 * 计算折线序列与横轴标签。
 *
 * @param points 后端返回的点位（已补零、已截断未来）
 * @param range 当前时间范围，决定横轴标签的形态
 * @param bucketHours 时段颗粒度，仅 `1d` 范围有意义
 */
export function useTimelineSeries(
  points: Ref<UsageTimelinePoint[]>,
  range: Ref<TimelineRange>,
  bucketHours: Ref<BucketHours>,
) {
  /**
   * 轴上限 —— 取三条线中的最大值再向上取整。
   *
   * 用总量线的最大值即可（它恒是三者中最大的），但仍按全部序列求极值，
   * 以免将来调整 {@link SERIES} 时这里悄悄失效。
   */
  const ceiling = computed(() => {
    const max = points.value.reduce((acc, point) => {
      return SERIES.reduce((inner, series) => Math.max(inner, series.valueOf(point)), acc)
    }, 0)
    const ticks = buildAxisTicks(max)
    return ticks.length ? ticks[ticks.length - 1].value : max
  })

  /** 纵轴刻度，与柱状图共用取整规则，两卡片的读数风格一致。 */
  const axisTicks = computed(() => buildAxisTicks(ceiling.value))

  /** 三条折线的坐标序列。 */
  const series = computed<RenderedSeries[]>(() =>
    SERIES.map((config) => ({
      config,
      points: points.value.map((point, index) => ({
        x: xRatioOf(index, points.value.length),
        y: yRatioOf(config.valueOf(point), ceiling.value),
        value: config.valueOf(point),
      })),
    })),
  )

  /**
   * 横轴第一行的时刻 / 日期标签。
   *
   * 近 7 日直接用 `M/D`；今日时段用桶标签本身（已是 `HH:mm`），
   * 点数多时隔位显示以免挤在一起。
   */
  const axisLabels = computed(() =>
    points.value.map((point, index) => ({
      key: point.bucket,
      text: range.value === '1d' ? point.bucket : shortDate(point.bucket),
      x: xRatioOf(index, points.value.length),
      visible: isLabelVisible(index, points.value.length),
    })),
  )

  /** 横轴第二行的日期分段，仅今日时段范围有。 */
  const dateSegments = computed<AxisDateSegment[]>(() =>
    range.value === '1d' ? buildDateSegments(points.value, bucketHours.value) : [],
  )

  return { ceiling, axisTicks, series, axisLabels, dateSegments }
}

/**
 * 点在横轴上的位置比例。
 *
 * 首尾点分别贴住绘图区左右边缘（0 与 1），这样折线铺满整个宽度；
 * 只有一个点时居中，否则它会孤零零贴在左边缘，看起来像图被截断了。
 */
export function xRatioOf(index: number, total: number): number {
  if (total <= 1) return SINGLE_POINT_X
  return index / (total - 1)
}

/** 数值在纵轴上的位置比例；上限非正时返回 0，避免 NaN 流入 SVG 坐标。 */
export function yRatioOf(value: number, ceiling: number): number {
  return ceiling > 0 && value > 0 ? Math.min(1, value / ceiling) : 0
}

/** `yyyy-MM-dd` → `M/D`，与柱状图的横轴标签格式一致。 */
export function shortDate(date: string): string {
  const parts = date.split('-')
  return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : date
}

/**
 * 决定某个标签是否显示。
 *
 * 卡片宽度有限，12 个以上的时刻标签会互相挤压。隔位显示既保住可读性，
 * 又始终保留首尾两个 —— 它们标出了时间轴的两端，是最不能省的。
 *
 * @param index 点的序号
 * @param total 点的总数
 */
export function isLabelVisible(index: number, total: number): boolean {
  if (total <= 1) return true
  if (index === 0 || index === total - 1) return true
  // 每多出一倍点数就再稀疏一档，标签间距因此大致恒定
  const stride = Math.max(1, Math.ceil(total / 12))
  return index % stride === 0
}

/**
 * 构造横轴第二行的日期分段。
 *
 * <h2>为什么需要这一行</h2>
 * 今日窗口从 05:00 跨到次日 05:00，单看时刻行无法判断 02:00 属于哪一天。
 * 补一行日期后，跨夜语义在视觉上不言自明。
 *
 * <h2>分界线按时间比例定位，不对齐桶边界</h2>
 * 从 05:00 到午夜是 19 小时，而 19 是质数 —— 除 1 小时外没有任何颗粒度能整除它，
 * 午夜必然落在某个桶的内部（如 2 小时颗粒度下位于 23:00–01:00 那一桶正中）。
 *
 * 这并不妨碍表达：日期行是标签而非刻度，分界线只需落在时间轴上正确的<strong>比例</strong>
 * 位置。硬要对齐桶边界反而会把 00:00 画到 23:00 或 01:00 上，那才是真的错位。
 *
 * @param points 已截断的点位
 * @param bucketHours 时段颗粒度
 */
export function buildDateSegments(
  points: UsageTimelinePoint[],
  bucketHours: BucketHours,
): AxisDateSegment[] {
  if (!points.length) return []

  const total = points.length
  const today = new Date()

  /** 午夜距窗口起点的小时数：05:00 → 24:00 共 19 小时。 */
  const hoursToMidnight = 24 - DAY_START_HOUR
  /** 换算成「第几个点」的位置，通常是小数。 */
  const midnightSlot = hoursToMidnight / bucketHours

  // 尚未跨过午夜：整条轴都属同一天，一段即可
  if (midnightSlot >= total - 1) {
    return [{ label: monthDay(today), start: 0, width: 1 }]
  }

  const boundary = xRatioOf(midnightSlot, total)
  const tomorrow = new Date(today.getTime() + 86_400_000)
  return [
    { label: monthDay(today), start: 0, width: boundary },
    { label: monthDay(tomorrow), start: boundary, width: 1 - boundary },
  ]
}

/** `Date` → `M/D`。 */
function monthDay(date: Date): string {
  return `${date.getMonth() + 1}/${date.getDate()}`
}

/**
 * 把坐标序列拼成 SVG `points` 属性。
 *
 * 纵向要翻转：SVG 的 y 轴向下增长，而数据的 y 是「距底部的比例」。
 *
 * @param seriesPoints 已换算的坐标
 * @param width viewBox 宽度
 * @param height viewBox 高度
 */
export function toPolylinePoints(
  seriesPoints: Array<{ x: number; y: number }>,
  width: number,
  height: number,
): string {
  return seriesPoints
    .map((point) => `${(point.x * width).toFixed(2)},${((1 - point.y) * height).toFixed(2)}`)
    .join(' ')
}
