import { describe, expect, it } from 'vitest'
import {
  buildMorphPoints,
  ENTER_X_OFFSET,
  hasEnteringPoint,
  LINE_MORPH_DURATION,
  snapshotOfPoints,
} from './useLineMorph'
import type { SeriesPoint } from './usageline'

/**
 * 折线端点形变模型的验证与锁定。
 *
 * 这套动效的全部意义在于「点数变化时仍有连续的起点」，因此测试锁的是配对规则本身：
 * 按位置序号从左对齐、多出的尾部进出场。只要这条不变，
 * 「点多→点少」「点少→点多」就无需各自适配 —— 它们本就是同一种运算。
 */

/** 造一批端点，只关心坐标。 */
function points(...coords: Array<[number, number]>): SeriesPoint[] {
  return coords.map(([x, y]) => ({ x, y, value: y * 100 }))
}

/** 把一批端点铺成「依次占用 0..n-1」的基准快照。 */
function seats(...coords: Array<[number, number]>): ReturnType<typeof snapshotOfPoints> {
  return snapshotOfPoints(points(...coords))
}

describe('按位置序号配对', () => {
  it('点数不变时全部原地复用，没有进出场', () => {
    const previous = seats([0, 0.1], [0.5, 0.2], [1, 0.3])
    const next = points([0, 0.9], [0.5, 0.8], [1, 0.7])

    const morph = buildMorphPoints(next, previous)

    expect(morph.map((item) => item.phase)).toEqual(['stable', 'stable', 'stable'])
    // 复用点直接落在目标坐标：它们的起点是自己上一刻的位置，本就连续
    expect(morph.map((item) => item.y)).toEqual([0.9, 0.8, 0.7])
  })

  it('点数增多时公共前缀复用，多出的尾部进场', () => {
    const previous = seats([0, 0.1], [1, 0.2])
    const next = points([0, 0.1], [0.5, 0.2], [1, 0.3])

    const morph = buildMorphPoints(next, previous)

    expect(morph.map((item) => item.phase)).toEqual(['stable', 'stable', 'enter'])
    expect(morph.map((item) => item.slot)).toEqual([0, 1, 2])
  })

  it('点数减少时多余的尾部退场且保留旧坐标', () => {
    const previous = seats([0, 0.1], [0.5, 0.2], [1, 0.3])
    const next = points([0, 0.4], [1, 0.5])

    const morph = buildMorphPoints(next, previous)

    expect(morph.map((item) => item.phase)).toEqual(['stable', 'stable', 'leave'])
    // 退场点沿用旧的纵坐标，故淡出时不会先跳到别处
    expect(morph[2].y).toBe(0.3)
  })

  it('首次渲染没有上一批，全部视为进场', () => {
    const morph = buildMorphPoints(points([0, 0.1], [1, 0.2]), [])

    expect(morph.map((item) => item.phase)).toEqual(['enter', 'enter'])
  })

  it('输出按位置升序，避免 Vue 重排正在过渡的节点', () => {
    const previous = seats([0, 0.1], [0.5, 0.2], [1, 0.3])
    const morph = buildMorphPoints(points([0, 0.4]), previous)

    const slots = morph.map((item) => item.slot)
    expect(slots).toEqual([...slots].sort((a, b) => a - b))
  })
})

describe('进场点的起始态', () => {
  it('未落位时停在目标位置右侧且透明', () => {
    const previous = seats([0, 0.1])
    const next = points([0, 0.1], [0.5, 0.5])

    const morph = buildMorphPoints(next, previous, false)

    // 起始帧给 CSS 过渡一个起点，下一帧才滑到目标
    expect(morph[1].x).toBeCloseTo(0.5 + ENTER_X_OFFSET)
    expect(morph[1].opacity).toBe(0)
  })

  it('落位后滑到目标位置并显影', () => {
    const previous = seats([0, 0.1])
    const next = points([0, 0.1], [0.5, 0.5])

    const morph = buildMorphPoints(next, previous, true)

    expect(morph[1].x).toBe(0.5)
    expect(morph[1].opacity).toBe(1)
  })

  it('起始位置钳在右边缘内，不会滑出绘图区太远', () => {
    const morph = buildMorphPoints(points([1, 0.5]), [], false)

    expect(morph[0].x).toBeLessThanOrEqual(1)
  })

  it('留存点不受落位状态影响，起点就是自己上一刻的位置', () => {
    const previous = seats([0, 0.1], [1, 0.2])
    const next = points([0, 0.9], [1, 0.8], [0.5, 0.5])

    const morph = buildMorphPoints(next, previous, false)

    expect(morph[0].x).toBe(0)
    expect(morph[0].opacity).toBe(1)
    expect(morph[1].opacity).toBe(1)
  })
})

describe('退场点的终态', () => {
  it('向右滑出并淡出', () => {
    const previous = seats([0, 0.1], [1, 0.3])
    const morph = buildMorphPoints(points([0, 0.1]), previous)

    expect(morph[1].phase).toBe('leave')
    expect(morph[1].opacity).toBe(0)
    // 位移与淡出同时发生，观感是「被推出图外」
    expect(morph[1].x).toBeGreaterThan(1 - ENTER_X_OFFSET)
  })

  it('多个退场点同时归零', () => {
    const previous = seats([0, 0.1], [0.3, 0.2], [0.6, 0.3], [1, 0.4])
    const morph = buildMorphPoints(points([0, 0.1]), previous)

    const leaving = morph.filter((item) => item.phase === 'leave')
    expect(leaving).toHaveLength(3)
    expect(leaving.every((item) => item.opacity === 0)).toBe(true)
  })
})

describe('两帧提交的判定', () => {
  it('点数增多时需要起始态', () => {
    // 全新节点若起止值同帧写入会被合并，表现为「一出现就在终点」
    expect(hasEnteringPoint(points([0, 0], [1, 1]), seats([0, 0]))).toBe(true)
  })

  it('点数不变或减少时无需起始态', () => {
    // 留存点在 DOM 里已有前一刻的位置，直接改值即可由 CSS 过渡接手
    expect(hasEnteringPoint(points([0, 0]), seats([0, 0]))).toBe(false)
    expect(hasEnteringPoint(points([0, 0]), seats([0, 0], [1, 1]))).toBe(false)
  })
})

describe('占位快照', () => {
  it('按顺序占用 0 起的位置', () => {
    const snapshot = snapshotOfPoints(points([0, 0.1], [0.5, 0.2], [1, 0.3]))

    expect(snapshot.map((item) => item.slot)).toEqual([0, 1, 2])
  })

  it('空数据产出空快照', () => {
    expect(snapshotOfPoints([])).toEqual([])
  })
})

describe('退场点的存活时长', () => {
  it('清理延迟与 CSS 过渡同值', () => {
    // 退场点由「旧批比新批多出来的尾部」派生，因此它在 DOM 里能活多久，
    // 完全取决于配对基准何时推进。若这个时长短于 CSS 过渡，元素会在淡出刚起步时
    // 被摘掉 —— 表现就是点数硬切。两者必须同值。
    expect(LINE_MORPH_DURATION).toBe(420)
  })
})
