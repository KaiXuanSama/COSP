import { describe, expect, it } from 'vitest'
import { MIMO_EXAMPLE_RULESET } from './defaultRequestBody'
import { migrateRuleSet } from './migration'
import { formatRuleSetJson, parseRuleSetJson } from './ruleSetJson'
import { createEmptyRuleSetV2 } from './types'

describe('规则集 JSON 编辑', () => {
  it('格式化后的空规则集可以无损解析', () => {
    const ruleSet = createEmptyRuleSetV2()
    const result = parseRuleSetJson(formatRuleSetJson(ruleSet))

    expect(result).toEqual({ valid: true, rules: ruleSet })
  })

  it('可以解析包含嵌套对象操作的 MiMo 规则集', () => {
    const ruleSet = migrateRuleSet(MIMO_EXAMPLE_RULESET)
    const result = parseRuleSetJson(formatRuleSetJson(ruleSet))

    expect(result).toEqual({ valid: true, rules: ruleSet })
  })

  it('返回便于定位的 JSON 语法错误', () => {
    const result = parseRuleSetJson('{"version": 2,')

    expect(result.valid).toBe(false)
    if (!result.valid) expect(result.error).toContain('JSON 语法错误')
  })

  it('拒绝 V1 规则集并提示需要装进 groups', () => {
    const result = parseRuleSetJson('{"version":1,"rules":[]}')

    expect(result.valid).toBe(false)
    if (!result.valid) expect(result.error).toContain('groups')
  })

  it('拒绝不受支持的协议版本', () => {
    const result = parseRuleSetJson('{"version":3,"groups":[]}')

    expect(result).toEqual({ valid: false, error: '规则集 version 必须为 2' })
  })

  it('拒绝未知的线路协议', () => {
    const ruleSet = migrateRuleSet(MIMO_EXAMPLE_RULESET)
    ruleSet.groups[0]!.protocols = ['GEMINI' as never]

    const result = parseRuleSetJson(formatRuleSetJson(ruleSet))

    expect(result.valid).toBe(false)
    if (!result.valid) expect(result.error).toContain('groups[0].protocols[0]')
  })

  it('拒绝重复的规则组 ID', () => {
    const ruleSet = migrateRuleSet(MIMO_EXAMPLE_RULESET)
    ruleSet.groups.push({ ...ruleSet.groups[0]!, order: 1 })

    const result = parseRuleSetJson(formatRuleSetJson(ruleSet))

    expect(result.valid).toBe(false)
    if (!result.valid) expect(result.error).toContain('id 重复')
  })

  it('拒绝缺少必要字段的规则', () => {
    const ruleSet = migrateRuleSet(MIMO_EXAMPLE_RULESET)
    ruleSet.groups[0]!.rules = [{ id: 'incomplete' } as never]

    const result = parseRuleSetJson(formatRuleSetJson(ruleSet))

    expect(result).toEqual({
      valid: false,
      error: 'groups[0].rules[0].order 必须是非负整数',
    })
  })

  it('拒绝无效的嵌套操作类型', () => {
    const invalid = JSON.parse(formatRuleSetJson(migrateRuleSet(MIMO_EXAMPLE_RULESET)))
    invalid.groups[0].rules[0].operations[0].rules[0].operations[0].type = 'replace'

    const result = parseRuleSetJson(JSON.stringify(invalid))

    expect(result.valid).toBe(false)
    if (!result.valid) {
      expect(result.error).toContain('operations[0].type')
    }
  })
})
