import { afterEach, describe, expect, it, vi } from 'vitest'
import { createPreviewScheduler, type PreviewInput, type PreviewResult } from './preview'

function input(marker: string): PreviewInput {
  return { previewBody: { marker }, rules: [] }
}

function ok(marker: string): PreviewResult {
  return { output: { marker }, warnings: [] }
}

/** 手动可控的请求函数：记录调用并按序号 resolve。 */
function deferredRequester() {
  const calls: PreviewInput[] = []
  const resolvers: Array<(value: PreviewResult) => void> = []
  const rejecters: Array<(reason: unknown) => void> = []
  const requester = (value: PreviewInput) => {
    calls.push(value)
    return new Promise<PreviewResult>((resolve, reject) => {
      resolvers.push(resolve)
      rejecters.push(reject)
    })
  }
  return { calls, resolvers, rejecters, requester }
}

describe('请求体规则预览调度', () => {
  afterEach(() => vi.useRealTimers())
  it('防抖窗口内只发最后一次请求', async () => {
    vi.useFakeTimers()
    const { calls, requester } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 200)

    scheduler.schedule(input('a'))
    scheduler.schedule(input('b'))
    scheduler.schedule(input('c'))
    await vi.advanceTimersByTimeAsync(200)

    expect(calls).toHaveLength(1)
    expect(calls[0]!.previewBody).toEqual({ marker: 'c' })
    vi.useRealTimers()
  })

  it('排入请求后立即标记结果过期', async () => {
    vi.useFakeTimers()
    const { resolvers, requester } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 100)

    scheduler.schedule(input('a'))
    await vi.advanceTimersByTimeAsync(100)
    resolvers[0]!(ok('a'))
    await vi.advanceTimersByTimeAsync(0)
    expect(scheduler.stale.value).toBe(false)

    scheduler.schedule(input('b'))
    expect(scheduler.stale.value).toBe(true)
    vi.useRealTimers()
  })

  it('后发先至时不采纳过期响应', async () => {
    vi.useFakeTimers()
    const { resolvers, requester } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 0)

    scheduler.schedule(input('first'))
    await vi.advanceTimersByTimeAsync(0)
    scheduler.schedule(input('second'))
    await vi.advanceTimersByTimeAsync(0)

    // 第二次先返回，第一次后返回 —— 最终结果必须是第二次的。
    resolvers[1]!(ok('second'))
    await vi.advanceTimersByTimeAsync(0)
    resolvers[0]!(ok('first'))
    await vi.advanceTimersByTimeAsync(0)

    expect(scheduler.result.value?.output).toEqual({ marker: 'second' })
    vi.useRealTimers()
  })

  it('过期请求结束时不清掉 loading', async () => {
    vi.useFakeTimers()
    const { resolvers, requester } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 0)

    scheduler.schedule(input('first'))
    await vi.advanceTimersByTimeAsync(0)
    scheduler.schedule(input('second'))
    await vi.advanceTimersByTimeAsync(0)

    resolvers[0]!(ok('first'))
    await vi.advanceTimersByTimeAsync(0)

    // 第二次仍在途，loading 必须保持为 true。
    expect(scheduler.loading.value).toBe(true)

    resolvers[1]!(ok('second'))
    await vi.advanceTimersByTimeAsync(0)
    expect(scheduler.loading.value).toBe(false)
    vi.useRealTimers()
  })

  it('失败时保留上次结果并置为过期', async () => {
    vi.useFakeTimers()
    const { resolvers, rejecters, requester } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 0)

    scheduler.schedule(input('good'))
    await vi.advanceTimersByTimeAsync(0)
    resolvers[0]!(ok('good'))
    await vi.advanceTimersByTimeAsync(0)

    scheduler.schedule(input('bad'))
    await vi.advanceTimersByTimeAsync(0)
    rejecters[1]!(new Error('网络错误'))
    await vi.advanceTimersByTimeAsync(0)

    expect(scheduler.result.value?.output).toEqual({ marker: 'good' })
    expect(scheduler.error.value).toBe('网络错误')
    expect(scheduler.stale.value).toBe(true)
    vi.useRealTimers()
  })

  it('成功后清空错误信息', async () => {
    vi.useFakeTimers()
    const { resolvers, rejecters, requester } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 0)

    scheduler.schedule(input('bad'))
    await vi.advanceTimersByTimeAsync(0)
    rejecters[0]!(new Error('boom'))
    await vi.advanceTimersByTimeAsync(0)
    expect(scheduler.error.value).toBe('boom')

    scheduler.schedule(input('good'))
    await vi.advanceTimersByTimeAsync(0)
    resolvers[1]!(ok('good'))
    await vi.advanceTimersByTimeAsync(0)

    expect(scheduler.error.value).toBe('')
    expect(scheduler.stale.value).toBe(false)
    vi.useRealTimers()
  })

  it('dispose 后不再发出已排队的请求', async () => {
    vi.useFakeTimers()
    const { calls, requester } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 100)

    scheduler.schedule(input('a'))
    scheduler.dispose()
    await vi.advanceTimersByTimeAsync(200)

    expect(calls).toHaveLength(0)
    vi.useRealTimers()
  })

  it('dispose 后在途响应不再写入状态', async () => {
    vi.useFakeTimers()
    const { resolvers, requester } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 0)

    scheduler.schedule(input('a'))
    await vi.advanceTimersByTimeAsync(0)
    scheduler.dispose()
    resolvers[0]!(ok('a'))
    await vi.advanceTimersByTimeAsync(0)

    expect(scheduler.result.value).toBeNull()
    vi.useRealTimers()
  })

  it('新请求仍在防抖期间，旧响应也不能成为当前成功快照', async () => {
    vi.useFakeTimers()
    const { requester, resolvers } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 100)
    scheduler.schedule(input('old'))
    await vi.advanceTimersByTimeAsync(100)
    scheduler.schedule(input('new'))
    resolvers[0]!(ok('old'))
    await Promise.resolve()
    expect(scheduler.snapshot.value).toBeNull()
    expect(scheduler.stale.value).toBe(true)
    await vi.advanceTimersByTimeAsync(100)
    resolvers[1]!(ok('new'))
    await Promise.resolve()
    expect(scheduler.snapshot.value?.input.previewBody).toEqual({ marker: 'new' })
    expect(scheduler.snapshot.value?.result.output).toEqual({ marker: 'new' })
  })

  it('成功快照保存发起时的输入文本和数据，不受宿主后续修改影响', async () => {
    vi.useFakeTimers()
    const { requester, resolvers } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 0)
    const payload = { ...input('first'), sourceText: '{\n "marker": "first"\n}' }
    scheduler.schedule(payload)
    payload.previewBody.marker = 'mutated'
    payload.sourceText = '{}'
    await vi.advanceTimersByTimeAsync(0)
    resolvers[0]!(ok('first'))
    await Promise.resolve()
    expect(scheduler.snapshot.value?.input.previewBody.marker).toBe('first')
    expect(scheduler.snapshot.value?.input.sourceText).toContain('first')
  })

  it('无效输入使排队和在途请求作废，保留已有成功结果并标记过期', async () => {
    vi.useFakeTimers()
    const { requester, resolvers, calls } = deferredRequester()
    const scheduler = createPreviewScheduler(requester, 0)
    scheduler.schedule(input('good'))
    await vi.advanceTimersByTimeAsync(0)
    resolvers[0]!(ok('good'))
    await Promise.resolve()
    scheduler.schedule(input('pending'))
    await vi.advanceTimersByTimeAsync(0)
    scheduler.invalidate()
    resolvers[1]!(ok('pending'))
    await Promise.resolve()
    expect(scheduler.snapshot.value?.result.output).toEqual({ marker: 'good' })
    expect(scheduler.loading.value).toBe(false)
    expect(scheduler.stale.value).toBe(true)
    scheduler.schedule(input('cancelled'))
    scheduler.invalidate()
    await vi.advanceTimersByTimeAsync(100)
    expect(calls).toHaveLength(2)
  })
})
