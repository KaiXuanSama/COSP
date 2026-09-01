import { describe, expect, it } from 'vitest'

import {
  createTransientFlagScheduler,
  type ScheduleHooks,
} from './transientFlag'

/**
 * 可控的定时器与帧回调。
 *
 * 不用 `vi.useFakeTimers()`：那个假造不了 `requestAnimationFrame`
 * （项目测试环境是纯 node，压根没有这个全局函数）。手写一份还能分别断言
 * 「延时」与「下一帧」两条路径，而假定时器会把它们混在一起。
 */
function createTestHooks() {
  const delays = new Map<number, { callback: () => void, ms: number }>()
  const defers = new Map<number, () => void>()
  let nextHandle = 1

  const hooks: ScheduleHooks = {
    delay(callback, ms) {
      const handle = nextHandle++
      delays.set(handle, { callback, ms })
      return handle
    },
    cancelDelay(handle) {
      delays.delete(handle)
    },
    defer(callback) {
      const handle = nextHandle++
      defers.set(handle, callback)
      return handle
    },
    cancelDefer(handle) {
      defers.delete(handle)
    },
  }

  return {
    hooks,
    /** 执行所有待执行的延时回调。 */
    runDelays() {
      const pending = [...delays.values()]
      delays.clear()
      pending.forEach(entry => entry.callback())
    },
    /** 执行所有待执行的帧回调。 */
    runDefers() {
      const pending = [...defers.values()]
      defers.clear()
      pending.forEach(callback => callback())
    },
    get pendingDelays() { return delays.size },
    get pendingDefers() { return defers.size },
    /** 最近一次注册的延时时长，用于断言时长被正确透传。 */
    get lastDelayMs() { return [...delays.values()].at(-1)?.ms },
  }
}

/** 一个最小的状态载体，替代组件里的 ref。 */
function createStore<T>() {
  const state: Record<number, T | null> = {}
  return {
    state,
    read: (key: number) => state[key],
    write: (key: number, flag: T | null) => { state[key] = flag },
  }
}

type Direction = 1 | -1

function setup(durationMs = 180) {
  const testHooks = createTestHooks()
  const store = createStore<Direction>()
  const scheduler = createTransientFlagScheduler<Direction>(
    durationMs,
    store.read,
    store.write,
    testHooks.hooks,
  )
  return { scheduler, store, testHooks }
}

describe('createTransientFlagScheduler', () => {
  it('触发后立即写入标记', () => {
    const { scheduler, store } = setup()
    scheduler.trigger(0, 1)
    expect(store.state[0]).toBe(1)
  })

  it('按传入的时长注册过期计时', () => {
    const { scheduler, testHooks } = setup(250)
    scheduler.trigger(0, 1)
    expect(testHooks.lastDelayMs).toBe(250)
  })

  /**
   * 自动过期是这个调度器存在的理由：标记表示「刚刚发生了一次」，
   * 不是「当前方向是」。长期挂着 class 会让之后任何重渲染都重播动画。
   */
  it('计时到点后清空', () => {
    const { scheduler, store, testHooks } = setup()
    scheduler.trigger(0, 1)
    expect(store.state[0]).toBe(1)
    testHooks.runDelays()
    expect(store.state[0]).toBeNull()
  })

  it('不同值直接替换，不隔帧', () => {
    const { scheduler, store, testHooks } = setup()
    scheduler.trigger(0, 1)
    scheduler.trigger(0, -1)
    expect(store.state[0]).toBe(-1)
    // 换方向时 class 本身就变了，CSS 动画会重播，不需要隔帧。
    expect(testHooks.pendingDefers).toBe(0)
  })

  /**
   * 同值重复触发必须先清空一帧。
   *
   * CSS animation 只在 class 从无到有时播放；同一个微任务里清空又设回去，
   * 框架只 patch 最终状态，class 从未离开 DOM，动画不会重新开始 ——
   * 连续同向滚动就只有第一格有反馈。
   */
  it('同值重复触发时先清空、下一帧再写回', () => {
    const { scheduler, store, testHooks } = setup()
    scheduler.trigger(0, 1)
    scheduler.trigger(0, 1)

    // 当前这一帧必须是空的，class 才会真正从 DOM 上摘掉。
    expect(store.state[0]).toBeNull()
    expect(testHooks.pendingDefers).toBe(1)

    testHooks.runDefers()
    expect(store.state[0]).toBe(1)
  })

  it('隔帧重触发后仍会注册过期计时', () => {
    const { scheduler, store, testHooks } = setup()
    scheduler.trigger(0, 1)
    scheduler.trigger(0, 1)
    testHooks.runDefers()
    expect(testHooks.pendingDelays).toBe(1)
    testHooks.runDelays()
    expect(store.state[0]).toBeNull()
  })

  /**
   * 连续同向触发若不取消上一次的计时，先注册的那个会提前把标记清掉，
   * 动画就在中途断了。
   */
  it('重复触发会取消上一次的过期计时', () => {
    const { scheduler, testHooks } = setup()
    scheduler.trigger(0, 1)
    expect(testHooks.pendingDelays).toBe(1)
    scheduler.trigger(0, -1)
    // 仍然只有一个，说明旧的被取消了而不是累积。
    expect(testHooks.pendingDelays).toBe(1)
  })

  it('clear 立即清空并取消计时', () => {
    const { scheduler, store, testHooks } = setup()
    scheduler.trigger(0, 1)
    scheduler.clear(0)
    expect(store.state[0]).toBeNull()
    expect(testHooks.pendingDelays).toBe(0)
  })

  it('clear 也取消待执行的帧回调', () => {
    const { scheduler, store, testHooks } = setup()
    scheduler.trigger(0, 1)
    scheduler.trigger(0, 1)
    expect(testHooks.pendingDefers).toBe(1)
    scheduler.clear(0)
    expect(testHooks.pendingDefers).toBe(0)
    // 帧回调已取消，跑一遍不会把标记写回来。
    testHooks.runDefers()
    expect(store.state[0]).toBeNull()
  })

  /**
   * 每个模型行的动画彼此独立：在 A 行滚动不该让 B 行也动一下。
   */
  it('不同键各自独立', () => {
    const { scheduler, store, testHooks } = setup()
    scheduler.trigger(0, 1)
    scheduler.trigger(1, -1)
    expect(store.state[0]).toBe(1)
    expect(store.state[1]).toBe(-1)
    expect(testHooks.pendingDelays).toBe(2)

    scheduler.clear(0)
    expect(store.state[0]).toBeNull()
    expect(store.state[1]).toBe(-1)
    expect(testHooks.pendingDelays).toBe(1)
  })

  it('dispose 取消全部待执行回调', () => {
    const { scheduler, store, testHooks } = setup()
    scheduler.trigger(0, 1)
    scheduler.trigger(1, -1)
    scheduler.trigger(1, -1)
    expect(testHooks.pendingDelays).toBeGreaterThan(0)
    expect(testHooks.pendingDefers).toBe(1)

    scheduler.dispose()
    expect(testHooks.pendingDelays).toBe(0)
    expect(testHooks.pendingDefers).toBe(0)

    // 跑一遍也不会再写状态 —— 组件卸载后写一个已不存在的 ref 会报错。
    const before = { ...store.state }
    testHooks.runDelays()
    testHooks.runDefers()
    expect(store.state).toEqual(before)
  })
})
