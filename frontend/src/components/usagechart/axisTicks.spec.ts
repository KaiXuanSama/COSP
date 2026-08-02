import { describe, expect, it } from 'vitest'
import { buildAxisTicks, formatTickValue, niceCeiling } from './axisTicks'

/**
 * 纵轴刻度计算的验证与锁定。
 *
 * 重点不是"某个具体数字等于多少"，而是三条不变量：
 * 轴上限不小于数据最大值（柱子不会溢出绘图区）、刻度读数是整数（"调用次数"没有小数）、
 * 以及 0 与上限一定在刻度里（读图的两个锚点）。
 */
describe('纵轴刻度', () => {
  describe('niceCeiling', () => {
    it('无数据时返回 0', () => {
      expect(niceCeiling(0)).toBe(0)
      expect(niceCeiling(-5)).toBe(0)
    })

    it('小量程不放大，避免柱子被压得过矮', () => {
      // 若把 3 拉到 10，柱子只有 30% 高，视觉上会误以为"几乎没调用"
      expect(niceCeiling(3)).toBe(3)
      expect(niceCeiling(5)).toBe(5)
    })

    it('取整到同数量级的易读倍数，且不过度放大', () => {
      expect(niceCeiling(37)).toBe(40)
      expect(niceCeiling(73)).toBe(80)
      expect(niceCeiling(234)).toBe(250)
      expect(niceCeiling(1200)).toBe(1500)
    })

    it('上限不会显著超出数据最大值，避免柱子被压矮', () => {
      // 档位过疏时 297 会被抬到 500，柱子只占六成高、版面空旷。
      // 约定：上限最多超出最大值 50%，保证柱子至少占三分之二高度。
      for (const raw of [6, 37, 73, 234, 297, 555, 1200, 8888]) {
        expect(niceCeiling(raw)).toBeLessThanOrEqual(raw * 1.5)
      }
    })

    it('轴上限永不小于数据最大值', () => {
      // 这是硬约束：一旦上限小于最大值，最高柱会溢出绘图区
      for (const raw of [1, 7, 19, 42, 99, 100, 101, 555, 1234, 98765]) {
        expect(niceCeiling(raw)).toBeGreaterThanOrEqual(raw)
      }
    })
  })

  describe('buildAxisTicks', () => {
    it('无数据时返回空刻度，图上不画轴', () => {
      expect(buildAxisTicks(0)).toEqual([])
    })

    it('包含 0 与轴上限作为读图锚点', () => {
      const ticks = buildAxisTicks(234)

      expect(ticks[0].value).toBe(0)
      expect(ticks[0].ratio).toBe(0)
      expect(ticks[ticks.length - 1].value).toBe(250)
      expect(ticks[ticks.length - 1].ratio).toBe(1)
    })

    it('所有刻度读数都是整数', () => {
      // 档数会自动向下退让以保证整除，"2.5 次调用"是无意义的读数
      for (const raw of [3, 7, 19, 42, 99, 234, 1234]) {
        for (const tick of buildAxisTicks(raw)) {
          expect(Number.isInteger(tick.value)).toBe(true)
        }
      }
    })

    it('刻度按值与占比同步递增', () => {
      const ticks = buildAxisTicks(100)

      for (let i = 1; i < ticks.length; i += 1) {
        expect(ticks[i].value).toBeGreaterThan(ticks[i - 1].value)
        expect(ticks[i].ratio).toBeGreaterThan(ticks[i - 1].ratio)
      }
    })

    it('占比与数值成正比，网格线才能对齐柱高', () => {
      const ticks = buildAxisTicks(100)
      const ceiling = ticks[ticks.length - 1].value

      for (const tick of ticks) {
        expect(tick.ratio).toBeCloseTo(tick.value / ceiling, 10)
      }
    })
  })

  describe('formatTickValue', () => {
    it('千以下原样显示', () => {
      expect(formatTickValue(0)).toBe('0')
      expect(formatTickValue(250)).toBe('250')
    })

    it('上千折算为 k，避免长数字挤压绘图区', () => {
      expect(formatTickValue(1000)).toBe('1k')
      expect(formatTickValue(1500)).toBe('1.5k')
      expect(formatTickValue(20000)).toBe('20k')
    })
  })
})
