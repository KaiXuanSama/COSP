import { getCurrentScope, onScopeDispose, shallowRef, watch, type Ref } from 'vue'
import { cubicBezier, prefersReducedMotion } from '../usagechart/useAxisScale'

/**
 * 按位置序号的形变模型 —— 一批元素数量变化时的复用、进场与退场。
 *
 * <h2>为什么插值在 JS 而不交给 CSS</h2>
 * 折线的视觉主体是线，但线本身无法过渡：{@code polyline} 的 {@code points} 是一个
 * 坐标字符串，7 个点与 25 个点的字符串长度都不同，CSS 无从在两者之间插值。
 * 因此线只能每帧按端点当前位置重算 —— 而「端点当前位置」必须是一个 JS 能读到的数。
 *
 * 若把位移交给 CSS（{@code left} 的 transition），这个数就只存在于合成器内部：
 * JS 侧拿到的始终是终点值，线会当帧跳到位、点却还在慢慢滑，
 * 观感就是「线闪现一下、点随后才追上」。
 *
 * 故位移改由本模块逐帧算出：线与点读的是<strong>同一批数值</strong>，
 * 两者同步是构造出来的，不依赖 JS 缓动曲线与 CSS 曲线是否恰好对得上。
 *
 * <h2>为什么按位置序号配对</h2>
 * 切换范围时元素的身份被整批替换（日期 → 时刻），按身份配对找不到任何留存元素，
 * 只能退化成整批淡出淡入。按位置配对则第 N 个元素永远接着上一批的第 N 个演化，
 * 「同一个位置上的东西在变」这一连续性才成立。
 *
 * <h2>为什么泛化而不只服务端点</h2>
 * 横轴标签与端点面对的是同一个问题：数量随范围变化、位置需要滑动、进出场要淡。
 * 两者唯一的差别是标签还带一段文字，而文字换了就该交叉淡出 —— 这由
 * {@link SlotMorphOptions.identity} 表达，其余规则完全共用。各写一份必然在时长、
 * 曲线、出发处上慢慢漂移，最终标签与点各走各的节奏。
 */

/** 形变时长（ms）。位移由 JS 逐帧驱动，故无需与任何 CSS 过渡对齐。 */
export const SLOT_MORPH_DURATION = 420

/**
 * 位移缓动 —— 与柱状图形变、纵轴换算同一条曲线。
 *
 * 多处动效常同时发生（切换范围既改点数也改轴上限），曲线不同会让它们各走各的节奏。
 */
export const SLOT_MORPH_EASING = cubicBezier(0.22, 0.61, 0.36, 1)

/**
 * 可参与形变的元素 —— 只要给出位置，其余字段随意。
 *
 * {@code opacity} 是<strong>目标</strong>不透明度，缺省为 1。用它表达「本就该淡显」
 * 的状态（未来时段的标签、被稀疏规则隐去的标签），使这类变化也走同一条淡入淡出，
 * 而不是靠 CSS 类名硬切。
 */
export interface MorphSeed {
  /** 横向位置占绘图区宽度的比例。 */
  x: number
  /** 纵向位置占绘图区高度的比例，自下而上。不参与纵向排布的轨道传 0。 */
  y: number
  /** 目标不透明度，缺省 1。 */
  opacity?: number
}

/** 一个元素在形变过程中的状态。 */
export interface MorphItem<T extends MorphSeed> {
  /**
   * 渲染 key。
   *
   * 同一位置的留存元素跨批次共用一个 key，故 DOM 节点被复用；
   * 退场元素另起一个 key，使它能与接手该位置的新元素同时存在（交叉淡出的前提）。
   */
  key: string
  /** 位置序号（自左向右，从 0 起）。 */
  slot: number
  /** 当前横向位置占绘图区宽度的比例。 */
  x: number
  /** 当前纵向位置占绘图区高度的比例，自下而上。 */
  y: number
  /** 当前不透明度。 */
  opacity: number
  /** 生命周期：{@code enter} 新生长出来，{@code leave} 正在退出，{@code stable} 原地移动。 */
  phase: 'stable' | 'enter' | 'leave'
  /**
   * 元素自身的数据。
   *
   * 取<strong>目标</strong>值而非插值 —— 读数与文字插到中间没有意义；
   * 退场元素则保留自己原本的数据，淡出途中显示的仍是它自己。
   */
  data: T
}

/** 某一帧的元素状态，作为下一次配对与插值的起点。 */
export interface MorphFrame<T extends MorphSeed> {
  slot: number
  x: number
  y: number
  opacity: number
  data: T
}

/** {@link useSlotMorph} 的可调参数。 */
export interface SlotMorphOptions<T extends MorphSeed> {
  /** 形变时长（ms）。 */
  duration?: number
  /**
   * 内容身份。给出时，位置相同但身份不同的元素视为<strong>换了一个东西</strong>：
   * 旧的淡出、新的淡入，两者同时朝该位置的新坐标滑动。
   *
   * 横轴标签需要它 —— 最左侧那一格从 `7/24` 变成 `05:00`，位置没动但内容全换了，
   * 直接改写文字是一次硬切。端点不需要 —— 一个点长什么样与它代表哪个时刻无关，
   * 滑过去就是最自然的表达。
   */
  identity?: (data: T) => string
}

/**
 * 算出某一帧的元素状态。
 *
 * 纯函数，不读时间也不碰 DOM —— 形变的全部规则都在这里，可逐条单测。
 *
 * <p>输出顺序为「留存与进场按位置升序，随后是退场元素按位置升序」。折线路径依赖
 * 这个顺序，而未给 {@code identity} 时退场元素必然都在尾部溢出段，两段拼起来仍是升序。
 *
 * @param from 起始帧（上一次变化发生时元素的实际位置）；首次渲染传空数组
 * @param to 目标元素
 * @param eased 已缓动的进度，0 为起始态、1 为目标态
 * @param identity 内容身份，见 {@link SlotMorphOptions.identity}
 */
export function buildMorphFrame<T extends MorphSeed>(
  from: MorphFrame<T>[],
  to: T[],
  eased: number,
  identity?: (data: T) => string,
): MorphItem<T>[] {
  const t = clamp01(eased)
  const fromBySlot = new Map(from.map((frame) => [frame.slot, frame]))

  /**
   * 新增元素的出发处 —— 上一批的末位。
   *
   * 线因此是从原有尾端抽出来的，而不是在新位置凭空多出一段；标签同理，
   * 新的一批时刻从原本的轴末长出来。首次渲染没有上一批，就地淡入（无处可出发）。
   */
  const tail = from.length ? from[from.length - 1] : null

  /** 被新内容顶掉的旧元素：位置还在，但装的东西换了，须与新元素交叉淡出。 */
  const replaced: MorphItem<T>[] = []

  const alive: MorphItem<T>[] = to.map((target, slot) => {
    const source = fromBySlot.get(slot)
    const targetOpacity = target.opacity ?? 1
    const continued = source !== undefined
      && (identity === undefined || identity(source.data) === identity(target))

    if (continued) {
      return {
        key: `slot-${slot}`,
        slot,
        x: lerp(source!.x, target.x, t),
        y: lerp(source!.y, target.y, t),
        opacity: lerp(source!.opacity, targetOpacity, t),
        phase: 'stable',
        data: target,
      }
    }

    if (source !== undefined) {
      // 该位置原本装着别的内容：让它随新元素一起滑到新坐标，同时淡出
      replaced.push({
        key: `out-${slot}`,
        slot,
        x: lerp(source.x, target.x, t),
        y: lerp(source.y, target.y, t),
        opacity: lerp(source.opacity, 0, t),
        phase: 'leave',
        data: source.data,
      })
    }

    // 从该位置原有内容处、或上一批的末位出发；两者都没有则就地淡入
    const origin = source ?? tail ?? { x: target.x, y: target.y }
    return {
      key: `slot-${slot}`,
      slot,
      x: lerp(origin.x, target.x, t),
      y: lerp(origin.y, target.y, t),
      opacity: lerp(0, targetOpacity, t),
      phase: 'enter',
      data: target,
    }
  })

  /**
   * 溢出元素的归处 —— 新一批的末位。
   *
   * 与进场对称：多余的尾部收回到新的线端，线是被拽短的而非截断的。
   * 新一批为空时就地淡出（无处可去）。
   */
  const sink = to.length ? to[to.length - 1] : null

  const overflow: MorphItem<T>[] = []
  for (let slot = to.length; slot < from.length; slot += 1) {
    const stale = fromBySlot.get(slot)
    if (!stale) continue
    const destination = sink ?? { x: stale.x, y: stale.y }
    overflow.push({
      key: `out-${slot}`,
      slot,
      x: lerp(stale.x, destination.x, t),
      y: lerp(stale.y, destination.y, t),
      opacity: lerp(stale.opacity, 0, t),
      phase: 'leave',
      data: stale.data,
    })
  }

  // 留存与进场在前、退场在后：折线路径按此顺序连线，而无 identity 时退场元素
  // 全在尾部溢出段，拼起来仍是位置升序。
  return [...alive, ...replaced, ...overflow]
}

/** 把一批目标元素铺成「依次占用 0 起的位置、已达目标不透明度」的帧，用作动画终态。 */
export function framesOfSeeds<T extends MorphSeed>(seeds: T[]): MorphFrame<T>[] {
  return seeds.map((seed, slot) => ({
    slot,
    x: seed.x,
    y: seed.y,
    opacity: seed.opacity ?? 1,
    data: seed,
  }))
}

/**
 * 一批元素的动画量签名 —— 只含被插值的量：位置、不透明度与内容身份。
 *
 * 用来识别「数据源刷新了，但没有任何东西需要动」。这种更新很常见：范围切换后
 * HTTP 首屏与 SSE 首帧携带同一份点位，相隔数十毫秒先后到达，各自都会让上游的
 * computed 产出一个新数组。若照常重启形变，第二次的起始帧里已经没有退场元素
 * （{@link framesOfMorph} 不收它们），正在淡出的那一批就当场被摘掉。
 *
 * 只比动画量而非整个对象：读数、原始值这类字段变了也无需重播动画，
 * 它们不参与插值。
 *
 * @param seeds 一批元素
 * @param identity 内容身份，见 {@link SlotMorphOptions.identity}
 */
export function morphSignature<T extends MorphSeed>(
  seeds: T[],
  identity?: (data: T) => string,
): string {
  return seeds
    .map((seed) => `${seed.x}|${seed.y}|${seed.opacity ?? 1}|${identity ? identity(seed) : ''}`)
    .join(';')
}

/**
 * 把当前帧收成下一次插值的起点。
 *
 * 退场元素不入下一轮：它们占的位置本次已让出。但正在淡出的<strong>被顶掉的</strong>
 * 元素也一并丢弃 —— 该位置已由新内容接手，它不再是任何位置的现状。
 */
export function framesOfMorph<T extends MorphSeed>(items: MorphItem<T>[]): MorphFrame<T>[] {
  return items
    .filter((item) => item.phase !== 'leave')
    .map((item) => ({
      slot: item.slot,
      x: item.x,
      y: item.y,
      opacity: item.opacity,
      data: item.data,
    }))
}

/**
 * 把一批元素接成随数量变化连续形变的状态流。
 *
 * <h2>为什么起点取「当前实际位置」而非「上一个目标」</h2>
 * 形变途中再次切换范围是常见操作。若从上一个目标起算，元素会先跳回那个还没到达的
 * 位置再重新出发；从当前实际位置续上，则中途打断也是一条连续轨迹。
 *
 * @param seeds 当前一批元素
 * @param options 时长与内容身份
 */
export function useSlotMorph<T extends MorphSeed>(
  seeds: Ref<T[]>,
  options: SlotMorphOptions<T> = {},
) {
  const duration = options.duration ?? SLOT_MORPH_DURATION
  const identity = options.identity

  /** 当前帧的元素状态 —— 所有依赖这批位置的渲染物共同的唯一数据来源。 */
  const morphItems = shallowRef<MorphItem<T>[]>([])

  /** 本次形变的起始帧与目标，逐帧插值的两端。 */
  let from: MorphFrame<T>[] = []
  let target: T[] = []

  /** 当前目标的动画量签名，用于识别「数据换了但没东西要动」的刷新。 */
  let targetSignature = ''

  let frame: number | null = null
  let startedAt = 0

  /** 收尾：目标态即下一次的起始帧，退场元素随之从列表消失。 */
  function finish(): void {
    from = framesOfSeeds(target)
    morphItems.value = buildMorphFrame(from, target, 1, identity)
  }

  watch(seeds, (next) => {
    const signature = morphSignature(next, identity)

    /*
     * 数据源刷新但动画量分毫未变。范围切换后 HTTP 首屏与 SSE 首帧携带同一份点位、
     * 相隔数十毫秒先后到达，就是这个情形。
     *
     * 此时只换数据引用、不碰进度：重启形变会以「当前实际位置」为新起点，而那份
     * 快照里没有退场元素，正在淡出的一批会当场消失 —— 表现就是动画播到一半被截断。
     */
    if (target.length && signature === targetSignature) {
      target = next
      // 没有动画在跑时列表不会再被重算，就地把数据引用刷新进去
      if (frame === null) finish()
      return
    }

    // 起点取当前实际位置，中途打断也能续上一条连续轨迹
    from = framesOfMorph(morphItems.value)
    target = next
    targetSignature = signature
    stop()

    if (!animatable()) {
      finish()
      return
    }

    startedAt = now()
    morphItems.value = buildMorphFrame(from, target, 0, identity)
    schedule()
  }, { immediate: true, flush: 'post' })

  function step(): void {
    frame = null
    const elapsed = (now() - startedAt) / duration
    if (elapsed >= 1) {
      finish()
      return
    }
    morphItems.value = buildMorphFrame(from, target, SLOT_MORPH_EASING(elapsed), identity)
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

  return { morphItems }
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
