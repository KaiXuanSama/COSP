import { describe, expect, it } from 'vitest'
import { MIMO_EXAMPLE_RULESET } from './defaultRequestBody'
import { formatRuleSetJson, parseRuleSetJson } from './ruleSetJson'
import { createEmptyRuleSet } from './types'

describe('规则集 JSON 编辑', () => {
  it('格式化后的空规则集可以无损解析', () => {
    const ruleSet = createEmptyRuleSet()
    const result = parseRuleSetJson(formatRuleSetJson(ruleSet))

    expect(result).toEqual({ valid: true, rules: ruleSet })
  })

  it('可以解析包含嵌套对象操作的 MiMo 规则集', () => {
    const result = parseRuleSetJson(formatRuleSetJson(MIMO_EXAMPLE_RULESET))

    expect(result).toEqual({ valid: true, rules: MIMO_EXAMPLE_RULESET })
  })

  it('返回便于定位的 JSON 语法错误', () => {
    const result = parseRuleSetJson('{"version": 1,')

    expect(result.valid).toBe(false)
    if (!result.valid) expect(result.error).toContain('JSON 语法错误')
  })

  it('拒绝不受支持的协议版本', () => {
    const result = parseRuleSetJson('{"version":2,"rules":[]}')

    expect(result).toEqual({ valid: false, error: '规则集 version 必须为 1' })
  })

  it('拒绝缺少必要字段的规则', () => {
    const result = parseRuleSetJson(JSON.stringify({
      version: 1,
      rules: [{ id: 'incomplete' }],
    }))

    expect(result).toEqual({
      valid: false,
      error: 'rules[0].order 必须是非负整数',
    })
  })

  it('拒绝无效的嵌套操作类型', () => {
    const invalid = JSON.parse(formatRuleSetJson(MIMO_EXAMPLE_RULESET))
    invalid.rules[0].operations[0].rules[0].operations[0].type = 'replace'

    const result = parseRuleSetJson(JSON.stringify(invalid))

    expect(result.valid).toBe(false)
    if (!result.valid) {
      expect(result.error).toContain('operations[0].type')
    }
  })
})
