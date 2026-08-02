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
 * <h2>柱体维度：先按身份对齐，对不上再按位置</h2>
 * 两批柱子的对齐方式取决于这次变化的性质：
 * <ol>
 *   <li><strong>身份对齐</strong>（{@link alignSlotsByIdentity}）—— 日期窗口滑动、
 *       拖动手柄改变跨度时，两批柱子有大量同名成员，只是整体错开了几格。
 *       找出使身份重合最多的整体位移，同名柱因此复用同一节点；
 *       没被覆盖的旧位置退场、没有旧柱的新位置进场，
 *       <strong>左右方位由位移量自然决定</strong>，不必判断「这次往哪边动」。</li>
 *   <li><strong>位置对齐</strong>（{@link assignSlots}）—— 下钻时身份整批替换
 *       （日期 → 供应商 → 模型），身份毫无重合，位置是唯一可循的线索。
 *       以左端为锚点：公共前缀原地复用、尾部进出。</li>
 * </ol>
 * 两者的入口是 {@link resolveSlots}。
 *
 * <p>身份对齐是后加的：早先只有位置对齐，于是「窗口左移一天」会被算成
 * 「7 根柱全部原地改值」—— 每根柱子的数值凭空跳变，而实际发生的事情是
 * 「整排右移一格、右端移出、左端补入」。同理，从左侧手柄拖宽窗口时新增的是
 * 更早的一天，位置对齐却只会在右端追加位置，新柱从右侧挤进来，与事实相反。
 *
 * <h2>进出场的方位不需要单独实现</h2>
 * 柱子按 slot 升序渲染，横向位置由 flex 权重分配。进场柱权重从 0 涨到 1、
 * 退场柱从 1 收到 0 —— 于是它在<strong>哪一侧</strong>出现或消失，
 * 完全取决于它的 slot 落在留存柱的左边还是右边。身份对齐把这件事一并解决了：
 * 窗口左移时新柱的 slot 比所有留存柱都小，它自然从左侧挤入。
 *
 * <h2>下钻锚点：让被点的那根柱子延续下去</h2>
 * 上述左端对齐是无指向的默认规则。但下钻是一次<strong>有明确指向</strong>的操作 ——
 * 使用者点了第 5 根，新一屏就是那根柱子的内部构成，因此新柱理应看起来
 * 「从第 5 根长出来」，而不是从第 1 根。若仍按前缀复用，被点的柱子会淡出、
 * 结果却出现在最左边，因果链在视觉上是断的。
 *
 * 解法是把复用位置的选取交给 {@link assignSlots}：被点位置必须入选，
 * 其余名额按最左优先补齐。5 → 3 且点第 5 根时保留 {@code {0, 1, 4}}，
 * 中间的 2、3 号位让出宽度退场，第 5 根原地演化成新的末位柱。
 *
 * @see assignSlots
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
  /**
   * 被本柱顶掉的旧标签文案；没被顶掉时为 {@code null}。
   *
   * 柱体本身按位置复用 DOM，横向位移与高度变化都已是连续的，但底部标签是
   * <strong>就地改写文字</strong>的 —— 同一个节点从「7/28」变成「gorouter」，
   * 中间没有任何可过渡的量，观感是一次硬切。
   *
   * 因此把旧文案一并带出来，让它与新文案同处一格交叉淡出淡入。它的存活时长
   * 与退场柱一致（由 {@link useStackMorph} 推进配对基准时一同移除），
   * 故不需要额外的清理时序。
   *
   * 只在<strong>文案确实变了</strong>时给值：内容没变却播一次淡入淡出，
   * 会让层级切换时未受影响的柱子也跟着闪一下。
   */
  outgoingLabel: string | null
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
 * 上一批柱子及其占用的位置 —— 配对的基准。
 *
 * 必须把 slot 与数据一起记住，不能事后由下标推算：锚定复用会留下不连续的
 * 占用（如 4 → 2 点第 3 根，留存 0 与 2），按下标重排会让同一批柱子换到
 * 别的 slot，也就换去复用另一个 DOM 节点。
 */
export interface MorphSnapshot {
  slot: number
  bar: StackBar
  /**
   * 该位置开始退场的时刻（{@code performance.now()} 读数）；留存柱为 {@code undefined}。
   *
   * <h2>为什么退场柱也要留在基准里</h2>
   * {@link buildMorphBars} 产出的柱子集合完全派生于「当前数据 ∪ 基准」。
   * 若基准只记留存柱，那么<strong>仅因为退场而存在于屏幕上</strong>的那些柱子，
   * 在下一批数据到来时会凭空从集合里消失 —— Vue 直接摘掉 DOM 节点，没有退场帧。
   *
   * <p>这正是快速连续滑动时「上一步的归零动画硬消失」的来源：左滑后 8/2 正在退场、
   * 7/26 刚进场；紧接着右滑，新一批身份与基准全匹配、不产生任何退场条目，
   * 于是 slot −1 上那根 7/26 无人认领，当场消失。
   *
   * <p>把退场条目一并留下，它们在下一次配对里仍是「可见的旧位置」：
   * 要么继续退场（无人占用），要么被回归的同名柱复用节点、从当前的近零宽度长回。
   *
   * <h2>为什么按条目各自计时而非全局定时器</h2>
   * 快速切换时不同批次的退场柱起始时刻不同。单个定时器只能表达「最后一次切换的
   * 动画何时结束」，先起步的那批会被延后清理、后起步的会被提前清理。
   * 逐条记录时刻后，过期判断是纯函数（见 {@link pruneSnapshot}），与切换节奏无关。
   */
  leavingSince?: number
}

/**
 * 剔除已播完退场动画的条目。
 *
 * <p>留存条目一律保留（它们是当前的占位事实）；退场条目只在超过 {@code duration}
 * 后移除 —— 提前移除等于把正在淡出的元素从 DOM 摘掉，那正是硬切的来源。
 *
 * @param snapshot 待清理的基准
 * @param now 当前时刻（{@code performance.now()} 读数）
 * @param duration 退场动画时长（ms）
 */
export function pruneSnapshot(
  snapshot: MorphSnapshot[],
  now: number,
  duration: number,
): MorphSnapshot[] {
  return snapshot.filter(
    item => item.leavingSince === undefined || now - item.leavingSince < duration,
  )
}

/**
 * 计算一批柱子的形变状态。
 *
 * @param bars 目标柱子（当前层级的数据）
 * @param previous 上一批柱子及其占位；首次渲染传空数组
 * @param ceiling 轴上限，用于把指标值换算成高度比例
 * @param settled 是否已进入目标态。为 {@code false} 时新增柱子仍停在起始高度
 *   （轴中位），供第一帧渲染用；下一帧传 {@code true} 才会驱动它调整到目标值。
 *   已复用的柱子不受此参数影响 —— 它们的起点就是自己上一刻的高度，本就连续。
 * @param anchor 必须被复用的位置（使用者点击的那根柱子）。
 *   为 {@code null} 时退化为最左优先，即历史行为。
 */
export function buildMorphBars(
  bars: StackBar[],
  previous: MorphSnapshot[],
  ceiling: number,
  settled = true,
  anchor: number | null = null,
): MorphBar[] {
  /** 旧批各位置上的柱子，供按 slot 取配对参照物。 */
  const previousBySlot = new Map(previous.map(item => [item.slot, item.bar]))
  /** 第 i 根新柱占用的位置序号；决定它接着旧批哪一根演化。 */
  const slots = resolveSlots(bars, previous, anchor)
  const taken = new Set(slots)
  const result: MorphBar[] = []

  bars.forEach((bar, index) => {
    const slot = slots[index]
    const previousBar = previousBySlot.get(slot)
    // 该位置在旧批里没有柱子，说明是新挤进来的
    const entering = previousBar === undefined
    result.push({
      slot,
      bar,
      // 同一格换了文案就带上旧的，两段文字交叉淡出淡入；文案没变则不播动画，
      // 否则层级切换时未受影响的柱子也会跟着闪一下。
      outgoingLabel: previousBar && previousBar.label !== bar.label ? previousBar.label : null,
      segments: entering && !settled
        // 新柱的第一帧：整柱压成单层、高度取轴中位。
        // 这一帧只为给 CSS transition 一个起点，紧接着就会被目标态替换。
        ? [{ slot: 0, segment: bar.segments[0] ?? EMPTY_SEGMENT, ratio: ENTER_HEIGHT_RATIO, leaving: false }]
        : buildMorphSegments(bar, previousBar, ceiling, settled),
      phase: entering ? 'enter' : 'stable',
      // 新柱的起始帧宽度为 0，下一帧才涨到满权重 —— 留存柱因此被「挤窄」，
      // 新柱看起来是从右侧挤进队列的。复用柱始终满权重，宽度由 flex 自然分配。
      weight: entering && !settled ? 0 : FULL_WEIGHT,
    })
  })

  // 没被选中的旧位置淡出；保留旧数据，使淡出期间仍显示原内容。
  // 锚定复用时被淘汰的位置可能在中间（如 5 → 3 点末根，淘汰 2、3 号位），
  // 故须遍历旧批全部占位，不能只从 bars.length 起算。
  previous.forEach(({ slot, bar }) => {
    if (taken.has(slot)) return
    result.push({
      slot,
      bar,
      // 退场柱的标签随柱子整体淡出，无需再做文案交叉
      outgoingLabel: null,
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
  })

  // 按位置升序输出：渲染顺序必须与 slot 一致，否则 Vue 会为对不上的 key
  // 移动 DOM 节点，正在过渡的柱子会被整体搬走，观感是一次硬跳。
  return result.sort((a, b) => a.slot - b.slot)
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
 * 为新一批柱子分配位置序号（即渲染 key，决定各自复用哪个 DOM 节点）。
 *
 * <h2>为什么复用关系归结为一次选位</h2>
 * 节点复用完全由渲染 key 决定：新柱拿到 slot {@code s}，它就接着上一批
 * 位于 {@code s} 的柱子演化。于是「复用哪几根」这个问题等价于
 * 「从旧批的位置里挑出 {@code count} 个留下」，本函数只做这一件事。
 *
 * <h2>选取规则</h2>
 * <ol>
 *   <li>{@code anchor} 指定的位置<strong>必须入选</strong> —— 它是使用者点击的那根，
 *       新一屏的内容由它展开而来，视觉上的因果链必须落在它身上；</li>
 *   <li>其余名额按<strong>最左优先</strong>补齐，与无锚点时的前缀复用保持一致，
 *       使未被点击的柱子不会无谓地换位；</li>
 *   <li>结果<strong>升序</strong>返回。</li>
 * </ol>
 *
 * 升序是硬要求：新柱本身有确定次序（一级按日期、二 / 三级按值降序），
 * 若映射后的 slot 不单调，Vue 会按 key 重排节点，横轴标签与柱体的对应关系
 * 会当场错位，观感是一次硬跳而非形变。
 *
 * <h2>为何以「占用的位置」而非「柱子数量」为输入</h2>
 * 锚定复用会留下不连续的占用（如 4 → 2 点第 3 根，留存 0 与 2）。此时若下一次
 * 分配仍按数量从 0 重排，同一批数据的 slot 会<strong>凭空改变</strong> ——
 * 而 slot 就是渲染 key，改变它等于让柱子去复用另一个 DOM 节点。
 * 退场清理正是这样一次「数据没变、只是基准推进」的重算，一旦发生重排，
 * 留存柱会被塞进刚淡出完毕的那具节点（宽 0、透明），随即又得过渡回正常尺寸，
 * 表现就是动画收尾时突兀地抽一下。
 *
 * 因此本函数只在给定的占用集合内做取舍：数量不变时原样返回，
 * 使「重算」成为幂等操作，退场清理不再有副作用。
 *
 * <h2>为何锚点只在「变少」时起作用</h2>
 * 名额不少于占用数时每个位置都还留着（没有柱子需要被挤掉），
 * 保留映射已是恒等，锚点无从改变任何结果。只有必须选择性淘汰时，
 * 「保谁」才是一个真问题。
 *
 * @param count 新批柱子数量
 * @param occupied 旧批实际占用的位置，升序；首次渲染传空数组
 * @param anchor 必须保留的位置；{@code null} 或不在 {@code occupied} 中时退化为最左优先
 * @returns 长度为 {@code count} 的升序 slot 序列，第 i 项即第 i 根新柱的 slot
 */
export function assignSlots(
  count: number,
  occupied: number[],
  anchor: number | null = null,
): number[] {
  if (count <= 0) return []

  // 名额够用时无需淘汰，沿用原占用并为多出的柱子追加新位置。
  // 新位置从当前最大值之后取，既不与留存者冲突，也天然排在它们右侧。
  if (count >= occupied.length) {
    const slots = [...occupied]
    let free = occupied.length ? Math.max(...occupied) + 1 : 0
    while (slots.length < count) {
      slots.push(free)
      free += 1
    }
    return slots
  }

  // 名额不足，必须淘汰。锚点若在占用集合内则独占一个名额，其余按最左优先补齐。
  const anchored = anchor !== null && occupied.includes(anchor)
  if (!anchored) return occupied.slice(0, count)

  const others = occupied.filter(slot => slot !== anchor).slice(0, count - 1)
  // 最左优先取到的位置天然升序，但锚点偏左时（如 5 → 2 点第 2 根）追加后会失序，
  // 而失序会让 Vue 重排正在过渡的节点，故统一排序。
  return [...others, anchor as number].sort((a, b) => a - b)
}

/**
 * 按<strong>业务身份</strong>对齐两批柱子，给出整体位移量。
 *
 * <h2>为什么位置对齐不够</h2>
 * {@link assignSlots} 把第 i 根新柱接到第 i 根旧柱上。这对下钻是对的（身份整批
 * 替换，位置是唯一可循的线索），但对<strong>窗口滑动</strong>就错了：
 * 日期窗口从 `7/27~8/2` 左移一天变成 `7/26~8/1`，两批各 7 根、身份错开一格 ——
 * 按位置配对得到「7 根柱全部原地改值」，没有任何进出场。观感是每根柱子的数值
 * 凭空跳变，而实际发生的事情是「整排右移一格、右端移出、左端补入」。
 *
 * <p>同理，从左侧手柄向外拖宽窗口时新增的是<strong>更早</strong>的一天，
 * 而 {@link assignSlots} 只会在右端追加位置，新柱于是从右侧挤进来 ——
 * 与「左边多了一天」的事实相反。
 *
 * <h2>做法：以匹配项为锚，向两侧顺推</h2>
 * 身份能在旧批里找到的柱子直接沿用它匹配的<strong>旧 slot</strong>；找不到的
 * （进场柱）则从<strong>最近的匹配项</strong>顺推一格。于是身份相同的柱子必然复用
 * 同一个 DOM 节点，没被覆盖的旧位置退场、新位置进场，
 * 而两者的左右方位由匹配关系自然决定 —— 不需要再判断「这次往左还是往右」。
 *
 * <h2>为什么不用「整体位移量」</h2>
 * 早先的做法是投票选出使重合最多的位移 δ，进场柱落在 {@code i + δ}。
 * 它有两个失效场景：
 * <ul>
 *   <li><strong>不连续占位</strong>（下钻锚定的产物，如 {@code [0, 1, 4]}）——
 *       对同一批数据重算时 δ = 0，slot 4 会被压回 2，那正是刚淡出完毕的退场柱
 *       所在的节点，留存柱被塞进去随即抽一下；</li>
 *   <li><strong>基准左端已是负 slot</strong>（连续左滑的产物）—— 再左滑一格时，
 *       新进场柱按 δ 会算到已被占用的位置上，落位碰撞导致失序、整体回退到位置对齐，
 *       于是每根柱子都换一个 DOM 节点，形变效果尽失。</li>
 * </ul>
 * 沿用旧 slot + 就近顺推同时解决两者：重算幂等，进场柱总落在紧邻匹配项的空位上。
 *
 * <p>slot 因此<strong>可以为负、可以不连续</strong>。它只是渲染 key 与排序依据；
 * 强行归一化会让同一批柱子的 key 凭空改变，反倒把留存柱推去复用别的节点。
 *
 * @param keys 新一批柱子的身份，顺序即从左到右
 * @param previous 旧批柱子及其占位
 * @returns 长度与 {@code keys} 相同的<strong>严格升序</strong> slot 序列；
 *   两批身份毫无重合、或匹配项本身失序时返回 {@code null}，
 *   交由调用方回退到位置对齐。失序回退是必要的兜底 ——
 *   数据顺序若与旧批不一致（理论上不会，但排序规则可能变），
 *   非升序会让 Vue 重排正在过渡的节点，反而制造硬跳。
 */
export function alignSlotsByIdentity(
  keys: string[],
  previous: MorphSnapshot[],
): number[] | null {
  if (keys.length === 0 || previous.length === 0) return null

  const slotByKey = new Map<string, number>()
  for (const item of previous) {
    // 同一批内身份唯一；真出现重复时取最左的那个，与「最左优先」的一贯取舍一致
    if (!slotByKey.has(item.bar.key)) slotByKey.set(item.bar.key, item.slot)
  }

  /** 各柱匹配到的旧 slot；{@code null} 表示这是一根进场柱。 */
  const matched = keys.map(key => slotByKey.get(key) ?? null)
  const firstMatch = matched.findIndex(slot => slot !== null)
  // 毫无重合：没有锚可依，交由位置对齐处理
  if (firstMatch === -1) return null

  const result: number[] = new Array(keys.length)

  // 从第一个匹配项向右：匹配的沿用旧 slot，进场的紧跟上一根之后
  result[firstMatch] = matched[firstMatch] as number
  for (let i = firstMatch + 1; i < keys.length; i += 1) {
    result[i] = matched[i] !== null ? (matched[i] as number) : result[i - 1] + 1
  }
  // 再从第一个匹配项向左回填：左侧的进场柱依次前推，故新柱出现在左端
  for (let i = firstMatch - 1; i >= 0; i -= 1) {
    result[i] = result[i + 1] - 1
  }

  // 落位必须严格升序，否则 Vue 会重排正在过渡的节点。不满足则交由调用方回退。
  for (let i = 1; i < result.length; i += 1) {
    if (result[i] <= result[i - 1]) return null
  }
  return result
}

/**
 * 决定新一批柱子各自占用哪个位置 —— 全部配对运算的唯一入口。
 *
 * <p>三处调用（{@link buildMorphBars}、{@link snapshotOf}、{@code hasNewcomer}）
 * 必须走同一条：它们对同一批数据的 slot 判断若不一致，就会出现
 * 「渲染时算作复用、快照里却记成另一个位置」这类错位，而错位只表现为动画抽一下。
 *
 * <h2>身份对齐优先，但下钻除外</h2>
 * 有锚点意味着这次变化是一次<strong>下钻</strong>：身份整批替换（日期 → 供应商），
 * 身份对齐本就找不到重合，而锚点表达的「新一屏从被点那根长出来」是更强的意图。
 * 故有锚点时直接走位置对齐，不去试探身份。
 *
 * @param bars 新一批柱子
 * @param previous 旧批柱子及其占位
 * @param anchor 必须被复用的位置（下钻时为被点击的那根）
 */
export function resolveSlots(
  bars: StackBar[],
  previous: MorphSnapshot[],
  anchor: number | null,
): number[] {
  if (anchor === null) {
    const aligned = alignSlotsByIdentity(bars.map(bar => bar.key), previous)
    if (aligned) return aligned
  }
  return assignSlots(bars.length, previous.map(item => item.slot), anchor)
}

/**
 * 判断新一批里是否存在「凭空出现」的柱子或堆叠层。
 *
 * 只有它们需要两帧提交 —— 复用的柱与层在 DOM 里已有前一刻的高度，
 * 直接改值就能被 CSS 过渡捕捉；而全新节点若起止值同帧写入会被合并，
 * 表现为「一出现就是最终高度」，正是要避免的硬切换。
 *
 * 层数比较必须走 {@link assignSlots} 的同一套映射：锚定复用下第 i 根新柱
 * 接的不再是旧批第 i 根，按下标直接比会拿错参照物，进而误判要不要两帧提交。
 *
 * @param bars 新一批柱子
 * @param previous 上一批柱子及其占位
 * @param anchor 必须被复用的位置
 */
/**
 * 结算一批柱子的占位，作为下一次配对的基准。
 *
 * 必须与 {@link buildMorphBars} 用同一套分配结果 —— 记忆里存的若是「柱子数据」
 * 而非「柱子及其占位」，下一次分配就会按数量重新从 0 排列，
 * 同一批数据的 slot 凭空改变，留存柱因此被塞进别的 DOM 节点。
 *
 * <h2>退场条目一并收进来</h2>
 * 「没被本批占用的旧位置」正在播退场动画，它们必须留在基准里，否则下一批数据
 * 到来时会凭空消失（详见 {@link MorphSnapshot.leavingSince}）。标记上退场起始
 * 时刻，由 {@link pruneSnapshot} 按各自时长过期。
 *
 * <p>已在退场中的条目<strong>沿用原时刻</strong>：连续切换时它不该被反复延期，
 * 否则一根柱子会在屏幕上以近零宽度赖着不走。
 *
 * @param bars 本批柱子
 * @param previous 本次配对所用的基准
 * @param anchor 本次配对所用的锚点
 * @param now 当前时刻，用于标记本次新产生的退场条目
 */
export function snapshotOf(
  bars: StackBar[],
  previous: MorphSnapshot[],
  anchor: number | null,
  now = 0,
): MorphSnapshot[] {
  const slots = resolveSlots(bars, previous, anchor)
  const taken = new Set(slots)
  const alive: MorphSnapshot[] = bars.map((bar, index) => ({ slot: slots[index], bar }))

  const leaving = previous
    .filter(item => !taken.has(item.slot))
    // 已在退场中的沿用原时刻，本次新退场的记为 now
    .map(item => ({ ...item, leavingSince: item.leavingSince ?? now }))

  // 按 slot 升序：基准本身不参与渲染，但有序便于调试对照
  return [...alive, ...leaving].sort((a, b) => a.slot - b.slot)
}

function hasNewcomer(bars: StackBar[], previous: MorphSnapshot[], anchor: number | null): boolean {
  const previousBySlot = new Map(previous.map(item => [item.slot, item.bar]))
  const slots = resolveSlots(bars, previous, anchor)
  // 身份对齐下柱数相同也可能有新柱（整排位移，两端一进一出），
  // 故不能靠 `bars.length > previous.length` 提前返回 —— 必须逐个位置查。
  return bars.some((bar, index) => {
    const previousBar = previousBySlot.get(slots[index])
    // 该位置本来就空着 —— 柱子本身是新的，自然算凭空出现
    if (!previousBar) return true
    return bar.segments.length > previousBar.segments.length
  })
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

  /**
   * 上一批柱子及其占位，用于按位置配对。
   *
   * 存的是「谁在哪个 slot」而非单纯的柱子列表：slot 就是渲染 key，
   * 必须与数据一同留存，否则下一次分配会从 0 重排，让柱子改去复用别的 DOM 节点。
   */
  const previous = shallowRef<MorphSnapshot[]>([])

  /**
   * 本次切换必须被复用的旧批位置，见 {@link morphFrom}。
   *
   * 只对紧随其后的那一批数据有效，退场清理时即归零 —— 它描述的是
   * 「这一次变化由谁引发」，而非一个持续的偏好。若让它跨批留存，
   * 后续无关的数据变化（如 SSE 推送导致柱数减少）会被错误地锚到旧位置上。
   */
  const anchor = shallowRef<number | null>(null)

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
    buildMorphBars(bars.value, previous.value, ceiling.value, settled.value, anchor.value),
  )

  /*
   * 关键时序：推进基准与清理退场必须分开，因为它们的正确时机根本不同。
   *
   * `morphBars` 的柱子集合完全派生于「bars ∪ previous」。于是：
   *   - previous 若<strong>不</strong>及时推进，进场柱会一直被算作 enter，
   *     下一批数据到来时它无人认领、凭空消失（快速连续滑动时的硬切来源）；
   *   - previous 若<strong>只记留存柱</strong>，正在退场的柱子同样凭空消失。
   *
   * 解法是让基准同时记住两类占位（见 {@link MorphSnapshot.leavingSince}），
   * 并把两件事各归其位：
   *   第 2 帧      —— 起始态放开 + 推进基准（scheduleSettle）；
   *   各自满时长后 —— 逐条剔除已播完的退场条目（scheduleRetire）。
   *
   * 第 2 帧推进是安全的：那一刻进场柱的起始帧（宽 0、透明）已提交到合成器，
   * 把它转成 stable 只是换个 phase 名字，weight 仍由同一条 CSS 过渡驱动。
   *
   * `immediate` 是必需的：图表组件由 `v-if` 控制，数据未到时渲染的是加载态，
   * 组件要等数据到齐才创建 —— 那一刻 `bars` 已是最终值，此后不再变化，
   * 非 immediate 的 watch 永远不会为这批数据触发，`previous` 就一直空着。
   * 后果是紧接着的第一次层级切换找不到任何可配对的旧柱，锚点也无从生效，
   * 形变退化为整批重新入场；第二次起才正常。故挂载即结算一次首批占位。
   */
  watch(bars, (next) => {
    // 需要起始态的只有「凭空出现的东西」：新挤进来的柱子、以及某根柱新长出的顶层。
    // 复用柱与退场层的起点都已存在于 DOM 中，直接改值即可由 CSS 过渡接手。
    // 基准取 previous 而非 watch 的旧值 —— previous 才是本次配对真正用的那一批。
    settled.value = !hasNewcomer(next, previous.value, anchor.value)
    // 快照取「本次实际渲染出的占位」而非裸数据：占位是这批柱子的 DOM 身份。
    // 退场条目一并收进来并打上时刻，故它们在后续批次里仍是可见的旧位置。
    scheduleSettle(snapshotOf(next, previous.value, anchor.value, now()))
    scheduleRetire()
  }, { flush: 'post', immediate: true })

  /**
   * 第 2 帧放开起始高度并推进配对基准。
   *
   * <h2>为什么两件事同帧做</h2>
   * 起始态的意义是「给 CSS 过渡一个起点」，那个起点在第 1 帧已提交到合成器；
   * 第 2 帧改值即触发过渡。此时把基准一并推进，进场柱从 enter 变成 stable ——
   * 但它的 weight 目标值不变（都是 {@link FULL_WEIGHT}），过渡照常跑完。
   *
   * <p>推进得<strong>不能更晚</strong>：只要基准还是陈旧的那一批，进场柱就一直
   * 依赖「当前数据」才存在于集合里，下一批数据一到它就无人认领。这正是
   * 快速连续滑动时上一步动画硬消失的根因。
   *
   * @param snapshot 本批渲染出的占位快照（含退场条目）
   */
  function scheduleSettle(snapshot: MorphSnapshot[]): void {
    cancelSettle()
    if (typeof requestAnimationFrame !== 'function') {
      settled.value = true
      advance(snapshot)
      return
    }
    // 双帧：第一帧确保起始高度已提交到合成器，第二帧再改值才会产生过渡
    settleFrame = requestAnimationFrame(() => {
      settleFrame = requestAnimationFrame(() => {
        settleFrame = null
        settled.value = true
        advance(snapshot)
      })
    })
  }

  /**
   * 推进配对基准，并让锚点失效。
   *
   * <p>锚点只描述「这一次变化的来源」，随基准一起失效。这要求
   * {@link resolveSlots} 对同一批数据在有无锚点时给出<strong>同样</strong>的 slot，
   * 否则推进本身就会让留存柱换节点 —— 那是 `alignSlotsByIdentity` 的幂等性
   * 所保证的（匹配柱一律沿用旧 slot）。
   */
  function advance(snapshot: MorphSnapshot[]): void {
    previous.value = snapshot
    anchor.value = null
  }

  /**
   * 逐条剔除已播完退场动画的条目。
   *
   * <h2>为什么不用「一次切换一个定时器」</h2>
   * 快速切换时不同批次的退场柱起始时刻不同。单个定时器只能表达「最后一次切换的
   * 动画何时结束」：先起步的那批被延后清理（在屏幕上以近零宽度赖着），
   * 后起步的被提前清理（淡出被截断）。
   *
   * <p>改为每次数据变化都排一次「一个时长之后再筛一遍」，判断交给纯函数
   * {@link pruneSnapshot} 按各条目自己的时刻做 —— 与切换节奏无关。
   * 若筛完仍有退场条目在场（更晚起步的那批），继续排下一轮。
   */
  function scheduleRetire(): void {
    cancelRetire()
    if (typeof window === 'undefined') return
    retireTimer = window.setTimeout(() => {
      retireTimer = null
      const pruned = pruneSnapshot(previous.value, now(), duration)
      if (pruned.length !== previous.value.length) previous.value = pruned
      // 仍有退场条目未到期（更晚起步的批次），继续排一轮
      if (pruned.some(item => item.leavingSince !== undefined)) scheduleRetire()
    }, duration)
  }

  /**
   * 声明下一批数据的形变锚点 —— 即「这次变化是由第 slot 根柱子引发的」。
   *
   * 须在改动数据<strong>之前</strong>调用（如点击回调里紧接着 emit 下钻），
   * 使随后那一批柱子的配对能把该位置算进留存名额。仅影响紧随的一批，
   * 之后自动归零，因此不必也不应在别处重置。
   *
   * @param slot 被点击柱子的位置序号
   */
  function morphFrom(slot: number): void {
    anchor.value = slot
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

  return { morphBars, morphFrom }
}

/**
 * 单调时钟 —— 优先 {@code performance.now()}，避免系统时间调整影响退场计时。
 *
 * 与 `useSlotMorph` 里的同名函数一致；两处都只用于「过了多久」这类差值运算。
 */
function now(): number {
  return typeof performance !== 'undefined' && typeof performance.now === 'function'
    ? performance.now()
    : Date.now()
}
