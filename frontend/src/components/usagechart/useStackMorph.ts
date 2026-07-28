import { computed, getCurrentScope, onScopeDispose, shallowRef, watch, type Ref } from 'vue'
import type { StackBar, StackSegment } from './usagechart'

/**
 * 层级切换时柱体与堆叠层的统一形变模型。
 *
 * <h2>一套逻辑覆盖全部情形</h2>
 * 三级视图的数据结构本就同构 —— 单一柱子只是「只有一层的堆叠柱」。
 * 因此不需要为「堆叠→单一」「单一→堆叠」「堆叠→堆叠」各写一套适配：
 * 只要把配对依据从<strong>身份</strong>换成<strong>位置序号</strong>，
 * 三种情形自然收敛为同一种运算。
 *
 * 这也是为什么不能按业务身份（{@code bar.key}）配对：跨层级时柱子身份被整批替换
 * （日期 → 供应商 → 模型），按身份配对找不到任何留存元素，只能退化成
 * 「全部归零、重新长高」。按位置配对则始终有留存：
 * 第 1 根柱永远接着上一批的第 1 根柱演化。
 *
 * <h2>柱体维度：左侧复用，右侧进出</h2>
 * 以左端为锚点对齐两批柱子：
 * <ol>
 *   <li>公共前缀（{@code min(oldCount, newCount)} 根）—— 原地复用，
 *       高度与分层从旧值连续演化到新值，不经过 0；</li>
 *   <li>新增的尾部柱 —— 自右向左淡入，起始高度取<strong>轴中位</strong>
 *       （见 {@link ENTER_HEIGHT_RATIO}），因此是「挤进来并调整」而非「从地面长出」；</li>
 *   <li>多余的尾部柱 —— 向右淡出，不再参与新布局。</li>
 * </ol>
 *
 * <h2>堆叠维度：底部复用，顶部进出</h2>
 * 同一根柱内部按层序号配对，锚点在底部（视觉上柱子从下往上生长）：
 * <ol>
 *   <li>公共底层 —— 平滑过渡到新值；</li>
 *   <li>新增顶层 —— 从 0 膨胀到目标值，观感是「从柱顶顶出来」；</li>
 *   <li>多余顶层 —— 平滑收缩为 0 后移除。</li>
 * </ol>
 *
 * <h2>为何在数据层而非 DOM 层解决</h2>
 * 形变的本质是「同一个 DOM 节点的样式值连续变化」，因此关键在于让 Vue
 * 认为新旧两批柱子是同一批节点 —— 即渲染 key 必须取位置序号而非业务身份。
 * 本模块产出的 {@link MorphBar.slot} 就是这个 key；有了它，
 * 高度变化交给 CSS transition 即可，无需手写逐帧动画。
 */

/**
 * 新增柱子的起始高度占轴上限的比例。
 *
 * 取轴中位而非 0：从 0 长起会让新柱看起来「刚被创建」，与左侧复用柱
 * 「持续演化」的观感割裂；从中位起则无论目标值高于还是低于它，
 * 都是一次幅度有限的调整，与复用柱的运动性质一致。
 */
export const ENTER_HEIGHT_RATIO = 0.5

/**
 * 进出场动画时长（ms），须与 CSS 里柱体进出场 keyframes 的时长一致。
 *
 * 退场柱要在 DOM 里存活满这段时间才能移除：它是「上一批比当前批多出来的尾部」
 * 派生出来的，一旦提前推进配对基准，正在淡出的元素就被摘掉，动画无从播放。
 */
export const MORPH_DURATION = 420

/** {@link useStackMorph} 的可调参数。 */
export interface StackMorphOptions {
  /** 进出场时长（ms），需与 CSS keyframes 同值。 */
  duration?: number
}

/**
 * 新增柱子起始帧的占位层数据。
 *
 * 只在「目标柱一层都没有」这种极端情形下兜底（数据为空的柱子）。
 * 它不会被 hover 到 —— 起始帧转瞬即被目标态替换，且退场/占位层不绑 tooltip。
 */
const EMPTY_SEGMENT: StackSegment = {
  primary: null,
  label: '',
  value: 0,
  ratio: 0,
  isOther: false,
  detail: [],
}

/**
 * 一根柱子占满自己那一份空间时的宽度权重。
 *
 * 所有留存柱都取这个值，因此它们始终等分可用宽度；进场柱从 0 涨到它，
 * 退场柱从它收到 0 —— 横向的挤压与让位就是这一过程的副作用。
 */
export const FULL_WEIGHT = 1

/** 一个堆叠层在形变过程中的状态。 */
export interface MorphSegment {
  /**
   * 层在柱内的位置序号（自下而上，从 0 起）。
   *
   * 作为渲染 key —— 底部同序号的层跨层级复用同一 DOM 节点，
   * 其高度变化才能被 CSS transition 捕捉为形变。
   */
  slot: number
  /** 层的业务数据；退场层保留旧数据以便平滑收缩。 */
  segment: StackSegment
  /** 目标高度占轴上限的比例；退场层为 0。 */
  ratio: number
  /** 是否正在退场（高度归零后移除）。 */
  leaving: boolean
}

/** 一根柱子在形变过程中的状态。 */
export interface MorphBar {
  /**
   * 柱子在图表中的位置序号（自左向右，从 0 起）。
   *
   * 作为渲染 key，使左侧公共前缀跨层级复用 DOM 节点。
   */
  slot: number
  /** 柱子的业务数据；退场柱保留旧数据以便淡出时仍有内容。 */
  bar: StackBar
  /** 堆叠层状态，自下而上。 */
  segments: MorphSegment[]
  /** 生命周期：{@code enter} 新挤入，{@code leave} 正在退出，{@code stable} 原地复用。 */
  phase: 'stable' | 'enter' | 'leave'
  /**
   * 横向宽度权重（{@code flex-grow} 的值）。
   *
   * 这是「挤压」观感的来源，也是复用柱能平滑移动的关键。柱子的横向位置由 flex
   * 分配决定，若柱数一变就直接重排，位置是<strong>瞬时</strong>跳变的 ——
   * 没有任何属性值在变化，CSS transition 无从介入，于是复用柱只能闪现到新位置。
   *
   * 把权重本身变成可过渡的属性后，一切都顺理成章：
   * 退场柱权重收到 0、进场柱从 0 涨到 1，留存柱的可用空间因此被连续地
   * 挤占或让出，位移与「向右滑出 / 从右挤入」都是这一变化的<strong>副作用</strong>，
   * 不需要再单独写位移动画。
   */
  weight: number
}

/**
 * 计算一批柱子的形变状态。
 *
 * @param bars 目标柱子（当前层级的数据）
 * @param previous 上一批柱子；首次渲染传空数组
 * @param ceiling 轴上限，用于把指标值换算成高度比例
 * @param settled 是否已进入目标态。为 {@code false} 时新增柱子仍停在起始高度
 *   （轴中位），供第一帧渲染用；下一帧传 {@code true} 才会驱动它调整到目标值。
 *   已复用的柱子不受此参数影响 —— 它们的起点就是自己上一刻的高度，本就连续。
 */
export function buildMorphBars(
  bars: StackBar[],
  previous: StackBar[],
  ceiling: number,
  settled = true,
): MorphBar[] {
  const result: MorphBar[] = []
  const shared = Math.min(bars.length, previous.length)

  bars.forEach((bar, slot) => {
    const entering = slot >= shared
    result.push({
      slot,
      bar,
      segments: entering && !settled
        // 新柱的第一帧：整柱压成单层、高度取轴中位。
        // 这一帧只为给 CSS transition 一个起点，紧接着就会被目标态替换。
        ? [{ slot: 0, segment: bar.segments[0] ?? EMPTY_SEGMENT, ratio: ENTER_HEIGHT_RATIO, leaving: false }]
        : buildMorphSegments(bar, previous[slot], ceiling, settled),
      // 超出旧批数量的柱子是新挤进来的，需要淡入
      phase: entering ? 'enter' : 'stable',
      // 新柱的起始帧宽度为 0，下一帧才涨到满权重 —— 留存柱因此被「挤窄」，
      // 新柱看起来是从右侧挤进队列的。复用柱始终满权重，宽度由 flex 自然分配。
      weight: entering && !settled ? 0 : 1,
    })
  })

  // 旧批多出来的尾部柱子向右淡出；保留旧数据，使淡出期间仍显示原内容
  for (let slot = bars.length; slot < previous.length; slot += 1) {
    const bar = previous[slot]
    result.push({
      slot,
      bar,
      segments: bar.segments.map((segment, index) => ({
        slot: index,
        segment,
        ratio: 0,
        leaving: true,
      })),
      phase: 'leave',
      // 退场柱宽度收到 0：让出的空间被留存柱连续吃掉，
      // 观感就是「被挤出去」，不必再写位移动画。
      weight: 0,
    })
  }

  return result
}

/**
 * 计算单根柱子内部各层的形变状态。
 *
 * 底部对齐：新旧同序号的层复用同一节点，多出的顶层进场或退场。
 *
 * @param bar 目标柱子
 * @param previousBar 该位置上一批的柱子；不存在时全部层视为新增
 * @param ceiling 轴上限
 */
function buildMorphSegments(
  bar: StackBar,
  previousBar: StackBar | undefined,
  ceiling: number,
  settled = true,
): MorphSegment[] {
  /** 旧柱在该位置的层数；没有旧柱时视为 0（全部层都是新增）。 */
  const previousDepth = previousBar?.segments.length ?? 0

  const segments: MorphSegment[] = bar.segments.map((segment, slot) => ({
    slot,
    segment,
    // 新增顶层（超出旧柱层数）在起始帧高度为 0，下一帧才膨胀到目标值 ——
    // 观感是「从柱顶顶出来」，而不是凭空出现在最终高度上。
    ratio: slot >= previousDepth && !settled ? 0 : ratioOf(segment.value, ceiling),
    leaving: false,
  }))

  if (!previousBar) return segments

  // 旧柱更高（层更多）时，多余顶层收缩为 0 —— 平滑消失而非突然截断
  for (let slot = bar.segments.length; slot < previousBar.segments.length; slot += 1) {
    segments.push({
      slot,
      segment: previousBar.segments[slot],
      ratio: 0,
      leaving: true,
    })
  }

  return segments
}

/** 值占轴上限的比例；上限非正时返回 0，避免 NaN 流入样式。 */
function ratioOf(value: number, ceiling: number): number {
  return ceiling > 0 && value > 0 ? value / ceiling : 0
}

/**
 * 判断新一批里是否存在「凭空出现」的柱子或堆叠层。
 *
 * 只有它们需要两帧提交 —— 复用的柱与层在 DOM 里已有前一刻的高度，
 * 直接改值就能被 CSS 过渡捕捉；而全新节点若起止值同帧写入会被合并，
 * 表现为「一出现就是最终高度」，正是要避免的硬切换。
 *
 * @param bars 新一批柱子
 * @param previous 上一批柱子
 */
function hasNewcomer(bars: StackBar[], previous: StackBar[]): boolean {
  if (bars.length > previous.length) return true
  // 柱数未增，仍需检查各柱内部是否长出了新的顶层
  return bars.some((bar, slot) => bar.segments.length > (previous[slot]?.segments.length ?? 0))
}

/**
 * 把柱子数据接成随层级切换连续形变的状态流。
 *
 * 内部只记忆「上一批柱子」，因此调用方无需关心层级语义 ——
 * 无论是下钻、上探还是 SSE 推送导致的数据变化，都走同一条形变路径。
 *
 * 记忆不在 computed 内部写入：computed 可能因依赖变化被重复求值，
 * 若在其中改写记忆，第二次求值就会拿「刚写进去的新值」当旧值，配对随之失真。
 * 它也不能在数据变化的当帧写入 —— 那会让退场态与起始态来不及渲染，
 * 详见下方 {@code watch} 处的时序说明。
 *
 * @param bars 当前层级的柱子
 * @param ceiling 轴上限（取整后的顶端刻度值）
 */
export function useStackMorph(
  bars: Ref<StackBar[]>,
  ceiling: Ref<number>,
  options: StackMorphOptions = {},
) {
  const duration = options.duration ?? MORPH_DURATION

  /** 上一批柱子的快照，用于按位置配对。 */
  const previous = shallowRef<StackBar[]>([])

  /**
   * 新增柱子是否已被推向目标态。
   *
   * 新柱在 DOM 里没有前一刻的高度可延续，若起止值写在同一帧，浏览器会把两者
   * 合并成「一开始就是目标高度」，CSS transition 无从播放。故拆成两帧：
   * 先渲染起始态（轴中位）、下一帧再切到目标态，过渡才会真正运行。
   */
  const settled = shallowRef(true)

  /** 待执行的落位任务，切换过快时用于取消上一次，避免起始态被提前抹掉。 */
  let settleFrame: number | null = null

  /** 待执行的退场清理任务，切换过快时用于取消上一次。 */
  let retireTimer: number | null = null

  const morphBars = computed(() =>
    buildMorphBars(bars.value, previous.value, ceiling.value, settled.value),
  )

  /*
   * 关键时序：起始态与退场清理必须分开调度，因为它们的正确时机根本不同。
   *
   * `morphBars` 以 previous 为配对基准，退场柱正是「previous 比 bars 多出来的
   * 尾部」派生出来的。因此 previous 一旦推进，那些柱子当场从列表里消失 ——
   * 提前推进等于把正在淡出的元素直接从 DOM 摘掉，观感就是硬切。
   *
   * 于是本回调只登记「这一批要不要走起始态」，两件后续工作各归其位：
   *   下一帧      —— 放开起始高度，凭空出现的柱与层开始调整（scheduleSettle）；
   *   动画结束后  —— 推进 previous，退场柱此时才被移除（scheduleRetire）。
   */
  watch(bars, (next) => {
    // 需要起始态的只有「凭空出现的东西」：新挤进来的柱子、以及某根柱新长出的顶层。
    // 复用柱与退场层的起点都已存在于 DOM 中，直接改值即可由 CSS 过渡接手。
    // 基准取 previous 而非 watch 的旧值 —— previous 才是本次配对真正用的那一批。
    settled.value = !hasNewcomer(next, previous.value)
    scheduleSettle()
    scheduleRetire(next)
  }, { flush: 'post' })

  /**
   * 下一帧放开起始高度，让凭空出现的柱与层调整到目标值。
   *
   * 只管起始态，不动 {@link previous} —— 后者一旦推进，退场柱当场从列表里消失，
   * 动画就没机会播。两件事的正确时机本就不同：起始态是「下一帧」，
   * 退场清理是「动画结束后」，故分由两个调度承担。
   */
  function scheduleSettle(): void {
    cancelSettle()
    if (typeof requestAnimationFrame !== 'function') {
      settled.value = true
      return
    }
    // 双帧：第一帧确保起始高度已提交到合成器，第二帧再改值才会产生过渡
    settleFrame = requestAnimationFrame(() => {
      settleFrame = requestAnimationFrame(() => {
        settleFrame = null
        settled.value = true
      })
    })
  }

  /**
   * 动画结束后推进配对基准，退场柱随之从列表移除。
   *
   * 必须等满一个 {@link duration}：退场柱是由「previous 比 bars 多出来的尾部」
   * 派生的，提前推进等于把正在淡出的元素直接从 DOM 摘掉 —— 那正是硬切的来源。
   *
   * @param next 本次的新批柱子，清理时成为下一轮的配对基准
   */
  function scheduleRetire(next: StackBar[]): void {
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

  return { morphBars }
}
