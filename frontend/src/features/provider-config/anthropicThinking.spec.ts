import { describe, expect, it } from 'vitest'

import {
  ANTHROPIC_THINKING_OVERWRITE_MODES,
  ANTHROPIC_THINKING_TYPE_OPTIONS,
  DEFAULT_ANTHROPIC_THINKING_CONFIG,
  anthropicThinkingUsesBudget,
} from './anthropicThinking'

describe('Anthropic thinking UI model', () => {
  it('支持三个协议 type', () => {
    expect(ANTHROPIC_THINKING_TYPE_OPTIONS).toEqual(['adaptive', 'enabled', 'disabled'])
  })

  /**
   * 这里刻意没有 delete：本轮 UI 只覆盖直连场景里最常用的三档干预程度，
   * 强制剥离仍可通过仅适用 ANTHROPIC 的请求体规则表达，不能假装尚未有后端契约的
   * delete 模式已经完整可用。
   */
  it('只提供透传、兜底、覆写三种模式', () => {
    expect(ANTHROPIC_THINKING_OVERWRITE_MODES).toEqual(['override', 'fallback', 'passthrough'])
  })

  it('默认是 adaptive + 兜底，预算留空', () => {
    expect(DEFAULT_ANTHROPIC_THINKING_CONFIG).toEqual({
      type: 'adaptive', mode: 'fallback', budgetTokens: '',
    })
  })

  /**
   * enabled + budget_tokens 是唯一手动预算形态。
   * adaptive 把预算交给上游，disabled 明确关思考；给它们开放输入框会产生无意义配置。
   */
  it('只有 enabled 允许编辑预算', () => {
    expect(anthropicThinkingUsesBudget('enabled')).toBe(true)
    expect(anthropicThinkingUsesBudget('adaptive')).toBe(false)
    expect(anthropicThinkingUsesBudget('disabled')).toBe(false)
  })
})
