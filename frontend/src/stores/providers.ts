import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api'

export interface ProviderModel {
  modelName: string
  enabled: boolean
  contextSize: string
  capsTools: boolean
  capsVision: boolean
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

  return { providers, loading, fakeVersion, fetchAll, toggleProvider, saveProviderConfig, pullProviderModels, saveFakeVersion, fetchFakeVersion }
})