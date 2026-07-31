/**
 * 离散范围滑块的选择模型 —— 与渲染、DOM、日期语义全部无关的纯逻辑。
 *
 * ## 为什么用「下标」而不是「值」
 * 组件面对的是一排<strong>等距的离散位置</strong>，位置本身没有量纲：可以是日期，
 * 也可以是版本号、章节、档位。把模型建在下标上，组件就不必知道每个位置代表什么，
 * 调用方只需按顺序给出刻度并解释下标。若模型里出现日期，这个组件就只能选日期了。
 *
 * ## 区间是闭区间
 * `{ start: 2, end: 8 }` 表示<strong>含</strong>两端共 7 个位置。跨度因此是
 * `end - start + 1` 而非差值 —— 这与「窗口宽度 7 天」这类说法直接对应，
 * 若用半开区间，每次读跨度都要在脑子里加一。
 *
 * ## 约束只有两条
 * 跨度下限与上限（{@link RangeBounds}）。它们足以表达「至少 7 天、最多 15 天」这类需求，
 * 而所有位移操作都必须在移动后重新满足这两条 —— 拖动的手感差异全在于
 * 「谁被动让位」：拖左端时右端固定，拖整块时跨度固定。
 */

/** 一个可选位置。`label` 供刻度文案使用，缺省则不显示。 */
export interface RangeSliderTick {
  /** 稳定标识，用于 `v-for` 的 key；不参与选择逻辑。 */
  key: string
  /** 刻度文案。只有需要标注的位置才给，其余留空以免横向拥挤。 */
  label?: string
}

/** 闭区间选择，两端都是刻度下标。 */
export interface RangeSelection {
  start: number
  end: number
}

/** 选择的合法范围。三个字段都已净化，可直接参与算术。 */
export interface RangeBounds {
  /** 刻度总数。 */
  count: number
  /** 跨度下限（含），至少 1。 */
  minSpan: number
  /** 跨度上限（含），至少等于 {@link minSpan}，至多 {@link count}。 */
  maxSpan: number
}

/** 把任意数值收敛成合法下标。非数值按 0 处理，避免 NaN 顺着算式扩散。 */
export function clampIndex(index: number, count: number): number {
  if (!Number.isFinite(index)) return 0
  const upper = Math.max(0, count - 1)
  return Math.min(upper, Math.max(0, Math.round(index)))
}

/** 闭区间的跨度（含两端）。 */
export function spanOf(selection: RangeSelection): number {
  return selection.end - selection.start + 1
}

/**
 * 净化边界配置。
 *
 * <p>顺序不可颠倒：上限要先与下限比较再与总数比较，否则
 * `minSpan = 10, maxSpan = 3, count = 20` 会得到 `maxSpan = 3 < minSpan`，
 * 后续所有约束判断都会互相矛盾且不会报错。
 */
export function resolveBounds(count: number, minSpan?: number, maxSpan?: number): RangeBounds {
  const total = Math.max(1, Math.floor(count) || 0)
  const min = Math.min(total, Math.max(1, Math.floor(minSpan ?? 1) || 1))
  const max = Math.min(total, Math.max(min, Math.floor(maxSpan ?? total) || total))
  return { count: total, minSpan: min, maxSpan: max }
}

/**
 * 刻度在轨道上的位置比例，0 为最左、1 为最右。
 *
 * <p>分母是 `count - 1` 而非 `count`：比例描述的是<strong>点</strong>的位置，
 * n 个点之间有 n-1 段间隔。用 `count` 会让最后一个点落在 `(n-1)/n` 处，
 * 右端凭空空出一格 —— 而这个偏差随刻度增多而变小，很容易被误认为是渲染误差。
 *
 * <p>只有一个刻度时无从分布，返回 0。
 */
export function tickRatio(index: number, count: number): number {
  if (count <= 1) return 0
  return clampIndex(index, count) / (count - 1)
}

/** 位置比例 → 最近的刻度下标。越界比例被收敛到两端。 */
export function indexFromRatio(ratio: number, count: number): number {
  if (count <= 1) return 0
  if (!Number.isFinite(ratio)) return 0
  return clampIndex(ratio * (count - 1), count)
}

/**
 * 把任意区间收敛成满足边界的合法选择。
 *
 * <p>这是所有操作的入口守卫：外部传入的 `modelValue` 可能来自持久化的旧状态、
 * 也可能在刻度数量变化后失效，直接参与算术会算出负下标或倒置区间。
 *
 * <h2>谁让位</h2>
 * 补足下限时优先<strong>向右</strong>扩，右侧到底再向左扩；收缩到上限时从右侧收。
 * 即以 `start` 为锚 —— 时间轴场景下这对应「保住起点，调整终点」，
 * 比两端同时动更可预期。
 */
export function normalizeSelection(selection: RangeSelection, bounds: RangeBounds): RangeSelection {
  const { count, minSpan, maxSpan } = bounds
  let start = clampIndex(selection.start, count)
  let end = clampIndex(selection.end, count)
  if (start > end) {
    const swap = start
    start = end
    end = swap
  }

  if (end - start + 1 < minSpan) {
    end = start + minSpan - 1
    if (end > count - 1) {
      end = count - 1
      start = Math.max(0, end - minSpan + 1)
    }
  }
  if (end - start + 1 > maxSpan) {
    end = start + maxSpan - 1
  }

  return { start, end }
}

/**
 * 拖动左端点：右端固定，起点移到目标位置。
 *
 * <p>两条约束都作用在起点上：不能越过「离右端 minSpan」这条线（否则区间太窄），
 * 也不能退到「离右端 maxSpan」之外（否则太宽）。因此手感是<strong>顶住</strong>
 * 而非跳变 —— 继续拖只是无效，不会把右端拽着走。
 */
export function moveStart(selection: RangeSelection, target: number, bounds: RangeBounds): RangeSelection {
  const current = normalizeSelection(selection, bounds)
  const { count, minSpan, maxSpan } = bounds
  let start = clampIndex(target, count)
  start = Math.min(start, current.end - minSpan + 1)
  start = Math.max(start, current.end - maxSpan + 1)
  return { start: clampIndex(start, count), end: current.end }
}

/** 拖动右端点：左端固定，约束与 {@link moveStart} 镜像。 */
export function moveEnd(selection: RangeSelection, target: number, bounds: RangeBounds): RangeSelection {
  const current = normalizeSelection(selection, bounds)
  const { count, minSpan, maxSpan } = bounds
  let end = clampIndex(target, count)
  end = Math.max(end, current.start + minSpan - 1)
  end = Math.min(end, current.start + maxSpan - 1)
  return { start: current.start, end: clampIndex(end, count) }
}

/**
 * 整块平移：跨度<strong>不变</strong>，起点移到目标位置。
 *
 * <p>关键是撞到边界时只停住、不压缩。若在这里让区间收窄，用户会看到
 * 「拖到头之后块变短了」，松手再拖回来也不会复原 —— 平移手势不该改变宽度。
 */
export function slideTo(selection: RangeSelection, targetStart: number, bounds: RangeBounds): RangeSelection {
  const current = normalizeSelection(selection, bounds)
  const span = spanOf(current)
  const highest = Math.max(0, bounds.count - span)
  const start = Math.min(highest, Math.max(0, Math.round(targetStart) || 0))
  return { start, end: start + span - 1 }
}

/** 整块按步数平移，供键盘操作使用。 */
export function shiftBy(selection: RangeSelection, delta: number, bounds: RangeBounds): RangeSelection {
  const current = normalizeSelection(selection, bounds)
  return slideTo(current, current.start + delta, bounds)
}

/** 两个选择是否等价。用于避免拖动途中重复 emit 同一个值。 */
export function selectionEquals(a: RangeSelection, b: RangeSelection): boolean {
  return a.start === b.start && a.end === b.end
}
