import { describe, expect, it } from 'vitest'

import { cacheHitRate, formatCacheHitRate } from './cacheHitRate'

/** 构造一份 token 用量，只需要参与计算的两个字段。 */
function usage(prompt: number | null, cached: number | null) {
  return { prompt_tokens: prompt, cached_tokens: cached }
}

describe('cacheHitRate', () => {
  /**
   * 只有一套口径：后端已保证 `prompt_tokens` 含缓存，所以分母就是它。
   *
   * 这里曾经按 `downstream_protocol` 分两支，因为 Anthropic 直连落的是不含缓存的
   * `input_tokens`。那个分支已随后端口径统一而删除 —— 见模块注释。
   */
  describe('分母直接取 prompt_tokens', () => {
    it('按 cached / prompt 计算', () => {
      expect(cacheHitRate(usage(22583, 22528))).toBeCloseTo(0.99756, 5)
    })

    it('缓存等于输入时是满命中', () => {
      expect(cacheHitRate(usage(1000, 1000))).toBe(1)
    })

    /**
     * 真实的 Anthropic 样本：input_tokens 510、cache_read 25344。
     *
     * 解析层已把两者相加，落库值是 25854，所以这里拿到的就是归一化后的数字，
     * 占比 25344/25854 = 98.0%。若解析层漏了这一步，前端会看到 25344/510 = 4969.4%。
     */
    it('归一化后的 Anthropic 样本落在合理区间', () => {
      expect(cacheHitRate(usage(25854, 25344))).toBeCloseTo(0.98027, 5)
    })

    /**
     * 归一化后分子必然是分母的一个加项，因此占比不可能超过 100%。
     *
     * 超过 100% 就意味着解析层没把缓存加进 prompt —— 那是这条链上最容易复发的缺陷，
     * 当初正是它让界面显示出 40960%。
     */
    it('绝不会超过 100%', () => {
      const rate = cacheHitRate(usage(1000000, 999999))
      expect(rate).not.toBeNull()
      expect(rate!).toBeLessThanOrEqual(1)
    })
  })

  describe('null 与 0 的区分', () => {
    it('没有用量行 → null', () => {
      expect(cacheHitRate(null)).toBeNull()
    })

    it('缓存缺失（上游未提供）→ null，而非 0', () => {
      expect(cacheHitRate(usage(100, null))).toBeNull()
    })

    it('输入缺失 → null', () => {
      expect(cacheHitRate(usage(null, 50))).toBeNull()
    })

    it('缓存为 0 且输入有值 → 0，那是真实未命中', () => {
      expect(cacheHitRate(usage(100, 0))).toBe(0)
    })

    it('输入为 0 时分母无效 → null', () => {
      expect(cacheHitRate(usage(0, 0))).toBeNull()
    })
  })
})

describe('formatCacheHitRate', () => {
  it('保留一位小数并带百分号', () => {
    expect(formatCacheHitRate(usage(25854, 25344))).toBe('98.0%')
  })

  it('真实未命中显示 0.0% 而非破折号', () => {
    expect(formatCacheHitRate(usage(100, 0))).toBe('0.0%')
  })

  it('无从计算显示破折号', () => {
    expect(formatCacheHitRate(usage(100, null))).toBe('—')
    expect(formatCacheHitRate(null)).toBe('—')
  })
})
