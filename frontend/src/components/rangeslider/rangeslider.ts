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

/**
 * 刻度文案的显示方式。
 *
 * - `edges` —— 只在两个手柄下方显示当前端点的文案，随手柄一起移动。
 *   默认值：横轴上永远只有两个数字，是「当前选了哪一段」最直接的读法，
 *   且刻度再多也不会拥挤。
 * - `all` —— 显示每个刻度自带的 `label`（未给 label 的位置留空）。
 *   适合需要看清整条轴的刻度体系时，代价是调用方要自己隔位标注。
 * - `none` —— 不渲染文案行，控件只剩槽本身。
 */
export type RangeSliderLabelMode = 'edges' | 'all' | 'none'

/** 选择的合法范围。全部字段都已净化，可直接参与算术。 */
export interface RangeBounds {
  /** 刻度总数。 */
  count: number
  /** 跨度下限（含），至少 1。 */
  minSpan: number
  /** 跨度上限（含），至少等于 {@link minSpan}，至多可达区间宽度。 */
  maxSpan: number
  /**
   * 可达区间的左界（含），默认 0。
   *
   * <p>它<strong>不改变刻度数量</strong>：左侧的点照样画出来，只是选择块与手柄
   * 不能退到这条线以外。这是「可达 / 不可达」与「有没有数据」两件事分开的关键 ——
   * 可达位置即使没有数据也照常显示为空，不可达位置即使有数据也不能选。
   */
  minIndex: number
  /** 可达区间的右界（含），默认 {@link count} − 1。语义与 {@link minIndex} 镜像。 */
  maxIndex: number
}

/** 把任意数值收敛成合法下标。非数值按 0 处理，避免 NaN 顺着算式扩散。 */
export function clampIndex(index: number, count: number): number {
  if (!Number.isFinite(index)) return 0
  const upper = Math.max(0, count - 1)
  return Math.min(upper, Math.max(0, Math.round(index)))
}

/**
 * 把任意数值收敛到<strong>可达区间</strong>内。
 *
 * <p>与 {@link clampIndex} 的区别是上下界取自 `minIndex` / `maxIndex` 而非
 * `0` / `count - 1`。所有位移操作都必须走这一条，只钳到刻度总数是不够的 ——
 * 那样块能跑进不可达区。
 */
export function clampReachable(index: number, bounds: RangeBounds): number {
  if (!Number.isFinite(index)) return bounds.minIndex
  return Math.min(bounds.maxIndex, Math.max(bounds.minIndex, Math.round(index)))
}

/** 可达区间的宽度（含两端）。 */
export function reachableSpan(bounds: RangeBounds): number {
  return bounds.maxIndex - bounds.minIndex + 1
}

/** 某个下标是否可达。供渲染层区分点的明暗。 */
export function isReachable(index: number, bounds: RangeBounds): boolean {
  return index >= bounds.minIndex && index <= bounds.maxIndex
}

/** 闭区间的跨度（含两端）。 */
export function spanOf(selection: RangeSelection): number {
  return selection.end - selection.start + 1
}

/** {@link resolveBounds} 的可选参数，避免五个位置参数排错顺序。 */
export interface ResolveBoundsOptions {
  minSpan?: number
  maxSpan?: number
  /** 可达区间左界（含）。越界或非数值时按 0 处理 */
  minIndex?: number
  /** 可达区间右界（含）。越界或非数值时按 `count - 1` 处理 */
  maxIndex?: number
}

/**
 * 净化边界配置。
 *
 * <h2>四步顺序不可颠倒</h2>
 * <ol>
 *   <li>刻度总数至少 1；</li>
 *   <li>可达区间先钳到 `[0, count - 1]` 并保证 `minIndex <= maxIndex`（倒置则交换）；</li>
 *   <li>跨度下限钳到 `[1, 可达宽度]` —— 上界是<strong>可达宽度</strong>而非刻度总数，
 *       否则会得到「跨度下限大于可选区间」这种自相矛盾的配置，此后所有约束判断
 *       都无解且不会报错；</li>
 *   <li>跨度上限先与下限比较、再与可达宽度比较。</li>
 * </ol>
 * 第 3、4 步依赖第 2 步算出的可达宽度，第 4 步依赖第 3 步的下限。
 */
export function resolveBounds(count: number, options: ResolveBoundsOptions = {}): RangeBounds {
  const total = Math.max(1, Math.floor(count) || 0)
  const lastIndex = total - 1

  let low = Number.isFinite(options.minIndex)
    ? Math.min(lastIndex, Math.max(0, Math.round(options.minIndex as number)))
    : 0
  let high = Number.isFinite(options.maxIndex)
    ? Math.min(lastIndex, Math.max(0, Math.round(options.maxIndex as number)))
    : lastIndex
  if (low > high) {
    const swap = low
    low = high
    high = swap
  }

  const reachable = high - low + 1
  const min = Math.min(reachable, Math.max(1, Math.floor(options.minSpan ?? 1) || 1))
  const max = Math.min(reachable, Math.max(min, Math.floor(options.maxSpan ?? reachable) || reachable))

  return { count: total, minSpan: min, maxSpan: max, minIndex: low, maxIndex: high }
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
 * 也可能在刻度数量或可达区间变化后失效，直接参与算术会算出越界或倒置的区间。
 *
 * <h2>谁让位</h2>
 * 补足下限时优先<strong>向右</strong>扩，右侧到底再向左扩；收缩到上限时从右侧收。
 * 即以 `start` 为锚 —— 时间轴场景下这对应「保住起点，调整终点」，
 * 比两端同时动更可预期。
 *
 * <h2>边界一律取可达区间</h2>
 * 所有 `0` / `count - 1` 都换成 `minIndex` / `maxIndex`。可达宽度已在
 * {@link resolveBounds} 里保证不小于 `minSpan`，故「向右扩、右侧到底转向左扩」
 * 这条链必然能找到落点，不会陷入两侧都放不下的死角。
 */
export function normalizeSelection(selection: RangeSelection, bounds: RangeBounds): RangeSelection {
  const { minSpan, maxSpan, minIndex, maxIndex } = bounds
  let start = clampReachable(selection.start, bounds)
  let end = clampReachable(selection.end, bounds)
  if (start > end) {
    const swap = start
    start = end
    end = swap
  }

  if (end - start + 1 < minSpan) {
    end = start + minSpan - 1
    if (end > maxIndex) {
      end = maxIndex
      start = Math.max(minIndex, end - minSpan + 1)
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
 * <p>三条约束都作用在起点上：不能越过「离右端 minSpan」这条线（否则区间太窄）、
 * 不能退到「离右端 maxSpan」之外（否则太宽）、也不能退出可达区间。
 * 因此手感是<strong>顶住</strong>而非跳变 —— 继续拖只是无效，不会把右端拽着走。
 */
export function moveStart(selection: RangeSelection, target: number, bounds: RangeBounds): RangeSelection {
  const current = normalizeSelection(selection, bounds)
  const { minSpan, maxSpan } = bounds
  let start = clampReachable(target, bounds)
  start = Math.min(start, current.end - minSpan + 1)
  start = Math.max(start, current.end - maxSpan + 1)
  return { start: clampReachable(start, bounds), end: current.end }
}

/** 拖动右端点：左端固定，约束与 {@link moveStart} 镜像。 */
export function moveEnd(selection: RangeSelection, target: number, bounds: RangeBounds): RangeSelection {
  const current = normalizeSelection(selection, bounds)
  const { minSpan, maxSpan } = bounds
  let end = clampReachable(target, bounds)
  end = Math.max(end, current.start + minSpan - 1)
  end = Math.min(end, current.start + maxSpan - 1)
  return { start: current.start, end: clampReachable(end, bounds) }
}

/**
 * 整块平移：跨度<strong>不变</strong>，起点移到目标位置。
 *
 * <p>关键是撞到边界时只停住、不压缩。若在这里让区间收窄，用户会看到
 * 「拖到头之后块变短了」，松手再拖回来也不会复原 —— 平移手势不该改变宽度。
 *
 * <p>右界是 `maxIndex - span + 1` 而非 `count - span`：块的<strong>右端</strong>
 * 必须落在可达区内，故起点的上界要把跨度扣掉。
 */
export function slideTo(selection: RangeSelection, targetStart: number, bounds: RangeBounds): RangeSelection {
  const current = normalizeSelection(selection, bounds)
  const span = spanOf(current)
  const lowest = bounds.minIndex
  const highest = Math.max(lowest, bounds.maxIndex - span + 1)
  const requested = Number.isFinite(targetStart) ? Math.round(targetStart) : lowest
  const start = Math.min(highest, Math.max(lowest, requested))
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
