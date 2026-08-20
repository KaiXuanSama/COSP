/**
 * 规则组列表的增删改序操作。
 *
 * 抽到 `features/` 而非留在组件里，是因为这些操作有一条不显然的约束（`order` 必须与
 * 数组下标同步）值得单测钉住，而组件本身没有测试覆盖。全部函数都返回新对象，
 * 不修改入参 —— 组件侧靠整体替换 ref 触发更新。
 */
import type { RuleGroup, RuleSetV2 } from './types'
import { createRuleGroup } from './editorState'

/**
 * 重排 `order` 使其与数组下标一致。
 *
 * 运行时按 `order` 排序执行规则组，因此界面上的先后顺序必须写回该字段 ——
 * 只交换数组位置而不改 `order`，会让「看到的顺序」与「实际执行顺序」分叉。
 * 已经一致的组原样返回，避免无意义地制造新对象。
 */
export function reindexGroups(groups: RuleGroup[]): RuleGroup[] {
  return groups.map((group, index) => (group.order === index ? group : { ...group, order: index }))
}

/** 替换指定下标的组；下标越界时原样返回。 */
export function replaceGroup(ruleSet: RuleSetV2, index: number, group: RuleGroup): RuleSetV2 {
  if (index < 0 || index >= ruleSet.groups.length) return ruleSet
  const groups = [...ruleSet.groups]
  groups[index] = group
  return { version: 2, groups }
}

/** 在末尾追加一个新组。 */
export function appendGroup(ruleSet: RuleSetV2): RuleSetV2 {
  return { version: 2, groups: [...ruleSet.groups, createRuleGroup(ruleSet.groups.length)] }
}

/** 上移或下移一个组；到边界时原样返回。 */
export function moveGroup(ruleSet: RuleSetV2, index: number, direction: -1 | 1): RuleSetV2 {
  const target = index + direction
  if (index < 0 || index >= ruleSet.groups.length) return ruleSet
  if (target < 0 || target >= ruleSet.groups.length) return ruleSet
  const groups = [...ruleSet.groups]
  const moved = groups[index]!
  groups[index] = groups[target]!
  groups[target] = moved
  return { version: 2, groups: reindexGroups(groups) }
}

/** 删除一个组并重排其余组的 `order`。 */
export function removeGroup(ruleSet: RuleSetV2, index: number): RuleSetV2 {
  if (index < 0 || index >= ruleSet.groups.length) return ruleSet
  return { version: 2, groups: reindexGroups(ruleSet.groups.filter((_, i) => i !== index)) }
}
