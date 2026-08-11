import { describe, expect, it } from 'vitest'
import { toProviderKey } from './providerKey'

/**
 * 这些期望值不是推导出来的，而是**实际运行后端表达式**取得的：
 *
 *   n.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")
 *
 * 若后端 `ProviderAdminService#toProviderKey` 有任何改动，本文件应当先失败。
 */
describe('toProviderKey', () => {
  it('小写化并保留纯字母数字名称', () => {
    expect(toProviderKey('MiMo')).toBe('mimo')
    expect(toProviderKey('a')).toBe('a')
  })

  it('把括号与空格折叠成单个连字符', () => {
    expect(toProviderKey('Kimi (CodePlan)')).toBe('kimi-codeplan')
    expect(toProviderKey('Mimo (TokenPlan)')).toBe('mimo-tokenplan')
  })

  it('连续分隔符折叠为一个，并去掉首尾连字符', () => {
    expect(toProviderKey('  Spaced  Name  ')).toBe('spaced-name')
    expect(toProviderKey('Agent__Router')).toBe('agent-router')
    expect(toProviderKey('Zhipu-AI_v2')).toBe('zhipu-ai-v2')
  })

  it('全为分隔符时得到空串', () => {
    expect(toProviderKey('---')).toBe('')
  })

  // 非 ASCII 在 toLowerCase 之后不属于 [a-z0-9]，会被整段折叠。
  // 纯中文名因此退化为空串 —— 调用方必须处理，否则会拿空 key 去请求。
  it('非 ASCII 字符被折叠，纯中文名退化为空串', () => {
    expect(toProviderKey('已有中文')).toBe('')
    expect(toProviderKey('MiMo自定义')).toBe('mimo')
  })
})
