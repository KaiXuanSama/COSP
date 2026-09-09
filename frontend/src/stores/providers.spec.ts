import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import http from '@/api'
import { useProviderStore } from './providers'

vi.mock('@/api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

describe('provider proxy state', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('uses the dedicated endpoint and updates the local provider state', async () => {
    const store = useProviderStore()
    store.providers.relay = {
      providerKey: 'relay',
      displayName: 'Relay',
      enabled: true,
      useProxy: false,
      baseUrl: 'https://relay.example.com/v1',
      apiKeys: [],
      models: [],
    }
    vi.mocked(http.post).mockResolvedValue({ data: { useProxy: true } })

    await store.toggleProviderProxy('relay', true)

    expect(http.post).toHaveBeenCalledWith('/providers/relay/proxy', { useProxy: true })
    expect(store.providers.relay.useProxy).toBe(true)
  })
})