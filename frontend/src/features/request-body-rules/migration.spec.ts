import { describe, expect, it } from 'vitest'
import { MIMO_EXAMPLE_RULESET } from './defaultRequestBody'
import { LEGACY_GROUP_NAME, migrateRuleSet } from './migration'
import { createEmptyRule } from './types'

describe('规则集 V1 → V2 迁移', () => {
  it('把 V1 的扁平规则装进单个仅适用 OpenAI 的规则组', () => {
    const result = migrateRuleSet(MIMO_EXAMPLE_RULESET)

    expect(result.version).toBe(2)
    expect(result.groups).toHaveLength(1)
    expect(result.groups[0]!.name).toBe(LEGACY_GROUP_NAME)
    expect(result.groups[0]!.protocols).toEqual(['OPENAI'])
    expect(result.groups[0]!.rules).toEqual(MIMO_EXAMPLE_RULESET.rules)
  })

  it('把 V1 时并列保存的模板键与预览请求体搬进那个组', () => {
    const result = migrateRuleSet(MIMO_EXAMPLE_RULESET, {
      templateKeys: ['message-tool-image'],
      previewBody: { model: 'kept', temperature: 0.42 },
    })

    expect(result.groups[0]!.templateKeys).toEqual(['message-tool-image'])
    expect(result.groups[0]!.previewBody).toEqual({ model: 'kept', temperature: 0.42 })
  })

  it('旧预览缺失时回退到默认模板而非空对象', () => {
    const result = migrateRuleSet(MIMO_EXAMPLE_RULESET)

    expect(result.groups[0]!.templateKeys).toEqual(['base'])
    expect(Object.keys(result.groups[0]!.previewBody).length).toBeGreaterThan(0)
  })

  it('对 V2 输入是幂等的', () => {
    const once = migrateRuleSet(MIMO_EXAMPLE_RULESET)
    const twice = migrateRuleSet(once)

    expect(twice).toEqual(once)
  })

  it('V2 组缺少 protocols 时视为全协议适用', () => {
    const result = migrateRuleSet({
      version: 2,
      groups: [{ id: 'g1', name: '组', order: 0, enabled: true, rules: [] }],
    })

    expect(result.groups[0]!.protocols).toEqual(['OPENAI', 'ANTHROPIC'])
  })

  it('丢弃 protocols 里无法识别的值', () => {
    const result = migrateRuleSet({
      version: 2,
      groups: [{
        id: 'g1',
        name: '组',
        order: 0,
        enabled: true,
        protocols: ['OPENAI', 'GEMINI', 'OPENAI'],
        rules: [],
      }],
    })

    expect(result.groups[0]!.protocols).toEqual(['OPENAI'])
  })

  it('V2 组缺少 id 时补一个稳定 ID', () => {
    const result = migrateRuleSet({
      version: 2,
      groups: [{ name: '无 ID 的组', order: 0, enabled: true, rules: [createEmptyRule(0)] }],
    })

    expect(result.groups[0]!.id).toBeTruthy()
    expect(result.groups[0]!.rules).toHaveLength(1)
  })

  it('无法识别的输入返回空规则集而不抛错', () => {
    expect(migrateRuleSet(null).groups).toEqual([])
    expect(migrateRuleSet('not json').groups).toEqual([])
    expect(migrateRuleSet({ version: 99 }).groups).toEqual([])
  })
})
