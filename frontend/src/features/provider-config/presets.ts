import { MIMO_EXAMPLE_RULESET } from '@/features/request-body-rules/defaultRequestBody'
import { createDefaultRequestBodyEditorState } from '@/features/request-body-rules/editorState'
import type { RequestBodyEditorState } from '@/features/request-body-rules/editorState'
import { migrateRuleSet } from '@/features/request-body-rules/migration'
import { composeRequestBodyTemplate } from '@/features/request-body-rules/requestBodyTemplates'
import type { RequestBodyTemplateKey } from '@/features/request-body-rules/requestBodyTemplates'
import type { RuleSet, RuleSetV2 } from '@/features/request-body-rules/types'

/**
 * 预设供应商模板。
 *
 * 预设只是**表单初值**，填入后用户仍可任意修改；它不参与运行时路由，
 * 也不会被后端读取 —— 保存后一切以数据库里的配置为准。
 *
 * 分三类仅为界面分栏，语义上没有区别：官方直连、整合商、中转站。
 */

/** 请求头键值对。`value` 为 `/del/` 表示删除该头（由后端规则引擎解释）。 */
export interface HeaderEntry {
  key: string
  value: string
}

export interface ProviderPreset {
  label: string
  baseUrl: string
  headers: HeaderEntry[]
  /**
   * 请求体规则集的**工厂**，而非现成对象。
   *
   * 预设是模块级常量，若直接挂一份规则集对象，用户在编辑器里的任何改动都会
   * 原地污染该常量，进而影响之后所有使用同一预设的供应商。改成工厂后
   * 每次取用都是全新对象，「记得深拷贝」这件事从约定变成了结构保证。
   *
   * 未配置时回退为空规则集。
   */
  createRules?: () => RuleSetV2
}

/**
 * 图片兼容模板键。
 *
 * 多数国内供应商对 Copilot 发来的多模态消息结构不耐受，需要这条模板把
 * 图片部分整形成它们接受的形状，故绝大多数预设都带上它。
 */
export const IMAGE_COMPATIBILITY_TEMPLATE_KEYS: RequestBodyTemplateKey[] = ['message-tool-image']

/**
 * 把预设自带的 V1 规则集升为单组 V2，并挂上该组的调试样本。
 *
 * 走 `migrateRuleSet` 而不是手写包装：迁移函数已经确定了「V1 规则只适用
 * OpenAI」这一判断（规则里的字段路径是照 OpenAI 请求体写的，`messages` 含
 * system 条目、无 `max_tokens`，作用在 Anthropic 上多数匹配不到，静默失效
 * 比不执行更难排查）。预设与存量配置因此共享同一套升级语义，不会漂移。
 */
export function presetRuleSetV2(
  rules: RuleSet,
  templateKeys: RequestBodyTemplateKey[] = IMAGE_COMPATIBILITY_TEMPLATE_KEYS,
): RuleSetV2 {
  return migrateRuleSet(cloneRuleSet(rules), {
    templateKeys,
    previewBody: composeRequestBodyTemplate(templateKeys),
  })
}

/** 深拷贝规则集，避免迁移函数把预设常量里的规则数组按引用搬进结果。 */
function cloneRuleSet(rules: RuleSet): RuleSet {
  return JSON.parse(JSON.stringify(rules)) as RuleSet
}

/** 带图片兼容配置的预设片段，避免逐个预设重复一行。 */
const withImageCompatibility = {
  createRules: () => presetRuleSetV2(MIMO_EXAMPLE_RULESET),
}

/** 官方直连供应商。 */
export const officialPresets: ProviderPreset[] = [
  { label: 'MiMo', baseUrl: 'https://api.xiaomimimo.com/v1', headers: [], ...withImageCompatibility },
  { label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', headers: [] },
  { label: 'LongCat', baseUrl: 'https://api.longcat.chat/openai/v1', headers: [], ...withImageCompatibility },
  { label: 'Kimi', baseUrl: 'https://api.moonshot.cn/v1', headers: [], ...withImageCompatibility },
  { label: 'Kimi (CodePlan)', baseUrl: 'https://api.kimi.com/coding/v1', headers: [], ...withImageCompatibility },
  { label: 'Mimo (TokenPlan)', baseUrl: 'https://token-plan-cn.xiaomimimo.com/v1', headers: [], ...withImageCompatibility },
  { label: 'Agnes', baseUrl: 'https://apihub.agnes-ai.com/v1', headers: [], ...withImageCompatibility },
  { label: 'Zhipu', baseUrl: 'https://open.bigmodel.cn/api/paas/v4', headers: [], ...withImageCompatibility },
  { label: 'StepFun', baseUrl: 'https://api.stepfun.com/v1', headers: [] },
]

/** 整合商。 */
export const aggregatorPresets: ProviderPreset[] = [
  { label: 'SenseNova', baseUrl: 'https://token.sensenova.cn/v1', headers: [], ...withImageCompatibility },
  { label: 'Uumit', baseUrl: 'https://agent.uumit.com/v1', headers: [], ...withImageCompatibility },
  { label: 'Xunfei', baseUrl: 'https://maas-api.cn-huabei-1.xf-yun.com/v2', headers: [], ...withImageCompatibility },
  { label: 'WorkBuddy', baseUrl: 'https://copilot.tencent.com/v2', headers: [], ...withImageCompatibility },
]

/** 中转站。 */
export const relayPresets: ProviderPreset[] = [
  {
    label: 'AgentRouter',
    baseUrl: 'https://agentrouter.org/v1',
    // Accept-Encoding 必须删除：该站会对 SSE 流做 Brotli 压缩，导致解码失败。
    headers: [
      { key: 'User-Agent', value: 'claude-cli/2.1.195 (external, cli)' },
      { key: 'Accept-Encoding', value: '/del/' },
    ],
    ...withImageCompatibility,
  },
  { label: 'FreeModel', baseUrl: 'https://api.freemodel.dev/v1', headers: [], ...withImageCompatibility },
]

export const allPresets: ProviderPreset[] = [...officialPresets, ...aggregatorPresets, ...relayPresets]

/** 按标签查找预设。 */
export function findPreset(label: string): ProviderPreset | undefined {
  return allPresets.find(preset => preset.label === label)
}

/** 供应商默认的图片兼容编辑器状态（新建供应商时的初值）。 */
export function createProviderDefaultEditorState(): RequestBodyEditorState {
  return { rules: presetRuleSetV2(MIMO_EXAMPLE_RULESET) }
}

/** 应用预设后的表单初值。 */
export interface PresetFormValues {
  displayName: string
  baseUrl: string
  headers: HeaderEntry[]
  editorState: RequestBodyEditorState
}

/**
 * 把预设展开成表单初值。
 *
 * 请求头逐项拷贝、规则集由工厂新造，因此表单编辑不会回写到预设常量上。
 */
export function toPresetFormValues(preset: ProviderPreset): PresetFormValues {
  return {
    displayName: preset.label,
    baseUrl: preset.baseUrl,
    headers: preset.headers.map(header => ({ ...header })),
    editorState: preset.createRules
      ? { rules: preset.createRules() }
      : createDefaultRequestBodyEditorState(),
  }
}
