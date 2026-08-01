/**
 * 分页窗口模型 —— 让离散范围滑块能在一条<strong>无界轴</strong>上翻页。
 *
 * ## 要解决什么
 * `DiscreteRangeSlider` 只认它拿到的那一批刻度：`ticks` 有多长，轴就多长。
 * 但时间轴是无界的，一次只能展示其中一段（「池」，pool）。翻页即让这个池
 * 在绝对轴上平移。
 *
 * ## 翻页只移动池，不移动选择
 * 这是本模块最核心的一条。用户选定的是<strong>一段日期</strong>，翻页换的是
 * 「看得见的窗口」，不是「选中的日期」。故翻页后块应当仍落在同样的日期上，
 * 只是它在池中的相对位置变了。
 *
 * <p>由此得到一个简化：块<strong>不必单独存</strong>，它是
 * 「用户期望区间 ∩ 当前可用区间」的派生结果（见 {@link resolveBlock}）。
 * 状态里只留池位置与期望区间，任何时刻的块都由它们算出 ——
 * 「被挤压」与「挤压解除后恢复」因此都不需要额外分支。
 *
 * ## 三层索引，别混淆
 * <ul>
 *   <li><strong>绝对索引</strong> —— 本模块的运算空间。整数、可正可负、无上界，
 *       由调用方定义原点（如「距今天多少天」）。</li>
 *   <li><strong>池内局部索引</strong> —— 传给滑块的 `modelValue`，取值 0 ~ poolSize-1。
 *       由 {@link toLocalSelection} 换算得出。</li>
 *   <li><strong>业务值</strong> —— 日期、版本号等。本模块<strong>完全不知道</strong>，
 *       换算由调用方负责。</li>
 * </ul>
 * 池一平移，同一个局部索引就指向了另一个绝对位置 —— 若把块存成局部索引，
 * 翻页后它会「粘」在原来那几个格子上、选中的日期随之改变。故状态一律用绝对索引。
 *
 * ## 两侧的墙不对称
 * <ul>
 *   <li><strong>左侧是软墙</strong>（{@link PagedWindowConfig.reachableStart}）——
 *       池可以越过它，露出的位置渲染成不可达；块不能越过。
 *       语义是「数据从这天开始，更早的没有」。</li>
 *   <li><strong>右侧是硬墙</strong>（{@link PagedWindowConfig.reachableEnd}）——
 *       池<strong>不能</strong>越过它。语义是「明天及之后还没发生」，
 *       露出一片未来的灰点没有意义。</li>
 * </ul>
 * 这个不对称是刻意的，不是遗漏。
 */

/**
 * 翻页窗口的状态。三个字段都是<strong>绝对索引</strong>。
 *
 * <p>没有 `blockStart` / `blockEnd` —— 块是派生量，见 {@link resolveBlock}。
 * 若把它也存起来，每次翻页后都得手工同步，而那个同步逻辑正是最容易与
 * 期望区间失配的地方。
 */
export interface PagedWindowState {
  /** 池的左端（含）。池覆盖 `[poolStart, poolStart + poolSize - 1]`。 */
  poolStart: number
  /**
   * 用户<strong>最后一次主动选定</strong>的区间左端。
   *
   * <p>只在手动拖动 / 键盘操作时更新（{@link withManualSelection}），翻页只读不写。
   * 翻页若也写它，块被挤压一次期望就永久变窄了，往返便不可逆。
   */
  desiredStart: number
  /** 用户最后一次主动选定的区间右端。 */
  desiredEnd: number
}

/** 翻页的约束配置。 */
export interface PagedWindowConfig {
  /** 池的宽度（可见刻度数），至少 1。 */
  poolSize: number
  /** 选择块的跨度下限（含），至少 1。 */
  minSpan: number
  /** 选择块的跨度上限（含），至少等于 {@link minSpan}。 */
  maxSpan: number
  /** 可达区间的左界（含），绝对索引。软墙。 */
  reachableStart: number
  /** 可达区间的右界（含），绝对索引。硬墙。 */
  reachableEnd: number
}

/** 净化后的配置，各字段已相互协调，可直接参与算术。 */
export interface ResolvedPagedConfig extends PagedWindowConfig {
  /** 可达区间宽度（含两端）。 */
  reachableSpan: number
  /**
   * 池左端能到的最小值。
   *
   * <p>由「块至少要有 minSpan 格可用空间」推出，而非另设一道墙：
   * 池左移时右端跟着左移，可用区间 `[reachableStart, poolEnd]` 随之变窄，
   * 窄到 minSpan 就不能再退。解 `poolEnd - reachableStart + 1 = minSpan` 得
   * `poolStart = reachableStart + minSpan - poolSize`。
   *
   * <p>这个值是<strong>常量</strong>，不随块的位置变化 —— 这是「块由派生」
   * 带来的直接好处：早先块与池绑定平移时，可翻格数还得看块在哪，
   * 「按钮什么时候禁用」也就难以解释。
   */
  poolStartMin: number
  /** 池左端能到的最大值 —— 池右端压在硬墙上时的位置。 */
  poolStartMax: number
}

/** 把任意数值收敛到闭区间 `[lo, hi]`。非有限值取 `lo`。 */
function clampTo(value: number, lo: number, hi: number): number {
  if (!Number.isFinite(value)) return lo
  return Math.min(hi, Math.max(lo, Math.round(value)))
}

/**
 * 净化配置。
 *
 * <h2>顺序不可颠倒</h2>
 * <ol>
 *   <li>池宽至少 1；</li>
 *   <li>可达区间先定（倒置则交换）；</li>
 *   <li>跨度下限钳到 `min(可达宽度, 池宽)` —— 块既要装进可达区间，
 *       也要装进池；漏掉后者会让块的一端永远露在可见范围之外；</li>
 *   <li>跨度上限先与下限比较、再与前两者比较；</li>
 *   <li>池位置的上下界由前面的结果推出。</li>
 * </ol>
 * 「至少要选 7 格但只有 5 格可达」这类无解配置不会报错，
 * 只会让块在两个矛盾条件间反复被纠正，故必须在这里就地收敛。
 *
 * <p>池宽<strong>不</strong>受可达宽度约束：池可以比可达区间宽（左侧露出灰点），
 * 那正是软墙的用途。
 */
export function resolvePagedConfig(config: PagedWindowConfig): ResolvedPagedConfig {
  const poolSize = Math.max(1, Math.floor(config.poolSize) || 1)

  let low = Math.round(config.reachableStart)
  let high = Math.round(config.reachableEnd)
  if (!Number.isFinite(low)) low = 0
  if (!Number.isFinite(high)) high = low
  if (low > high) {
    const swap = low
    low = high
    high = swap
  }
  const reachableSpan = high - low + 1

  const spanCeiling = Math.min(reachableSpan, poolSize)
  const minSpan = Math.min(spanCeiling, Math.max(1, Math.floor(config.minSpan) || 1))
  const maxSpan = Math.min(spanCeiling, Math.max(minSpan, Math.floor(config.maxSpan) || minSpan))

  const poolStartMax = high - poolSize + 1
  // 下界不得超过上界：可达区间比池窄很多时两者会相遇，此时池无处可移。
  const poolStartMin = Math.min(poolStartMax, low + minSpan - poolSize)

  return {
    poolSize,
    minSpan,
    maxSpan,
    reachableStart: low,
    reachableEnd: high,
    reachableSpan,
    poolStartMin,
    poolStartMax,
  }
}

/** 池的右端（含）。 */
export function poolEndOf(state: PagedWindowState, config: ResolvedPagedConfig): number {
  return state.poolStart + config.poolSize - 1
}

/**
 * 当前可用区间 —— 块只能落在这里面。
 *
 * <p>是「池」与「可达区间」的交集：既要看得见（在池内），也要能选（在墙内）。
 * 由 {@link resolvePagedConfig} 的池位置边界保证其宽度不小于 `minSpan`。
 */
export function availableRangeOf(
  state: PagedWindowState,
  config: ResolvedPagedConfig,
): { lo: number; hi: number } {
  return {
    lo: Math.max(state.poolStart, config.reachableStart),
    hi: Math.min(poolEndOf(state, config), config.reachableEnd),
  }
}

/**
 * 由期望区间与可用区间派生出实际的块位置。
 *
 * <h2>最大程度保留</h2>
 * 把期望区间<strong>钳进</strong>可用区间 —— 交集就是「用户想要的、且此刻拿得到的」
 * 那一段。不做任何主动扩张：期望 15 天而只有 13 天可见时就显示 13 天，
 * 硬扩到 15 会把用户没选的日期也框进来。
 *
 * <h2>唯一的例外是跨度下限</h2>
 * 交集窄于 `minSpan` 时必须扩张（那是硬约束）。方向是<strong>先向右</strong>、
 * 右侧到底再向左 —— 与 `rangeslider.normalizeSelection` 同一策略，两处保持一致。
 * 实际效果是块贴住可用区间离期望区间更近的那一端。
 *
 * <p>「挤压」与「挤压解除后恢复」都是这个式子的自然结果，无需分支：
 * 池左移使 `hi` 变小 → 交集变窄 → 块看起来被压；池右移使 `hi` 变大 →
 * 交集重新展开 → 块自动长回去。
 */
export function resolveBlock(
  state: PagedWindowState,
  config: ResolvedPagedConfig,
): { start: number; end: number } {
  const { lo, hi } = availableRangeOf(state, config)
  const { minSpan, maxSpan } = config

  let desiredStart = Math.round(state.desiredStart)
  let desiredEnd = Math.round(state.desiredEnd)
  if (!Number.isFinite(desiredStart)) desiredStart = lo
  if (!Number.isFinite(desiredEnd)) desiredEnd = lo
  if (desiredStart > desiredEnd) {
    const swap = desiredStart
    desiredStart = desiredEnd
    desiredEnd = swap
  }

  let start = clampTo(desiredStart, lo, hi)
  let end = clampTo(desiredEnd, lo, hi)

  // 跨度上限：从右侧收。期望区间本身已受 maxSpan 约束，此处仅为防御。
  if (end - start + 1 > maxSpan) {
    end = start + maxSpan - 1
  }

  // 跨度下限：先向右扩，右侧到底再向左扩。
  if (end - start + 1 < minSpan) {
    end = start + minSpan - 1
    if (end > hi) {
      end = hi
      start = Math.max(lo, end - minSpan + 1)
    }
  }

  return { start, end }
}

/** 块的实际跨度（含两端）。可能小于期望跨度（被可用区间裁短时）。 */
export function blockSpanOf(state: PagedWindowState, config: ResolvedPagedConfig): number {
  const block = resolveBlock(state, config)
  return block.end - block.start + 1
}

/** 期望跨度（含两端）—— 用户主动选定的宽度，不受挤压影响。 */
export function desiredSpanOf(state: PagedWindowState): number {
  return Math.abs(state.desiredEnd - state.desiredStart) + 1
}

/**
 * 把状态收敛成合法值。
 *
 * <p>所有位移操作的出口守卫。外部传入的状态可能来自持久化的旧值、
 * 也可能在可达区间变化后失效（后端返回的可查范围收窄）。
 *
 * <p>只需收敛两件事：池位置钳进 `[poolStartMin, poolStartMax]`，
 * 期望区间钳进可达区间并满足跨度限制。块是派生的，无需收敛。
 */
export function normalizePagedWindow(
  state: PagedWindowState,
  config: ResolvedPagedConfig,
): PagedWindowState {
  const { reachableStart, reachableEnd, minSpan, maxSpan, poolStartMin, poolStartMax } = config

  const poolStart = clampTo(state.poolStart, poolStartMin, poolStartMax)

  let desiredStart = clampTo(state.desiredStart, reachableStart, reachableEnd)
  let desiredEnd = clampTo(state.desiredEnd, reachableStart, reachableEnd)
  if (desiredStart > desiredEnd) {
    const swap = desiredStart
    desiredStart = desiredEnd
    desiredEnd = swap
  }

  // 期望跨度同样受限：超上限从右收，不足下限先向右扩、右侧到底再向左。
  if (desiredEnd - desiredStart + 1 > maxSpan) {
    desiredEnd = desiredStart + maxSpan - 1
  }
  if (desiredEnd - desiredStart + 1 < minSpan) {
    desiredEnd = desiredStart + minSpan - 1
    if (desiredEnd > reachableEnd) {
      desiredEnd = reachableEnd
      desiredStart = Math.max(reachableStart, desiredEnd - minSpan + 1)
    }
  }

  return { poolStart, desiredStart, desiredEnd }
}

/**
 * 池位置的可移动范围，表达为相对当前位置的<strong>位移</strong>。
 *
 * <p>两个边界都是 {@link ResolvedPagedConfig} 里的常量减去当前池位置 ——
 * 不依赖块在哪。早先块与池绑定平移时，可翻格数还得看 `blockEnd`，
 * 于是「块被压到最小」会被误判成「不能再翻」。
 */
export function pageBounds(
  state: PagedWindowState,
  config: ResolvedPagedConfig,
): { min: number; max: number } {
  const current = normalizePagedWindow(state, config)
  return {
    min: config.poolStartMin - current.poolStart,
    max: config.poolStartMax - current.poolStart,
  }
}

/**
 * 是否还能向左翻。
 *
 * <p>等价于「池左端尚未触及 {@link ResolvedPagedConfig.poolStartMin}」，
 * 其几何含义是「块尚未被压到最小跨度且贴在池右端」。
 *
 * <p>注意<strong>块处于最小跨度并不意味着不能再翻</strong>：只要块还没贴住池右端，
 * 池就能继续左移、让更多不可达点进入视野，块留在原处即可。
 * 这是早先实现的一个 bug —— 块一到 `minSpan` 就把按钮禁用了。
 */
export function canPagePrev(state: PagedWindowState, config: ResolvedPagedConfig): boolean {
  return pageBounds(state, config).min < 0
}

/** 是否还能向右翻。等价于「池右端尚未压在硬墙上」。 */
export function canPageNext(state: PagedWindowState, config: ResolvedPagedConfig): boolean {
  return pageBounds(state, config).max > 0
}

/**
 * 平移<strong>池</strong>。期望区间原样不动。
 *
 * <p>请求的位移先与 {@link pageBounds} 取交集 —— 这就是「最大保留」：
 * 走不到请求的距离时走到能走的最远处，而不是拒绝整个操作。
 *
 * @param delta 位移格数。负为向左（更早），正为向右（更近）
 */
export function shiftPagedWindow(
  state: PagedWindowState,
  delta: number,
  config: ResolvedPagedConfig,
): PagedWindowState {
  const current = normalizePagedWindow(state, config)
  if (!Number.isFinite(delta) || delta === 0) return current

  const { min, max } = pageBounds(current, config)
  const applied = Math.min(max, Math.max(min, Math.round(delta)))
  if (applied === 0) return current

  return normalizePagedWindow({ ...current, poolStart: current.poolStart + applied }, config)
}

/**
 * 翻一整页 —— 位移等于池宽。
 *
 * @param direction 负为向左，正为向右；绝对值不参与计算，只取符号
 */
export function pagePagedWindow(
  state: PagedWindowState,
  direction: number,
  config: ResolvedPagedConfig,
): PagedWindowState {
  const sign = direction < 0 ? -1 : 1
  return shiftPagedWindow(state, sign * config.poolSize, config)
}

/**
 * 还需再走多少格才凑满一页 —— 供「先走一格、再补齐」的双击处理使用。
 *
 * <p>单击立即走 1 格，若判定窗口内来了第二击，再补上本函数给出的位移。
 * 因为位移可叠加，「先 −1 再 −14」与「直接 −15」结果完全相同。
 *
 * @param direction 负为向左，正为向右
 */
export function pendingPageDelta(
  state: PagedWindowState,
  direction: number,
  config: ResolvedPagedConfig,
): number {
  const sign = direction < 0 ? -1 : 1
  const { min, max } = pageBounds(state, config)
  // 已经走掉的那一格要从整页里扣除。
  const remaining = sign * config.poolSize - sign
  return Math.min(max, Math.max(min, remaining))
}

/**
 * 把块的位置换成<strong>池内局部索引</strong>，供滑块的 `modelValue` 使用。
 *
 * <p>{@link resolveBlock} 已把块钳进「池 ∩ 可达」，故结果必然落在
 * `[0, poolSize - 1]` 内。
 */
export function toLocalSelection(
  state: PagedWindowState,
  config: ResolvedPagedConfig,
): { start: number; end: number } {
  const block = resolveBlock(state, config)
  return {
    start: block.start - state.poolStart,
    end: block.end - state.poolStart,
  }
}

/**
 * 把可达区间换成<strong>池内局部索引</strong>，供滑块的 `minIndex` / `maxIndex` 使用。
 *
 * <p>结果可能超出 `[0, poolSize - 1]`（软墙在池左侧之外、硬墙在池右侧之外），
 * 滑块的 `resolveBounds` 会自行钳制。此处不预先钳制是为了让「池完全落在
 * 可达区间内」这种情形也能正确表达 —— 那时两个值都在池外，全部刻度可达。
 */
export function toLocalReachable(
  state: PagedWindowState,
  config: ResolvedPagedConfig,
): { minIndex: number; maxIndex: number } {
  return {
    minIndex: config.reachableStart - state.poolStart,
    maxIndex: config.reachableEnd - state.poolStart,
  }
}

/**
 * 用户<strong>手动</strong>调整选择后写回 —— 同时刷新期望区间。
 *
 * <p>这是期望区间唯一的写入点。翻页绝不能走这里，否则块被挤压一次
 * 期望就永久变窄了，往返便不可逆。
 *
 * @param local 滑块给出的池内局部索引
 */
export function withManualSelection(
  state: PagedWindowState,
  local: { start: number; end: number },
  config: ResolvedPagedConfig,
): PagedWindowState {
  return normalizePagedWindow(
    {
      poolStart: state.poolStart,
      desiredStart: state.poolStart + local.start,
      desiredEnd: state.poolStart + local.end,
    },
    config,
  )
}
