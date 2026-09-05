import { describe, expect, it } from 'vitest'

import { cacheHitRate, formatCacheHitRate } from './cacheHitRate'

/** 构造一份 token 用量，只需要参与计算的两个字段。 */
function usage(prompt: number | null, cached: number | null) {
  return { prompt_tokens: prompt, cached_tokens: cached }
}

describe('cacheHitRate', () => {
  describe('下游 OPENAI：prompt_tokens 已含缓存，直接相除', () => {
    it('按 cached / prompt 计算', () => {
      expect(cacheHitRate(usage(22583, 22528), 'OPENAI')).toBeCloseTo(0.99756, 5)
    })

    it('缓存等于输入时是满命中', () => {
      expect(cacheHitRate(usage(1000, 1000), 'OPENAI')).toBe(1)
    })
  })

  describe('下游 ANTHROPIC：input_tokens 不含缓存，分母需加回', () => {
    it('分母取 prompt + cached', () => {
      // 真实样本：input_tokens 510、cache_read 25344。
      // 不加回时是 25344/510 = 4969.4%，加回后 25344/25854 = 98.0%。
      expect(cacheHitRate(usage(510, 25344), 'ANTHROPIC')).toBeCloseTo(0.98027, 5)
    })

    it('绝不会超过 100% —— 分子是分母的一个加项', () => {
      const rate = cacheHitRate(usage(1, 999999), 'ANTHROPIC')
      expect(rate).not.toBeNull()
      expect(rate!).toBeLessThan(1)
    })

    it('prompt 为 0 但缓存有值时分母仍有效：全部输入来自缓存', () => {
      expect(cacheHitRate(usage(0, 800), 'ANTHROPIC')).toBe(1)
    })
  })

  describe('同一份数字在两种协议下解读不同', () => {
    it('OPENAI 与 ANTHROPIC 的结果必须不同，否则分支没有生效', () => {
      const row = usage(510, 25344)
      expect(cacheHitRate(row, 'OPENAI')).not.toBe(cacheHitRate(row, 'ANTHROPIC'))
    })
  })

  describe('null 与 0 的区分', () => {
    it('没有用量行 → null', () => {
      expect(cacheHitRate(null, 'OPENAI')).toBeNull()
    })

    it('缓存缺失（上游未提供）→ null，而非 0', () => {
      expect(cacheHitRate(usage(100, null), 'OPENAI')).toBeNull()
      expect(cacheHitRate(usage(100, null), 'ANTHROPIC')).toBeNull()
    })

    it('输入缺失 → null', () => {
      expect(cacheHitRate(usage(null, 50), 'OPENAI')).toBeNull()
      expect(cacheHitRate(usage(null, 50), 'ANTHROPIC')).toBeNull()
    })

    it('缓存为 0 且输入有值 → 0，那是真实未命中', () => {
      expect(cacheHitRate(usage(100, 0), 'OPENAI')).toBe(0)
      expect(cacheHitRate(usage(100, 0), 'ANTHROPIC')).toBe(0)
    })

    it('两者都是 0 时分母为 0 → null', () => {
      expect(cacheHitRate(usage(0, 0), 'OPENAI')).toBeNull()
      expect(cacheHitRate(usage(0, 0), 'ANTHROPIC')).toBeNull()
    })
  })
})

describe('formatCacheHitRate', () => {
  it('保留一位小数并带百分号', () => {
    expect(formatCacheHitRate(usage(510, 25344), 'ANTHROPIC')).toBe('98.0%')
  })

  it('真实未命中显示 0.0% 而非破折号', () => {
    expect(formatCacheHitRate(usage(100, 0), 'OPENAI')).toBe('0.0%')
  })

  it('无从计算显示破折号', () => {
    expect(formatCacheHitRate(usage(100, null), 'OPENAI')).toBe('—')
    expect(formatCacheHitRate(null, 'OPENAI')).toBe('—')
  })
})
