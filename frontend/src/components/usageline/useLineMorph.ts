import { computed, getCurrentScope, onScopeDispose, shallowRef, watch, type Ref } from 'vue'
import type { SeriesPoint } from './usageline'

/**
 * 折线端点的形变模型 —— 点数变化时的复用、进场与退场。
 *
 * <h2>为什么由「点动」带动「线动」</h2>
 * 折线的视觉主体是线，但线本身无法插值：{@code polyline} 的 {@code points} 是一个
 * 坐标字符串，7 个点与 25 个点的字符串长度都不同，CSS 无从在两者之间过渡。
 *
 * 端点则不同 —— 每个点是独立的 DOM 节点，位置由 {@code left} / {@code bottom}
 * 百分比给出，都是可过渡的属性。于是把动画的主体交给点：
 * 点各自平滑移动到新位置，线每帧按当前点集重算路径，自然就跟着形变。
 * 这比直接补间路径简单得多，也不必处理点数不等时的重采样。
 *
 * <h2>与柱状图形变模型的异同</h2>
 * 复用规则完全一致 —— 按<strong>位置序号</strong>配对而非业务身份：
 * 切换范围时点的身份被整批替换（日期 → 时刻），按身份配对找不到任何留存元素，
 * 只能退化成整批淡出淡入。按位置配对则第 N 个点永远接着上一批的第 N 个点演化。
 *
 * 差异在于位移的实现。柱状图的横向位置由 flex 分配，位置跳变时没有任何属性值
 * 在变化，故需把 {@code flex-grow} 权重变成可过渡的属性、让位移成为宽度变化的
 * 副作用。折线的点直接用 {@code left} 定位，位移本就是一次属性变化，
 * CSS 过渡可以直接接手，无需这层间接。
 *
 * <h2>为何在数据层而非 DOM 层解决</h2>
 * 形变的本质是「同一个 DOM 节点的样式值连续变化」，因此关键在于让 Vue 认为
 * 新旧两批点是同一批节点 —— 即渲染 key 必须取位置序号。本模块产出的
 * {@link MorphPoint.slot} 就是这个 key。
 */

/**
 * 进出场动画时长（ms），须与 CSS 里端点的过渡时长一致。
 *
 * 退场点要在 DOM 里存活满这段时间才能移除：它是「上一批比当前批多出来的尾部」
 * 派生出来的，一旦提前推进配对基准，正在淡出的元素就被摘掉，动画无从播放。
 */
export const LINE_MORPH_DURATION = 420

/**
 * 进场点的起始横向偏移（占绘图区宽度的比例）。
 *
 * 新增点从目标位置右侧一段距离淡入，观感是「从右侧滑进来」而非凭空出现。
 * 取值不宜大：过大时新点会从图外很远处飞入，与「留存点小幅调整」的运动性质割裂。
 */
export const ENTER_X_OFFSET = 0.08

/** {@link useLineMorph} 的可调参数。 */
export interface LineMorphOptions {
  /** 进出场时长（ms），需与 CSS 过渡同值。 */
  duration?: number
}

/** 一个端点在形变过程中的状态。 */
export interface MorphPoint {
  /**
   * 点在折线中的位置序号（自左向右，从 0 起）。
   *
   * 作为渲染 key —— 同序号的点跨批次复用同一 DOM 节点，
   * 其位置变化才能被 CSS transition 捕捉为平滑移动。
   */
  slot: number
  /** 横向位置占绘图区宽度的比例。 */
  x: number
  /** 纵向位置占绘图区高度的比例，自下而上。 */
  y: number
  /** 原始数值，供浮框显示。 */
  value: number
  /** 生命周期：{@code enter} 新滑入，{@code leave} 正在退出，{@code stable} 原地移动。 */
  phase: 'stable' | 'enter' | 'leave'
  /** 不透明度。进场起始帧与退场终态为 0，使滑入滑出伴随淡入淡出。 */
  opacity: number
}

/**
 * 上一批端点及其占用的位置。
 *
 * 必须把 slot 与坐标一起记住，不能事后由下标推算 —— slot 就是渲染 key，
 * 一旦重排，同一批点会改去复用别的 DOM 节点。
 */
export interface LineMorphSnapshot {
  slot: number
  point: SeriesPoint
}

/**
 * 计算一批端点的形变状态。
 *
 * @param points 目标端点（当前范围的数据）
 * @param previous 上一批端点及其占位；首次渲染传空数组
 * @param settled 是否已进入目标态。为 {@code false} 时新增点仍停在起始位置
 *   （目标位置右侧、透明），供第一帧渲染用；下一帧传 {@code true} 才驱动它滑到目标。
 *   留存点不受此参数影响 —— 它们的起点就是自己上一刻的位置，本就连续。
 */
export function buildMorphPoints(
  points: SeriesPoint[],
  previous: LineMorphSnapshot[],
  settled = true,
): MorphPoint[] {
  const previousBySlot = new Map(previous.map((item) => [item.slot, item.point]))
  const result: MorphPoint[] = []

  points.forEach((point, slot) => {
    // 该位置在旧批里没有点，说明是新滑进来的
    const entering = !previousBySlot.has(slot)
    const pending = entering && !settled
    result.push({
      slot,
      // 起始帧停在目标位置右侧：给 CSS 过渡一个起点，下一帧才滑到位。
      // 纵向不偏移 —— 让点沿水平方向进入，运动方向单一更易读。
      x: pending ? Math.min(1, point.x + ENTER_X_OFFSET) : point.x,
      y: point.y,
      value: point.value,
      phase: entering ? 'enter' : 'stable',
      opacity: pending ? 0 : 1,
    })
  })

  // 旧批多出来的尾部点向右滑出并淡出；保留旧坐标作为过渡起点
  for (let slot = points.length; slot < previous.length; slot += 1) {
    const stale = previousBySlot.get(slot)
    if (!stale) continue
    result.push({
      slot,
      // 终态在原位右侧：位移与淡出同时发生，观感是「被推出图外」
      x: Math.min(1, stale.x + ENTER_X_OFFSET),
      y: stale.y,
      value: stale.value,
      phase: 'leave',
      opacity: 0,
    })
  }

  // 按位置升序输出：渲染顺序必须与 slot 一致，否则 Vue 会为对不上的 key
  // 移动 DOM 节点，正在过渡的点会被整体搬走，观感是一次硬跳。
  return result.sort((a, b) => a.slot - b.slot)
}

/**
 * 结算一批端点的占位，作为下一次配对的基准。
 *
 * 只收留存点：退场点的位置本次已让出，不应再占用名额。
 *
 * @param points 本批端点
 */
export function snapshotOfPoints(points: SeriesPoint[]): LineMorphSnapshot[] {
  return points.map((point, slot) => ({ slot, point }))
}

/**
 * 判断新一批里是否存在「凭空出现」的点。
 *
 * 只有它们需要两帧提交 —— 留存点在 DOM 里已有前一刻的位置，直接改值就能被
 * CSS 过渡捕捉；而全新节点若起止值同帧写入会被合并，表现为「一出现就在终点」。
 *
 * @param points 新一批端点
 * @param previous 上一批端点及其占位
 */
export function hasEnteringPoint(points: SeriesPoint[], previous: LineMorphSnapshot[]): boolean {
  return points.length > previous.length
}

/**
 * 把端点数据接成随范围切换连续形变的状态流。
 *
 * 记忆不在 computed 内部写入：computed 可能因依赖变化被重复求值，
 * 若在其中改写记忆，第二次求值就会拿「刚写进去的新值」当旧值，配对随之失真。
 * 它也不能在数据变化的当帧写入 —— 那会让退场态与起始态来不及渲染。
 *
 * @param points 当前范围的端点坐标
 * @param options 时长
 */
export function useLineMorph(points: Ref<SeriesPoint[]>, options: LineMorphOptions = {}) {
  const duration = options.duration ?? LINE_MORPH_DURATION

  /** 上一批端点及其占位，用于按位置配对。 */
  const previous = shallowRef<LineMorphSnapshot[]>([])

  /**
   * 新增点是否已被推向目标态。
   *
   * 新点在 DOM 里没有前一刻的位置可延续，若起止值写在同一帧，浏览器会把两者
   * 合并成「一开始就在终点」，CSS transition 无从播放。故拆成两帧：
   * 先渲染起始态（右侧、透明）、下一帧再切到目标态。
   */
  const settled = shallowRef(true)

  /**
   * 折线是否正在等待新增点落位。
   *
   * 点数增多时线要跟着淡入：新点尚未到位时线已按新点集重算过路径，
   * 若直接以最终不透明度出现，那一段延伸出去的线会显得突然。
   */
  const lineEntering = computed(() => !settled.value)

  /** 待执行的落位任务，切换过快时用于取消上一次，避免起始态被提前抹掉。 */
  let settleFrame: number | null = null

  /** 待执行的退场清理任务，切换过快时用于取消上一次。 */
  let retireTimer: number | null = null

  const morphPoints = computed(() =>
    buildMorphPoints(points.value, previous.value, settled.value),
  )

  /*
   * 关键时序：起始态与退场清理必须分开调度，两者的正确时机不同。
   *
   * `morphPoints` 以 previous 为配对基准，退场点正是「previous 比 points 多出来的
   * 尾部」派生出来的。因此 previous 一旦推进，那些点当场从列表里消失 ——
   * 提前推进等于把正在淡出的元素直接从 DOM 摘掉，观感就是硬切。
   */
  watch(points, (next) => {
    settled.value = !hasEnteringPoint(next, previous.value)
    scheduleSettle()
    scheduleRetire(snapshotOfPoints(next))
  }, { flush: 'post' })

  /** 下一帧放开起始位置，让新增点滑向目标。 */
  function scheduleSettle(): void {
    cancelSettle()
    if (typeof requestAnimationFrame !== 'function') {
      settled.value = true
      return
    }
    // 双帧：第一帧确保起始位置已提交到合成器，第二帧再改值才会产生过渡
    settleFrame = requestAnimationFrame(() => {
      settleFrame = requestAnimationFrame(() => {
        settleFrame = null
        settled.value = true
      })
    })
  }

  /**
   * 动画结束后推进配对基准，退场点随之从列表移除。
   *
   * 必须等满一个 {@link duration}：退场点由「旧批多出来的尾部」派生，
   * 提前推进等于把正在淡出的元素直接摘掉。
   */
  function scheduleRetire(next: LineMorphSnapshot[]): void {
    cancelRetire()
    if (typeof window === 'undefined') {
      previous.value = next
      return
    }
    retireTimer = window.setTimeout(() => {
      retireTimer = null
      previous.value = next
    }, duration)
  }

  function cancelSettle(): void {
    if (settleFrame !== null && typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(settleFrame)
    }
    settleFrame = null
  }

  function cancelRetire(): void {
    if (retireTimer !== null && typeof window !== 'undefined') {
      window.clearTimeout(retireTimer)
    }
    retireTimer = null
  }

  if (getCurrentScope()) {
    onScopeDispose(() => {
      cancelSettle()
      cancelRetire()
    })
  }

  return { morphPoints, lineEntering }
}
