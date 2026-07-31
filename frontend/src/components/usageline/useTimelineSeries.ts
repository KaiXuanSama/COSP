import { computed, type Ref } from 'vue'
import { buildAxisTicks } from '../usagechart/axisTicks'
import {
  SERIES,
  type AxisDateSegment,
  type AxisLabel,
  type RenderedSeries,
  type UsageTimelinePoint,
} from './usageline'

/**
 * 把归约后的点位换算成可直接绘制的折线与两行横轴标签。
 *
 * <h2>为什么在数据层做换算</h2>
 * SVG 折线需要的是坐标序列，而上游给的是数值序列。这层换算涉及轴上限取整、
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
 * <h2>为什么形态只看数据、不看当前范围</h2>
 * 切换范围时 {@code range} 立即变，而 {@code points} 要等请求回来才变 ——
 * 中间那几帧会用「今日」的格式器去渲染日期桶，标签闪出 `2026-07-24` 这样的原始值。
 * 桶标识自身已经带着形态（`yyyy-MM-dd` 与 `HH:mm` 长度不同），据它判断则两者
 * 永远同步，desync 这一类问题从根上消失。
 *
 * @param points 后端返回的点位（已补零，未来段带 `future` 标记）
 */
export function useTimelineSeries(points: Ref<UsageTimelinePoint[]>) {
  /**
   * 轴上限 —— 取三条线中的最大值再向上取整。
   *
   * 只看已发生的点：未来段恒为 0，参与求极值不会改变结果，但把它们排除掉
   * 使这里与 {@link series} 的取值范围保持一致，将来改动不易漏。
   */
  const ceiling = computed(() => {
    const max = points.value.reduce((acc, point) => {
      if (point.future) return acc
      return SERIES.reduce((inner, series) => Math.max(inner, series.valueOf(point)), acc)
    }, 0)
    const ticks = buildAxisTicks(max)
    return ticks.length ? ticks[ticks.length - 1].value : max
  })

  /** 纵轴刻度，与柱状图共用取整规则，两卡片的读数风格一致。 */
  const axisTicks = computed(() => buildAxisTicks(ceiling.value))

  /**
   * 三条折线的坐标序列。
   *
   * 横向位置按<strong>全部</strong>点位换算（横轴始终覆盖完整一天），
   * 但只画到最后一个已发生的点为止 —— 未来段的 0 是「还没发生」而非
   * 「用量为零」，连过去会让折线贴底延伸到轴末，读起来像用量已归零。
   */
  const series = computed<RenderedSeries[]>(() => {
    const total = points.value.length
    const drawable = points.value.filter((point) => !point.future)
    return SERIES.map((config) => ({
      config,
      points: drawable.map((point, index) => ({
        x: xRatioOf(index, total),
        y: yRatioOf(config.valueOf(point), ceiling.value),
        value: config.valueOf(point),
      })),
    }))
  })

  /**
   * 横轴第一行的时刻 / 日期标签。
   *
   * 日期桶换算成 `M/D`，时刻桶已是 `HH:mm` 故原样取用 —— 形态由桶自己决定，
   * 见本 composable 的类注释。
   *
   * 稀疏与未来时段都折进 {@code opacity}，而非交给组件用类名切换：标签与端点共用
   * 一套形变模型，不透明度是被逐帧插值的量，用类名会让它在滑动途中突然跳变。
   * 隐去的标签仍留在序列里 —— 它占的那个位置是形变配对的依据，抽掉会让后面的
   * 标签整体错位一格。
   */
  const axisLabels = computed<AxisLabel[]>(() =>
    points.value.map((point, index) => ({
      x: xRatioOf(index, points.value.length),
      y: 0,
      opacity: labelOpacityOf(
        isLabelVisible(index, points.value.length),
        point.future === true,
      ),
      text: point.label ?? bucketLabel(point.bucket),
    })),
  )

  /** 横轴第二行的日期分段，仅时刻桶（今日范围）才有跨夜问题。 */
  const dateSegments = computed<AxisDateSegment[]>(() =>
    isClockBucket(points.value[0]?.bucket) ? buildDateSegments(points.value) : [],
  )

  /**
   * 已发生时段的数量 —— 即折线实际画到第几个点。
   *
   * hover 要据此把光标限制在有数据的区间内：悬停到未来段会读出一串 0，
   * 那是「还没发生」而非真实读数。
   */
  const drawableCount = computed(() => points.value.filter((point) => !point.future).length)

  return { ceiling, axisTicks, series, axisLabels, dateSegments, drawableCount }
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
 * 桶标识是否为时刻桶（完整时间戳）而非日期桶（`yyyy-MM-dd`）。
 *
 * 据桶自身判断形态，而非据当前范围 —— 范围立即变、数据要等归约完成，
 * 两者不同步的那几帧会用错格式器，标签闪出原始值。
 *
 * 时刻桶是 `yyyy-MM-ddTHH:mm:ss`，日期桶是 `yyyy-MM-dd`，故以 `T` 分隔符为准。
 *
 * @param bucket 桶标识；缺省视为日期
 */
export function isClockBucket(bucket: string | undefined): boolean {
  return bucket !== undefined && bucket.includes('T')
}

/**
 * 桶标识 → 横轴标签文字。
 *
 * 时刻桶取时间部分的 `HH:mm`（桶键含日期，整串显示会撑爆横轴），
 * 日期桶缩成 `M/D`。归约层已给出 `label` 时优先用它，此函数是缺省兜底。
 */
export function bucketLabel(bucket: string): string {
  return isClockBucket(bucket) ? bucket.slice(11, 16) : shortDate(bucket)
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
 * 未来时段标签的不透明度。
 *
 * 保留而非隐去：它们标出「今天还剩多少时间」，正是完整显示一天的意义所在。
 * 但淡化能让「折线止于此处是因为还没发生」这件事不言自明。
 */
export const FUTURE_LABEL_OPACITY = 0.42

/**
 * 标签的目标不透明度。
 *
 * 把「稀疏隐去」与「未来淡化」两种状态归到同一个量上，使它们都能被形变模型逐帧插值。
 * 隐去取 0 而非移除元素 —— 位置是形变配对的依据，抽掉会让后面的标签错位一格。
 *
 * @param visible 稀疏规则是否让这一格显示
 * @param future 该时段是否尚未到来
 */
export function labelOpacityOf(visible: boolean, future: boolean): number {
  if (!visible) return 0
  return future ? FUTURE_LABEL_OPACITY : 1
}

/**
 * 构造横轴第二行的日期分段。
 *
 * <h2>为什么需要这一行</h2>
 * 今日窗口从 05:00 跨到次日 05:00，单看时刻行无法判断 02:00 属于哪一天。
 * 补一行日期后，跨夜语义在视觉上不言自明。
 *
 * <h2>日期取自桶键，而非当前时刻</h2>
 * 桶键是完整时间戳，日期直接读得出来。这比 `new Date()` 可靠 ——
 * 窗口在建流时算定、此后冻结，若用当前时刻推断，跨过午夜后第一段会被标成
 * 「明天」，而它实际代表的仍是建流那天。
 *
 * <h2>分界线落在午夜对应的比例位置</h2>
 * 日期行是标签而非刻度，因此分界线只需落在时间轴上正确的<strong>比例</strong>位置，
 * 无须与某条刻度线严格重合。
 *
 * @param points 完整一天的点位（时刻桶）
 */
export function buildDateSegments(points: UsageTimelinePoint[]): AxisDateSegment[] {
  if (!points.length) return []

  const total = points.length
  const firstDate = datePartOf(points[0].bucket)

  // 首个与自己不同日的点即跨夜处；找不到说明整条轴同属一天
  const midnightIndex = points.findIndex((point) => datePartOf(point.bucket) !== firstDate)
  if (midnightIndex <= 0 || midnightIndex > total - 1) {
    return [{ label: monthDay(firstDate), start: 0, width: 1 }]
  }

  const boundary = xRatioOf(midnightIndex, total)
  return [
    { label: monthDay(firstDate), start: 0, width: boundary },
    { label: monthDay(datePartOf(points[midnightIndex].bucket)), start: boundary, width: 1 - boundary },
  ]
}

/** 桶键的日期部分。 */
function datePartOf(bucket: string): string {
  return bucket.slice(0, 10)
}

/** `yyyy-MM-dd` → `M/D`。 */
function monthDay(date: string): string {
  return shortDate(date)
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
