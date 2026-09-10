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

  /**
   * 代理开关随新增请求一并提交，不再由前端补一次专项调用。
   *
   * 两次写入若不在同一个请求里，第二次失败会留下「供应商已建但开关没生效」的中间态。
   */
  it('carries useProxy in the add-provider form', async () => {
    const store = useProviderStore()
    vi.mocked(http.post).mockResolvedValue({ data: { providerKey: 'relay' } })
    vi.mocked(http.get).mockResolvedValue({ data: { providers: [] } })

    await store.addProvider('Relay', '[]', 'https://relay.example.com/v1', {
      bodyTemplateKeysJson: '[]',
      bodyPreviewJson: '{}',
      bodyRulesJson: '{"version":2,"groups":[]}',
    }, undefined, true)

    const body = vi.mocked(http.post).mock.calls[0][1] as string
    expect(new URLSearchParams(body).get('useProxy')).toBe('true')
    // 只发一次请求：创建即带开关，没有随后的 /proxy 专项调用。
    expect(vi.mocked(http.post).mock.calls).toHaveLength(1)
  })

  it('carries useProxy in the update-provider form', async () => {
    const store = useProviderStore()
    vi.mocked(http.put).mockResolvedValue({ data: { ok: true } })
    vi.mocked(http.get).mockResolvedValue({ data: { providers: [] } })

    await store.updateProvider('relay', 'Relay', '[]', 'https://relay.example.com/v1', {
      bodyTemplateKeysJson: '[]',
      bodyPreviewJson: '{}',
      bodyRulesJson: '{"version":2,"groups":[]}',
    }, undefined, false)

    const body = vi.mocked(http.put).mock.calls[0][1] as string
    expect(new URLSearchParams(body).get('useProxy')).toBe('false')
  })

  /**
   * 不传 useProxy 时该字段完全不出现 —— 后端据此保留原值。
   *
   * 若发成空串或 'false'，任何一条不关心代理的保存路径都会把用户已开的代理静默关掉。
   */
  it('omits useProxy entirely when the caller does not supply it', async () => {
    const store = useProviderStore()
    vi.mocked(http.put).mockResolvedValue({ data: { ok: true } })
    vi.mocked(http.get).mockResolvedValue({ data: { providers: [] } })

    await store.updateProvider('relay', 'Relay', '[]', 'https://relay.example.com/v1', {
      bodyTemplateKeysJson: '[]',
      bodyPreviewJson: '{}',
      bodyRulesJson: '{"version":2,"groups":[]}',
    })

    const body = vi.mocked(http.put).mock.calls[0][1] as string
    expect(new URLSearchParams(body).has('useProxy')).toBe(false)
  })
})