import {
  MIMO_EXAMPLE_RULESET,
  MIMO_RESPONSES_TEXT_FORMAT_PREVIEW,
  MIMO_RESPONSES_TEXT_FORMAT_RULESET,
  MIMO_RESPONSES_WEB_SEARCH_PREVIEW,
  MIMO_RESPONSES_WEB_SEARCH_RULESET,
} from '@/features/request-body-rules/defaultRequestBody'
import { createDefaultRequestBodyEditorState } from '@/features/request-body-rules/editorState'
import type { RequestBodyEditorState } from '@/features/request-body-rules/editorState'
import { migrateRuleSet } from '@/features/request-body-rules/migration'
import { composeRequestBodyTemplate } from '@/features/request-body-rules/requestBodyTemplates'
import type { RequestBodyTemplateKey } from '@/features/request-body-rules/requestBodyTemplates'
import { generateRuleGroupId } from '@/features/request-body-rules/types'
import type { FieldRule, RuleGroup, RuleSet, RuleSetV2 } from '@/features/request-body-rules/types'

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
  /** Chat Completions 协议的请求地址。 */
  baseUrl: string
  /**
   * Anthropic Messages 协议的请求地址。
   *
   * 未声明时视为**与 Chat 同源**（由 {@link toPresetFormValues} 填成 `baseUrl` 的值）。
   * 目前没有任何预设需要单独声明它 —— 这是个乐观假设，而不是已验证的事实：
   * 中转站把 Anthropic 端点摆在哪里不可预测，一旦发现某个预设不同源，
   * 在它上面补一行 `anthropicBaseUrl` 即可，不必改动这里的结构。
   */
  anthropicBaseUrl?: string
  /**
   * OpenAI Responses 协议的请求地址。
   *
   * 与 {@link anthropicBaseUrl} 同一约定，但**常态相反**：多数中转站根本没有
   * Responses 端点，因此这里留空并非「同源」的乐观假设，而是「多半用不上」。
   * 真有某个预设提供独立的 Responses 端点时在它上面补一行即可。
   */
  responsesBaseUrl?: string
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
 * `CHAT`」这一判断（规则里的字段路径是照 Chat 请求体写的，`messages` 含
 * system 条目、无 `max_tokens`，作用在 Anthropic 或 Responses 上多数匹配不到，
 * 静默失效比不执行更难排查）。预设与存量配置因此共享同一套升级语义，不会漂移。
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

/**
 * 组装一个只适用 Responses 线路的规则组。
 *
 * 模板键取 `custom` 而非结构化模板：现有 7 个模板都是照 Chat 请求体形状写的
 * （`messages` 数组），而 Responses 用 `input`，套上去规则一条也匹配不到。
 * 但 `custom` 自身不产生内容，因此调用方**必须**给一份 `previewBody` ——
 * 留空对象会让预览两栏都是 `{}`，规则改成什么样都看不出效果。
 *
 * @param order 组间执行顺序，由调用方按数组下标给出（必须与下标一致，
 *              否则界面顺序与执行顺序分叉）
 * @param previewBody 该组的调试样本，取自真实请求的最小可复现形态
 */
function responsesRuleGroup(
  name: string,
  order: number,
  rules: FieldRule[],
  previewBody: Record<string, unknown>,
): RuleGroup {
  return {
    // 生成式 ID 而非写死常量：与 `presetRuleSetV2`（走 migrateRuleSet）保持一致。
    // 当前 `applyPreset` 是整体替换编辑器状态、不会累积，写死也不会立刻冲突；
    // 但一旦将来出现「把预设规则追加到已有组」的入口，写死的 ID 就会撞上
    // 校验器的「组 ID 重复」并让整个规则集保存失败。
    id: generateRuleGroupId(),
    name,
    order,
    enabled: true,
    protocols: ['RESPONSES'],
    templateKeys: ['custom'],
    // 深拷贝：与 rules 同一理由，样本也是模块级常量。
    previewBody: JSON.parse(JSON.stringify(previewBody)) as Record<string, unknown>,
    // 深拷贝：规则数组来自模块级常量，直接挂上去会让用户的编辑原地污染它，
    // 进而影响之后所有套用同一预设的供应商。与 presetRuleSetV2 同一防线。
    rules: JSON.parse(JSON.stringify(rules)) as FieldRule[],
  }
}

/**
 * MiMo 的完整规则集：图片兼容（Chat）+ 两条 Responses 网关限制适配。
 *
 * 三组各自独立、协议互不重叠，因此顺序不影响结果 —— 但 `order` 仍必须与数组下标
 * 严格一致（运行时按 `order` 排序执行，界面按数组渲染，不一致会让两者分叉）。
 *
 * <p>为何只给 MiMo 而不是所有带图片兼容的预设：后两条规则针对的是
 * **MiMo 网关在 Responses 协议上的能力子集**（拒收托管 `web_search`、
 * 拒收 `json_schema`），这是逐次撞出来的实测事实而非通用缺陷。
 * 给 LongCat / MiniMax 等同样被 cc-switch 列入 web_search 黑名单的供应商套用
 * 需要各自验证 —— 未经验证就铺开，等于把猜测写成预设。
 */
function createMimoRuleSet(): RuleSetV2 {
  const base = presetRuleSetV2(MIMO_EXAMPLE_RULESET)
  return {
    version: 2,
    groups: [
      ...base.groups,
      responsesRuleGroup(
        '移除 web_search 工具',
        base.groups.length,
        MIMO_RESPONSES_WEB_SEARCH_RULESET,
        MIMO_RESPONSES_WEB_SEARCH_PREVIEW,
      ),
      responsesRuleGroup(
        'json_schema 降级为 json_object',
        base.groups.length + 1,
        MIMO_RESPONSES_TEXT_FORMAT_RULESET,
        MIMO_RESPONSES_TEXT_FORMAT_PREVIEW,
      ),
    ],
  }
}

/** MiMo 系预设共用的片段（官方直连与 TokenPlan 是同一套网关限制）。 */
const withMimoCompatibility = {
  createRules: createMimoRuleSet,
}

/** 官方直连供应商。 */
export const officialPresets: ProviderPreset[] = [
  { label: 'MiMo', baseUrl: 'https://api.xiaomimimo.com/v1', headers: [], ...withMimoCompatibility },
  { label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', headers: [] },
  { label: 'LongCat', baseUrl: 'https://api.longcat.chat/openai/v1', headers: [], ...withImageCompatibility },
  { label: 'Kimi', baseUrl: 'https://api.moonshot.cn/v1', headers: [], ...withImageCompatibility },
  { label: 'Kimi (CodePlan)', baseUrl: 'https://api.kimi.com/coding/v1', headers: [], ...withImageCompatibility },
  { label: 'Mimo (TokenPlan)', baseUrl: 'https://token-plan-cn.xiaomimimo.com/v1', headers: [], ...withMimoCompatibility },
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
  /** Chat Completions 协议的请求地址。 */
  baseUrl: string
  /** Anthropic Messages 协议的请求地址；预设未单独声明时与 {@link baseUrl} 相同。 */
  anthropicBaseUrl: string
  /** OpenAI Responses 协议的请求地址；预设未单独声明时与 {@link baseUrl} 相同。 */
  responsesBaseUrl: string
  headers: HeaderEntry[]
  editorState: RequestBodyEditorState
}

/**
 * 把预设展开成表单初值。
 *
 * 请求头逐项拷贝、规则集由工厂新造，因此表单编辑不会回写到预设常量上。
 *
 * <p>两个协议专属地址在预设未声明时都回退为 Chat 地址，而不是留空：展开预设的语义是
 * 「把一个已知可用的配置填进表单」，而地址同源正是当前对这些供应商的假设。
 * 留空会产生一个微妙的错误：空字串会触发输入框的联动逻辑，于是用户选了预设后
 * 只要碰一下 Chat 地址框，那一格就会被重写 —— 看上去像预设没生效。
 *
 * <p>Responses 也照抄 Chat 地址，尽管多数中转站没有这个端点：这里填的是「用哪个地址」，
 * 而「用不用」由协议勾选决定。填一个多半打不通的地址不会造成任何请求，
 * 而留空会踩上面那个联动坑。
 */
export function toPresetFormValues(preset: ProviderPreset): PresetFormValues {
  return {
    displayName: preset.label,
    baseUrl: preset.baseUrl,
    anthropicBaseUrl: preset.anthropicBaseUrl ?? preset.baseUrl,
    responsesBaseUrl: preset.responsesBaseUrl ?? preset.baseUrl,
    headers: preset.headers.map(header => ({ ...header })),
    editorState: preset.createRules
      ? { rules: preset.createRules() }
      : createDefaultRequestBodyEditorState(),
  }
}
