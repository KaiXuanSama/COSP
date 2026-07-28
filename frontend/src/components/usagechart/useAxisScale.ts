import { getCurrentScope, onScopeDispose, ref, watch, type Ref } from 'vue'
import type { AxisTick } from './axisTicks'

/**
 * 纵轴标尺的换算动画 —— 下钻 / 上探时刻度的「压缩」与「解压」。
 *
 * <h2>要解决什么</h2>
 * 各层级的归一化基准不同（一级按全局最大总量、二 / 三级按本视图最大值），
 * 因此层级切换时轴上限会变，刻度读数随之从 0/50/100/150/200 换成 0/10/20/30/40 这类新值。
 * 刻度原本是按「第几档」等分定位的，各档占比恒为 0/25/50/75/100%，
 * <strong>位置不变、只有数字瞬间换掉</strong> —— 观感上像什么都没发生，
 * 使用者容易误以为纵轴没变，从而读错柱高对应的量级。
 *
 * <h2>怎么解决</h2>
 * 不去动刻度的档位，而是让<strong>标尺</strong>动起来：
 * 把「1 个单位数值占多少高度」这个换算系数（{@link AxisScale.displayScale}）
 * 从旧上限对应的值缓动到新上限对应的值，刻度位置全程按 {@code 数值 × 标尺} 实时投影。
 *
 * 于是同一批刻度会在纵轴上真实地移动：
 * <ul>
 *   <li>上限变小（颗粒变细，如 200 → 40）—— 标尺变大，刻度自下往上散开，是「解压」；</li>
 *   <li>上限变大（颗粒变粗，如 40 → 200）—— 标尺变小，刻度自上往下收拢，是「压缩」。</li>
 * </ul>
 *
 * <h2>为何在倒数空间插值</h2>
 * 位置是 {@code 数值 / 上限}，与上限成反比。若直接对上限做线性插值，
 * 200 → 40 会在「上限还很大、位置几乎不动」的区间耗掉大半时长，末段再突然窜开，
 * 观感是先卡顿后甩尾。改为对 {@code 1 / 上限} 插值，位置便随进度均匀推进。
 *
 * <h2>为何不用 CSS transition</h2>
 * 刻度的 {@code bottom} 是百分比，而百分比在换标尺前后可能相同
 * （等分档位下永远是 0/25/50/75/100%），没有属性值变化可供过渡 ——
 * 与 {@link useFlip} 面对的是同一类问题：变的是换算规则，不是某个属性。
 */

/**
 * 标尺换算时长（ms）。
 *
 * 与柱高过渡（{@code transition: height 0.42s}）同值，使「柱子长高」与「刻度重排」
 * 看起来是同一个动作的两个侧面，而不是两段各自为政的动画。
 */
export const AXIS_SCALE_DURATION = 420

/**
 * 换算过程中刻度的可见区间外扩量（占绘图区高度的比例）。
 *
 * 「压缩」方向上，新刻度的起始投影位置会远高于绘图区（如上限 40 → 200 时，
 * 刻度 200 起始于 500% 处），需要剔除掉尚未进入视野的刻度，否则它们会溢出卡片、
 * 与相邻区块重叠。留一点余量是为了让刻度从边缘外「滑入」而不是正好贴边冒出。
 */
export const AXIS_SCALE_CULL_MARGIN = 0.08

/** 与柱高过渡同一条曲线（CSS 中的 {@code cubic-bezier(0.4, 0, 0.2, 1)}）。 */
const EASING = cubicBezier(0.4, 0, 0.2, 1)

/** {@link useAxisScale} 的可调参数。 */
export interface AxisScaleOptions {
  /** 换算时长（ms）。 */
  duration?: number
}

/** {@link useAxisScale} 的返回值。 */
export interface AxisScale {
  /**
   * 当前标尺：1 个单位数值对应的高度占比。
   *
   * 刻度位置取 {@code 数值 × displayScale}。无数据（上限 0）时为 0。
   */
  displayScale: Ref<number>
  /**
   * 是否正在换算。
   *
   * 为 {@code true} 时刻度应按 {@link displayScale} 实时投影；
   * 静止时应改用刻度自带的精确占比，避免浮点残差让位置差出亚像素。
   */
  rescaling: Ref<boolean>
  /** 当前缓动进度（0 到 1），供旧/新刻度的数值、位置与透明度过渡共用。 */
  progress: Ref<number>
}

/** 正在过渡中的显示刻度，{@code key} 在整个过渡期间稳定。 */
export interface AnimatedAxisTick extends AxisTick {
  key: string
  opacity: number
}

/**
 * 创建一个跟随轴上限缓动的标尺。
 *
 * @param targetCeiling 轴上限（取整后的顶端刻度值），变化即触发一次换算动画
 * @param options 时长
 */
export function useAxisScale(targetCeiling: Ref<number>, options: AxisScaleOptions = {}): AxisScale {
  const duration = options.duration ?? AXIS_SCALE_DURATION

  const displayScale = ref(scaleOf(targetCeiling.value))
  const rescaling = ref(false)
  const progress = ref(1)

  /** 当前动画帧句柄；null 表示没有动画在跑。 */
  let frame: number | null = null
  let fromScale = displayScale.value
  let toScale = displayScale.value
  let startedAt = 0

  watch(targetCeiling, (next, previous) => {
    const nextScale = scaleOf(next)

    // 以下几种情况没有「可动画的换算过程」，直接落位：
    // 1. 标尺没变（层级切换但上限恰好相同）；
    // 2. 空数据与有数据之间切换（无轴可比，动画只会显得突兀）；
    // 3. 系统要求减少动效；
    // 4. 环境没有 rAF（SSR / 单测）。
    if (
      nextScale === displayScale.value
      || nextScale === 0
      || scaleOf(previous) === 0
      || prefersReducedMotion()
      || !hasAnimationFrame()
    ) {
      stop()
      displayScale.value = nextScale
      progress.value = 1
      return
    }

    // 从「当前」而非「上一个目标」起算，使换算中途再次切层级也能平滑接续
    fromScale = displayScale.value
    toScale = nextScale
    startedAt = now()
    progress.value = 0
    rescaling.value = true
    schedule()
  })

  /** 推进一帧。 */
  function step(): void {
    frame = null
    const elapsedProgress = Math.min(1, (now() - startedAt) / duration)

    if (elapsedProgress >= 1) {
      // 收尾对齐到精确目标，消除插值累积的浮点残差
      displayScale.value = toScale
      progress.value = 1
      rescaling.value = false
      return
    }

    const eased = EASING(elapsedProgress)
    displayScale.value = fromScale + (toScale - fromScale) * eased
    progress.value = eased
    schedule()
  }

  function schedule(): void {
    if (frame !== null) return
    frame = requestAnimationFrame(step)
  }

  /** 中止进行中的换算（不改变当前标尺值）。 */
  function stop(): void {
    if (frame !== null) {
      cancelAnimationFrame(frame)
      frame = null
    }
    rescaling.value = false
  }

  // 组件卸载时收掉动画帧，避免回调在已销毁的作用域里继续跑
  if (getCurrentScope()) onScopeDispose(stop)

  return { displayScale, rescaling, progress }
}

/**
 * 构造旧/新两组刻度之间的显示帧。
 *
 * 旧/新刻度按<strong>数值身份</strong>配对，绝不插值或改写刻度读数。
 * 旧刻度始终通过动画中的标尺投影到纵轴，因而会真实收束或发散；数量不相等时，
 * 新增目标刻度在间隙中淡入、已淘汰旧刻度淡出。这样
 * 0/100/200 → 0/50/100 会保留 0/100 两条已有线并让它们发散，50 在中间淡入。
 *
 * @param from 切换前刻度
 * @param to 切换后刻度
 * @param progress 已缓动的动画进度（0 到 1），仅控制新增/淘汰刻度的透明度
 * @param displayScale 当前动画标尺（每个数值单位对应的高度占比）
 */
export function buildAnimatedAxisTicks(
  from: AxisTick[],
  to: AxisTick[],
  progress: number,
  displayScale: number,
): AnimatedAxisTick[] {
  const eased = Math.max(0, Math.min(1, progress))
  const project = (value: number): number => value * displayScale
  if (!from.length) {
    return to.map((tick, index) => ({
      ...tick,
      ratio: project(tick.value),
      key: `enter-${index}`,
      opacity: eased,
    }))
  }
  if (!to.length) {
    return from.map((tick, index) => ({
      ...tick,
      ratio: project(tick.value),
      key: `exit-${index}`,
      opacity: 1 - eased,
    }))
  }

  const oldValues = new Set(from.map((tick) => tick.value))
  const newValues = new Set(to.map((tick) => tick.value))
  const result: AnimatedAxisTick[] = []

  for (const tick of from) {
    result.push({
      key: `value-${tick.value}`,
      value: tick.value,
      // 已淘汰的大刻度可能被新标尺推到绘图区上方。钳在边界后继续淡出，
      // 既保留「收束到顶端」的运动方向，也不会溢出卡片。
      ratio: clampRatio(project(tick.value)),
      opacity: newValues.has(tick.value) ? 1 : 1 - eased,
    })
  }

  for (const tick of to) {
    if (!oldValues.has(tick.value)) {
      result.push({
        key: `value-${tick.value}`,
        value: tick.value,
        ratio: clampRatio(project(tick.value)),
        opacity: eased,
      })
    }
  }

  return result.sort((a, b) => a.ratio - b.ratio)
}

/** 将过渡刻度限制在绘图区，避免临时刻度越界覆盖卡片其它区域。 */
function clampRatio(ratio: number): number {
  return Math.max(0, Math.min(1, ratio))
}

/**
 * 轴上限 → 标尺（1 个单位数值对应的高度占比）。
 *
 * @param ceiling 轴上限
 * @returns 上限为正时返回其倒数；无数据（{@code <= 0}）时返回 0
 */
export function scaleOf(ceiling: number): number {
  return ceiling > 0 ? 1 / ceiling : 0
}

/**
 * 构造三次贝塞尔缓动函数，与 CSS 的 {@code cubic-bezier()} 同语义。
 *
 * 自己实现而不用 CSS，是因为这里的插值发生在 JS 里（逐帧改投影），
 * 但曲线必须与柱高的 CSS 过渡完全一致，否则两者会以不同节奏运动、彼此脱节。
 *
 * @param x1 第一控制点的 x
 * @param y1 第一控制点的 y
 * @param x2 第二控制点的 x
 * @param y2 第二控制点的 y
 * @returns 进度（0 ~ 1）到缓动值（0 ~ 1）的映射
 */
export function cubicBezier(x1: number, y1: number, x2: number, y2: number): (progress: number) => number {
  /** 首末控制点固定为 0 与 1 的一维三次贝塞尔取值。 */
  const at = (a: number, b: number, t: number): number => {
    const inv = 1 - t
    return 3 * inv * inv * t * a + 3 * inv * t * t * b + t * t * t
  }

  return (progress: number) => {
    if (progress <= 0) return 0
    if (progress >= 1) return 1

    // 二分求解 x(t) = progress。20 次迭代精度约 1e-6，远细于像素，
    // 且迭代次数固定，不会像牛顿法那样在导数接近 0 处发散。
    let low = 0
    let high = 1
    let t = progress
    for (let i = 0; i < 20; i += 1) {
      if (at(x1, x2, t) < progress) low = t
      else high = t
      t = (low + high) / 2
    }
    return at(y1, y2, t)
  }
}

/** 单调时钟，优先用 performance.now 以免系统时间调整影响进度。 */
function now(): number {
  return typeof performance !== 'undefined' && typeof performance.now === 'function'
    ? performance.now()
    : Date.now()
}

/** 当前环境是否支持逐帧回调（SSR 与单测里没有）。 */
function hasAnimationFrame(): boolean {
  return typeof requestAnimationFrame === 'function' && typeof cancelAnimationFrame === 'function'
}

/**
 * 尊重系统「减少动态效果」设置：开启时直接换到新标尺，不播放压缩 / 解压。
 *
 * 无 window 或不支持 matchMedia 时一律视为「不需要减少动效」，
 * 使本模块在非浏览器环境下也能安全求值而不抛错。
 */
function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}
