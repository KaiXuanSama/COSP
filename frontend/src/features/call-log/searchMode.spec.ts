import { describe, expect, it } from 'vitest'
import { createLogListSession } from './searchMode'
import type { LogQueryParams } from './searchFilter'

/** 可由测试控制完成时机的请求。 */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const PARAMS_A: LogQueryParams = { status: ['5xx'] }
const PARAMS_B: LogQueryParams = { provider: 'stepfun', providerMatch: 'exact' }

describe('createLogListSession', () => {
  it('初始为实时模式，接受 SSE 信号', () => {
    const session = createLogListSession()
    expect(session.mode).toBe('live')
    expect(session.params).toBeUndefined()
    expect(session.acceptsLiveSignal()).toBe(true)
  })

  it('检索模式丢弃 SSE 信号，退出后恢复', () => {
    const session = createLogListSession()
    session.enterSearch(PARAMS_A)
    expect(session.mode).toBe('search')
    expect(session.acceptsLiveSignal()).toBe(false)
    session.exitSearch()
    expect(session.mode).toBe('live')
    expect(session.params).toBeUndefined()
    expect(session.acceptsLiveSignal()).toBe(true)
  })

  it('每次换列表都推进代次', () => {
    const session = createLogListSession()
    const start = session.generation
    session.enterSearch(PARAMS_A)
    session.enterSearch(PARAMS_B)
    session.invalidate()
    session.exitSearch()
    expect(session.generation).toBe(start + 4)
  })

  it('同一代内的请求正常落地，并收到发起时的参数', async () => {
    const session = createLogListSession()
    session.enterSearch(PARAMS_A)
    const applied: string[] = []
    let seen: LogQueryParams | undefined
    const ok = await session.run(
      async (params) => {
        seen = params
        return 'page'
      },
      (r) => applied.push(r),
    )
    expect(ok).toBe(true)
    expect(seen).toEqual(PARAMS_A)
    expect(applied).toEqual(['page'])
  })

  it('竞态 1：实时增量同步在途时进入检索，结果被丢弃', async () => {
    const session = createLogListSession()
    const sync = deferred<string>()
    const applied: string[] = []
    let seen: LogQueryParams | undefined = PARAMS_B
    const pending = session.run(
      (params) => {
        seen = params
        return sync.promise
      },
      (r) => applied.push(r),
    )
    session.enterSearch(PARAMS_A)
    sync.resolve('latest-unfiltered')
    expect(await pending).toBe(false)
    expect(applied).toEqual([])
    // 发起时是实时模式，用的是无条件参数，不会被后来的条件污染。
    expect(seen).toBeUndefined()
  })

  it('竞态 2：实时模式翻页在途时进入检索，下一页不会追加到检索结果', async () => {
    const session = createLogListSession()
    const more = deferred<string>()
    const applied: string[] = []
    const pending = session.run(() => more.promise, (r) => applied.push(r))
    session.enterSearch(PARAMS_A)
    await session.run(async () => 'search-first', (r) => applied.push(r))
    more.resolve('live-next-page')
    expect(await pending).toBe(false)
    expect(applied).toEqual(['search-first'])
  })

  it('竞态 3：连续两次检索，先发的后返回不覆盖新结果', async () => {
    const session = createLogListSession()
    const first = deferred<string>()
    const second = deferred<string>()
    const applied: string[] = []
    session.enterSearch(PARAMS_A)
    const p1 = session.run(() => first.promise, (r) => applied.push(r))
    session.enterSearch(PARAMS_B)
    const p2 = session.run(() => second.promise, (r) => applied.push(r))
    second.resolve('B')
    first.resolve('A')
    expect(await p2).toBe(true)
    expect(await p1).toBe(false)
    expect(applied).toEqual(['B'])
  })

  it('竞态 4：退出检索时检索翻页仍在途，不会混入实时列表', async () => {
    const session = createLogListSession()
    session.enterSearch(PARAMS_A)
    const more = deferred<string>()
    const applied: string[] = []
    const pending = session.run(() => more.promise, (r) => applied.push(r))
    session.exitSearch()
    more.resolve('search-next-page')
    expect(await pending).toBe(false)
    expect(applied).toEqual([])
  })

  it('手动刷新作废旧列表的翻页', async () => {
    const session = createLogListSession()
    const more = deferred<string>()
    const applied: string[] = []
    const pending = session.run(() => more.promise, (r) => applied.push(r))
    session.invalidate()
    more.resolve('stale')
    expect(await pending).toBe(false)
    expect(applied).toEqual([])
  })

  it('当代请求失败照常抛出', async () => {
    const session = createLogListSession()
    await expect(session.run(() => Promise.reject(new Error('boom')), () => {})).rejects.toThrow('boom')
  })

  it('过期请求的失败被吞掉', async () => {
    const session = createLogListSession()
    const req = deferred<string>()
    const pending = session.run(() => req.promise, () => {})
    session.enterSearch(PARAMS_A)
    req.reject(new Error('stale failure'))
    await expect(pending).resolves.toBe(false)
  })
})
