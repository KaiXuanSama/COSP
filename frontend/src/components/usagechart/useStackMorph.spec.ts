import { describe, expect, it } from 'vitest'
import { buildMorphBars, ENTER_HEIGHT_RATIO, MORPH_DURATION } from './useStackMorph'
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

describe('柱体维度：左侧复用、右侧进出', () => {
  it('柱子变多时，公共前缀原地复用，多出的尾部柱进场', () => {
    const previous = [bar('a', 100), bar('b', 80), bar('c', 60)]
    const next = [bar('x', 90), bar('y', 70), bar('z', 50), bar('w', 30), bar('v', 10)]

    const morph = buildMorphBars(next, previous, 200)

    // 位置序号即渲染 key，前三根沿用同一批 DOM 节点
    expect(morph.map(item => item.slot)).toEqual([0, 1, 2, 3, 4])
    expect(morph.slice(0, 3).map(item => item.phase)).toEqual(['stable', 'stable', 'stable'])
    expect(morph.slice(3).map(item => item.phase)).toEqual(['enter', 'enter'])
  })

  it('柱子变少时，多余的尾部柱退场且保留旧数据以便淡出', () => {
    const previous = [bar('a', 100), bar('b', 80), bar('c', 60), bar('d', 40)]
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
    const previous = [bar('a', 100), bar('b', 80)]
    const next = [bar('x', 20), bar('y', 160)]

    const morph = buildMorphBars(next, previous, 200)

    expect(morph.map(item => item.phase)).toEqual(['stable', 'stable'])
  })

  it('退场柱宽度权重收到 0，让出的空间被留存柱吃掉', () => {
    // 「向右滑出」不是位移动画，而是 flex-grow 归零后留存柱扩张的副作用。
    // 一旦这里回到非 0，挤压观感就会退化成硬切。
    const previous = [bar('a', 100), bar('b', 80), bar('c', 60), bar('d', 40), bar('e', 20)]
    const next = [bar('x', 90), bar('y', 70), bar('z', 50)]

    const morph = buildMorphBars(next, previous, 200)

    // 1:1:1:0:0 —— 留存三根满权重，多余两根归零
    expect(morph.map(item => item.weight)).toEqual([1, 1, 1, 0, 0])
  })

  it('新增柱起始帧宽度为 0，落位后才扩张到等分', () => {
    const previous = [bar('a', 100), bar('b', 80), bar('c', 60)]
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
    const previous = [bar('a', 100)]
    const next = [bar('x', 100), bar('y', 20)]

    const morph = buildMorphBars(next, previous, 200, false)

    // 新柱先渲染一帧起始态，给 CSS transition 一个起点
    expect(morph[1].segments).toHaveLength(1)
    expect(morph[1].segments[0].ratio).toBe(ENTER_HEIGHT_RATIO)
    // 复用柱不受影响：它的起点是自己上一刻的高度，本就连续
    expect(morph[0].segments[0].ratio).toBeCloseTo(0.5)
  })

  it('落位后新增柱调整到目标高度', () => {
    const previous = [bar('a', 100)]
    const next = [bar('x', 100), bar('y', 20)]

    const morph = buildMorphBars(next, previous, 200, true)

    expect(morph[1].segments[0].ratio).toBeCloseTo(0.1)
  })
})
describe('起始态：凭空出现的层从 0 膨胀', () => {
  it('新增顶层在起始帧高度为 0，落位后才到目标值', () => {
    const previous = [bar('p', 60)]
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
    const start = buildMorphBars([bar('p', 80, 20)], [bar('p', 50)], 100, false)

    expect(start[0].segments[0].ratio).toBe(0.8)
  })
})
describe('堆叠维度：底部复用、顶部进出', () => {
  it('层数变少时，多余顶层收缩为 0 并标记退场', () => {
    // 5 层 → 3 层：底部三层复用，顶部两层归零
    const previous = [bar('a', 50, 40, 30, 20, 10)]
    const next = [bar('x', 60, 50, 40)]

    const segments = buildMorphBars(next, previous, 200)[0].segments

    expect(segments.map(item => item.slot)).toEqual([0, 1, 2, 3, 4])
    expect(segments.slice(0, 3).map(item => item.leaving)).toEqual([false, false, false])
    expect(segments.slice(3).map(item => item.leaving)).toEqual([true, true])
    expect(segments.slice(3).every(item => item.ratio === 0)).toBe(true)
  })

  it('层数变多时，底部复用、新增顶层直接给出目标高度从 0 膨胀', () => {
    // 3 层 → 5 层：新增的第 4、5 层在 DOM 里是新节点，从 0 长到目标值
    const previous = [bar('a', 50, 40, 30)]
    const next = [bar('x', 60, 50, 40, 30, 20)]

    const segments = buildMorphBars(next, previous, 200)[0].segments

    expect(segments).toHaveLength(5)
    expect(segments.every(item => item.leaving === false)).toBe(true)
    expect(segments[4].ratio).toBeCloseTo(0.1)
  })

  it('单层与多层互转走同一条路径，无需专门适配', () => {
    // 单一柱 → 堆叠柱：单一柱只是「只有一层的堆叠柱」
    const toStack = buildMorphBars([bar('x', 60, 50)], [bar('a', 100)], 200)[0].segments
    expect(toStack).toHaveLength(2)
    expect(toStack.every(item => item.leaving === false)).toBe(true)

    // 堆叠柱 → 单一柱：顶层退场，底层复用
    const toSingle = buildMorphBars([bar('x', 100)], [bar('a', 60, 50)], 200)[0].segments
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

describe('退场柱的存活时长', () => {
  it('清理延迟与 CSS 进出场动画同值', () => {
    // 退场柱由「previous 比 bars 多出来的尾部」派生，因此它在 DOM 里能活多久，
    // 完全取决于配对基准何时推进。若这个时长短于 CSS 动画，元素会在淡出刚起步时
    // 被摘掉 —— 表现就是柱子数量硬切。两者必须同值，这条断言即为该契约的锁。
    expect(MORPH_DURATION).toBe(420)
  })
})
