/**
 * 请求体映射规则 V1 前端协议。
 *
 * 规则通过供应商配置持久化，并由后端 RequestBodyRuleEngine 在生产请求中执行。
 */

/** 条件运算符。V1 只实现 exists 和 equals。 */
export type ConditionOperator = 'exists' | 'equals'

/** 单个条件。 */
export interface RuleCondition {
  /** 相对路径，以 ./ 开头，如 ./image_url 或 ./content[*]/image_url */
  path: string
  /** 运算符 */
  operator: ConditionOperator
  /** 比较值，exists 时为 null，equals 时为 JSON 值 */
  value: unknown
}

/** 多条件组合方式。V1 固定为 AND。 */
export type ConditionMode = 'all'

/** 字段操作类型。V1 只实现 MiMo 所需的最小集。 */
export type OperationType = 'edit_object' | 'set_value' | 'delete'

/** 字段操作。 */
export interface FieldOperation {
  /** 操作类型 */
  type: OperationType
  /** set_value 的目标值（JSON 类型安全） */
  value?: unknown
  /** edit_object 内部的嵌套规则列表 */
  rules?: FieldRule[]
}

/** 字段规则。 */
export interface FieldRule {
  /** 稳定 ID，用于 Vue 列表 key */
  id: string
  /** 执行顺序，从 0 开始 */
  order: number
  /** 目标字段名 */
  field: string
  /** 是否遍历数组。true 时对数组每个对象元素执行；false 时进入普通对象 */
  array: boolean
  /** 是否启用条件执行 */
  conditional: boolean
  /** 条件组合方式 */
  conditionMode: ConditionMode
  /** 条件列表 */
  conditions: RuleCondition[]
  /** 操作列表，按顺序执行 */
  operations: FieldOperation[]
}

/** 规则集根。 */
export interface RuleSet {
  /** 协议版本 */
  version: 1
  /** 根字段规则列表 */
  rules: FieldRule[]
}

/** 引擎执行中产生的结构化警告。 */
export interface TransformWarning {
  /** 规则 ID */
  ruleId: string
  /** 字段路径描述 */
  fieldPath: string
  /** 警告消息 */
  message: string
}

/** 转换结果。 */
export interface TransformResult {
  /** 转换后的 JSON 对象（深拷贝，不修改原始输入） */
  output: unknown
  /** 执行过程中产生的警告 */
  warnings: TransformWarning[]
}

/** 生成唯一规则 ID */
export function generateRuleId(): string {
  return `rule-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

/** 创建空规则 */
export function createEmptyRule(order: number): FieldRule {
  return {
    id: generateRuleId(),
    order,
    field: '',
    array: false,
    conditional: false,
    conditionMode: 'all',
    conditions: [],
    operations: [],
  }
}

/** 创建空条件 */
export function createEmptyCondition(): RuleCondition {
  return {
    path: '',
    operator: 'exists',
    value: null,
  }
}

/** 创建空规则集 */
export function createEmptyRuleSet(): RuleSet {
  return { version: 1, rules: [] }
}
