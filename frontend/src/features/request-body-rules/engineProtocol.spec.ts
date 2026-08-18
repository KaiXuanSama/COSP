import { describe, expect, it } from 'vitest'
import { transformForProtocol, transformWithRules } from './engine'
import { createEmptyRule, type RuleGroup, type RuleSetV2 } from './types'
import type { WireProtocol } from '@/types/protocol'

/** 构造一个把 marker 字段设为指定值的单规则组。 */
function markerGroup(
  id: string,
  order: number,
  protocols: WireProtocol[],
  value: unknown,
  enabled = true,
): RuleGroup {
  return {
    id,
    name: id,
    order,
    enabled,
    protocols,
    templateKeys: ['custom'],
    previewBody: {},
    rules: [{
      ...createEmptyRule(0),
      id: `${id}-rule`,
      field: 'marker',
      operations: [{ type: 'set_value', value }],
    }],
  }
}

describe('按线路协议执行 V2 规则集', () => {
  it('只执行 protocols 包含当前协议的组', () => {
    const ruleSet: RuleSetV2 = {
      version: 2,
      groups: [
        markerGroup('openai-only', 0, ['OPENAI'], 'from-openai'),
        markerGroup('anthropic-only', 1, ['ANTHROPIC'], 'from-anthropic'),
      ],
    }

    expect(transformForProtocol({ marker: 'none' }, ruleSet, 'OPENAI').output)
      .toEqual({ marker: 'from-openai' })
    expect(transformForProtocol({ marker: 'none' }, ruleSet, 'ANTHROPIC').output)
      .toEqual({ marker: 'from-anthropic' })
  })

  it('跳过已禁用的组', () => {
    const ruleSet: RuleSetV2 = {
      version: 2,
      groups: [markerGroup('disabled', 0, ['OPENAI'], 'should-not-apply', false)],
    }

    expect(transformForProtocol({ marker: 'kept' }, ruleSet, 'OPENAI').output)
      .toEqual({ marker: 'kept' })
  })

  it('多个适用组按 order 依次作用于同一份请求体', () => {
    const ruleSet: RuleSetV2 = {
      version: 2,
      groups: [
        markerGroup('second', 1, ['OPENAI'], 'last-wins'),
        markerGroup('first', 0, ['OPENAI'], 'first'),
      ],
    }

    expect(transformForProtocol({}, ruleSet, 'OPENAI').output)
      .toEqual({ marker: 'last-wins' })
  })

  it('protocols 为空数组的组永不执行', () => {
    const ruleSet: RuleSetV2 = {
      version: 2,
      groups: [markerGroup('no-protocol', 0, [], 'should-not-apply')],
    }

    expect(transformForProtocol({ marker: 'kept' }, ruleSet, 'OPENAI').output)
      .toEqual({ marker: 'kept' })
    expect(transformForProtocol({ marker: 'kept' }, ruleSet, 'ANTHROPIC').output)
      .toEqual({ marker: 'kept' })
  })

  it('不修改输入对象', () => {
    const input = { marker: 'original' }
    const ruleSet: RuleSetV2 = {
      version: 2,
      groups: [markerGroup('g', 0, ['OPENAI'], 'changed')],
    }

    transformForProtocol(input, ruleSet, 'OPENAI')

    expect(input).toEqual({ marker: 'original' })
  })

  it('单组入口与规则列表入口结果一致', () => {
    const group = markerGroup('g', 0, ['OPENAI'], 'value')
    const viaGroup = transformForProtocol({}, { version: 2, groups: [group] }, 'OPENAI')
    const viaRules = transformWithRules({}, group.rules)

    expect(viaGroup.output).toEqual(viaRules.output)
    expect(viaGroup.warnings).toEqual(viaRules.warnings)
  })
})
