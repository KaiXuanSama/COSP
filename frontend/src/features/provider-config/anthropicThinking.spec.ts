import { describe, expect, it } from 'vitest'

import {
  ANTHROPIC_THINKING_OVERWRITE_MODES,
  ANTHROPIC_THINKING_TYPE_OPTIONS,
  DEFAULT_ANTHROPIC_THINKING_CONFIG,
  UNSET_THINKING_BUDGET_TOKENS,
  anthropicThinkingLockedByEffort,
  anthropicThinkingUsesBudget,
  nextAnthropicThinkingOverwriteMode,
  parseAnthropicThinkingConfig,
  serializeAnthropicThinkingBudget,
  serializeAnthropicThinkingMode,
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

  describe('解析持久化形态', () => {
    it('读取 thinking_mode 的形态与模式，预算来自另一列', () => {
      expect(
        parseAnthropicThinkingConfig(
          '{"thinking_type":"enabled","overwrite_mode":"override"}',
          4096,
        ),
      ).toEqual({ type: 'enabled', mode: 'override', budgetTokens: '4096' })
    })

    /**
     * 这一列自 V10 起就是 JSON，不存在历史裸值形态，所以认不出的原文只能是脏数据。
     * 回退默认而非让整条模型失效 —— 否则一行脏数据会让整个供应商编辑页打不开。
     */
    it('原文缺失或畸形时回退默认形态与模式', () => {
      for (const raw of [null, undefined, '', '  ', 'not json', '{"thinking_type":"weird"}']) {
        const parsed = parseAnthropicThinkingConfig(raw, UNSET_THINKING_BUDGET_TOKENS)
        expect(parsed.type).toBe('adaptive')
        expect(parsed.mode).toBe('fallback')
      }
    })

    /**
     * 哨兵、0、负数都表达「没有可用预算」，输入框显示 -1 会让用户以为那是个要理解的配置。
     */
    it('哨兵与非正数预算渲染成空输入框', () => {
      for (const raw of [UNSET_THINKING_BUDGET_TOKENS, 0, -8, 'abc', null, undefined]) {
        expect(parseAnthropicThinkingConfig('{}', raw).budgetTokens).toBe('')
      }
    })

    it('字符串形态的正数预算同样能读出来', () => {
      expect(parseAnthropicThinkingConfig('{}', ' 2048 ').budgetTokens).toBe('2048')
    })
  })

  describe('序列化成两个出站值', () => {
    it('thinking_mode 只含形态与模式，不含预算', () => {
      const json = serializeAnthropicThinkingMode({
        type: 'enabled', mode: 'override', budgetTokens: '4096',
      })
      expect(JSON.parse(json)).toEqual({ thinking_type: 'enabled', overwrite_mode: 'override' })
      expect(json).not.toContain('4096')
    })

    it('预算单独出站为整数', () => {
      expect(
        serializeAnthropicThinkingBudget({ type: 'enabled', mode: 'override', budgetTokens: '4096' }),
      ).toBe(4096)
    })

    /** 列约束只放行正数与 -1，所以空值与任何非正数都必须折成哨兵。 */
    it('空值与非正数预算折成哨兵', () => {
      for (const raw of ['', '   ', '0', '-3', 'abc']) {
        expect(
          serializeAnthropicThinkingBudget({ type: 'enabled', mode: 'override', budgetTokens: raw }),
        ).toBe(UNSET_THINKING_BUDGET_TOKENS)
      }
    })

    /**
     * 预算跟着形态走、没有自己的注入模式，所以 adaptive 形态下残留的输入内容也会照常出站；
     * 是否使用由后端按形态决定，前端不静默清空用户填过的数。
     */
    it('往返一次不改变形态与模式', () => {
      const original = { type: 'enabled', mode: 'passthrough', budgetTokens: '1024' } as const
      const roundTripped = parseAnthropicThinkingConfig(
        serializeAnthropicThinkingMode(original),
        serializeAnthropicThinkingBudget(original),
      )
      expect(roundTripped).toEqual(original)
    })
  })

  describe('模式轮转', () => {
    it('按声明顺序循环', () => {
      expect(nextAnthropicThinkingOverwriteMode('override')).toBe('fallback')
      expect(nextAnthropicThinkingOverwriteMode('fallback')).toBe('passthrough')
      expect(nextAnthropicThinkingOverwriteMode('passthrough')).toBe('override')
    })
  })
})
