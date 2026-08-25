/**
 * 供应商的线路协议开关与两个协议各自的请求地址。
 *
 * <p>本模块只有纯函数与常量，不含 UI 与请求，故 `Settings.vue` 只负责持有 ref
 * 与绑定事件，判断全部落在这里、可单测。
 */

import { ALL_WIRE_PROTOCOLS, isWireProtocol, type WireProtocol } from '@/types/protocol'

/**
 * 新建供应商时默认勾选的协议。
 *
 * <p>与数据库 DDL 默认值一致（两种都勾）。两者相同不是巧合而是同一个乐观假设：
 * 多数中转站的 Anthropic 端点与 OpenAI 同源，勾上后不通至多是上游报错，
 * 而默认不勾会让「明明支持却调不通」变成需要用户自己发现的问题。
 */
export const DEFAULT_NEW_PROVIDER_PROTOCOLS: readonly WireProtocol[] = ['OPENAI', 'ANTHROPIC']

/**
 * 把后端回传的协议集合归一化成前端可用的数组。
 *
 * <p>读不懂时回退为两种都支持，与后端 `ProviderProtocolSupport` 的宽容口径一致 ——
 * 否则会出现「界面上看不到勾选，实际却两条线路都能跑」这种说不通的状态。
 * 但**显式的空数组如实保留**：它是用户主动声明的非法状态，需要在界面上可见才能被改回来。
 */
export function normalizeProtocols(raw: unknown): WireProtocol[] {
  if (!Array.isArray(raw)) {
    return [...DEFAULT_NEW_PROVIDER_PROTOCOLS]
  }
  const seen = new Set<WireProtocol>()
  for (const item of raw) {
    const upper = typeof item === 'string' ? item.trim().toUpperCase() : ''
    if (isWireProtocol(upper)) {
      seen.add(upper)
    }
  }
  // 数组存在但一个都认不出来（元素全是脏值）与「字段读不懂」同类，回退为全集。
  // 真正的空数组在上面的循环里不会产生任何元素，因此要靠原数组长度区分。
  if (seen.size === 0 && raw.length > 0) {
    return [...DEFAULT_NEW_PROVIDER_PROTOCOLS]
  }
  return ALL_WIRE_PROTOCOLS.filter(protocol => seen.has(protocol))
}

/** 勾选/取消某个协议，结果按 `ALL_WIRE_PROTOCOLS` 的固定顺序排列。 */
export function toggleProtocol(
  protocols: readonly WireProtocol[],
  protocol: WireProtocol,
  enabled: boolean,
): WireProtocol[] {
  const next = new Set(protocols)
  if (enabled) {
    next.add(protocol)
  } else {
    next.delete(protocol)
  }
  return ALL_WIRE_PROTOCOLS.filter(item => next.has(item))
}

/** 序列化成后端表单期望的 JSON 字符串数组。 */
export function protocolsToJson(protocols: readonly WireProtocol[]): string {
  return JSON.stringify(ALL_WIRE_PROTOCOLS.filter(protocol => protocols.includes(protocol)))
}

/**
 * 进入 OpenAI 地址输入框时，判断本次编辑是否应联动 Anthropic 地址。
 *
 * <h2>为何要在获得焦点时拍快照，而不是每次输入时判空</h2>
 * 若在每次输入时判断「Anthropic 是否为空」，用户在 OpenAI 框里敲第一个字符后，
 * Anthropic 就被同步成了那一个字符 —— 于是它不再为空，第二个字符便不再同步。
 * 结果是 Anthropic 永远只有一个字母。判断依据必须是**这一轮编辑开始时**的状态，
 * 而那个时刻正是获得焦点。
 */
export function shouldMirrorOnFocus(anthropicBaseUrl: string): boolean {
  return anthropicBaseUrl.trim() === ''
}

/**
 * 计算 OpenAI 地址变化后 Anthropic 地址应有的值。
 *
 * @param openAiBaseUrl     OpenAI 地址的新值
 * @param mirroring         本轮编辑是否处于联动状态（由 {@link shouldMirrorOnFocus} 得出）
 * @param anthropicBaseUrl  Anthropic 地址的当前值
 * @returns 联动时返回与 OpenAI 相同的值，否则原样返回
 */
export function mirrorAnthropicBaseUrl(
  openAiBaseUrl: string,
  mirroring: boolean,
  anthropicBaseUrl: string,
): string {
  return mirroring ? openAiBaseUrl : anthropicBaseUrl
}

/**
 * 推导某个协议的完整请求端点，用于在标题旁展示实际会打到哪里。
 *
 * <p>地址为空时返回空串而不是拼出一个只有路径的 URL：那种半成品在界面上比不显示更让人困惑。
 * 尾斜杠会被去掉，与后端 `normalizeBaseUrl` 的口径一致。
 */
export function describeEndpoint(baseUrl: string, suffix: string): string {
  const trimmed = baseUrl.trim().replace(/\/+$/, '')
  return trimmed === '' ? '' : `${trimmed}${suffix}`
}

/** OpenAI 聊天补全的路径后缀。 */
export const OPENAI_ENDPOINT_SUFFIX = '/chat/completions'

/** Anthropic Messages 的路径后缀。 */
export const ANTHROPIC_ENDPOINT_SUFFIX = '/messages'

/** 拉取模型时选定的线路。 */
export interface ModelPullTarget {
  protocol: WireProtocol
  baseUrl: string
}

/**
 * 决定拉取模型该打哪条线路的哪个地址。
 *
 * <h2>为何固定优先 OpenAI</h2>
 * 两个协议的模型列表端点**路径完全相同**（都是 `GET /v1/models`），只有请求头不同
 * （Anthropic 必须带 `anthropic-version`）。这意味着面对同一个中转站，无法从响应
 * 判断它到底以哪种协议回答了 —— 于是「让用户选从哪条线路拉」这个选项是没有参考价值的，
 * 用户也没有依据去选。固定优先 OpenAI 并在它未启用时才退到 Anthropic，
 * 是唯一不需要用户判断的规则。
 *
 * <p>返回 `null` 表示两个协议都没启用：调用方应提示用户先启用一个，
 * 而不是随便挑一个地址发出去 —— 那会让「没启用协议」这个真正的问题被一个
 * 上游错误掩盖。
 *
 * @param protocols        当前启用的协议集合
 * @param openAiBaseUrl    OpenAI 地址
 * @param anthropicBaseUrl Anthropic 地址；空串表示与 OpenAI 同源
 */
export function resolveModelPullTarget(
  protocols: readonly WireProtocol[],
  openAiBaseUrl: string,
  anthropicBaseUrl: string,
): ModelPullTarget | null {
  if (protocols.includes('OPENAI')) {
    return { protocol: 'OPENAI', baseUrl: openAiBaseUrl.trim() }
  }
  if (protocols.includes('ANTHROPIC')) {
    // 空串回退到 OpenAI 地址，与后端 resolveAnthropicBaseUrl 同口径：
    // 「留空即与 OpenAI 相同」在界面上是一句提示，在这里必须是同一条规则，
    // 否则用户会看到「提示说相同，但拉取报地址为空」。
    const trimmed = anthropicBaseUrl.trim()
    return { protocol: 'ANTHROPIC', baseUrl: trimmed || openAiBaseUrl.trim() }
  }
  return null
}
