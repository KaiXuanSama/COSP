/**
 * 离散范围选择模型的验证与锁定。
 *
 * 重点全在<strong>约束的相互作用</strong>上：三种手势各有不同的「谁让位」规则，
 * 而这些规则的错误几乎都不会抛异常 —— 只会让拖动手感变得别扭（块自己变宽、
 * 拖到头跳一下、拖左端把右端拽走），而单看代码很难判断哪种才对。
 */
import { describe, expect, it } from 'vitest'
import {
  clampIndex,
  indexFromRatio,
  moveEnd,
  moveStart,
  normalizeSelection,
  resolveBounds,
  selectionEquals,
  shiftBy,
  slideTo,
  spanOf,
  tickRatio,
} from './rangeslider'

/** 15 个刻度、跨度限定 7~15 —— 概览窗口的实际配置。 */
const B = resolveBounds(15, 7, 15)

describe('clampIndex', () => {
  it('收敛到 [0, count-1]', () => {
    expect(clampIndex(-3, 15)).toBe(0)
    expect(clampIndex(99, 15)).toBe(14)
    expect(clampIndex(7, 15)).toBe(7)
  })

  it('四舍五入到整数下标', () => {
    expect(clampIndex(3.4, 15)).toBe(3)
    expect(clampIndex(3.6, 15)).toBe(4)
  })

  it('非有限值按 0 处理，不让 NaN 顺着算式扩散', () => {
    expect(clampIndex(Number.NaN, 15)).toBe(0)
    expect(clampIndex(Number.POSITIVE_INFINITY, 15)).toBe(0)
  })

  it('count 为 0 或 1 时只有下标 0', () => {
    expect(clampIndex(5, 1)).toBe(0)
    expect(clampIndex(5, 0)).toBe(0)
  })
})

describe('resolveBounds', () => {
  it('缺省时跨度不受限', () => {
    expect(resolveBounds(10)).toEqual({ count: 10, minSpan: 1, maxSpan: 10 })
  })

  /**
   * 上限先与下限比较、再与总数比较。
   *
   * 顺序颠倒会得到 maxSpan < minSpan，此后所有约束判断相互矛盾且不报错。
   */
  it('上限低于下限时被抬到下限', () => {
    expect(resolveBounds(20, 10, 3)).toEqual({ count: 20, minSpan: 10, maxSpan: 10 })
  })

  it('下限与上限都不超过刻度总数', () => {
    expect(resolveBounds(5, 9, 99)).toEqual({ count: 5, minSpan: 5, maxSpan: 5 })
  })

  it('总数至少为 1，跨度至少为 1', () => {
    expect(resolveBounds(0, 0, 0)).toEqual({ count: 1, minSpan: 1, maxSpan: 1 })
    expect(resolveBounds(-5)).toEqual({ count: 1, minSpan: 1, maxSpan: 1 })
  })
})

describe('spanOf', () => {
  /** 闭区间：含两端。若按差值算，「7 天窗口」会变成 8 天。 */
  it('是闭区间跨度', () => {
    expect(spanOf({ start: 2, end: 8 })).toBe(7)
    expect(spanOf({ start: 3, end: 3 })).toBe(1)
  })
})

describe('tickRatio / indexFromRatio', () => {
  /**
   * 分母是 count-1：比例描述点的位置，n 个点之间有 n-1 段间隔。
   *
   * 用 count 会让最后一个点落在 (n-1)/n 处，右端凭空空出一格 ——
   * 且偏差随刻度增多而变小，很容易被当成渲染误差。
   */
  it('首尾刻度恰在 0 与 1', () => {
    expect(tickRatio(0, 15)).toBe(0)
    expect(tickRatio(14, 15)).toBe(1)
  })

  it('中点刻度在 0.5', () => {
    expect(tickRatio(2, 5)).toBe(0.5)
  })

  it('只有一个刻度时无从分布', () => {
    expect(tickRatio(0, 1)).toBe(0)
    expect(indexFromRatio(0.7, 1)).toBe(0)
  })

  it('与 indexFromRatio 互逆', () => {
    for (let i = 0; i < 15; i += 1) {
      expect(indexFromRatio(tickRatio(i, 15), 15)).toBe(i)
    }
  })

  it('越界比例收敛到两端', () => {
    expect(indexFromRatio(-0.4, 15)).toBe(0)
    expect(indexFromRatio(1.8, 15)).toBe(14)
  })
})

describe('normalizeSelection', () => {
  it('合法选择原样返回', () => {
    expect(normalizeSelection({ start: 2, end: 8 }, B)).toEqual({ start: 2, end: 8 })
  })

  it('倒置的区间被交换而非丢弃', () => {
    expect(normalizeSelection({ start: 8, end: 2 }, B)).toEqual({ start: 2, end: 8 })
  })

  /** 以 start 为锚向右扩 —— 时间轴场景下对应「保住起点，调整终点」。 */
  it('跨度不足时向右扩到下限', () => {
    expect(normalizeSelection({ start: 3, end: 4 }, B)).toEqual({ start: 3, end: 9 })
  })

  /** 右侧到底后转为向左扩，否则会算出越界的 end。 */
  it('右侧空间不足时改为向左扩', () => {
    expect(normalizeSelection({ start: 13, end: 14 }, B)).toEqual({ start: 8, end: 14 })
  })

  it('跨度超限时从右侧收', () => {
    const tight = resolveBounds(15, 7, 10)
    expect(normalizeSelection({ start: 0, end: 14 }, tight)).toEqual({ start: 0, end: 9 })
  })

  /**
   * 刻度数量缩小后旧选择会越界 —— 这是最实际的一种失效场景
   * （窗口从 15 格切到 7 格），直接参与百分比计算会把范围块画到轨道外。
   */
  it('刻度变少后旧选择被收敛进新范围', () => {
    const small = resolveBounds(7, 7, 7)
    expect(normalizeSelection({ start: 8, end: 14 }, small)).toEqual({ start: 0, end: 6 })
  })
})

describe('moveStart', () => {
  /**
   * 右端不动，起点跟到目标。
   *
   * 起点必须留出足够空间：end=14、minSpan=7 时上界是 8，故 5 在合法区内。
   * 若拿一个已经贴着下限的区间来测（如 end=8 时 start 上界只有 2），
   * 看到的会是「顶住」而不是「跟随」—— 两种行为都对，但测的不是同一件事。
   */
  it('右端不动，起点跟到目标', () => {
    expect(moveStart({ start: 3, end: 14 }, 5, B)).toEqual({ start: 5, end: 14 })
  })

  /**
   * 顶住而非跳变：继续拖只是无效，不会把右端拽着走。
   *
   * 若在这里改动 end，用户拖左端时会看到右端一起动，
   * 那是「平移」的手感，与拖端点的语义冲突。
   */
  it('触及跨度下限时顶住', () => {
    // end=8、minSpan=7 ⇒ start 最大只能到 2
    expect(moveStart({ start: 2, end: 8 }, 7, B)).toEqual({ start: 2, end: 8 })
  })

  it('触及跨度上限时顶住', () => {
    const tight = resolveBounds(15, 7, 10)
    // end=12、maxSpan=10 ⇒ start 最小只能到 3
    expect(moveStart({ start: 3, end: 12 }, 0, tight)).toEqual({ start: 3, end: 12 })
  })

  it('不会越出左边界', () => {
    expect(moveStart({ start: 5, end: 14 }, -9, B).start).toBe(0)
  })
})

describe('moveEnd', () => {
  it('左端不动，终点跟到目标', () => {
    expect(moveEnd({ start: 2, end: 8 }, 11, B)).toEqual({ start: 2, end: 11 })
  })

  it('触及跨度下限时顶住', () => {
    // start=2、minSpan=7 ⇒ end 最小只能到 8
    expect(moveEnd({ start: 2, end: 8 }, 4, B)).toEqual({ start: 2, end: 8 })
  })

  it('触及跨度上限时顶住', () => {
    const tight = resolveBounds(15, 7, 10)
    // start=2、maxSpan=10 ⇒ end 最大只能到 11
    expect(moveEnd({ start: 2, end: 8 }, 14, tight)).toEqual({ start: 2, end: 11 })
  })

  it('不会越出右边界', () => {
    expect(moveEnd({ start: 0, end: 6 }, 99, B).end).toBe(14)
  })
})

describe('slideTo', () => {
  it('跨度不变，整块平移', () => {
    expect(slideTo({ start: 2, end: 8 }, 5, B)).toEqual({ start: 5, end: 11 })
  })

  /**
   * 撞到边界只停住、不压缩。
   *
   * 若在这里让区间收窄，用户会看到「拖到头之后块变短了」，
   * 松手再拖回来也不复原 —— 平移手势不该改变宽度。
   */
  it('撞到右边界时停住且跨度不变', () => {
    const result = slideTo({ start: 2, end: 8 }, 99, B)
    expect(result).toEqual({ start: 8, end: 14 })
    expect(spanOf(result)).toBe(7)
  })

  it('撞到左边界时同样保持跨度', () => {
    const result = slideTo({ start: 5, end: 11 }, -9, B)
    expect(result).toEqual({ start: 0, end: 6 })
    expect(spanOf(result)).toBe(7)
  })

  it('跨度铺满全轨时无处可移', () => {
    expect(slideTo({ start: 0, end: 14 }, 5, B)).toEqual({ start: 0, end: 14 })
  })
})

describe('shiftBy', () => {
  it('按步数平移，跨度不变', () => {
    expect(shiftBy({ start: 2, end: 8 }, 3, B)).toEqual({ start: 5, end: 11 })
    expect(shiftBy({ start: 5, end: 11 }, -2, B)).toEqual({ start: 3, end: 9 })
  })

  it('越界时与 slideTo 同样停在边界', () => {
    expect(shiftBy({ start: 8, end: 14 }, 5, B)).toEqual({ start: 8, end: 14 })
  })
})

describe('selectionEquals', () => {
  it('逐字段比较', () => {
    expect(selectionEquals({ start: 1, end: 2 }, { start: 1, end: 2 })).toBe(true)
    expect(selectionEquals({ start: 1, end: 2 }, { start: 1, end: 3 })).toBe(false)
  })
})

describe('不变量', () => {
  /**
   * 任何操作的结果都必须满足边界约束。
   *
   * 这一条比逐个用例更有价值：它挡住的是「某条分支忘了钳制」这类遗漏，
   * 而那种遗漏通常只在特定起点上才暴露。
   */
  it('三种操作在所有起点与目标下都产出合法区间', () => {
    for (let start = 0; start < 15; start += 1) {
      for (let target = -3; target <= 17; target += 1) {
        const base = normalizeSelection({ start, end: start + 6 }, B)
        for (const next of [
          moveStart(base, target, B),
          moveEnd(base, target, B),
          slideTo(base, target, B),
        ]) {
          expect(next.start).toBeGreaterThanOrEqual(0)
          expect(next.end).toBeLessThanOrEqual(14)
          expect(next.start).toBeLessThanOrEqual(next.end)
          expect(spanOf(next)).toBeGreaterThanOrEqual(B.minSpan)
          expect(spanOf(next)).toBeLessThanOrEqual(B.maxSpan)
        }
      }
    }
  })

  /** 平移永不改变跨度 —— 单独立一条，因为它是三种手势里唯一的宽度不变量。 */
  it('平移在任何目标下都保持跨度', () => {
    for (const span of [7, 10, 15]) {
      const base = normalizeSelection({ start: 0, end: span - 1 }, B)
      for (let target = -5; target <= 20; target += 1) {
        expect(spanOf(slideTo(base, target, B))).toBe(span)
      }
    }
  })
})
