import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api'

export interface ProviderModel {
  modelName: string
  enabled: boolean
  contextSize: string
  maxOutputTokens: string
  capsTools: boolean
  capsVision: boolean
  reasoningEffort: string
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
  baseUrl: string | null
  apiFormat: string
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

export interface CustomProviderRequestTransformInput {
  bodyTemplateKeysJson: string
  bodyPreviewJson: string
  bodyRulesJson: string
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

  async function fetchFakeVersion() {
    try {
      const res = await http.get('/fake-version')
      fakeVersion.value = res.data.fakeVersion || ''
    } catch {
      // ignore
    }
  }

  // ==================== 自定义供应商 ====================

  async function addCustomProvider(displayName: string, headerRulesJson: string,
                                   baseUrl: string, requestTransform: CustomProviderRequestTransformInput) {
    const formData = new URLSearchParams()
    formData.append('displayName', displayName)
    formData.append('headerRulesJson', headerRulesJson)
    formData.append('baseUrl', baseUrl)
    formData.append('bodyTemplateKeysJson', requestTransform.bodyTemplateKeysJson)
    formData.append('bodyPreviewJson', requestTransform.bodyPreviewJson)
    formData.append('bodyRulesJson', requestTransform.bodyRulesJson)
    const res = await http.post('/custom-providers', formData.toString(), {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    })
    // 重新拉取列表
    await fetchAll()
    return res.data
  }

  async function deleteCustomProvider(providerKey: string) {
    await http.delete(`/custom-providers/${providerKey}`)
    // 重新拉取列表
    await fetchAll()
  }

  async function updateCustomProvider(providerKey: string, displayName: string,
                                      headerRulesJson: string, baseUrl: string,
                                      requestTransform: CustomProviderRequestTransformInput) {
    const formData = new URLSearchParams()
    formData.append('displayName', displayName)
    formData.append('headerRulesJson', headerRulesJson)
    formData.append('baseUrl', baseUrl)
    formData.append('bodyTemplateKeysJson', requestTransform.bodyTemplateKeysJson)
    formData.append('bodyPreviewJson', requestTransform.bodyPreviewJson)
    formData.append('bodyRulesJson', requestTransform.bodyRulesJson)
    await http.put(`/custom-providers/${providerKey}`, formData.toString(), {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    })
    // 重新拉取列表
    await fetchAll()
  }

  return { providers, loading, fakeVersion, fetchAll, toggleProvider, saveProviderConfig, pullProviderModels, saveFakeVersion, fetchFakeVersion, addCustomProvider, deleteCustomProvider, updateCustomProvider }
})