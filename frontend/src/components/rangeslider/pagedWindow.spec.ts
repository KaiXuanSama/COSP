/**
 * 分页窗口模型的验证与锁定。
 *
 * ## 场景设定
 * 用「7 月的第几天」作绝对索引：7/1 = 1、7/31 = 31、8/1 = 32。
 * 可达区间 `[17, 32]`（7/17 至 8/1），池宽 15，跨度限 7~15。
 *
 * ## 为什么重点在「翻页只移动池」
 * 这一版修掉了两个 bug，两者都不会抛异常、图上照样能画：
 * <ul>
 *   <li>块跟着池一起平移 —— 于是「选中的日期」随翻页而改变，
 *       用户看到的是「相对位置不变、日期在变」，与「换个窗口看同一段」相反；</li>
 *   <li>块被压到 minSpan 就禁用左翻 —— 而池明明还能左移，
 *       让更多不可达点进入视野。</li>
 * </ul>
 * 故本文件大量断言具体位置，而不只断言「合法」。
 */
import { describe, expect, it } from 'vitest'
import {
  availableRangeOf,
  blockSpanOf,
  canPageNext,
  canPagePrev,
  desiredSpanOf,
  normalizePagedWindow,
  pageBounds,
  pagePagedWindow,
  pendingPageDelta,
  poolEndOf,
  resolveBlock,
  resolvePagedConfig,
  shiftPagedWindow,
  toLocalReachable,
  toLocalSelection,
  withManualSelection,
  type PagedWindowState,
} from './pagedWindow'

/** 可达 7/17 ~ 8/1、池宽 15、跨度 7~15。 */
const CFG = resolvePagedConfig({
  poolSize: 15,
  minSpan: 7,
  maxSpan: 15,
  reachableStart: 17,
  reachableEnd: 32,
})

/** 初始状态：池 7/18~8/1，期望区间就是这满满 15 天。 */
const FULL: PagedWindowState = {
  poolStart: 18,
  desiredStart: 18,
  desiredEnd: 32,
}

/** 初始状态：池 7/18~8/1，期望区间为最近 7 天（7/26~8/1）—— 贴在池右端。 */
const WEEK: PagedWindowState = {
  poolStart: 18,
  desiredStart: 26,
  desiredEnd: 32,
}

/**
 * 初始状态：池 7/18~8/1，期望区间在<strong>中段</strong>（7/22~7/28）。
 *
 * <p>「翻页保持选中日期不变」只有中段的块能完整体现 —— 贴在池右端的块
 * 会因「日期已不可见」而被迫跟着走，那是几何约束而非模型行为。
 */
const MID: PagedWindowState = {
  poolStart: 18,
  desiredStart: 22,
  desiredEnd: 28,
}

/** 把状态读成紧凑元组，便于逐步断言。 */
function snap(state: PagedWindowState) {
  const block = resolveBlock(state, CFG)
  return {
    pool: [state.poolStart, poolEndOf(state, CFG)],
    block: [block.start, block.end],
    span: block.end - block.start + 1,
    desired: [state.desiredStart, state.desiredEnd],
  }
}

describe('resolvePagedConfig', () => {
  it('算出可达宽度与池位置边界', () => {
    expect(CFG).toMatchObject({
      poolSize: 15,
      minSpan: 7,
      maxSpan: 15,
      reachableStart: 17,
      reachableEnd: 32,
      reachableSpan: 16,
      // 池右端压在硬墙上：32 - 15 + 1 = 18
      poolStartMax: 18,
      // 可用区间窄到 minSpan：17 + 7 - 15 = 9
      poolStartMin: 9,
    })
  })

  it('倒置的可达边界被交换', () => {
    expect(resolvePagedConfig({ poolSize: 15, minSpan: 7, maxSpan: 15, reachableStart: 32, reachableEnd: 17 }))
      .toMatchObject({ reachableStart: 17, reachableEnd: 32 })
  })

  /**
   * 跨度上下限收敛到 `min(可达宽度, 池宽)`。
   *
   * 块既要装进可达区间也要装进池 —— 漏掉后者会让块的一端永远露在可见范围外。
   */
  it('跨度限制被可达宽度收紧', () => {
    expect(resolvePagedConfig({ poolSize: 15, minSpan: 7, maxSpan: 15, reachableStart: 28, reachableEnd: 32 }))
      .toMatchObject({ minSpan: 5, maxSpan: 5, reachableSpan: 5 })
  })

  it('跨度限制也被池宽收紧', () => {
    expect(resolvePagedConfig({ poolSize: 5, minSpan: 7, maxSpan: 15, reachableStart: 1, reachableEnd: 100 }))
      .toMatchObject({ minSpan: 5, maxSpan: 5 })
  })

  /** 池宽<strong>不</strong>受可达宽度约束 —— 左侧露出灰点正是软墙的用途。 */
  it('池宽可以大于可达宽度', () => {
    expect(resolvePagedConfig({ poolSize: 15, minSpan: 1, maxSpan: 5, reachableStart: 28, reachableEnd: 32 }))
      .toMatchObject({ poolSize: 15 })
  })

  /** 可达区间比池窄很多时上下界相遇，池无处可移。 */
  it('池无处可移时上下界相等', () => {
    const tight = resolvePagedConfig({ poolSize: 15, minSpan: 3, maxSpan: 3, reachableStart: 30, reachableEnd: 32 })
    expect(tight.poolStartMin).toBe(tight.poolStartMax)
  })

  it('池宽至少为 1', () => {
    expect(resolvePagedConfig({ poolSize: 0, minSpan: 1, maxSpan: 1, reachableStart: 0, reachableEnd: 0 }))
      .toMatchObject({ poolSize: 1 })
  })
})

describe('availableRangeOf', () => {
  /** 可用区间是「池 ∩ 可达」：既看得见也能选。 */
  it('是池与可达区间的交集', () => {
    expect(availableRangeOf(FULL, CFG)).toEqual({ lo: 18, hi: 32 })
    // 池左移到 9~23：软墙 17 收紧左端，池右端 23 收紧右端
    expect(availableRangeOf({ ...FULL, poolStart: 9 }, CFG)).toEqual({ lo: 17, hi: 23 })
  })
})

describe('resolveBlock', () => {
  it('期望区间完全可用时原样返回', () => {
    expect(resolveBlock(FULL, CFG)).toEqual({ start: 18, end: 32 })
    expect(resolveBlock(WEEK, CFG)).toEqual({ start: 26, end: 32 })
  })

  /**
   * 期望区间超出可用范围时被<strong>裁短</strong>，而不是整体平移。
   *
   * 这是「最大程度保留」的核心：保留交集，不主动扩张。
   * 池 9~23、期望 18~32 ⇒ 只有 18~23 这 6 天既想要又拿得到，
   * 但 6 < minSpan=7，故向左补一格到 17。
   */
  it('期望区间被裁到可用范围', () => {
    const block = resolveBlock({ ...FULL, poolStart: 9 }, CFG)
    expect(block).toEqual({ start: 17, end: 23 })
  })

  /**
   * 期望区间完全落在可用范围之外时，块贴住最近的那一端。
   *
   * 池 9~23、期望 26~32（全在池右侧之外）⇒ 块贴住 hi=23，向左取 minSpan 格。
   */
  it('期望区间在右侧之外时块贴住右端', () => {
    expect(resolveBlock({ ...WEEK, poolStart: 9 }, CFG)).toEqual({ start: 17, end: 23 })
  })

  /** 期望区间在左侧之外时块贴住左端。 */
  it('期望区间在左侧之外时块贴住左端', () => {
    const state: PagedWindowState = { poolStart: 18, desiredStart: 17, desiredEnd: 19 }
    // 可用 18~32，期望 17~19 ⇒ 交集 18~19（2 格）⇒ 向右补到 7 格
    expect(resolveBlock(state, CFG)).toEqual({ start: 18, end: 24 })
  })

  /** 跨度上限从右侧收。 */
  it('跨度超上限时从右侧收', () => {
    const capped = resolvePagedConfig({ poolSize: 15, minSpan: 7, maxSpan: 10, reachableStart: 17, reachableEnd: 32 })
    expect(resolveBlock({ poolStart: 18, desiredStart: 18, desiredEnd: 32 }, capped))
      .toEqual({ start: 18, end: 27 })
  })
})

describe('normalizePagedWindow', () => {
  it('合法状态原样返回', () => {
    expect(snap(normalizePagedWindow(FULL, CFG))).toEqual({
      pool: [18, 32], block: [18, 32], span: 15, desired: [18, 32],
    })
  })

  it('池位置被钳进上下界', () => {
    expect(normalizePagedWindow({ ...FULL, poolStart: 99 }, CFG).poolStart).toBe(18)
    expect(normalizePagedWindow({ ...FULL, poolStart: -99 }, CFG).poolStart).toBe(9)
  })

  /**
   * 期望区间被钳进可达区间。
   *
   * <p>钳制后 `17~32` 宽 16，超过 `maxSpan = 15`，故右端再从 32 收到 31 ——
   * 「先钳进可达区间、再钳跨度」这个顺序在这里同时生效。
   */
  it('期望区间被钳进可达区间', () => {
    const state = normalizePagedWindow({ poolStart: 18, desiredStart: 1, desiredEnd: 99 }, CFG)
    expect([state.desiredStart, state.desiredEnd]).toEqual([17, 31])
    expect(desiredSpanOf(state)).toBe(CFG.maxSpan)
  })

  it('倒置的期望区间被交换', () => {
    const state = normalizePagedWindow({ poolStart: 18, desiredStart: 30, desiredEnd: 20 }, CFG)
    expect([state.desiredStart, state.desiredEnd]).toEqual([20, 30])
  })

  it('期望跨度不足下限时补足', () => {
    const state = normalizePagedWindow({ poolStart: 18, desiredStart: 20, desiredEnd: 21 }, CFG)
    expect(desiredSpanOf(state)).toBe(7)
  })
})

describe('shiftPagedWindow — 翻页只移动池', () => {
  /**
   * 单格左移：<strong>期望区间不动</strong>，块因可用区间收窄而被裁短。
   *
   * 这一组断言是本次修复的核心 —— 早先块跟着池一起平移，
   * 于是选中的日期随翻页改变（「相对位置不变、日期在变」），
   * 而正确行为是「日期尽量不变、相对位置在变」。
   */
  it('逐格左移时期望区间不变、块被裁短', () => {
    const steps: Array<{ pool: number[]; block: number[]; span: number }> = [
      { pool: [17, 31], block: [18, 31], span: 14 },
      { pool: [16, 30], block: [18, 30], span: 13 },
      { pool: [15, 29], block: [18, 29], span: 12 },
      { pool: [14, 28], block: [18, 28], span: 11 },
      { pool: [13, 27], block: [18, 27], span: 10 },
      { pool: [12, 26], block: [18, 26], span: 9 },
      { pool: [11, 25], block: [18, 25], span: 8 },
      { pool: [10, 24], block: [18, 24], span: 7 },
      // 从这里起交集已只有 minSpan，块的左端被迫跟着走
      { pool: [9, 23], block: [17, 23], span: 7 },
    ]

    let state = FULL
    for (const expected of steps) {
      state = shiftPagedWindow(state, -1, CFG)
      expect(snap(state)).toEqual({ ...expected, desired: [18, 32] })
    }
  })

  /** 触底后继续左移无效果。 */
  it('触底后继续左移无变化', () => {
    let state = FULL
    for (let i = 0; i < 9; i += 1) state = shiftPagedWindow(state, -1, CFG)
    const bottom = snap(state)
    expect(snap(shiftPagedWindow(state, -1, CFG))).toEqual(bottom)
    expect(snap(shiftPagedWindow(state, -99, CFG))).toEqual(bottom)
  })

  /**
   * 中段的 7 天块在池右端追上它之前<strong>完全不动</strong>。
   *
   * 池 18~32、块 22~28：池左移 4 格后池右端才变成 28，此前块选的日期
   * 一格都没变。这条最直观地表达「翻页换的是窗口、不是选择」——
   * 早先的实现下块会跟着池一起左移，选中的日期随之改变。
   */
  it('中段块在池右端追上它之前完全不动', () => {
    let state = MID
    for (let i = 0; i < 4; i += 1) {
      state = shiftPagedWindow(state, -1, CFG)
      expect(snap(state).block).toEqual([22, 28])
    }
    // 第 5 格：池 13~27，交集 22~27 只有 6 格 < minSpan=7，
    // 故向左补一格到 21 —— 这是跨度下限这条硬约束，不是「保留」的例外。
    state = shiftPagedWindow(state, -1, CFG)
    expect(snap(state).block).toEqual([21, 27])
  })

  /** 期望区间在整个翻页过程中始终不变。 */
  it('期望区间全程不变', () => {
    let state = FULL
    for (let i = 0; i < 12; i += 1) {
      state = shiftPagedWindow(state, -1, CFG)
      expect([state.desiredStart, state.desiredEnd]).toEqual([18, 32])
    }
  })
})

describe('canPagePrev — 修复「块到最小就禁用」', () => {
  it('初始状态只能往左', () => {
    expect(canPagePrev(FULL, CFG)).toBe(true)
    expect(canPageNext(FULL, CFG)).toBe(false)
  })

  /**
   * 块已被压到 minSpan，但只要池还能左移，左翻就<strong>仍然可用</strong>。
   *
   * 这正是用户报的 bug：早先的实现看 `blockEnd` 算下界，块一到 7 天就禁用了；
   * 而池明明还能再退，让更多不可达点进入视野。
   */
  it('块压到最小跨度后仍可继续左翻', () => {
    let state = FULL
    // 走 7 格：池 11~25、块 18~25（8 天）
    for (let i = 0; i < 7; i += 1) state = shiftPagedWindow(state, -1, CFG)
    expect(blockSpanOf(state, CFG)).toBe(8)
    expect(canPagePrev(state, CFG)).toBe(true)

    // 第 8 格：块被压到 7 天，但池还没到底
    state = shiftPagedWindow(state, -1, CFG)
    expect(blockSpanOf(state, CFG)).toBe(7)
    expect(state.poolStart).toBe(10)
    expect(canPagePrev(state, CFG)).toBe(true)

    // 第 9 格：池到底，此时才禁用
    state = shiftPagedWindow(state, -1, CFG)
    expect(state.poolStart).toBe(CFG.poolStartMin)
    expect(canPagePrev(state, CFG)).toBe(false)
  })

  /**
   * 7 天块（期望本就是最小跨度）同样能一路翻到池的下界。
   *
   * 早先的实现下这种块从一开始就算不出可用位移。
   */
  it('期望本就是最小跨度时也能翻到底', () => {
    let state = WEEK
    let steps = 0
    while (canPagePrev(state, CFG) && steps < 50) {
      state = shiftPagedWindow(state, -1, CFG)
      steps += 1
    }
    expect(steps).toBe(9)
    expect(state.poolStart).toBe(9)
    expect(snap(state).block).toEqual([17, 23])
  })

  it('触底后只能往右', () => {
    let state = FULL
    for (let i = 0; i < 9; i += 1) state = shiftPagedWindow(state, -1, CFG)
    expect(canPagePrev(state, CFG)).toBe(false)
    expect(canPageNext(state, CFG)).toBe(true)
  })

  /** 可翻格数与块的位置<strong>无关</strong> —— 只看池。 */
  it('可翻格数不随块位置变化', () => {
    expect(pageBounds(FULL, CFG)).toEqual({ min: -9, max: 0 })
    expect(pageBounds(WEEK, CFG)).toEqual({ min: -9, max: 0 })
    expect(pageBounds({ poolStart: 18, desiredStart: 17, desiredEnd: 23 }, CFG))
      .toEqual({ min: -9, max: 0 })
  })
})

describe('pagePagedWindow — 跨页', () => {
  /**
   * 双击左翻一整页：请求 −15，但池只能走 −9。
   *
   * 结果是池 7/9~7/23、块 7/17~7/23 —— 与逐格走 9 次一致。
   */
  it('请求整页但只走到能走的最远处', () => {
    expect(snap(pagePagedWindow(FULL, -1, CFG))).toEqual({
      pool: [9, 23], block: [17, 23], span: 7, desired: [18, 32],
    })
  })

  it('与逐格走 9 次等价', () => {
    let stepwise = FULL
    for (let i = 0; i < 9; i += 1) stepwise = shiftPagedWindow(stepwise, -1, CFG)
    expect(snap(pagePagedWindow(FULL, -1, CFG))).toEqual(snap(stepwise))
  })

  /** 空间充裕时走满一页，期望区间照旧不动 —— 块因此完全离开视野、被贴到池右端。 */
  it('空间充裕时走满一页', () => {
    const wide = resolvePagedConfig({ poolSize: 15, minSpan: 7, maxSpan: 15, reachableStart: -100, reachableEnd: 32 })
    const state = pagePagedWindow(FULL, -1, wide)
    expect([state.poolStart, poolEndOf(state, wide)]).toEqual([3, 17])
    // 期望 18~32 全在池右侧之外 ⇒ 块贴住 hi=17
    expect(resolveBlock(state, wide)).toEqual({ start: 11, end: 17 })
    expect([state.desiredStart, state.desiredEnd]).toEqual([18, 32])
  })
})

describe('往返可逆性', () => {
  /**
   * 左翻到底再右翻回来，块与期望完全复原。
   *
   * 「期望区间只在手动选择时写入」是这条成立的唯一前提。
   */
  it('逐格往返完全复原', () => {
    let state = FULL
    for (let i = 0; i < 9; i += 1) state = shiftPagedWindow(state, -1, CFG)
    expect(snap(state)).toEqual({ pool: [9, 23], block: [17, 23], span: 7, desired: [18, 32] })

    for (let i = 0; i < 9; i += 1) state = shiftPagedWindow(state, 1, CFG)
    expect(snap(state)).toEqual({ pool: [18, 32], block: [18, 32], span: 15, desired: [18, 32] })
  })

  it('跨页往返复原', () => {
    const there = pagePagedWindow(FULL, -1, CFG)
    const back = pagePagedWindow(there, 1, CFG)
    expect(snap(back)).toEqual(snap(FULL))
  })

  /** 右翻途中跨度逐格恢复，而非最后一步突变 —— 否则动画会在某一帧跳。 */
  it('右翻途中跨度逐格恢复', () => {
    let state = pagePagedWindow(FULL, -1, CFG)
    const spans: number[] = []
    for (let i = 0; i < 9; i += 1) {
      state = shiftPagedWindow(state, 1, CFG)
      spans.push(blockSpanOf(state, CFG))
    }
    expect(spans).toEqual([7, 8, 9, 10, 11, 12, 13, 14, 15])
  })

  /** 7 天块往返后仍是 7 天，且回到原来那 7 天。 */
  it('7 天块往返回到同样的日期', () => {
    const there = pagePagedWindow(WEEK, -1, CFG)
    const back = pagePagedWindow(there, 1, CFG)
    expect(snap(back).block).toEqual([26, 32])
  })
})

describe('pendingPageDelta — 双击的补位', () => {
  /**
   * 「先走一格、判定窗口内再补齐」与「直接跨页」结果相同。
   *
   * 这是规避双击判定延迟的关键：单击立即生效，若来了第二击再补上剩余位移。
   */
  it('先走一格再补齐与直接跨页等价', () => {
    const afterOne = shiftPagedWindow(FULL, -1, CFG)
    const remaining = pendingPageDelta(afterOne, -1, CFG)
    expect(remaining).toBe(-8)

    const combined = shiftPagedWindow(afterOne, remaining, CFG)
    expect(snap(combined)).toEqual(snap(pagePagedWindow(FULL, -1, CFG)))
  })

  it('触底后补位为 0', () => {
    let state = FULL
    for (let i = 0; i < 9; i += 1) state = shiftPagedWindow(state, -1, CFG)
    expect(pendingPageDelta(state, -1, CFG)).toBe(0)
  })
})

describe('局部索引换算', () => {
  it('块的局部索引落在池内', () => {
    expect(toLocalSelection(FULL, CFG)).toEqual({ start: 0, end: 14 })
    expect(toLocalSelection(WEEK, CFG)).toEqual({ start: 8, end: 14 })
  })

  /**
   * 池左移后同一段日期的局部索引<strong>变大</strong>。
   *
   * 这正是「翻页只移动池」的可观测表现：块选的日期没变，但它在池里的位置右移了。
   * 早先的实现下这个值恒定不变 —— 那就是 bug。
   *
   * <p>用中段块观察：贴在池右端的块会因日期不可见而被裁，右端的局部索引
   * 反而始终是 14，看不出位移。
   */
  it('池左移后局部索引右移', () => {
    let state = MID
    expect(toLocalSelection(state, CFG)).toEqual({ start: 4, end: 10 })

    state = shiftPagedWindow(state, -1, CFG)
    // 池 17~31，块仍是 22~28 ⇒ 局部 5~11
    expect(toLocalSelection(state, CFG)).toEqual({ start: 5, end: 11 })

    state = shiftPagedWindow(state, -1, CFG)
    // 池 16~30，块仍是 22~28 ⇒ 局部 6~12
    expect(toLocalSelection(state, CFG)).toEqual({ start: 6, end: 12 })
  })

  it('任何状态下局部索引都在池范围内', () => {
    for (const base of [FULL, WEEK]) {
      let state = base
      for (let i = 0; i < 14; i += 1) {
        state = shiftPagedWindow(state, -1, CFG)
        const local = toLocalSelection(state, CFG)
        expect(local.start).toBeGreaterThanOrEqual(0)
        expect(local.end).toBeLessThanOrEqual(CFG.poolSize - 1)
        expect(local.start).toBeLessThanOrEqual(local.end)
      }
    }
  })

  /** 可达边界换成局部索引后可以越界，由滑块自行钳制。 */
  it('可达边界的局部索引可以超出池范围', () => {
    expect(toLocalReachable(FULL, CFG)).toEqual({ minIndex: -1, maxIndex: 14 })

    const paged = pagePagedWindow(FULL, -1, CFG)
    // 池 9~23：软墙 17 ⇒ 局部 8；硬墙 32 ⇒ 局部 23（远超池右端）
    expect(toLocalReachable(paged, CFG)).toEqual({ minIndex: 8, maxIndex: 23 })
  })
})

describe('withManualSelection', () => {
  /** 手动调整会刷新期望区间 —— 这是它唯一的写入点。 */
  it('手动选择刷新期望区间', () => {
    const next = withManualSelection(FULL, { start: 4, end: 14 }, CFG)
    expect([next.desiredStart, next.desiredEnd]).toEqual([22, 32])
    expect(desiredSpanOf(next)).toBe(11)
  })

  /** 翻页后手动选择，期望区间按<strong>当时的池位置</strong>换算。 */
  it('翻页后手动选择按当前池换算', () => {
    const paged = shiftPagedWindow(FULL, -5, CFG) // 池 13~27
    const next = withManualSelection(paged, { start: 0, end: 6 }, CFG)
    // 局部 0~6 在池 13~27 里是绝对 13~19，但 13 在软墙外 ⇒ 收敛到 17~23
    expect([next.desiredStart, next.desiredEnd]).toEqual([17, 23])
  })

  /** 手动缩窄后翻页往返回到新宽度。 */
  it('手动缩窄后往返回到新宽度', () => {
    const narrowed = withManualSelection(FULL, { start: 8, end: 14 }, CFG)
    expect(blockSpanOf(narrowed, CFG)).toBe(7)

    const there = pagePagedWindow(narrowed, -1, CFG)
    const back = pagePagedWindow(there, 1, CFG)
    expect(blockSpanOf(back, CFG)).toBe(7)
    expect([back.desiredStart, back.desiredEnd]).toEqual([26, 32])
  })

  /** 翻页不写期望区间。 */
  it('翻页不改期望区间', () => {
    const paged = pagePagedWindow(FULL, -1, CFG)
    expect(blockSpanOf(paged, CFG)).toBe(7)
    expect([paged.desiredStart, paged.desiredEnd]).toEqual([18, 32])
  })
})

/**
 * 单点形态下的翻页。
 *
 * <p>`minSpan = maxSpan = 1` 时块只占一格，池的可移动范围因此比区间形态更宽 ——
 * `poolStartMin = reachableStart + 1 - poolSize`，即池左端可以退到「软墙左侧
 * poolSize−1 格」处，那时可用区间恰好只剩软墙那一格。
 */
describe('单点形态的翻页', () => {
  const SCFG = resolvePagedConfig({
    poolSize: 15,
    minSpan: 1,
    maxSpan: 1,
    reachableStart: 17,
    reachableEnd: 32,
  })

  /** 池 18~32、点落在 7/25。 */
  const SPOINT: PagedWindowState = { poolStart: 18, desiredStart: 25, desiredEnd: 25 }

  it('池的可移动范围随跨度下限放宽', () => {
    // 17 + 1 - 15 = 3
    expect(SCFG.poolStartMin).toBe(3)
    expect(SCFG.poolStartMax).toBe(18)
  })

  it('块恒为一格', () => {
    expect(resolveBlock(SPOINT, SCFG)).toEqual({ start: 25, end: 25 })
    expect(blockSpanOf(SPOINT, SCFG)).toBe(1)
  })

  /** 翻页时点<strong>留在原来的日期上</strong>，只有相对位置改变。 */
  it('翻页时点不动、局部索引右移', () => {
    expect(toLocalSelection(SPOINT, SCFG)).toEqual({ start: 7, end: 7 })

    const shifted = shiftPagedWindow(SPOINT, -5, SCFG)
    expect(resolveBlock(shifted, SCFG)).toEqual({ start: 25, end: 25 })
    expect(toLocalSelection(shifted, SCFG)).toEqual({ start: 12, end: 12 })
  })

  /** 点被池右端追上后跟着走。 */
  it('池右端追上点后点跟着走', () => {
    // 池左移 8 格：池 10~24，点 25 已不可见 ⇒ 贴到 hi=24
    const shifted = shiftPagedWindow(SPOINT, -8, SCFG)
    expect(resolveBlock(shifted, SCFG)).toEqual({ start: 24, end: 24 })
    // 期望仍是 25，翻回来即可复原
    expect([shifted.desiredStart, shifted.desiredEnd]).toEqual([25, 25])
    const back = shiftPagedWindow(shifted, 8, SCFG)
    expect(resolveBlock(back, SCFG)).toEqual({ start: 25, end: 25 })
  })

  it('手动选择刷新期望位置', () => {
    const next = withManualSelection(SPOINT, { start: 3, end: 3 }, SCFG)
    expect([next.desiredStart, next.desiredEnd]).toEqual([21, 21])
  })

  it('翻到底后点顶在软墙上', () => {
    let state = SPOINT
    while (canPagePrev(state, SCFG)) state = shiftPagedWindow(state, -1, SCFG)
    expect(state.poolStart).toBe(3)
    expect(resolveBlock(state, SCFG)).toEqual({ start: 17, end: 17 })
  })
})

describe('不变量', () => {
  /**
   * 任意位移序列后状态都合法。
   *
   * 挡住的是「某条分支忘了钳制」这类遗漏 —— 那种遗漏通常只在特定序列下暴露。
   */
  it('任意位移后块与池都合法', () => {
    const deltas = [-1, 1, -15, 15, -3, 7, -99, 99, 0]
    for (const base of [FULL, WEEK]) {
      let state = base
      for (const delta of deltas) {
        state = shiftPagedWindow(state, delta, CFG)
        const block = resolveBlock(state, CFG)

        // 块在可达区间内
        expect(block.start).toBeGreaterThanOrEqual(CFG.reachableStart)
        expect(block.end).toBeLessThanOrEqual(CFG.reachableEnd)
        expect(block.start).toBeLessThanOrEqual(block.end)

        // 块在池内
        expect(block.start).toBeGreaterThanOrEqual(state.poolStart)
        expect(block.end).toBeLessThanOrEqual(poolEndOf(state, CFG))

        // 跨度在限制内
        const span = block.end - block.start + 1
        expect(span).toBeGreaterThanOrEqual(CFG.minSpan)
        expect(span).toBeLessThanOrEqual(CFG.maxSpan)

        // 池在上下界内
        expect(state.poolStart).toBeGreaterThanOrEqual(CFG.poolStartMin)
        expect(state.poolStart).toBeLessThanOrEqual(CFG.poolStartMax)
      }
    }
  })

  /** 位移可叠加：分两步走与一次走完结果相同（双击补位技巧的前提）。 */
  it('位移可叠加', () => {
    for (const [a, b] of [[-1, -8], [-3, -6], [-2, -20], [-9, 4]]) {
      const stepwise = shiftPagedWindow(shiftPagedWindow(FULL, a, CFG), b, CFG)
      const direct = shiftPagedWindow(FULL, a + b, CFG)
      expect(snap(stepwise)).toEqual(snap(direct))
    }
  })

  /**
   * 块始终是「期望 ∩ 可用」，除跨度下限补足外不做扩张。
   *
   * 这条把「最大程度保留」立成不变量：块的每一格都必须既在期望区间内、
   * 又在可用区间内 —— 除非交集本身不足 minSpan。
   */
  it('块不超出期望区间，除非要补足下限', () => {
    let state = FULL
    for (let i = 0; i < 12; i += 1) {
      state = shiftPagedWindow(state, -1, CFG)
      const block = resolveBlock(state, CFG)
      const { lo, hi } = availableRangeOf(state, CFG)
      const overlapLo = Math.max(state.desiredStart, lo)
      const overlapHi = Math.min(state.desiredEnd, hi)
      const overlapSpan = overlapHi - overlapLo + 1

      if (overlapSpan >= CFG.minSpan) {
        // 交集够宽：块必须恰好是交集
        expect(block).toEqual({ start: overlapLo, end: overlapHi })
      } else {
        // 交集不足：块被扩到 minSpan，但仍在可用区间内
        expect(block.end - block.start + 1).toBe(CFG.minSpan)
        expect(block.start).toBeGreaterThanOrEqual(lo)
        expect(block.end).toBeLessThanOrEqual(hi)
      }
    }
  })
})
