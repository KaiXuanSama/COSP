import { getCurrentScope, onScopeDispose, shallowRef, watch, type Ref } from 'vue'
import { cubicBezier, prefersReducedMotion } from '../usagechart/useAxisScale'
import type { SeriesPoint } from './usageline'

/**
 * 折线端点的形变模型 —— 点数变化时的复用、进场与退场。
 *
 * <h2>为什么插值在 JS 而不交给 CSS</h2>
 * 折线的视觉主体是线，但线本身无法过渡：{@code polyline} 的 {@code points} 是一个
 * 坐标字符串，7 个点与 25 个点的字符串长度都不同，CSS 无从在两者之间插值。
 * 因此线只能每帧按端点当前位置重算 —— 而「端点当前位置」必须是一个 JS 能读到的数。
 *
 * 若把端点位移交给 CSS（{@code left} / {@code bottom} 的 transition），这个数就只存在
 * 于合成器内部：JS 侧拿到的始终是终点值，线会当帧跳到位、点却还在慢慢滑，
 * 观感就是「线闪现一下、点随后才追上」。
 *
 * 故位移改由本模块逐帧算出：线与点读的是<strong>同一批数值</strong>，
 * 两者同步是构造出来的，不依赖 JS 缓动曲线与 CSS 曲线是否恰好对得上。
 *
 * <h2>为什么线要从尾端生长而非凭空多出一段</h2>
 * 新增点若从自己的目标位置附近淡入，连向它的那一段线会在第一帧就整段出现 ——
 * 淡入的是点，线却是硬切。改为让新增点从<strong>上一批的末点</strong>出发，
 * 线便是从原有尾端一路抽出来的；点数减少时对称地收回到新的末点。
 * 「线是跟着点走的」这一因果因此在两个方向上都成立。
 *
 * <h2>与柱状图形变模型的异同</h2>
 * 复用规则完全一致 —— 按<strong>位置序号</strong>配对而非业务身份：
 * 切换范围时点的身份被整批替换（日期 → 时刻），按身份配对找不到任何留存元素，
 * 只能退化成整批淡出淡入。按位置配对则第 N 个点永远接着上一批的第 N 个点演化。
 *
 * 差异在于谁来插值。柱体的高度与宽度都是 CSS 能过渡的属性，交给 CSS 最省事；
 * 折线多了「线必须读到中间值」这一条约束，只能自己算。
 */

/** 进出场动画时长（ms）。位移由 JS 逐帧驱动，故无需与任何 CSS 过渡对齐。 */
export const LINE_MORPH_DURATION = 420

/**
 * 位移缓动 —— 与柱状图形变、纵轴换算同一条曲线。
 *
 * 三处动效常同时发生（切换范围既改点数也改轴上限），曲线不同会让它们各走各的节奏。
 */
export const LINE_MORPH_EASING = cubicBezier(0.22, 0.61, 0.36, 1)

/** {@link useLineMorph} 的可调参数。 */
export interface LineMorphOptions {
  /** 进出场时长（ms）。 */
  duration?: number
}

/** 一个端点在形变过程中的状态。 */
export interface MorphPoint {
  /**
   * 点在折线中的位置序号（自左向右，从 0 起）。
   *
   * 作为渲染 key —— 同序号的点跨批次复用同一 DOM 节点，位置才是连续变化的。
   */
  slot: number
  /** 横向位置占绘图区宽度的比例。 */
  x: number
  /** 纵向位置占绘图区高度的比例，自下而上。 */
  y: number
  /** 原始数值，供浮框显示。取目标值 —— 插值出来的中间读数没有意义。 */
  value: number
  /** 生命周期：{@code enter} 新生长出来，{@code leave} 正在收回，{@code stable} 原地移动。 */
  phase: 'stable' | 'enter' | 'leave'
  /** 不透明度。进场自 0 起、退场终于 0，使生长与收回伴随淡入淡出。 */
  opacity: number
}

/**
 * 某一帧的端点位置，作为下一次配对与插值的起点。
 *
 * 必须把 slot 与坐标一起记住，不能事后由下标推算 —— slot 就是渲染 key，
 * 一旦重排，同一批点会改去复用别的 DOM 节点。
 *
 * 也必须记住 {@code opacity}：形变途中再次切换时，一个正在淡出的位置可能被新点接手，
 * 若不从当前不透明度续上，它会突然实体化。
 */
export interface LineMorphFrame {
  slot: number
  x: number
  y: number
  value: number
  opacity: number
}

/**
 * 算出某一帧的端点状态。
 *
 * 纯函数，不读时间也不碰 DOM —— 形变的全部规则都在这里，可逐条单测。
 *
 * @param from 起始帧（上一次变化发生时端点的实际位置）；首次渲染传空数组
 * @param to 目标端点
 * @param eased 已缓动的进度，0 为起始态、1 为目标态
 */
export function buildMorphFrame(
  from: LineMorphFrame[],
  to: SeriesPoint[],
  eased: number,
): MorphPoint[] {
  const t = clamp01(eased)
  const fromBySlot = new Map(from.map((frame) => [frame.slot, frame]))

  /**
   * 新增点的出发处 —— 上一批的末点。
   *
   * 线因此是从原有尾端抽出来的，而不是在新位置凭空多出一段。
   * 首次渲染没有上一批，新增点就地淡入，不做位移（无处可出发）。
   */
  const tail = from.length ? from[from.length - 1] : null

  const alive: MorphPoint[] = to.map((point, slot) => {
    const source = fromBySlot.get(slot)
    if (source) {
      return {
        slot,
        x: lerp(source.x, point.x, t),
        y: lerp(source.y, point.y, t),
        value: point.value,
        phase: 'stable',
        opacity: lerp(source.opacity, 1, t),
      }
    }
    // 该位置在起始帧里没有点，说明是新生长出来的
    const origin = tail ?? { x: point.x, y: point.y }
    return {
      slot,
      x: lerp(origin.x, point.x, t),
      y: lerp(origin.y, point.y, t),
      value: point.value,
      phase: 'enter',
      opacity: t,
    }
  })

  /**
   * 退场点的归处 —— 新一批的末点。
   *
   * 与进场对称：多余的尾部收回到新的线端，线是被拽短的而非截断的。
   * 新一批为空时就地淡出（无处可去）。
   */
  const sink = to.length ? to[to.length - 1] : null

  const leaving: MorphPoint[] = []
  for (let slot = to.length; slot < from.length; slot += 1) {
    const stale = fromBySlot.get(slot)
    if (!stale) continue
    const destination = sink ?? { x: stale.x, y: stale.y }
    leaving.push({
      slot,
      x: lerp(stale.x, destination.x, t),
      y: lerp(stale.y, destination.y, t),
      value: stale.value,
      phase: 'leave',
      opacity: lerp(stale.opacity, 0, t),
    })
  }

  // 按位置升序输出：渲染顺序必须与 slot 一致，否则 Vue 会为对不上的 key
  // 移动 DOM 节点，正在过渡的点会被整体搬走，观感是一次硬跳。
  return [...alive, ...leaving]
}

/** 把一批端点铺成「依次占用 0 起的位置、完全显影」的帧，用作动画终态。 */
export function framesOfPoints(points: SeriesPoint[]): LineMorphFrame[] {
  return points.map((point, slot) => ({
    slot,
    x: point.x,
    y: point.y,
    value: point.value,
    opacity: 1,
  }))
}

/** 把当前帧的端点状态收成下一次插值的起点。 */
export function framesOfMorph(points: MorphPoint[]): LineMorphFrame[] {
  return points.map((point) => ({
    slot: point.slot,
    x: point.x,
    y: point.y,
    value: point.value,
    opacity: point.opacity,
  }))
}

/**
 * 把端点数据接成随范围切换连续形变的状态流。
 *
 * <h2>为什么起点取「当前实际位置」而非「上一个目标」</h2>
 * 形变途中再次切换范围是常见操作。若从上一个目标起算，点会先跳回那个还没到达的
 * 位置再重新出发；从当前实际位置续上，则中途打断也是一条连续轨迹。
 *
 * @param points 当前范围的端点坐标
 * @param options 时长
 */
export function useLineMorph(points: Ref<SeriesPoint[]>, options: LineMorphOptions = {}) {
  const duration = options.duration ?? LINE_MORPH_DURATION

  /** 当前帧的端点状态 —— 线与点共同的唯一数据来源。 */
  const morphPoints = shallowRef<MorphPoint[]>([])

  /** 本次形变的起始帧与目标，逐帧插值的两端。 */
  let from: LineMorphFrame[] = []
  let target: SeriesPoint[] = []

  let frame: number | null = null
  let startedAt = 0

  /** 收尾：目标态即下一次的起始帧，退场点随之从列表消失。 */
  function finish(): void {
    from = framesOfPoints(target)
    morphPoints.value = buildMorphFrame(from, target, 1)
  }

  watch(points, (next) => {
    // 起点取当前实际位置，中途打断也能续上一条连续轨迹
    from = framesOfMorph(morphPoints.value)
    target = next
    stop()

    if (!animatable()) {
      finish()
      return
    }

    startedAt = now()
    morphPoints.value = buildMorphFrame(from, target, 0)
    schedule()
  }, { immediate: true, flush: 'post' })

  function step(): void {
    frame = null
    const elapsed = (now() - startedAt) / duration
    if (elapsed >= 1) {
      finish()
      return
    }
    morphPoints.value = buildMorphFrame(from, target, LINE_MORPH_EASING(elapsed))
    schedule()
  }

  function schedule(): void {
    if (frame !== null) return
    frame = requestAnimationFrame(step)
  }

  function stop(): void {
    if (frame !== null && typeof cancelAnimationFrame === 'function') {
      cancelAnimationFrame(frame)
    }
    frame = null
  }

  /**
   * 本次变化是否值得播放动画。
   *
   * 首帧（起始帧为空）直接落位：没有「从哪里来」，播放出来只是整批淡入。
   * 其余不可动画的情形与纵轴换算一致 —— 减少动效偏好、无 rAF 的环境（SSR / 单测）。
   */
  function animatable(): boolean {
    if (!from.length) return false
    if (prefersReducedMotion()) return false
    return typeof requestAnimationFrame === 'function' && duration > 0
  }

  if (getCurrentScope()) onScopeDispose(stop)

  return { morphPoints }
}

/**
 * 线性插值。
 *
 * 两端做精确返回，不走乘加 —— 浮点残差会让终态落在 0.8999999999999999 这类值上，
 * 而终态即下一次形变的起点，误差会逐次累积。
 */
function lerp(from: number, to: number, t: number): number {
  if (t <= 0) return from
  if (t >= 1) return to
  return from + (to - from) * t
}

function clamp01(value: number): number {
  return Math.max(0, Math.min(1, value))
}

/** 单调时钟，优先用 performance.now 以免系统时间调整影响进度。 */
function now(): number {
  return typeof performance !== 'undefined' && typeof performance.now === 'function'
    ? performance.now()
    : Date.now()
}
