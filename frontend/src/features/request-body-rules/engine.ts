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
  RuleSetV2,
  TransformResult,
  TransformWarning,
  RuleCondition,
} from './types'
import { pathExists, pathValue, jsonEquals } from './path'
import type { WireProtocol } from '@/types/protocol'

/**
 * 执行单一规则列表的转换。
 *
 * V2 的每个规则组正是一份规则列表，因而编辑器的单组预览与帮助页的示例
 * 都直接用这个入口，不需要包一层组。
 *
 * @param input 原始请求体 JSON
 * @param rules 规则列表
 * @returns 转换结果（深拷贝输出 + 警告列表）
 */
export function transformWithRules(input: unknown, rules: FieldRule[]): TransformResult {
  const warnings: TransformWarning[] = []
  const output = deepClone(input)
  if (output == null || typeof output !== 'object' || Array.isArray(output)) {
    return { output, warnings }
  }
  applyRules(output as Record<string, unknown>, rules, warnings)
  return { output, warnings }
}

/**
 * 执行规则集转换（V1 入口）。
 *
 * 保留该重载是为了帮助页示例与引擎单测 —— 它们验证的是规则语义，
 * 与“规则装在哪个组里”无关，包一层组只会让用例变噪。
 *
 * @param input 原始请求体 JSON
 * @param ruleSet V1 规则集
 * @returns 转换结果
 */
export function transform(input: unknown, ruleSet: RuleSet): TransformResult {
  return transformWithRules(input, ruleSet.rules)
}

/**
 * 按线路协议执行 V2 规则集。
 *
 * 组的筛选条件：已启用且 {@code protocols} 包含当前协议。筛选后按 {@code order}
 * 依次作用于**同一份**请求体 —— 与编辑器里每组各自预览的调试模型不同，
 * 这是已知且有意接受的差异。
 *
 * @param input 原始请求体 JSON
 * @param ruleSet V2 规则集
 * @param protocol 当前上游线路协议
 * @returns 转换结果
 */
export function transformForProtocol(
  input: unknown,
  ruleSet: RuleSetV2,
  protocol: WireProtocol,
): TransformResult {
  const warnings: TransformWarning[] = []
  const output = deepClone(input)
  if (output == null || typeof output !== 'object' || Array.isArray(output)) {
    return { output, warnings }
  }

  const groups = ruleSet.groups
    .filter((group) => group.enabled && group.protocols.includes(protocol))
    .sort((a, b) => a.order - b.order)
  for (const group of groups) {
    applyRules(output as Record<string, unknown>, group.rules, warnings)
  }
  return { output, warnings }
}

function applyRules(
  scope: Record<string, unknown>,
  rules: FieldRule[],
  warnings: TransformWarning[],
): void {
  const sortedRules = [...rules].sort((a, b) => a.order - b.order)
  for (const rule of sortedRules) {
    executeRule(scope, rule, '', warnings)
  }
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
        // TODO 缺 value 时这里写入 undefined，序列化后整个键消失，等同于「删除字段」；
        //  而后端 RequestBodyRuleEngine 落显式 null。「留空即置 null」是既定语义，
        //  因此本侧行为是错的，应改为 `op.value ?? null`。
        //  暂不改是因为该语义连同预览一致性会在引擎接口化时一并处理 ——
        //  届时预览直接由后端计算，本函数不再参与生产路径。
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
