import { describe, expect, it } from 'vitest'
import {
  alignSlotsByIdentity,
  assignSlots,
  buildMorphBars,
  ENTER_HEIGHT_RATIO,
  MORPH_DURATION,
  snapshotOf,
  type MorphSnapshot,
} from './useStackMorph'
import type { StackBar, StackSegment } from './usagechart'

/**
 * 统一形变模型的验证与锁定。
 *
 * 这套动效的全部意义在于「跨层级也有连续的起点」，因此测试锁的是配对规则本身：
 * 柱体按位置从左对齐、堆叠层按位置从底对齐。只要这两条不变，
 * 「堆叠↔单一」「堆叠↔堆叠」就无需各自适配 —— 它们本就是同一种运算。
 */

/** 造一个段。 */
function seg(primary: string, value: number, isOther = false): StackSegment {
  return { primary, label: primary, value, ratio: 0, isOther, detail: [] }
}

/** 造一根柱子，段值自下而上给出。 */
function bar(key: string, ...values: number[]): StackBar {
  const segments = values.map((value, index) => seg(`${key}-s${index}`, value))
  return {
    key,
    label: key,
    fullLabel: key,
    total: values.reduce((sum, value) => sum + value, 0),
    segments,
  }
}

/**
 * 把一批柱子铺成「依次占用 0..n-1」的基准快照。
 *
 * 绝大多数场景的上一批都是这样连续占位的，故用它简化用例；
 * 需要验证不连续占位（锚定复用的产物）时直接手写 {@link MorphSnapshot}。
 */
function seats(...bars: StackBar[]): MorphSnapshot[] {
  return bars.map((bar, slot) => ({ slot, bar }))
}

/**
 * 生成 0..n-1 的占位数组，用于 {@link assignSlots} 的 occupied 参数。
 */
function slots(n: number): number[] {
  return Array.from({ length: n }, (_unused, index) => index)
}

describe('柱体维度：左侧复用、右侧进出', () => {
  it('柱子变多时，公共前缀原地复用，多出的尾部柱进场', () => {
    const previous = seats(bar('a', 100), bar('b', 80), bar('c', 60))
    const next = [bar('x', 90), bar('y', 70), bar('z', 50), bar('w', 30), bar('v', 10)]

    const morph = buildMorphBars(next, previous, 200)

    // 位置序号即渲染 key，前三根沿用同一批 DOM 节点
    expect(morph.map(item => item.slot)).toEqual([0, 1, 2, 3, 4])
    expect(morph.slice(0, 3).map(item => item.phase)).toEqual(['stable', 'stable', 'stable'])
    expect(morph.slice(3).map(item => item.phase)).toEqual(['enter', 'enter'])
  })

  it('柱子变少时，多余的尾部柱退场且保留旧数据以便淡出', () => {
    const previous = seats(bar('a', 100), bar('b', 80), bar('c', 60), bar('d', 40))
    const next = [bar('x', 90), bar('y', 70)]

    const morph = buildMorphBars(next, previous, 200)

    expect(morph.map(item => item.phase)).toEqual(['stable', 'stable', 'leave', 'leave'])
    // 退场柱仍带旧身份，否则淡出期间会显示空白
    expect(morph[2].bar.key).toBe('c')
    expect(morph[3].bar.key).toBe('d')
    // 退场柱的层高归零，平滑收缩而非突然消失
    expect(morph[2].segments.every(item => item.ratio === 0)).toBe(true)
  })

  it('数量不变时全部原地复用，没有进出场', () => {
    const previous = seats(bar('a', 100), bar('b', 80))
    const next = [bar('x', 20), bar('y', 160)]

    const morph = buildMorphBars(next, previous, 200)

    expect(morph.map(item => item.phase)).toEqual(['stable', 'stable'])
  })

  it('退场柱宽度权重收到 0，让出的空间被留存柱吃掉', () => {
    // 「向右滑出」不是位移动画，而是 flex-grow 归零后留存柱扩张的副作用。
    // 一旦这里回到非 0，挤压观感就会退化成硬切。
    const previous = seats(bar('a', 100), bar('b', 80), bar('c', 60), bar('d', 40), bar('e', 20))
    const next = [bar('x', 90), bar('y', 70), bar('z', 50)]

    const morph = buildMorphBars(next, previous, 200)

    // 1:1:1:0:0 —— 留存三根满权重，多余两根归零
    expect(morph.map(item => item.weight)).toEqual([1, 1, 1, 0, 0])
  })

  it('新增柱起始帧宽度为 0，落位后才扩张到等分', () => {
    const previous = seats(bar('a', 100), bar('b', 80), bar('c', 60))
    const next = [bar('x', 90), bar('y', 70), bar('z', 50), bar('w', 30), bar('v', 10)]

    // 起始帧：新柱还没有宽度，留存柱仍占满 —— 给 CSS 过渡一个起点
    expect(buildMorphBars(next, previous, 200, false).map(item => item.weight))
      .toEqual([1, 1, 1, 0, 0])

    // 落位：一起扩张到等分，观感是新柱从右侧挤入
    expect(buildMorphBars(next, previous, 200, true).map(item => item.weight))
      .toEqual([1, 1, 1, 1, 1])
  })

  it('首次渲染没有上一批，全部柱子视为进场', () => {
    const morph = buildMorphBars([bar('a', 100), bar('b', 50)], [], 200)

    expect(morph.map(item => item.phase)).toEqual(['enter', 'enter'])
  })
})

describe('新增柱的起始态：从轴中位挤入', () => {
  it('未落位时新增柱压成单层并停在轴中位', () => {
    const previous = seats(bar('a', 100))
    const next = [bar('x', 100), bar('y', 20)]

    const morph = buildMorphBars(next, previous, 200, false)

    // 新柱先渲染一帧起始态，给 CSS transition 一个起点
    expect(morph[1].segments).toHaveLength(1)
    expect(morph[1].segments[0].ratio).toBe(ENTER_HEIGHT_RATIO)
    // 复用柱不受影响：它的起点是自己上一刻的高度，本就连续
    expect(morph[0].segments[0].ratio).toBeCloseTo(0.5)
  })

  it('落位后新增柱调整到目标高度', () => {
    const previous = seats(bar('a', 100))
    const next = [bar('x', 100), bar('y', 20)]

    const morph = buildMorphBars(next, previous, 200, true)

    expect(morph[1].segments[0].ratio).toBeCloseTo(0.1)
  })
})
describe('起始态：凭空出现的层从 0 膨胀', () => {
  it('新增顶层在起始帧高度为 0，落位后才到目标值', () => {
    const previous = seats(bar('p', 60))
    const next = [bar('p', 60, 40)]

    // settled=false 即「起始帧」：新顶层还没有高度，CSS 过渡因此有起点可依
    const start = buildMorphBars(next, previous, 100, false)
    expect(start[0].segments.map(item => item.ratio)).toEqual([0.6, 0])

    // 下一帧落位：新顶层膨胀到目标值，底部复用层不受影响
    const settled = buildMorphBars(next, previous, 100, true)
    expect(settled[0].segments.map(item => item.ratio)).toEqual([0.6, 0.4])
  })

  it('底部复用层在起始帧就已是目标值，不经过 0', () => {
    // 复用层的起点是它自己上一刻的高度，归零会把连续性打断成闪烁
    const start = buildMorphBars([bar('p', 80, 20)], seats(bar('p', 50)), 100, false)

    expect(start[0].segments[0].ratio).toBe(0.8)
  })
})
describe('堆叠维度：底部复用、顶部进出', () => {
  it('层数变少时，多余顶层收缩为 0 并标记退场', () => {
    // 5 层 → 3 层：底部三层复用，顶部两层归零
    const previous = seats(bar('a', 50, 40, 30, 20, 10))
    const next = [bar('x', 60, 50, 40)]

    const segments = buildMorphBars(next, previous, 200)[0].segments

    expect(segments.map(item => item.slot)).toEqual([0, 1, 2, 3, 4])
    expect(segments.slice(0, 3).map(item => item.leaving)).toEqual([false, false, false])
    expect(segments.slice(3).map(item => item.leaving)).toEqual([true, true])
    expect(segments.slice(3).every(item => item.ratio === 0)).toBe(true)
  })

  it('层数变多时，底部复用、新增顶层直接给出目标高度从 0 膨胀', () => {
    // 3 层 → 5 层：新增的第 4、5 层在 DOM 里是新节点，从 0 长到目标值
    const previous = seats(bar('a', 50, 40, 30))
    const next = [bar('x', 60, 50, 40, 30, 20)]

    const segments = buildMorphBars(next, previous, 200)[0].segments

    expect(segments).toHaveLength(5)
    expect(segments.every(item => item.leaving === false)).toBe(true)
    expect(segments[4].ratio).toBeCloseTo(0.1)
  })

  it('单层与多层互转走同一条路径，无需专门适配', () => {
    // 单一柱 → 堆叠柱：单一柱只是「只有一层的堆叠柱」
    const toStack = buildMorphBars([bar('x', 60, 50)], seats(bar('a', 100)), 200)[0].segments
    expect(toStack).toHaveLength(2)
    expect(toStack.every(item => item.leaving === false)).toBe(true)

    // 堆叠柱 → 单一柱：顶层退场，底层复用
    const toSingle = buildMorphBars([bar('x', 100)], seats(bar('a', 60, 50)), 200)[0].segments
    expect(toSingle).toHaveLength(2)
    expect(toSingle[0].leaving).toBe(false)
    expect(toSingle[1].leaving).toBe(true)
    expect(toSingle[1].ratio).toBe(0)
  })
})

describe('高度换算的边界', () => {
  it('轴上限为 0 时所有比例为 0，不产生 NaN', () => {
    const segments = buildMorphBars([bar('x', 10, 20)], [], 0)[0].segments

    expect(segments.every(item => item.ratio === 0)).toBe(true)
  })

  it('比例按值占轴上限换算，与刻度共用同一基准', () => {
    const segments = buildMorphBars([bar('x', 50, 100)], [], 200)[0].segments

    expect(segments[0].ratio).toBeCloseTo(0.25)
    expect(segments[1].ratio).toBeCloseTo(0.5)
  })
})

describe('标签文案的交叉淡化', () => {
  it('同一格换了文案时带上旧的，两段短暂共存', () => {
    // 柱体按位置复用 DOM 节点，标签文字因此是被就地改写的。
    // 带上旧文案，新旧才有重叠的一瞬可供交叉淡化。
    const previous = seats(bar('7/28', 100), bar('7/29', 80))
    const morph = buildMorphBars([bar('gorouter', 100), bar('longcat', 80)], previous, 200)

    expect(morph[0].outgoingLabel).toBe('7/28')
    expect(morph[1].outgoingLabel).toBe('7/29')
  })

  it('文案未变则不带旧的 —— 否则未受影响的柱子也会跟着闪一下', () => {
    // SSE 推送只改数值不改分类，此时标签不该有任何动作
    const previous = seats(bar('a', 100), bar('b', 80))
    const morph = buildMorphBars([bar('a', 120), bar('b', 90)], previous, 200)

    expect(morph.every(item => item.outgoingLabel === null)).toBe(true)
  })

  it('新挤入的柱子没有旧文案可淡出', () => {
    // 该位置原本空着，整柱是淡入的，标签跟着柱子一起出现即可
    const morph = buildMorphBars([bar('a', 100), bar('b', 80)], seats(bar('a', 100)), 200)

    expect(morph[1].phase).toBe('enter')
    expect(morph[1].outgoingLabel).toBeNull()
  })

  it('退场柱不做文案交叉 —— 标签随整柱一起淡出', () => {
    const morph = buildMorphBars([bar('a', 100)], seats(bar('a', 100), bar('b', 80)), 200)

    const leaving = morph.find(item => item.phase === 'leave')!
    expect(leaving.outgoingLabel).toBeNull()
  })

  it('锚定复用时旧文案取该位置原本的那一个', () => {
    // 5 → 3 且点末根：第 4 号位留存，它的旧文案是 e 而非按下标算出的 c
    const previous = seats(
      bar('a', 100), bar('b', 90), bar('c', 80), bar('d', 70), bar('e', 60),
    )
    const morph = buildMorphBars(
      [bar('x', 50), bar('y', 40), bar('z', 30)], previous, 200, true, 4,
    )

    const anchored = morph.find(item => item.slot === 4)!
    expect(anchored.outgoingLabel).toBe('e')
  })
})

describe('退场柱的存活时长', () => {
  it('清理延迟与 CSS 进出场动画同值', () => {
    // 退场柱由「previous 比 bars 多出来的尾部」派生，因此它在 DOM 里能活多久，
    // 完全取决于配对基准何时推进。若这个时长短于 CSS 动画，元素会在淡出刚起步时
    // 被摘掉 —— 表现就是柱子数量硬切。两者必须同值，这条断言即为该契约的锁。
    expect(MORPH_DURATION).toBe(420)
  })
})

describe('选位规则：锚点必留，其余最左优先', () => {
  it('无锚点时退化为前缀映射，即历史行为', () => {
    expect(assignSlots(3, slots(5), null)).toEqual([0, 1, 2])
    expect(assignSlots(5, slots(3), null)).toEqual([0, 1, 2, 3, 4])
  })

  it('5 → 3 点末根：留 0、1 与锚点 4，中间两位被淘汰', () => {
    expect(assignSlots(3, slots(5), 4)).toEqual([0, 1, 4])
  })

  it('5 → 1 点末根：唯一名额归锚点', () => {
    expect(assignSlots(1, slots(5), 4)).toEqual([4])
  })

  it('5 → 2 点末根：一个名额给最左，一个给锚点', () => {
    expect(assignSlots(2, slots(5), 4)).toEqual([0, 4])
  })

  it('锚点偏左时结果仍升序，锚点不会被重复计入', () => {
    // 5 → 2 点第 2 根（slot 1）：最左优先取 0，锚点 1 —— 恰好等于前缀映射
    expect(assignSlots(2, slots(5), 1)).toEqual([0, 1])
    // 5 → 3 点第 2 根：最左取 0、2，加锚点 1，排序后 [0,1,2]
    expect(assignSlots(3, slots(5), 1)).toEqual([0, 1, 2])
  })

  it('锚点即最左位时与前缀映射一致', () => {
    expect(assignSlots(2, slots(5), 0)).toEqual([0, 1])
  })

  it('柱数不减时锚点无效 —— 每个旧位置都还留着，无需选择性淘汰', () => {
    expect(assignSlots(5, slots(5), 4)).toEqual([0, 1, 2, 3, 4])
    expect(assignSlots(7, slots(5), 4)).toEqual([0, 1, 2, 3, 4, 5, 6])
  })

  it('锚点越界或旧批为空时安全退化', () => {
    expect(assignSlots(2, slots(5), 9)).toEqual([0, 1])
    expect(assignSlots(2, slots(5), -1)).toEqual([0, 1])
    expect(assignSlots(3, slots(0), 0)).toEqual([0, 1, 2])
  })

  it('新批为空时不产出任何位置', () => {
    expect(assignSlots(0, slots(5), 4)).toEqual([])
  })
})

describe('锚定复用：点击的那根柱子必然留存', () => {
  it('5 → 1 点末根，新柱接在锚点位上，其余四根全部退场', () => {
    // 「点哪根 → 哪根留下」是这条规则的全部意义：下一屏由被点柱展开而来，
    // 视觉上的因果链必须落在它身上，而不是莫名其妙落到最左那根。
    const previous = seats(bar('a', 100), bar('b', 80), bar('c', 60), bar('d', 40), bar('e', 20))
    const next = [bar('e-detail', 20)]

    const morph = buildMorphBars(next, previous, 200, true, 4)

    // 唯一的新柱占用锚点位 4，因此它复用的是被点那根的 DOM 节点
    const stable = morph.filter(item => item.phase === 'stable')
    expect(stable).toHaveLength(1)
    expect(stable[0].slot).toBe(4)
    expect(stable[0].bar.key).toBe('e-detail')

    // 其余四根退场并保留旧身份，宽度归零后被挤出
    expect(morph.filter(item => item.phase === 'leave').map(item => item.bar.key))
      .toEqual(['a', 'b', 'c', 'd'])
    expect(morph.filter(item => item.phase === 'leave').every(item => item.weight === 0)).toBe(true)
  })

  it('5 → 3 点末根，复用 0、1 与锚点 4，中间两根退场', () => {
    const previous = seats(bar('a', 100), bar('b', 80), bar('c', 60), bar('d', 40), bar('e', 20))
    const next = [bar('x', 90), bar('y', 70), bar('z', 50)]

    const morph = buildMorphBars(next, previous, 200, true, 4)

    // 输出按 slot 升序，留存与退场交错排列
    expect(morph.map(item => item.slot)).toEqual([0, 1, 2, 3, 4])
    expect(morph.map(item => item.phase)).toEqual(['stable', 'stable', 'leave', 'leave', 'stable'])
    // 被点那根的位置上换成了新数据的最后一项
    expect(morph[4].bar.key).toBe('z')
    // 淘汰的是中间两位，它们仍带旧身份以便淡出时有内容
    expect([morph[2].bar.key, morph[3].bar.key]).toEqual(['c', 'd'])
  })

  it('输出始终按 slot 升序，避免 Vue 重排正在过渡的节点', () => {
    // 渲染顺序与 slot 不一致时，Vue 会按 key 搬动 DOM，正在跑过渡的柱子被整体
    // 移走，观感是一次硬跳。这条断言锁住排序契约。
    const previous = seats(bar('a', 10), bar('b', 20), bar('c', 30), bar('d', 40), bar('e', 50))
    const morph = buildMorphBars([bar('x', 10), bar('y', 20)], previous, 100, true, 3)

    const slots = morph.map(item => item.slot)
    expect(slots).toEqual([...slots].sort((a, b) => a - b))
    // 5 → 2 点 slot 3：留存 0 与 3
    expect(morph.filter(item => item.phase === 'stable').map(item => item.slot)).toEqual([0, 3])
  })

  it('锚点位上的柱子按堆叠规则继续形变，不走进场路径', () => {
    // 被点的单层柱下钻成多层柱时，底层应当复用而非归零重长 ——
    // 锚定的价值正在于此：连续性从被点那根延续下去。
    const previous = seats(bar('a', 100), bar('b', 80), bar('c', 60))
    const next = [bar('c-detail', 40, 20)]

    const morph = buildMorphBars(next, previous, 200, false, 2)
    const anchored = morph.find(item => item.slot === 2)!

    expect(anchored.phase).toBe('stable')
    // 底层复用：起始帧就是目标值，不经过 0
    expect(anchored.segments[0].ratio).toBeCloseTo(0.2)
    // 新增顶层：起始帧为 0，下一帧才膨胀
    expect(anchored.segments[1].ratio).toBe(0)
    expect(buildMorphBars(next, previous, 200, true, 2).find(item => item.slot === 2)!.segments[1].ratio)
      .toBeCloseTo(0.1)
  })

  it('留存柱一律满权重，退场柱一律零权重', () => {
    const previous = seats(bar('a', 10), bar('b', 20), bar('c', 30), bar('d', 40), bar('e', 50))
    const morph = buildMorphBars([bar('x', 10), bar('y', 20)], previous, 100, true, 4)

    // 权重是挤压观感的唯一来源：留存的等分空间，被淘汰的让出全部空间
    expect(morph.map(item => item.weight)).toEqual([1, 0, 0, 0, 1])
  })

  it('锚点不影响柱数增加的情形，仍是前缀复用加尾部进场', () => {
    const previous = seats(bar('a', 100), bar('b', 80))
    const next = [bar('x', 90), bar('y', 70), bar('z', 50)]

    const morph = buildMorphBars(next, previous, 200, true, 1)

    expect(morph.map(item => item.phase)).toEqual(['stable', 'stable', 'enter'])
  })
})

/**
 * 身份对齐 —— 窗口滑动与跨度变化时的方位正确性。
 *
 * <p>这组用例锁的是「进出场发生在哪一侧」。位置对齐把第 i 根新柱接到第 i 根旧柱上，
 * 于是窗口左移一天会被算成「全部原地改值」：没有任何进出场，每根柱的数值凭空跳变。
 * 而实际发生的事情是「整排右移一格、右端移出、左端补入」。
 *
 * <p>方位本身不需要单独实现 —— 柱子按 slot 升序渲染、宽度由 flex 权重分配，
 * 进场柱的 slot 比留存柱小它就从左侧挤入。故这里断言的是 slot 与 phase 的组合。
 */
describe('身份对齐：窗口滑动与跨度变化', () => {
  it('窗口左移一格：右端退场、左端进场，中间原地复用', () => {
    // 7/27~7/31 → 7/26~7/30。三根同名柱（7/27~7/29）必须复用同一节点。
    const previous = seats(bar('7/27', 10), bar('7/28', 20), bar('7/29', 30), bar('7/30', 40))
    const next = [bar('7/26', 5), bar('7/27', 10), bar('7/28', 20), bar('7/29', 30)]

    const morph = buildMorphBars(next, previous, 100)

    // 位移 δ = -1：新柱 i 落在 i-1，故 slot 从 -1 起。负 slot 无妨 ——
    // 它只是渲染 key 与排序依据。
    expect(morph.map(item => item.slot)).toEqual([-1, 0, 1, 2, 3])
    // 最左是新进场的 7/26，最右是退场的 7/30
    expect(morph.map(item => item.phase)).toEqual(['enter', 'stable', 'stable', 'stable', 'leave'])
    expect(morph[0].bar.key).toBe('7/26')
    expect(morph[4].bar.key).toBe('7/30')
    // 进场柱在最左、退场柱在最右 —— 这就是「从左侧补入、从右侧移出」
    expect(morph[0].slot).toBeLessThan(morph[1].slot)
    expect(morph[4].slot).toBeGreaterThan(morph[3].slot)
  })

  it('窗口右移一格：左端退场、右端进场', () => {
    const previous = seats(bar('7/26', 5), bar('7/27', 10), bar('7/28', 20), bar('7/29', 30))
    const next = [bar('7/27', 10), bar('7/28', 20), bar('7/29', 30), bar('7/30', 40)]

    const morph = buildMorphBars(next, previous, 100)

    // 位移 δ = +1
    expect(morph.map(item => item.slot)).toEqual([0, 1, 2, 3, 4])
    expect(morph.map(item => item.phase)).toEqual(['leave', 'stable', 'stable', 'stable', 'enter'])
    expect(morph[0].bar.key).toBe('7/26')
    expect(morph[4].bar.key).toBe('7/30')
  })

  it('左手柄向外拖：新柱出现在左侧而非右侧', () => {
    // 这是位置对齐最明显的错处 —— 新增的是更早的一天，而 assignSlots
    // 只会在右端追加位置，新柱于是从右侧挤进来，与事实相反。
    const previous = seats(bar('7/28', 20), bar('7/29', 30), bar('7/30', 40))
    const next = [bar('7/26', 5), bar('7/27', 10), bar('7/28', 20), bar('7/29', 30), bar('7/30', 40)]

    const morph = buildMorphBars(next, previous, 100)

    expect(morph.map(item => item.slot)).toEqual([-2, -1, 0, 1, 2])
    expect(morph.map(item => item.phase)).toEqual(['enter', 'enter', 'stable', 'stable', 'stable'])
    // 两根新柱都在留存柱左侧
    expect(morph.filter(item => item.phase === 'enter').map(item => item.bar.key))
      .toEqual(['7/26', '7/27'])
  })

  it('右手柄向外拖：新柱出现在右侧', () => {
    const previous = seats(bar('7/26', 5), bar('7/27', 10), bar('7/28', 20))
    const next = [bar('7/26', 5), bar('7/27', 10), bar('7/28', 20), bar('7/29', 30)]

    const morph = buildMorphBars(next, previous, 100)

    expect(morph.map(item => item.slot)).toEqual([0, 1, 2, 3])
    expect(morph.map(item => item.phase)).toEqual(['stable', 'stable', 'stable', 'enter'])
  })

  it('左手柄向内收：左端退场，右端不动', () => {
    const previous = seats(bar('7/26', 5), bar('7/27', 10), bar('7/28', 20), bar('7/29', 30))
    const next = [bar('7/28', 20), bar('7/29', 30)]

    const morph = buildMorphBars(next, previous, 100)

    // δ = +2：新柱 0 接旧 slot 2
    expect(morph.map(item => item.slot)).toEqual([0, 1, 2, 3])
    expect(morph.map(item => item.phase)).toEqual(['leave', 'leave', 'stable', 'stable'])
    expect([morph[0].bar.key, morph[1].bar.key]).toEqual(['7/26', '7/27'])
  })

  it('整批身份都变（下钻）时回退到位置对齐', () => {
    // 日期 → 供应商，身份毫无重合，此时位置是唯一可循的线索
    const previous = seats(bar('7/26', 5), bar('7/27', 10), bar('7/28', 20))
    const next = [bar('deepseek', 30), bar('zhipu', 20)]

    const morph = buildMorphBars(next, previous, 100)

    expect(morph.map(item => item.slot)).toEqual([0, 1, 2])
    expect(morph.map(item => item.phase)).toEqual(['stable', 'stable', 'leave'])
  })

  it('有锚点时不做身份对齐 —— 下钻的指向性优先', () => {
    // 极端情形：下钻后的某个供应商恰好与某个日期同名。身份对齐会把它接到那个
    // 日期上，而锚点表达的「新一屏从被点那根长出来」是更强的意图。
    const previous = seats(bar('a', 10), bar('b', 20), bar('c', 30))
    const next = [bar('a', 5)]

    const morph = buildMorphBars(next, previous, 100, true, 2)

    // 锚点 2 生效：新柱落在被点那根的位置，而非被身份对齐拉到 slot 0
    expect(morph.find(item => item.phase === 'stable')!.slot).toBe(2)
  })

  it('窗口整体跳到无重合的另一段时回退到位置对齐', () => {
    // 翻页一整页：两批日期完全不相交，没有位移量可循
    const previous = seats(bar('7/01', 5), bar('7/02', 10), bar('7/03', 20))
    const next = [bar('7/20', 30), bar('7/21', 40), bar('7/22', 50)]

    const morph = buildMorphBars(next, previous, 100)

    expect(morph.map(item => item.slot)).toEqual([0, 1, 2])
    expect(morph.map(item => item.phase)).toEqual(['stable', 'stable', 'stable'])
  })

  it('身份完全一致时无进出场，slot 不变', () => {
    // SSE 推送导致数值变化，但窗口没动 —— 不该有任何柱子进出
    const previous = seats(bar('7/26', 5), bar('7/27', 10))
    const next = [bar('7/26', 50), bar('7/27', 100)]

    const morph = buildMorphBars(next, previous, 200)

    expect(morph.map(item => item.slot)).toEqual([0, 1])
    expect(morph.map(item => item.phase)).toEqual(['stable', 'stable'])
  })
})

describe('alignSlotsByIdentity', () => {
  it('两批毫无重合时返回 null，交由调用方回退', () => {
    const previous = seats(bar('a', 1), bar('b', 2))
    expect(alignSlotsByIdentity(['x', 'y'], previous)).toBeNull()
  })

  it('任一批为空时返回 null', () => {
    expect(alignSlotsByIdentity([], seats(bar('a', 1)))).toBeNull()
    expect(alignSlotsByIdentity(['a'], [])).toBeNull()
  })

  it('取票数最多的位移量', () => {
    // 旧批 a,b,c,d 在 slot 0..3；新批 b,c,d,e 中三个身份指向 δ=+1
    const previous = seats(bar('a', 1), bar('b', 2), bar('c', 3), bar('d', 4))
    expect(alignSlotsByIdentity(['b', 'c', 'd', 'e'], previous)).toEqual([1, 2, 3, 4])
  })

  /**
   * 平票时取 |δ| 最小的。
   *
   * 刻度身份在一批内唯一，故正常情况下最高票是唯一的；平票只出现在重合极少的
   * 边缘情形，此时位移越小、留存柱的横向移动越少，更接近「几乎没变」这个事实。
   */
  it('平票时取位移绝对值更小的', () => {
    // 旧批只有 b 在 slot 1；新批 [b, x] 让 δ=+1 得一票，[x, b] 让 δ=0 得一票
    const previous: MorphSnapshot[] = [{ slot: 1, bar: bar('b', 2) }]
    expect(alignSlotsByIdentity(['x', 'b'], previous)).toEqual([0, 1])
  })

  /** 不连续占位（锚定复用的产物）同样适用 —— 位移是相对旧 slot 算的。 */
  it('旧批占位不连续时仍按旧 slot 算位移', () => {
    const previous: MorphSnapshot[] = [
      { slot: 0, bar: bar('a', 1) },
      { slot: 4, bar: bar('e', 5) },
    ]
    // 新批 [e, f]：e 在旧 slot 4，δ = 4 - 0 = 4
    expect(alignSlotsByIdentity(['e', 'f'], previous)).toEqual([4, 5])
  })
})
