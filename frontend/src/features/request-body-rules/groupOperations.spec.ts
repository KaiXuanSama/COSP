import { describe, expect, it } from 'vitest'
import {
  appendGroup,
  moveGroup,
  reindexGroups,
  removeGroup,
  replaceGroup,
} from './groupOperations'
import type { RuleGroup, RuleSetV2 } from './types'

function group(id: string, order: number): RuleGroup {
  return {
    id,
    name: id,
    order,
    enabled: true,
    protocols: ['OPENAI'],
    templateKeys: ['base'],
    previewBody: {},
    rules: [],
  }
}

function ruleSet(...ids: string[]): RuleSetV2 {
  return { version: 2, groups: ids.map((id, index) => group(id, index)) }
}

describe('规则组列表操作', () => {
  it('替换指定下标的组', () => {
    const next = replaceGroup(ruleSet('a', 'b'), 1, { ...group('b', 1), name: '改名' })

    expect(next.groups[1]!.name).toBe('改名')
    expect(next.groups[0]!.name).toBe('a')
  })

  it('下标越界时原样返回', () => {
    const original = ruleSet('a')

    expect(replaceGroup(original, 5, group('x', 5))).toBe(original)
    expect(moveGroup(original, 5, 1)).toBe(original)
    expect(removeGroup(original, 5)).toBe(original)
  })

  it('追加的新组 order 接在末尾', () => {
    const next = appendGroup(ruleSet('a', 'b'))

    expect(next.groups).toHaveLength(3)
    expect(next.groups[2]!.order).toBe(2)
  })

  it('上移交换相邻两组并重排 order', () => {
    const next = moveGroup(ruleSet('a', 'b', 'c'), 2, -1)

    expect(next.groups.map((g) => g.id)).toEqual(['a', 'c', 'b'])
    expect(next.groups.map((g) => g.order)).toEqual([0, 1, 2])
  })

  it('下移交换相邻两组并重排 order', () => {
    const next = moveGroup(ruleSet('a', 'b', 'c'), 0, 1)

    expect(next.groups.map((g) => g.id)).toEqual(['b', 'a', 'c'])
    expect(next.groups.map((g) => g.order)).toEqual([0, 1, 2])
  })

  it('到边界时不移动', () => {
    const original = ruleSet('a', 'b')

    expect(moveGroup(original, 0, -1)).toBe(original)
    expect(moveGroup(original, 1, 1)).toBe(original)
  })

  it('删除中间组后其余组 order 连续', () => {
    const next = removeGroup(ruleSet('a', 'b', 'c'), 1)

    expect(next.groups.map((g) => g.id)).toEqual(['a', 'c'])
    expect(next.groups.map((g) => g.order)).toEqual([0, 1])
  })

  it('order 与下标不一致时被重排', () => {
    const reindexed = reindexGroups([group('a', 7), group('b', 3)])

    expect(reindexed.map((g) => g.order)).toEqual([0, 1])
  })

  it('order 已一致时不制造新对象', () => {
    const groups = [group('a', 0), group('b', 1)]

    const reindexed = reindexGroups(groups)

    expect(reindexed[0]).toBe(groups[0])
    expect(reindexed[1]).toBe(groups[1])
  })

  it('全部操作不修改入参', () => {
    const original = ruleSet('a', 'b')
    const snapshot = JSON.stringify(original)

    appendGroup(original)
    moveGroup(original, 0, 1)
    removeGroup(original, 0)
    replaceGroup(original, 0, group('x', 0))

    expect(JSON.stringify(original)).toBe(snapshot)
  })
})
