/**
 * 请求体规则集 V1 → V2 迁移。
 *
 * 数据库里的 `body_rules_json` 在 V8.7 之后统一是 V2；但前端仍需要这份迁移：
 * 用户可能从旧版本导出的 JSON 粘贴进来，规则 JSON 也是可手工编辑的文本。
 * 迁移是幂等的 —— 传入 V2 原样返回（仅补齐缺省字段）。
 *
 * <h2>为何把旧的 templateKeys / previewBody 也吞进来</h2>
 * V1 时代这两项与规则并列存在供应商配置里，是**全局唯一**的一份调试样本。
 * V2 把它们下沉到每个组，于是迁移必须把那份全局样本搬进唯一的那个组，
 * 否则旧配置一升级就丢掉用户精心调过的预览请求体。
 */
import type { FieldRule, RuleGroup, RuleSetV2 } from './types'
import { generateRuleGroupId } from './types'
import type { RequestBodyTemplateKey } from './requestBodyTemplates'
import { composeRequestBodyTemplate, DEFAULT_TEMPLATE_KEYS } from './requestBodyTemplates'
import { ALL_WIRE_PROTOCOLS, isWireProtocol, type WireProtocol } from '@/types/protocol'

/** 由 V1 迁移而来的规则组名称。 */
export const LEGACY_GROUP_NAME = 'OpenAI 规则组'

/**
 * 迁移入参里的旧编辑器状态。
 *
 * V1 的这两项存在独立的数据库列（`body_template_keys_json` / `body_preview_json`），
 * 与规则 JSON 不在同一个字符串里，因此必须作为额外参数传入。
 */
export interface LegacyEditorFields {
  templateKeys?: unknown
  previewBody?: unknown
}

/**
 * 把任意来源的规则集归一化为 V2。
 *
 * 无法识别的输入返回**空 V2 规则集**而非抛错：规则集是可手工编辑的文本，
 * 编辑器需要在语法崩坏时仍能渲染出界面让用户改回来。校验由 `ruleSetJson.ts`
 * 负责并给出可读错误，此处只做结构归一。
 *
 * @param raw 已解析的规则集对象（V1 或 V2 形态）
 * @param legacy V1 时代与规则并列保存的模板键与预览请求体
 */
export function migrateRuleSet(raw: unknown, legacy: LegacyEditorFields = {}): RuleSetV2 {
  if (!isRecord(raw)) return { version: 2, groups: [] }

  if (raw.version === 2) {
    const groups = Array.isArray(raw.groups) ? raw.groups : []
    return {
      version: 2,
      groups: groups.map((group, index) => normalizeGroup(group, index)),
    }
  }

  if (raw.version === 1) {
    const rules = Array.isArray(raw.rules) ? (raw.rules as FieldRule[]) : []
    return { version: 2, groups: [legacyGroup(rules, legacy)] }
  }

  return { version: 2, groups: [] }
}

/**
 * 把 V1 的扁平规则列表包成单个「OpenAI 规则组」。
 *
 * 协议**只给 OPENAI** 而非两者全选：这些规则的字段路径是照 OpenAI 请求体写的
 * （`messages` 里含 system 条目、无 `max_tokens`），作用在 Anthropic 请求体上
 * 多数匹配不到 —— 静默失效比不执行更难排查。
 */
function legacyGroup(rules: FieldRule[], legacy: LegacyEditorFields): RuleGroup {
  return {
    id: generateRuleGroupId(),
    name: LEGACY_GROUP_NAME,
    order: 0,
    enabled: true,
    protocols: ['OPENAI'],
    templateKeys: normalizeTemplateKeys(legacy.templateKeys),
    previewBody: isRecord(legacy.previewBody)
      ? (legacy.previewBody as Record<string, unknown>)
      : composeRequestBodyTemplate(DEFAULT_TEMPLATE_KEYS),
    rules,
  }
}

/**
 * 补齐 V2 规则组的缺省字段。
 *
 * `protocols` 缺失时视为**全协议适用**，与 V1 迁移刻意不同：一个 V2 组能存在，
 * 说明它是在协议概念存在之后写的，作者省略该字段更可能是「没在意」而非「只要 OpenAI」。
 * V1 的默认值来自数据事实（那些规则确实只为 OpenAI 写的），两者依据不同。
 */
function normalizeGroup(raw: unknown, index: number): RuleGroup {
  if (!isRecord(raw)) return emptyGroup(index)
  return {
    id: typeof raw.id === 'string' && raw.id ? raw.id : generateRuleGroupId(),
    name: typeof raw.name === 'string' ? raw.name : `规则组 ${index + 1}`,
    order: Number.isInteger(raw.order) && (raw.order as number) >= 0 ? (raw.order as number) : index,
    enabled: typeof raw.enabled === 'boolean' ? raw.enabled : true,
    protocols: normalizeProtocols(raw.protocols),
    templateKeys: normalizeTemplateKeys(raw.templateKeys),
    previewBody: isRecord(raw.previewBody)
      ? (raw.previewBody as Record<string, unknown>)
      : composeRequestBodyTemplate(DEFAULT_TEMPLATE_KEYS),
    rules: Array.isArray(raw.rules) ? (raw.rules as FieldRule[]) : [],
  }
}

function emptyGroup(index: number): RuleGroup {
  return {
    id: generateRuleGroupId(),
    name: `规则组 ${index + 1}`,
    order: index,
    enabled: true,
    protocols: [...ALL_WIRE_PROTOCOLS],
    templateKeys: [...DEFAULT_TEMPLATE_KEYS],
    previewBody: composeRequestBodyTemplate(DEFAULT_TEMPLATE_KEYS),
    rules: [],
  }
}

function normalizeProtocols(raw: unknown): WireProtocol[] {
  if (!Array.isArray(raw)) return [...ALL_WIRE_PROTOCOLS]
  const seen = new Set<WireProtocol>()
  for (const item of raw) {
    if (isWireProtocol(item)) seen.add(item)
  }
  return [...seen]
}

function normalizeTemplateKeys(raw: unknown): RequestBodyTemplateKey[] {
  if (!Array.isArray(raw) || raw.length === 0) return [...DEFAULT_TEMPLATE_KEYS]
  const keys = raw.filter((item): item is RequestBodyTemplateKey => typeof item === 'string')
  return keys.length > 0 ? keys : [...DEFAULT_TEMPLATE_KEYS]
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
