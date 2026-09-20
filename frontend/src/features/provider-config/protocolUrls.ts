/**
 * 供应商的线路协议开关与各协议的请求地址。
 *
 * <p>本模块只有纯函数与常量，不含 UI 与请求，故 `Settings.vue` 只负责持有 ref
 * 与绑定事件，判断全部落在这里、可单测。
 */

import { ALL_WIRE_PROTOCOLS, isWireProtocol, type WireProtocol } from '@/types/protocol'

/**
 * 新建供应商时默认勾选的协议。
 *
 * <p>与数据库 DDL 默认值一致（三条全勾）。两者相同不是巧合而是同一个产品决定：
 * 多数中转站的其它端点与 Chat 同源，勾上后不通至多是上游报错，用户能**感知**到并
 * 取消勾选；而默认不勾会让「明明支持却调不通」变成需要用户自己想到去勾的隐藏状态。
 * 可感知的失败优于沉默的不可用。
 *
 * <p>后端有三处同源口径（`schema.sql` 的 DEFAULT、V13 迁移的回填、
 * `ProviderProtocolSupport` 的解析回退），本常量是它们在前端的对应物。
 *
 * <p>**从 {@link ALL_WIRE_PROTOCOLS} 派生而非硬编码**：它的定义就是「全部」，
 * 而硬编码一份会在协议顺序或成员变化时漏改 —— 那个漏改还会连带影响
 * `normalizeProtocols` 的脏数据回退（它用的是本常量）。
 */
export const DEFAULT_NEW_PROVIDER_PROTOCOLS: readonly WireProtocol[] = [...ALL_WIRE_PROTOCOLS]

/**
 * 把后端回传的协议集合归一化成前端可用的数组。
 *
 * <p>读不懂时回退为全部都支持，与后端 `ProviderProtocolSupport` 的宽容口径一致 ——
 * 否则会出现「界面上看不到勾选，实际却那条线路能跑」这种说不通的状态。
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
 * 进入 Chat 地址输入框时，判断本次编辑是否应联动某个协议专属地址。
 *
 * <h2>为何要在获得焦点时拍快照，而不是每次输入时判空</h2>
 * 若在每次输入时判断「目标地址是否为空」，用户在 Chat 框里敲第一个字符后，
 * 目标就被同步成了那一个字符 —— 于是它不再为空，第二个字符便不再同步。
 * 结果是目标地址永远只有一个字母。判断依据必须是**这一轮编辑开始时**的状态，
 * 而那个时刻正是获得焦点。
 *
 * <p>每个被联动的地址各需一次判断（各自可能有值也可能为空），因此调用方要为
 * 每条线路持有独立的联动状态。
 */
export function shouldMirrorOnFocus(protocolBaseUrl: string): boolean {
  return protocolBaseUrl.trim() === ''
}

/**
 * 计算 Chat 地址变化后某个协议专属地址应有的值。
 *
 * <p>原名 `mirrorAnthropicBaseUrl`。加入 Responses 后联动从「一对一」变成「一对多」，
 * 而函数体本身与目标是哪条线路无关，因此只改名不改实现 —— 留着协议专属的名字会让
 * 调用方以为需要为每条线路再写一份。
 *
 * @param chatBaseUrl      Chat 地址的新值（联动的源）
 * @param mirroring        本轮编辑是否处于联动状态（由 {@link shouldMirrorOnFocus} 得出）
 * @param protocolBaseUrl  被联动的协议专属地址的当前值
 * @returns 联动时返回与 Chat 相同的值，否则原样返回
 */
export function mirrorBaseUrl(
  chatBaseUrl: string,
  mirroring: boolean,
  protocolBaseUrl: string,
): string {
  return mirroring ? chatBaseUrl : protocolBaseUrl
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

/** Chat Completions 的路径后缀。 */
export const CHAT_ENDPOINT_SUFFIX = '/chat/completions'

/** Responses 的路径后缀。 */
export const RESPONSES_ENDPOINT_SUFFIX = '/responses'

/** Anthropic Messages 的路径后缀。 */
export const MESSAGES_ENDPOINT_SUFFIX = '/messages'

/** 协议 → 端点路径后缀，供预览文案与端点提示统一取用。 */
export const WIRE_PROTOCOL_ENDPOINT_SUFFIXES: Record<WireProtocol, string> = {
  CHAT: CHAT_ENDPOINT_SUFFIX,
  RESPONSES: RESPONSES_ENDPOINT_SUFFIX,
  MESSAGES: MESSAGES_ENDPOINT_SUFFIX,
}

/**
 * 决定折叠时哪个协议的地址显示在第一行。
 *
 * <p>按 {@link ALL_WIRE_PROTOCOLS} 的顺序取**第一个已启用**的协议 ——
 * 折叠状态下只看得见第一行，若它恒为 Chat，一个只走 Messages 的供应商展开前
 * 看到的是一个自己禁用了的地址，等于没有信息。
 *
 * <p>一个都没启用时仍返回第一个协议，不留「无行可显」的状态：那时用户需要看到
 * 一行输入框才能开始配置。
 *
 * <p><strong>调用方应在打开面板时取一次快照，而不是做成随勾选变化的 computed。</strong>
 * 顺序实时重排会让「取消勾选 Chat」的瞬间几行交换位置，用户正在编辑的输入框
 * 跳到另一行去 —— 而这个跳动没有任何信息价值，纯粹是布局规则的副作用。
 */
export function resolvePrimaryProtocol(protocols: readonly WireProtocol[]): WireProtocol {
  return ALL_WIRE_PROTOCOLS.find(protocol => protocols.includes(protocol)) ?? ALL_WIRE_PROTOCOLS[0]
}

/**
 * 按首行协议排出全部行的显示顺序。
 *
 * <p>首行是传入的那个，其余按 {@link ALL_WIRE_PROTOCOLS} 的固定顺序跟在后面。
 * 返回的数组**总是包含全部协议** —— 未勾选的协议也要有输入行，否则用户没法
 * 在勾选之前先填好地址。
 */
export function orderProtocolRows(primary: WireProtocol): WireProtocol[] {
  return [primary, ...ALL_WIRE_PROTOCOLS.filter(protocol => protocol !== primary)]
}

/** 拉取模型时选定的线路。 */
export interface ModelPullTarget {
  protocol: WireProtocol
  baseUrl: string
}

/**
 * 决定拉取模型该打哪条线路的哪个地址。
 *
 * <h2>为何不让用户选线路</h2>
 * 三个协议的模型列表端点**路径完全相同**（都是 `GET /v1/models`），只有请求头不同
 * （Messages 必须带 `anthropic-version`，另两条不带）。这意味着面对同一个中转站，
 * 无法从响应判断它到底以哪种协议回答了 —— 于是「让用户选从哪条线路拉」这个选项
 * 没有参考价值，用户也没有依据去选。按固定优先级取第一个已启用的，
 * 是唯一不需要用户判断的规则。
 *
 * <h2>优先级：CHAT → RESPONSES → MESSAGES</h2>
 * Responses 排在 Messages 之前，因为它与 Chat **同为 OpenAI 系**：同一个
 * `Authorization: Bearer`、不需要 `anthropic-version`。因此当 Chat 未启用时，
 * 退到 Responses 比退到 Messages 少一处请求头差异。
 *
 * <p>这个顺序与 {@link ALL_WIRE_PROTOCOLS}（`CHAT, MESSAGES, RESPONSES`）**不同**，
 * 而这个差异本身就是它必须独立声明的证据：那个顺序表达「翻译回退优先级」，
 * 本顺序表达「请求头相似度」。拉取模型不经任何翻译，因此不该受翻译优先级影响。
 *
 * <p>返回 `null` 表示一个协议都没启用：调用方应提示用户先启用一个，
 * 而不是随便挑一个地址发出去 —— 那会让「没启用协议」这个真正的问题被一个
 * 上游错误掩盖。
 *
 * @param protocols  当前启用的协议集合
 * @param baseUrls   各协议的地址；协议专属地址为空串表示与 Chat 同源
 */
export function resolveModelPullTarget(
  protocols: readonly WireProtocol[],
  baseUrls: Record<WireProtocol, string>,
): ModelPullTarget | null {
  const chatBaseUrl = baseUrls.CHAT.trim()
  for (const protocol of MODEL_PULL_PRIORITY) {
    if (!protocols.includes(protocol)) {
      continue
    }
    if (protocol === 'CHAT') {
      return { protocol, baseUrl: chatBaseUrl }
    }
    // 空串回退到 Chat 地址，与后端 resolveAnthropicBaseUrl / resolveResponsesBaseUrl
    // 同口径：「留空即与 Chat 相同」在界面上是一句提示，在这里必须是同一条规则，
    // 否则用户会看到「提示说相同，但拉取报地址为空」。
    return { protocol, baseUrl: baseUrls[protocol].trim() || chatBaseUrl }
  }
  return null
}

/**
 * 拉取模型的线路优先级，按**请求头相似度**排列。
 *
 * <p>见 {@link resolveModelPullTarget} 的说明：Chat 与 Responses 同为 OpenAI 系
 * （同一个 `Authorization: Bearer`、不带 `anthropic-version`），因此 Responses
 * 排在 Messages 之前。
 *
 * <p><strong>刻意与 {@link ALL_WIRE_PROTOCOLS} 的 `CHAT, MESSAGES, RESPONSES` 不同。</strong>
 * 那个是翻译回退优先级，而拉取模型不经任何翻译 —— 它只是拿同一个
 * `GET /v1/models` 去问上游，差别仅在请求头。把两者统一会让「某个协议的请求头变了」
 * 与「某个翻译方向被实现了」这两件毫不相干的事互相牵连。
 */
const MODEL_PULL_PRIORITY: readonly WireProtocol[] = ['CHAT', 'RESPONSES', 'MESSAGES']
