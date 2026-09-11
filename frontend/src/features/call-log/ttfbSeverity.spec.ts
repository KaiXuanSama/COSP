import { describe, expect, it } from 'vitest'

import {
  TTFB_CRITICAL_MS,
  TTFB_SLOW_MS,
  TTFB_WARN_MS,
  ttfbSeverity,
} from './ttfbSeverity'

describe('ttfbSeverity', () => {
  /**
   * 三档边界逐个钉住。用 `>=` 而非 `>`：显示精度是 0.1s，恰好 20.0s 的行
   * 与刚被判为告警的行在界面上无从区分，写严格大于会让边界值看起来像漏判。
   */
  describe('阈值边界', () => {
    it('20s 整即进入偏慢档', () => {
      expect(ttfbSeverity(TTFB_WARN_MS - 1)).toBe('normal')
      expect(ttfbSeverity(TTFB_WARN_MS)).toBe('warn')
    })

    it('40s 整即进入缓慢档', () => {
      expect(ttfbSeverity(TTFB_SLOW_MS - 1)).toBe('warn')
      expect(ttfbSeverity(TTFB_SLOW_MS)).toBe('slow')
    })

    it('60s 整即进入严重档', () => {
      expect(ttfbSeverity(TTFB_CRITICAL_MS - 1)).toBe('slow')
      expect(ttfbSeverity(TTFB_CRITICAL_MS)).toBe('critical')
    })

    /** 阈值常量本身要与需求一致：20 / 40 / 60 秒。 */
    it('阈值是 20s / 40s / 60s', () => {
      expect([TTFB_WARN_MS, TTFB_SLOW_MS, TTFB_CRITICAL_MS]).toEqual([20_000, 40_000, 60_000])
    })
  })

  describe('正常区间', () => {
    it('毫秒级与秒级的快响应都不着色', () => {
      expect(ttfbSeverity(0)).toBe('normal')
      expect(ttfbSeverity(320)).toBe('normal')
      expect(ttfbSeverity(8_500)).toBe('normal')
      expect(ttfbSeverity(19_999)).toBe('normal')
    })
  })

  /**
   * `null` 是非流式调用或上游未测得的标记，界面显示为「—」。
   * 把破折号染成告警色会让人以为「这次很慢」，而事实是「无从得知」。
   */
  describe('无数据与异常值不上色', () => {
    it('null / undefined 归正常档', () => {
      expect(ttfbSeverity(null)).toBe('normal')
      expect(ttfbSeverity(undefined)).toBe('normal')
    })

    /** 负数在实现里是哨兵值，不能让它落到最严重档。 */
    it('负数归正常档', () => {
      expect(ttfbSeverity(-1)).toBe('normal')
      expect(ttfbSeverity(-60_000)).toBe('normal')
    })
  })

  it('超过一分钟后一直保持严重档', () => {
    expect(ttfbSeverity(60_001)).toBe('critical')
    expect(ttfbSeverity(5 * 60_000)).toBe('critical')
  })
})
