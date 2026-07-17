import type {
  ConditionOperator,
  FieldOperation,
  FieldRule,
  RuleCondition,
  RuleSet,
} from './types'

/** 规则集 JSON 解析结果。 */
export type RuleSetJsonParseResult =
  | { valid: true; rules: RuleSet }
  | { valid: false; error: string }

const CONDITION_OPERATORS = new Set<ConditionOperator>(['exists', 'equals'])
const OPERATION_TYPES = new Set<FieldOperation['type']>([
  'edit_object',
  'set_value',
  'delete',
])

/** 将规则集格式化为便于编辑和传播的 JSON。 */
export function formatRuleSetJson(ruleSet: RuleSet): string {
  return JSON.stringify(ruleSet, null, 2)
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
  return { valid: true, rules: value as RuleSet }
}

function validateRuleSet(value: unknown): string {
  if (!isRecord(value)) return '规则集必须是 JSON 对象'
  if (value.version !== 1) return '规则集 version 必须为 1'
  if (!Array.isArray(value.rules)) return '规则集 rules 必须是数组'

  for (let index = 0; index < value.rules.length; index += 1) {
    const error = validateFieldRule(value.rules[index], `rules[${index}]`)
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
