/**
 * 请求体转换引擎 — 纯 TypeScript 实现，无副作用。
 *
 * 接收原始请求体 JSON 和规则集，返回深拷贝后的转换结果和结构化警告。
 * 不修改原始输入。
 *
 * 执行模型：
 * - 数组模式（array=true）：遍历 scope[field] 数组的每个对象元素，
 *   条件在元素上评估，操作在元素上执行。
 * - 非数组模式（array=false）：条件在 scope（父对象）上评估，
 *   edit_object 进入 scope[field] 执行嵌套规则，
 *   set_value 设置 scope[field] = value，
 *   delete 删除 scope[field]。
 */

import type {
  FieldRule,
  RuleSet,
  TransformResult,
  TransformWarning,
  RuleCondition,
} from './types'
import { pathExists, pathValue, jsonEquals } from './path'

/**
 * 执行规则集转换。
 *
 * @param input 原始请求体 JSON
 * @param ruleSet 规则集
 * @returns 转换结果（深拷贝输出 + 警告列表）
 */
export function transform(input: unknown, ruleSet: RuleSet): TransformResult {
  const warnings: TransformWarning[] = []
  const output = deepClone(input)
  if (output == null || typeof output !== 'object' || Array.isArray(output)) {
    return { output, warnings }
  }

  const sortedRules = [...ruleSet.rules].sort((a, b) => a.order - b.order)
  for (const rule of sortedRules) {
    executeRule(output as Record<string, unknown>, rule, '', warnings)
  }

  return { output, warnings }
}

/**
 * 在当前作用域对象上执行单条字段规则。
 *
 * @param scope 当前作用域对象（包含目标字段的父对象，会被修改）
 * @param rule 字段规则
 * @param parentPath 父级路径描述，用于警告信息
 * @param warnings 警告收集列表
 */
function executeRule(
  scope: Record<string, unknown>,
  rule: FieldRule,
  parentPath: string,
  warnings: TransformWarning[],
): void {
  if (!rule.field) {
    warnings.push({
      ruleId: rule.id,
      fieldPath: parentPath,
      message: '规则未选择目标字段，已跳过',
    })
    return
  }

  const fieldPath = parentPath ? `${parentPath}.${rule.field}` : rule.field

  if (rule.array) {
    executeArrayRule(scope, rule, fieldPath, warnings)
  } else {
    executeScalarRule(scope, rule, fieldPath, warnings)
  }
}

/**
 * 数组模式：遍历 scope[field] 的每个对象元素。
 * 条件在元素上评估，操作在元素上执行。
 */
function executeArrayRule(
  scope: Record<string, unknown>,
  rule: FieldRule,
  fieldPath: string,
  warnings: TransformWarning[],
): void {
  const arr = scope[rule.field]
  if (!Array.isArray(arr)) {
    return // 字段不是数组，静默跳过
  }

  for (let i = 0; i < arr.length; i++) {
    const item = arr[i]
    if (item == null || typeof item !== 'object' || Array.isArray(item)) {
      continue // 非对象元素，跳过
    }
    const element = item as Record<string, unknown>
    const elementPath = `${fieldPath}[${i}]`

    if (!checkConditions(element, rule)) {
      continue
    }

    // 在元素上执行操作
    for (const op of rule.operations) {
      executeOperationOnScope(element, op, elementPath, rule.id, warnings)
    }
  }
}

/**
 * 非数组模式：条件在 scope（父对象）上评估。
 * edit_object 进入 scope[field]，set_value/delete 作用于 scope[field]。
 */
function executeScalarRule(
  scope: Record<string, unknown>,
  rule: FieldRule,
  fieldPath: string,
  warnings: TransformWarning[],
): void {
  // 条件在父对象上评估
  if (!checkConditions(scope, rule)) {
    return
  }

  for (const op of rule.operations) {
    switch (op.type) {
      case 'edit_object': {
        if (!(rule.field in scope)) break
        const target = scope[rule.field]
        if (target == null || typeof target !== 'object' || Array.isArray(target)) {
          warnings.push({
            ruleId: rule.id,
            fieldPath,
            message: '字段值不是对象，无法执行"调整对象内容"',
          })
          break
        }
        const targetObj = target as Record<string, unknown>
        if (op.rules && op.rules.length > 0) {
          const sortedNested = [...op.rules].sort((a, b) => a.order - b.order)
          for (const nestedRule of sortedNested) {
            executeRule(targetObj, nestedRule, fieldPath, warnings)
          }
        }
        break
      }
      case 'set_value':
        scope[rule.field] = op.value
        break
      case 'delete':
        delete scope[rule.field]
        break
      default:
        warnings.push({
          ruleId: rule.id,
          fieldPath,
          message: `未知操作类型: ${op.type}`,
        })
    }
  }
}

/**
 * 在数组元素（作为 scope）上执行操作。
 * 数组模式下，edit_object 的嵌套规则在元素上执行；
 * set_value 和 delete 不直接适用于数组模式（应通过嵌套规则定位字段）。
 */
function executeOperationOnScope(
  scope: Record<string, unknown>,
  op: FieldRule['operations'][number],
  fieldPath: string,
  ruleId: string,
  warnings: TransformWarning[],
): void {
  switch (op.type) {
    case 'edit_object': {
      if (op.rules && op.rules.length > 0) {
        const sortedNested = [...op.rules].sort((a, b) => a.order - b.order)
        for (const nestedRule of sortedNested) {
          executeRule(scope, nestedRule, fieldPath, warnings)
        }
      }
      break
    }
    case 'set_value':
      // 数组模式下 set_value 无目标字段，应通过嵌套规则使用
      warnings.push({
        ruleId,
        fieldPath,
        message: '数组模式下"设置字段值"应通过嵌套规则定位字段',
      })
      break
    case 'delete':
      warnings.push({
        ruleId,
        fieldPath,
        message: '数组模式下"删除字段"应通过嵌套规则定位字段',
      })
      break
    default:
      warnings.push({
        ruleId,
        fieldPath,
        message: `未知操作类型: ${op.type}`,
      })
  }
}

/**
 * 检查规则的所有条件是否满足（V1 固定 AND）。
 */
function checkConditions(scope: Record<string, unknown>, rule: FieldRule): boolean {
  if (!rule.conditional || rule.conditions.length === 0) {
    return true
  }
  for (const condition of rule.conditions) {
    if (!evaluateCondition(scope, condition)) {
      return false
    }
  }
  return true
}

/**
 * 评估单个条件。
 */
function evaluateCondition(scope: Record<string, unknown>, condition: RuleCondition): boolean {
  if (!condition.path) return false
  switch (condition.operator) {
    case 'exists':
      return pathExists(scope, condition.path)
    case 'equals':
      return jsonEquals(pathValue(scope, condition.path), condition.value)
    default:
      return false
  }
}

/**
 * 深拷贝 JSON 值。
 */
function deepClone<T>(value: T): T {
  if (value == null || typeof value !== 'object') {
    return value
  }
  if (typeof structuredClone === 'function') {
    try {
      return structuredClone(value)
    } catch {
      // 回退到 JSON 方式
    }
  }
  return JSON.parse(JSON.stringify(value))
}
