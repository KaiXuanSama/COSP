import { describe, expect, it } from 'vitest'
import {
  buildMorphFrame,
  framesOfMorph,
  framesOfPoints,
  LINE_MORPH_EASING,
} from './useLineMorph'
import type { SeriesPoint } from './usageline'

/**
 * 折线端点形变模型的验证与锁定。
 *
 * 这套动效的核心主张是「线与点读同一批数值」，因此测试锁的是插值本身：
 * 任意进度下端点都落在起止之间，且新增 / 退场点从线端出发或收回。
 * 只要这两条成立，「点多→点少」「点少→点多」就无需各自适配 —— 它们本是同一种运算。
 */

/** 造一批端点，只关心坐标。 */
function points(...coords: Array<[number, number]>): SeriesPoint[] {
  return coords.map(([x, y]) => ({ x, y, value: y * 100 }))
}

/** 把一批端点铺成「依次占用 0..n-1、完全显影」的起始帧。 */
function frames(...coords: Array<[number, number]>): ReturnType<typeof framesOfPoints> {
  return framesOfPoints(points(...coords))
}

describe('按位置序号配对', () => {
  it('点数不变时全部原地移动，没有进出场', () => {
    const from = frames([0, 0.1], [0.5, 0.2], [1, 0.3])
    const to = points([0, 0.9], [0.5, 0.8], [1, 0.7])

    const morph = buildMorphFrame(from, to, 1)

    expect(morph.map((item) => item.phase)).toEqual(['stable', 'stable', 'stable'])
    expect(morph.map((item) => item.y)).toEqual([0.9, 0.8, 0.7])
  })

  it('中途进度落在起止之间 —— 线因此能读到中间位置', () => {
    const from = frames([0, 0])
    const to = points([0, 1])

    const morph = buildMorphFrame(from, to, 0.5)

    expect(morph[0].y).toBeCloseTo(0.5)
  })

  it('点数增多时公共前缀复用，多出的尾部进场', () => {
    const from = frames([0, 0.1], [1, 0.2])
    const to = points([0, 0.1], [0.5, 0.2], [1, 0.3])

    const morph = buildMorphFrame(from, to, 1)

    expect(morph.map((item) => item.phase)).toEqual(['stable', 'stable', 'enter'])
    expect(morph.map((item) => item.slot)).toEqual([0, 1, 2])
  })

  it('点数减少时多余的尾部退场', () => {
    const from = frames([0, 0.1], [0.5, 0.2], [1, 0.3])
    const to = points([0, 0.4], [1, 0.5])

    const morph = buildMorphFrame(from, to, 0.5)

    expect(morph.map((item) => item.phase)).toEqual(['stable', 'stable', 'leave'])
  })

  it('输出按位置升序，避免 Vue 重排正在过渡的节点', () => {
    const morph = buildMorphFrame(frames([0, 0.1], [0.5, 0.2], [1, 0.3]), points([0, 0.4]), 0.5)

    const slots = morph.map((item) => item.slot)
    expect(slots).toEqual([...slots].sort((a, b) => a - b))
  })

  it('读数取目标值，不随位置一起插值', () => {
    // 插值出来的中间读数没有意义，浮框要显示的是这一点真正的用量
    const morph = buildMorphFrame(frames([0, 0.1]), points([0, 0.9]), 0.3)

    expect(morph[0].value).toBe(90)
  })
})

describe('新增点从线端生长', () => {
  it('起始帧里新增点落在上一批的末点上', () => {
    const from = frames([0, 0.2], [0.5, 0.6])
    const to = points([0, 0.2], [0.5, 0.6], [1, 0.9])

    const morph = buildMorphFrame(from, to, 0)

    // 线因此是从原有尾端抽出来的，而不是在新位置凭空多出一段
    expect(morph[2].x).toBeCloseTo(0.5)
    expect(morph[2].y).toBeCloseTo(0.6)
    expect(morph[2].opacity).toBe(0)
  })

  it('进度走满后新增点到达目标并完全显影', () => {
    const morph = buildMorphFrame(frames([0, 0.2]), points([0, 0.2], [1, 0.9]), 1)

    expect(morph[1].x).toBe(1)
    expect(morph[1].y).toBe(0.9)
    expect(morph[1].opacity).toBe(1)
  })

  it('首次渲染没有上一批，新增点就地淡入', () => {
    // 无处可出发，位移会退化成一次凭空飞入
    const morph = buildMorphFrame([], points([0, 0.1], [1, 0.2]), 0)

    expect(morph.map((item) => item.phase)).toEqual(['enter', 'enter'])
    expect(morph[0].x).toBe(0)
    expect(morph[1].x).toBe(1)
  })

  it('多个新增点共用同一出发处', () => {
    const from = frames([0, 0.5])
    const to = points([0, 0.5], [0.5, 0.2], [1, 0.8])

    const morph = buildMorphFrame(from, to, 0)

    expect(morph[1].x).toBeCloseTo(0)
    expect(morph[2].x).toBeCloseTo(0)
  })
})

describe('退场点向线端收回', () => {
  it('终态落在新一批的末点上', () => {
    const from = frames([0, 0.1], [0.5, 0.4], [1, 0.9])
    const to = points([0, 0.1], [1, 0.5])

    const morph = buildMorphFrame(from, to, 1)

    const leaving = morph.find((item) => item.phase === 'leave')!
    // 线被拽短到消失，而不是当帧截断
    expect(leaving.x).toBeCloseTo(1)
    expect(leaving.y).toBeCloseTo(0.5)
    expect(leaving.opacity).toBe(0)
  })

  it('起始帧里退场点仍在原位且不透明', () => {
    const from = frames([0, 0.1], [1, 0.9])
    const morph = buildMorphFrame(from, points([0, 0.1]), 0)

    expect(morph[1].x).toBe(1)
    expect(morph[1].y).toBe(0.9)
    expect(morph[1].opacity).toBe(1)
  })

  it('多个退场点同时收拢到同一处', () => {
    const from = frames([0, 0.1], [0.3, 0.2], [0.6, 0.3], [1, 0.4])
    const morph = buildMorphFrame(from, points([0, 0.7]), 1)

    const leaving = morph.filter((item) => item.phase === 'leave')
    expect(leaving).toHaveLength(3)
    expect(leaving.every((item) => Math.abs(item.x - 0) < 1e-6)).toBe(true)
    expect(leaving.every((item) => Math.abs(item.y - 0.7) < 1e-6)).toBe(true)
  })

  it('新一批为空时退场点就地淡出', () => {
    // 无处可去，收拢会退化成一次飞向原点
    const morph = buildMorphFrame(frames([0.4, 0.6]), [], 1)

    expect(morph[0].x).toBe(0.4)
    expect(morph[0].opacity).toBe(0)
  })
})

describe('形变途中打断', () => {
  it('从当前帧续上，起点不是上一个目标', () => {
    // 中途切换时若从上一个目标起算，点会先跳回还没到达的位置再重新出发
    const half = buildMorphFrame(frames([0, 0]), points([0, 1]), 0.5)
    const resumed = buildMorphFrame(framesOfMorph(half), points([0, 0]), 0)

    expect(resumed[0].y).toBeCloseTo(0.5)
  })

  it('接手一个正在淡出位置的新点从当前不透明度续上', () => {
    const fading = buildMorphFrame(frames([0, 0.1], [1, 0.9]), points([0, 0.1]), 0.5)
    const resumed = buildMorphFrame(
      framesOfMorph(fading),
      points([0, 0.1], [1, 0.9]),
      0,
    )

    // 半透明处接手，不会突然实体化
    expect(resumed[1].opacity).toBeCloseTo(0.5)
  })
})

describe('进度钳制', () => {
  it('越界进度按端点处理', () => {
    const from = frames([0, 0])
    const to = points([0, 1])

    expect(buildMorphFrame(from, to, -1)[0].y).toBe(0)
    expect(buildMorphFrame(from, to, 2)[0].y).toBe(1)
  })
})

describe('缓动曲线', () => {
  it('单调且首末对齐，与柱状图形变同一条曲线', () => {
    expect(LINE_MORPH_EASING(0)).toBe(0)
    expect(LINE_MORPH_EASING(1)).toBe(1)

    let previous = 0
    for (let i = 1; i <= 20; i += 1) {
      const current = LINE_MORPH_EASING(i / 20)
      expect(current).toBeGreaterThanOrEqual(previous)
      previous = current
    }
  })
})

describe('帧快照', () => {
  it('按顺序占用 0 起的位置且完全显影', () => {
    const snapshot = framesOfPoints(points([0, 0.1], [0.5, 0.2], [1, 0.3]))

    expect(snapshot.map((item) => item.slot)).toEqual([0, 1, 2])
    expect(snapshot.every((item) => item.opacity === 1)).toBe(true)
  })

  it('空数据产出空快照', () => {
    expect(framesOfPoints([])).toEqual([])
    expect(framesOfMorph([])).toEqual([])
  })
})
