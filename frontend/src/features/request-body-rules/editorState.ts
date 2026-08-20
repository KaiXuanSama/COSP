/**
 * 请求体规则编辑器需要原子保存的完整状态。
 *
 * V2 起状态就是一组「规则组」—— 模板键与预览请求体已下沉进每个组，
 * 因为不同线路协议的请求体形状不同，共用一份调试样本会让至少一边匹配不到。
 */
import type { RuleGroup, RuleSetV2 } from './types'
import { createEmptyRuleSetV2, generateRuleGroupId } from './types'
import { composeRequestBodyTemplate, DEFAULT_TEMPLATE_KEYS } from './requestBodyTemplates'
import { ALL_WIRE_PROTOCOLS } from '@/types/protocol'

/** 请求体规则编辑器需要原子保存的完整状态。 */
export interface RequestBodyEditorState {
  rules: RuleSetV2
}

/**
 * 创建默认请求体规则编辑器状态。
 *
 * 默认**不含任何规则组**：新供应商多数无需请求体改造，凭空给一个空组会让
 * 「已配置 0 条规则」变成「已配置 1 个组 0 条规则」，反而要求用户先去删掉它。
 */
export function createDefaultRequestBodyEditorState(): RequestBodyEditorState {
  return { rules: createEmptyRuleSetV2() }
}

/** 创建一个新规则组，默认适用于全部线路协议。 */
export function createRuleGroup(order: number): RuleGroup {
  return {
    id: generateRuleGroupId(),
    name: `规则组 ${order + 1}`,
    order,
    enabled: true,
    protocols: [...ALL_WIRE_PROTOCOLS],
    templateKeys: [...DEFAULT_TEMPLATE_KEYS],
    previewBody: composeRequestBodyTemplate(DEFAULT_TEMPLATE_KEYS),
    rules: [],
  }
}

/** 统计规则集内的规则总条数（跨全部组，只算根规则）。 */
export function countRules(ruleSet: RuleSetV2): number {
  return ruleSet.groups.reduce((total, group) => total + group.rules.length, 0)
}
