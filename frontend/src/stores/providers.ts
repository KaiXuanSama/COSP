import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api'

export interface ProviderModel {
  modelName: string
  enabled: boolean
  contextSize: string
  capsTools: boolean
  capsVision: boolean
  reasoningEffort: string
}

export interface Provider {
  providerKey: string
  enabled: boolean
  baseUrl: string | null
  apiKey: string
  apiFormat: string
  models: ProviderModel[]
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

  async function pullProviderModels(providerKey: string, payload: { baseUrl: string; apiKey: string; modelPullPath?: string }) {
    const res = await http.post(`/providers/${providerKey}/pull-models`, payload)
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

  async function addCustomProvider(displayName: string, customTransforms?: string, baseUrl?: string) {
    const formData = new URLSearchParams()
    formData.append('displayName', displayName)
    if (customTransforms) {
      formData.append('customTransforms', customTransforms)
    }
    if (baseUrl) {
      formData.append('baseUrl', baseUrl)
    }
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

  async function updateCustomProvider(providerKey: string, displayName: string, customTransforms?: string, baseUrl?: string) {
    const formData = new URLSearchParams()
    formData.append('displayName', displayName)
    if (customTransforms) {
      formData.append('customTransforms', customTransforms)
    }
    if (baseUrl !== undefined) {
      formData.append('baseUrl', baseUrl)
    }
    await http.put(`/custom-providers/${providerKey}`, formData.toString(), {
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    })
    // 重新拉取列表
    await fetchAll()
  }

  return { providers, loading, fakeVersion, fetchAll, toggleProvider, saveProviderConfig, pullProviderModels, saveFakeVersion, fetchFakeVersion, addCustomProvider, deleteCustomProvider, updateCustomProvider }
})