import { describe, expect, it, vi } from 'vitest'
import { createDwellTrigger } from './dwell'

/**
 * 验证与锁定：停留触发器。
 *
 * 核心是「快速划过只触发一次、停住即触发」这条 debounce 语义 ——
 * 若误写成 throttle，划过 10 个点会打出 10 个请求，而那些点用户根本没停留。
 */
describe('createDwellTrigger', () => {
  it('停留达成后执行，携带最后一次提交的值', () => {
    vi.useFakeTimers()
    const run = vi.fn()
    const trigger = createDwellTrigger<string>(200, run)

    trigger.schedule('a')
    vi.advanceTimersByTime(199)
    expect(run).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(run).toHaveBeenCalledExactlyOnceWith('a')
    vi.useRealTimers()
  })

  /**
   * 连续提交只触发一次 —— 这是 debounce 与 throttle 的分界。
   *
   * 模拟「快速划过 5 个离散点」：每次换点都重置计时，只有最后停住的那个点被加载。
   */
  it('连续提交只触发最后一个值', () => {
    vi.useFakeTimers()
    const run = vi.fn()
    const trigger = createDwellTrigger<string>(200, run)

    for (const value of ['a', 'b', 'c', 'd', 'e']) {
      trigger.schedule(value)
      vi.advanceTimersByTime(50)
    }
    expect(run).not.toHaveBeenCalled()

    vi.advanceTimersByTime(200)
    expect(run).toHaveBeenCalledExactlyOnceWith('e')
    vi.useRealTimers()
  })

  /** 提交相同值也会重置计时 —— 判重是调用方的事。 */
  it('相同值也重置计时', () => {
    vi.useFakeTimers()
    const run = vi.fn()
    const trigger = createDwellTrigger<string>(200, run)

    trigger.schedule('a')
    vi.advanceTimersByTime(150)
    trigger.schedule('a')
    vi.advanceTimersByTime(150)
    expect(run).not.toHaveBeenCalled()

    vi.advanceTimersByTime(50)
    expect(run).toHaveBeenCalledExactlyOnceWith('a')
    vi.useRealTimers()
  })

  it('cancel 阻止执行', () => {
    vi.useFakeTimers()
    const run = vi.fn()
    const trigger = createDwellTrigger<string>(200, run)

    trigger.schedule('a')
    trigger.cancel()
    vi.advanceTimersByTime(1000)
    expect(run).not.toHaveBeenCalled()
    expect(trigger.pending).toBe(false)
    vi.useRealTimers()
  })

  it('flush 立即执行待处理的值', () => {
    vi.useFakeTimers()
    const run = vi.fn()
    const trigger = createDwellTrigger<string>(200, run)

    trigger.schedule('a')
    trigger.flush()
    expect(run).toHaveBeenCalledExactlyOnceWith('a')

    // 已执行过的不会被重复触发
    vi.advanceTimersByTime(1000)
    expect(run).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  /** 没有待处理值时 flush 不做任何事 —— 不会重放上一次的值。 */
  it('无待处理值时 flush 无副作用', () => {
    vi.useFakeTimers()
    const run = vi.fn()
    const trigger = createDwellTrigger<string>(200, run)

    trigger.schedule('a')
    vi.advanceTimersByTime(200)
    expect(run).toHaveBeenCalledTimes(1)

    trigger.flush()
    expect(run).toHaveBeenCalledTimes(1)
    vi.useRealTimers()
  })

  it('pending 反映是否有待执行的值', () => {
    vi.useFakeTimers()
    const trigger = createDwellTrigger<string>(200, vi.fn())

    expect(trigger.pending).toBe(false)
    trigger.schedule('a')
    expect(trigger.pending).toBe(true)
    vi.advanceTimersByTime(200)
    expect(trigger.pending).toBe(false)
    vi.useRealTimers()
  })

  /** 非正数延迟同步执行 —— 供测试与「关闭停留」使用。 */
  it('延迟为 0 时同步执行', () => {
    const run = vi.fn()
    const trigger = createDwellTrigger<string>(0, run)

    trigger.schedule('a')
    expect(run).toHaveBeenCalledExactlyOnceWith('a')
  })

  /**
   * 值为 undefined 时仍能正确触发。
   *
   * 内部用单元素容器而非裸变量正是为此 —— 靠 `!== undefined` 判断
   * 「有没有待处理值」会在允许 undefined 的 T 上失效。
   */
  it('undefined 值也能触发', () => {
    vi.useFakeTimers()
    const run = vi.fn()
    const trigger = createDwellTrigger<string | undefined>(200, run)

    trigger.schedule(undefined)
    expect(trigger.pending).toBe(true)
    vi.advanceTimersByTime(200)
    expect(run).toHaveBeenCalledExactlyOnceWith(undefined)
    vi.useRealTimers()
  })
})
