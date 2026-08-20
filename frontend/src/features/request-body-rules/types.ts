/**
 * 请求体映射规则前端协议。
 *
 * 规则通过供应商配置持久化，并由后端 `RequestBodyRuleEngine` 执行 —— 生产转换与编辑器预览
 * 都走那一份实现，前端只负责编辑与展示，不再自带引擎。
 *
 * <h2>两个版本共存</h2>
 * V1（{@link RuleSet}）是单一扁平规则列表，只服务 OpenAI 一条线路。
 * V2（{@link RuleSetV2}）把规则装进「规则组」，每组声明自己适用于哪些线路协议。
 * V1 类型保留是为了让迁移函数能类型安全地读旧数据，**不再作为保存格式**。
 */
import type { WireProtocol } from '@/types/protocol'
import type { RequestBodyTemplateKey } from './requestBodyTemplates'

/** 条件运算符。当前只实现 exists 和 equals。 */
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

/** 规则集根（V1，仅用于读取旧数据并迁移）。 */
export interface RuleSet {
  /** 协议版本 */
  version: 1
  /** 根字段规则列表 */
  rules: FieldRule[]
}

/**
 * 规则组：一批规则 + 它们适用的线路协议 + 该组自己的调试样本。
 *
 * <h3>为何协议放在组上而不是单条规则上</h3>
 * 适配一个上游差异往往需要好几条规则协同（改 messages、删字段、补字段），
 * 它们必然同进同出。放在每条规则上等于要求用户重复勾选同一组值，
 * 且一旦某条漏勾就会出现「一半规则跑了一半没跑」的半成品请求体。
 *
 * <h3>为何每组各带一份 previewBody</h3>
 * 不同协议的请求体形状不同（Anthropic 的 system 在顶层、必带 max_tokens），
 * 用同一个样本调试两条线路的规则会让至少一边的预览完全匹配不到。
 * 代价是组间预览**不串联** —— 每组的预览是自包含的调试样本，
 * 不是上一组的输出。运行时多组会依次作用于同一请求体，编辑器不模拟这一点。
 */
export interface RuleGroup {
  /** 稳定 ID，用于 Vue 列表 key */
  id: string
  /** 组名，仅用于界面识别 */
  name: string
  /** 组间执行顺序，从 0 开始 */
  order: number
  /** 是否启用；关闭时该组整体跳过 */
  enabled: boolean
  /** 适用的线路协议；空数组表示任何线路都不执行 */
  protocols: WireProtocol[]
  /** 该组预览请求体所选的模板片段 */
  templateKeys: RequestBodyTemplateKey[]
  /** 该组的调试样本请求体 */
  previewBody: Record<string, unknown>
  /** 组内字段规则列表 */
  rules: FieldRule[]
}

/** 规则集根（V2，当前保存格式）。 */
export interface RuleSetV2 {
  /** 协议版本 */
  version: 2
  /** 规则组列表，按 order 依次执行 */
  groups: RuleGroup[]
}

/**
 * 引擎执行中产生的结构化警告。
 *
 * 由后端预览端点回传（字段名与 Java 侧 `TransformWarning` record 一致），
 * 编辑器按 `fieldPath` + `message` 展示给用户。
 */
export interface TransformWarning {
  /** 规则 ID */
  ruleId: string
  /** 字段路径描述 */
  fieldPath: string
  /** 警告消息 */
  message: string
}

/**
 * 转换结果。
 *
 * 前端不再自带引擎实现 —— 该结构是后端预览端点的响应形态，
 * 与生产转换出自同一份代码，因此「预览与实际不一致」在结构上不再可能。
 */
export interface TransformResult {
  /** 转换后的 JSON 对象 */
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

/** 创建空规则集（V1；仅测试与迁移入参构造使用） */
export function createEmptyRuleSet(): RuleSet {
  return { version: 1, rules: [] }
}

/** 生成唯一规则组 ID */
export function generateRuleGroupId(): string {
  return `group-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
}

/** 创建空 V2 规则集 */
export function createEmptyRuleSetV2(): RuleSetV2 {
  return { version: 2, groups: [] }
}

