import type {
  ConditionOperator,
  FieldOperation,
  FieldRule,
  RuleCondition,
  RuleSetV2,
} from './types'
import { ALL_WIRE_PROTOCOLS, isWireProtocol } from '@/types/protocol'

/** 规则集 JSON 解析结果。 */
export type RuleSetJsonParseResult =
  | { valid: true; rules: RuleSetV2 }
  | { valid: false; error: string }

const CONDITION_OPERATORS = new Set<ConditionOperator>(['exists', 'equals'])
const OPERATION_TYPES = new Set<FieldOperation['type']>([
  'edit_object',
  'set_value',
  'delete',
])

/** 将规则集格式化为便于编辑和传播的 JSON。 */
export function formatRuleSetJson(ruleSet: RuleSetV2): string {
  return JSON.stringify(ruleSet, null, 2)
}

/**
 * 把一份规则列表包成完整 V2 规则集 JSON，供只读展示与复制粘贴。
 *
 * 帮助页的示例只关心规则语义，不关心它们装在哪个组里；但展示出来的 JSON
 * 必须能直接粘进编辑器的 JSON 视图，否则“照着帮助抄”会得到校验报错。
 * 因此这里补上固定的组元信息（固定 ID 而非随机，保证展示可重现）。
 */
export function formatRuleListAsRuleSetJson(rules: FieldRule[]): string {
  return formatRuleSetJson({
    version: 2,
    groups: [{
      id: 'help-example-group',
      name: '示例规则组',
      order: 0,
      enabled: true,
      protocols: [...ALL_WIRE_PROTOCOLS],
      templateKeys: ['custom'],
      previewBody: {},
      rules,
    }],
  })
}

/** 解析并校验请求体规则 JSON。 */
export function parseRuleSetJson(text: string): RuleSetJsonParseResult {
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch (error) {
    const message = error instanceof Error ? error.message : '未知语法错误'
    return { valid: false, error: `JSON 语法错误：${message}` }
  }

  const error = validateRuleSet(value)
  if (error) return { valid: false, error }
  return { valid: true, rules: value as RuleSetV2 }
}

/**
 * 校验规则集根结构。
 *
 * 只接受 V2：V1 已在数据库层面一次性迁移完毕，而编辑器里的 JSON 文本框
 * 是用户正在写的**保存格式**。粘进一段 V1 应该得到明确报错而非默默升级 ——
 * 默默升级会让用户以为 V1 仍是可写的格式。读取旧数据走 `migrateRuleSet`。
 */
function validateRuleSet(value: unknown): string {
  if (!isRecord(value)) return '规则集必须是 JSON 对象'
  if (value.version === 1) {
    return '规则集 version 已升至 2：V1 的扁平 rules 需装进 groups 里'
  }
  if (value.version !== 2) return '规则集 version 必须为 2'
  if (!Array.isArray(value.groups)) return '规则集 groups 必须是数组'

  const ids = new Set<string>()
  for (let index = 0; index < value.groups.length; index += 1) {
    const error = validateRuleGroup(value.groups[index], `groups[${index}]`, ids)
    if (error) return error
  }
  return ''
}

function validateRuleGroup(value: unknown, path: string, ids: Set<string>): string {
  if (!isRecord(value)) return `${path} 必须是对象`
  if (typeof value.id !== 'string' || !value.id) return `${path}.id 必须是非空字符串`
  if (ids.has(value.id)) return `${path}.id 重复：${value.id}`
  ids.add(value.id)
  if (typeof value.name !== 'string') return `${path}.name 必须是字符串`
  if (!Number.isInteger(value.order) || (value.order as number) < 0) {
    return `${path}.order 必须是非负整数`
  }
  if (typeof value.enabled !== 'boolean') return `${path}.enabled 必须是布尔值`
  if (!Array.isArray(value.protocols)) return `${path}.protocols 必须是数组`
  for (let index = 0; index < value.protocols.length; index += 1) {
    if (!isWireProtocol(value.protocols[index])) {
      return `${path}.protocols[${index}] 必须为 "OPENAI" 或 "ANTHROPIC"`
    }
  }
  if (!Array.isArray(value.templateKeys)) return `${path}.templateKeys 必须是数组`
  if (!isRecord(value.previewBody)) return `${path}.previewBody 必须是 JSON 对象`
  if (!Array.isArray(value.rules)) return `${path}.rules 必须是数组`

  for (let index = 0; index < value.rules.length; index += 1) {
    const error = validateFieldRule(value.rules[index], `${path}.rules[${index}]`)
    if (error) return error
  }
  return ''
}

function validateFieldRule(value: unknown, path: string): string {
  if (!isRecord(value)) return `${path} 必须是对象`
  if (typeof value.id !== 'string') return `${path}.id 必须是字符串`
  if (!Number.isInteger(value.order) || (value.order as number) < 0) {
    return `${path}.order 必须是非负整数`
  }
  if (typeof value.field !== 'string') return `${path}.field 必须是字符串`
  if (typeof value.array !== 'boolean') return `${path}.array 必须是布尔值`
  if (typeof value.conditional !== 'boolean') return `${path}.conditional 必须是布尔值`
  if (value.conditionMode !== 'all') return `${path}.conditionMode 必须为 "all"`
  if (!Array.isArray(value.conditions)) return `${path}.conditions 必须是数组`
  if (!Array.isArray(value.operations)) return `${path}.operations 必须是数组`

  for (let index = 0; index < value.conditions.length; index += 1) {
    const error = validateCondition(value.conditions[index], `${path}.conditions[${index}]`)
    if (error) return error
  }
  for (let index = 0; index < value.operations.length; index += 1) {
    const error = validateOperation(value.operations[index], `${path}.operations[${index}]`)
    if (error) return error
  }
  return ''
}

function validateCondition(value: unknown, path: string): string {
  if (!isRecord(value)) return `${path} 必须是对象`
  if (typeof value.path !== 'string') return `${path}.path 必须是字符串`
  if (!CONDITION_OPERATORS.has(value.operator as ConditionOperator)) {
    return `${path}.operator 必须为 "exists" 或 "equals"`
  }
  if (!Object.prototype.hasOwnProperty.call(value, 'value')) {
    return `${path}.value 不能为空`
  }
  return ''
}

function validateOperation(value: unknown, path: string): string {
  if (!isRecord(value)) return `${path} 必须是对象`
  if (!OPERATION_TYPES.has(value.type as FieldOperation['type'])) {
    return `${path}.type 必须为 "edit_object"、"set_value" 或 "delete"`
  }
  if (value.type === 'edit_object') {
    if (!Array.isArray(value.rules)) return `${path}.rules 必须是数组`
    for (let index = 0; index < value.rules.length; index += 1) {
      const error = validateFieldRule(value.rules[index], `${path}.rules[${index}]`)
      if (error) return error
    }
  }
  return ''
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
