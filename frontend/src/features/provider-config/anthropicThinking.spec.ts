import { describe, expect, it } from 'vitest'

import {
  ANTHROPIC_THINKING_OVERWRITE_MODES,
  ANTHROPIC_THINKING_TYPE_OPTIONS,
  DEFAULT_ANTHROPIC_THINKING_CONFIG,
  anthropicThinkingLockedByEffort,
  anthropicThinkingUsesBudget,
} from './anthropicThinking'

describe('Anthropic thinking UI model', () => {
  /**
   * 没有 disabled：关闭思考统一由思考深度的 Off 档表达。
   * 两处都能关思考且各自带注入模式时，会产出自相矛盾的请求体。
   */
  it('只提供 adaptive 与 enabled，不重复提供关闭思考', () => {
    expect(ANTHROPIC_THINKING_TYPE_OPTIONS).toEqual(['adaptive', 'enabled'])
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
  })

  describe('思考深度 Off 时锁定本组', () => {
    it('覆写与兜底档下的 Off 会锁定', () => {
      expect(anthropicThinkingLockedByEffort('Off', 'override')).toBe(true)
      expect(anthropicThinkingLockedByEffort('Off', 'fallback')).toBe(true)
    })

    /**
     * 透传与删除不使用此处配置的档位，那时 Off 只是备选值、不会出站，
     * 据此锁定等于把一个未生效的配置当成了事实。
     */
    it('透传与删除档下不锁定', () => {
      expect(anthropicThinkingLockedByEffort('Off', 'passthrough')).toBe(false)
      expect(anthropicThinkingLockedByEffort('Off', 'delete')).toBe(false)
    })

    it('其余档位不锁定，档位大小写不敏感', () => {
      expect(anthropicThinkingLockedByEffort('Medium', 'override')).toBe(false)
      expect(anthropicThinkingLockedByEffort('off', 'override')).toBe(true)
      expect(anthropicThinkingLockedByEffort(' OFF ', 'override')).toBe(true)
    })
  })
})
