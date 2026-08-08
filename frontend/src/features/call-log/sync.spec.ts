import { describe, expect, it, vi } from 'vitest'
import { createCoalescingSync } from './sync'

/** 造一个可由测试精确控制完成时机的任务。 */
function deferredTask() {
  const resolvers: Array<() => void> = []
  let calls = 0
  const task = () =>
    new Promise<void>((resolve) => {
      calls += 1
      resolvers.push(resolve)
    })
  return {
    task,
    get calls() {
      return calls
    },
    /** 放行第 index 次调用（默认最早未完成的那次）。 */
    release(index = 0) {
      const r = resolvers[index]
      if (r) r()
    },
  }
}

describe('createCoalescingSync', () => {
  it('单个触发直接执行一次', async () => {
    const d = deferredTask()
    const sync = createCoalescingSync(d.task)
    const p = sync()
    expect(d.calls).toBe(1)
    d.release(0)
    await p
    expect(d.calls).toBe(1)
  })

  it('执行期间到来的信号在完成后补拉一次，而非被丢弃', async () => {
    const d = deferredTask()
    const sync = createCoalescingSync(d.task)

    const first = sync()
    expect(d.calls).toBe(1)

    // 第一次还没结束就来了信号：此刻不该并发发起第二次
    void sync()
    expect(d.calls).toBe(1)

    d.release(0)
    // 让排空循环推进到补偿轮
    await Promise.resolve()
    await Promise.resolve()
    // 这一轮正是修复前会被丢掉的那次
    expect(d.calls).toBe(2)

    d.release(1)
    await first
    expect(d.calls).toBe(2)
  })

  it('期间积压多个信号只补拉一次，因为同步是幂等的全量对齐', async () => {
    const d = deferredTask()
    const sync = createCoalescingSync(d.task)

    const first = sync()
    void sync()
    void sync()
    void sync()
    expect(d.calls).toBe(1)

    d.release(0)
    await Promise.resolve()
    await Promise.resolve()
    // 三个积压信号合并成一次补拉，而非三次空转
    expect(d.calls).toBe(2)

    d.release(1)
    await first
    expect(d.calls).toBe(2)
  })

  it('任务失败也会补拉，避免失败与丢信号叠加成更长的不同步窗口', async () => {
    let calls = 0
    const task = vi.fn(async () => {
      calls += 1
      if (calls === 1) throw new Error('network flake')
    })
    const sync = createCoalescingSync(task)

    const first = sync()
    void sync()
    await expect(first).rejects.toThrow('network flake')
    expect(task).toHaveBeenCalledTimes(2)
  })

  it('串行完成后再触发是一次全新执行，不受上轮 pending 影响', async () => {
    const d = deferredTask()
    const sync = createCoalescingSync(d.task)

    const first = sync()
    d.release(0)
    await first
    expect(d.calls).toBe(1)

    const second = sync()
    expect(d.calls).toBe(2)
    d.release(1)
    await second
    expect(d.calls).toBe(2)
  })
})
