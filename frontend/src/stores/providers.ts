import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api'
import type { WireProtocol } from '@/types/protocol'

export interface ProviderModel {
  modelName: string
  enabled: boolean
  contextSize: string
  maxOutputTokens: string
  capsTools: boolean
  capsVision: boolean
  reasoningEffort: string
  /** Anthropic 思考方式（形态 + 注入模式）的 JSON 原文。 */
  thinkingMode: string
  /** 思考预算；`-1` 表示未设置。后端以数字返回。 */
  thinkingBudgetTokens: number
}

export interface ApiKeyEntry {
  keyUuid?: string
  name: string
  // 后端返回的脱敏值，仅用于展示；新增或修改时使用 apiKey 字段承载明文
  masked?: string
  // 编辑时用户输入的新明文；未修改时为空
  apiKey?: string
  active?: boolean
}

export interface Provider {
  id?: number
  providerKey: string
  displayName: string
  enabled: boolean
  /** 该供应商的出站请求是否使用全局代理地址。 */
  useProxy: boolean
  /** OpenAI 协议的请求地址。 */
  baseUrl: string | null
  /**
   * 该供应商声明支持的线路协议。
   *
   * 后端以**数组**返回（不是 JSON 字符串），可直接绑控件。旧后端或读不懂时可能缺失，
   * 由 `normalizeProtocols` 回退为两种都支持。
   */
  supportedProtocols?: WireProtocol[]
  /** Anthropic 协议的独立地址；空串表示回退到 {@link baseUrl}。 */
  anthropicBaseUrl?: string
  requestTransform?: ProviderRequestTransform
  apiKeys: ApiKeyEntry[]
  models: ProviderModel[]
}

export interface ProviderRequestTransform {
  headerRulesVersion: number
  headerRulesJson: string
  bodyTemplateKeysJson: string
  bodyPreviewJson: string
  bodyRulesVersion: number
  bodyRulesJson: string
}

export interface ProviderRequestTransformInput {
  bodyTemplateKeysJson: string
  bodyPreviewJson: string
  bodyRulesJson: string
}

/** 协议配置的提交载荷。 */
export interface ProviderProtocolInput {
  /** JSON 字符串数组，如 `["OPENAI"]`。 */
  supportedProtocolsJson: string
  /** Anthropic 独立地址；空串表示回退到 OpenAI 地址。 */
  anthropicBaseUrl: string
}

/**
 * 把协议配置写进表单；未传时一个字段都不发。
 *
 * <p>后端把「字段未出现」视为保留原值，因此不传 = 不改。绝不能为了「字段齐全」
 * 而发空串—— 空串的 `anthropicBaseUrl` 会被当成「清空，回退到 base_url」，那是真实的修改。
 */
function appendProtocolFields(formData: URLSearchParams, protocols?: ProviderProtocolInput) {
  if (!protocols) return
  formData.append('supportedProtocolsJson', protocols.supportedProtocolsJson)
  formData.append('anthropicBaseUrl', protocols.anthropicBaseUrl)
}

export interface GatewayAuthStatus {
  enabled: boolean
  maskedKey: string
  configured: boolean
}

export interface RetryPolicyView {
  maxAttempts: number
  defaultValue: number
  maxConfigurable: number
}

export interface GeneratedGatewayKey {
  apiKey: string
  maskedKey: string
}

export interface RuntimeConfigView {
  fakeVersion: string
  gatewayAuth: GatewayAuthStatus
  retryPolicy: RetryPolicyView
  /** 出站代理地址，形如 host:port；空串表示未配置代理。 */
  upstreamProxyAddress: string
}

export const useProviderStore = defineStore('providers', () => {
  const providers = ref<Record<string, Provider>>({})
  const loading = ref(false)
  const fakeVersion = ref('')

  async function fetchAll() {
    loading.value = true
    try {
      const res = await http.get('/providers')
      providers.value = res.data
    } catch {
      // will be loaded from settings page data
    } finally {
      loading.value = false
    }
  }

  async function toggleProvider(providerKey: string, enabled: boolean) {
    await http.post(`/providers/${providerKey}/toggle`, { enabled })
    if (providers.value[providerKey]) {
      providers.value[providerKey].enabled = enabled
    } else {
      // 本地尚无此 provider 记录，重新拉取全量
      await fetchAll()
    }
  }

  async function toggleProviderProxy(providerKey: string, useProxy: boolean) {
    await http.post(`/providers/${providerKey}/proxy`, { useProxy })
    if (providers.value[providerKey]) {
      providers.value[providerKey].useProxy = useProxy
    } else {
      // 改名后的 key 尚未进入本地列表时，以后端全量结果为准。
      await fetchAll()
    }
  }

  async function saveProviderConfig(providerKey: string, params: Record<string, string>) {
    const formData = new URLSearchParams()
    for (const [k, v] of Object.entries(params)) {
      formData.append(k, v)
    }
    await http.post(`/providers/${providerKey}/config`, formData.toString(), {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    })
  }

  async function pullProviderModels(providerKey: string, payload: Record<string, string>) {
   const res = await http.post(`/providers/${providerKey}/pull-models`, payload, {
   skipAuthRedirect: true as any,
   } as any)
    return res.data
  }

  async function saveFakeVersion(version: string) {
    await http.post('/fake-version', null, { params: { fakeVersion: version } })
    fakeVersion.value = version
  }

  // 聚合读取全部运行时配置（伪造版本号 + 下游鉴权状态 + 重试策略 + 出站代理），一次调用拿全部显示信息。
  // 敏感值已在后端脱敏；明文 Key 仍只经 reveal / regenerate 按需获取。
  async function fetchRuntimeConfig(): Promise<RuntimeConfigView> {
    const res = await http.get('/runtime-config')
    fakeVersion.value = res.data.fakeVersion || ''
    const auth = res.data.gatewayAuth || {}
    const retry = res.data.retryPolicy || {}
    return {
      fakeVersion: res.data.fakeVersion || '',
      gatewayAuth: {
        enabled: !!auth.enabled,
        maskedKey: auth.maskedKey || '',
        configured: !!auth.configured,
      },
      retryPolicy: {
        maxAttempts: retry.maxAttempts ?? 5,
        defaultValue: retry.defaultValue ?? 5,
        maxConfigurable: retry.maxConfigurable ?? 100,
      },
      upstreamProxyAddress: typeof res.data.upstreamProxyAddress === 'string'
        ? res.data.upstreamProxyAddress
        : '',
    }
  }

  async function saveRetryMaxAttempts(maxAttempts: number) {
    await http.post('/retry-policy', null, { params: { maxAttempts } })
  }

  /** 专项写入出站代理地址；与其它运行时配置一样统一使用 query string。 */
  async function saveProxyAddress(address: string) {
    await http.post('/proxy-address', null, { params: { address } })
  }

  // ==================== 下游鉴权（网关 API Key）====================

  // 切换开关
  async function setGatewayAuthEnabled(enabled: boolean) {
    await http.post('/gateway-auth/toggle', null, { params: { enabled } })
  }

  // 读取明文 Key（供复制到剪贴板）
  async function revealGatewayApiKey(): Promise<string> {
    const res = await http.get('/gateway-auth/reveal')
    return res.data.apiKey || ''
  }

  // 重新生成 Key，返回明文与脱敏值（一次性显示 + 复制）
  async function regenerateGatewayApiKey(): Promise<GeneratedGatewayKey> {
    const res = await http.post('/gateway-auth/regenerate')
    return {
      apiKey: res.data.apiKey || '',
      maskedKey: res.data.maskedKey || '',
    }
  }

  // ==================== 供应商创建与重命名 ====================

  async function addProvider(displayName: string, headerRulesJson: string,
                                   baseUrl: string, requestTransform: ProviderRequestTransformInput,
                                   protocols?: ProviderProtocolInput) {
    const formData = new URLSearchParams()
    formData.append('displayName', displayName)
    formData.append('headerRulesJson', headerRulesJson)
    formData.append('baseUrl', baseUrl)
    formData.append('bodyTemplateKeysJson', requestTransform.bodyTemplateKeysJson)
    formData.append('bodyPreviewJson', requestTransform.bodyPreviewJson)
    formData.append('bodyRulesJson', requestTransform.bodyRulesJson)
    appendProtocolFields(formData, protocols)
    const res = await http.post('/providers', formData.toString(), {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    })
    // 重新拉取列表
    await fetchAll()
    return res.data
  }

  async function deleteProvider(providerKey: string) {
    await http.delete(`/providers/${providerKey}`)
    // 重新拉取列表
    await fetchAll()
  }

  async function updateProvider(providerKey: string, displayName: string,
                                      headerRulesJson: string, baseUrl: string,
                                      requestTransform: ProviderRequestTransformInput,
                                      protocols?: ProviderProtocolInput) {
    const formData = new URLSearchParams()
    formData.append('displayName', displayName)
    formData.append('headerRulesJson', headerRulesJson)
    formData.append('baseUrl', baseUrl)
    formData.append('bodyTemplateKeysJson', requestTransform.bodyTemplateKeysJson)
    formData.append('bodyPreviewJson', requestTransform.bodyPreviewJson)
    formData.append('bodyRulesJson', requestTransform.bodyRulesJson)
    appendProtocolFields(formData, protocols)
    await http.put(`/providers/${providerKey}`, formData.toString(), {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    })
    // 重新拉取列表
    await fetchAll()
  }

  return { providers, loading, fakeVersion, fetchAll, toggleProvider, toggleProviderProxy, saveProviderConfig, pullProviderModels, saveFakeVersion, fetchRuntimeConfig, saveRetryMaxAttempts, saveProxyAddress, setGatewayAuthEnabled, revealGatewayApiKey, regenerateGatewayApiKey, addProvider, deleteProvider, updateProvider }
})