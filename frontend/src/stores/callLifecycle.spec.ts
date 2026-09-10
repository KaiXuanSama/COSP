import { describe, expect, it } from 'vitest'
import {
  isTerminalPhase,
  mergeLifecycleEvent,
  type CallLifecycleEvent,
  type CallPhase,
  type CallToast,
} from './callLifecycle'

function event(partial: Partial<CallLifecycleEvent> & { phase: CallPhase }): CallLifecycleEvent {
  return {
    requestId: 'req-1',
    model: 'gpt-x',
    stream: true,
    chunkCount: 0,
    attempt: 0,
    timestamp: 0,
    ...partial,
  }
}

function toast(partial: Partial<CallToast> = {}): CallToast {
  return {
    requestId: 'req-1',
    phase: 'RECEIVED',
    model: 'gpt-x',
    stream: true,
    chunkCount: 0,
    attempt: 0,
    leaving: false,
    ...partial,
  }
}

describe('mergeLifecycleEvent', () => {
  it('首个事件插入一条新 Toast', () => {
    const merged = mergeLifecycleEvent([], event({ phase: 'RECEIVED', stream: false }))

    expect(merged).toHaveLength(1)
    expect(merged[0].stream).toBe(false)
    expect(merged[0].phase).toBe('RECEIVED')
  })

  /**
   * stream 是整轮不变量：首帧确定后，任何后续事件都不能改它。
   *
   * 这个字段是静默重试菜单项是否显示的唯一判据。若后续帧能覆盖它，
   * 菜单项会在调用途中凭空出现或消失，而那种缺陷只在特定时序下复现。
   */
  it('后续事件不得改写 stream（非流式调用始终保持非流式）', () => {
    const toasts = [toast({ stream: false })]

    // 模拟一个「本该不出现」的流式帧：即便它声称 stream=true，也不该被采纳。
    mergeLifecycleEvent(toasts, event({ phase: 'CHUNK', stream: true, chunkCount: 3 }))

    expect(toasts[0].stream).toBe(false)
    // 其余字段照常更新，确认不是整帧被丢弃。
    expect(toasts[0].phase).toBe('CHUNK')
    expect(toasts[0].chunkCount).toBe(3)
  })

  it('后续事件不得改写 stream（流式调用始终保持流式）', () => {
    const toasts = [toast({ stream: true })]

    mergeLifecycleEvent(toasts, event({ phase: 'COMPLETED', stream: false }))

    expect(toasts[0].stream).toBe(true)
    expect(toasts[0].phase).toBe('COMPLETED')
  })

  it('终态不被迟到的 CHUNK 覆盖', () => {
    const toasts = [toast({ phase: 'COMPLETED', chunkCount: 9 })]

    mergeLifecycleEvent(toasts, event({ phase: 'CHUNK', chunkCount: 10 }))

    expect(toasts[0].phase).toBe('COMPLETED')
    expect(toasts[0].chunkCount).toBe(9)
  })

  it('chunkCount 只增不减', () => {
    const toasts = [toast({ chunkCount: 7 })]

    mergeLifecycleEvent(toasts, event({ phase: 'CHUNK', chunkCount: 2 }))

    expect(toasts[0].chunkCount).toBe(7)
  })

  it('attempt 仅在非零时覆盖', () => {
    const toasts = [toast({ attempt: 2 })]

    mergeLifecycleEvent(toasts, event({ phase: 'CHUNK', attempt: 0 }))
    expect(toasts[0].attempt).toBe(2)

    mergeLifecycleEvent(toasts, event({ phase: 'RETRYING', attempt: 3 }))
    expect(toasts[0].attempt).toBe(3)
  })

  it('不同 requestId 各自独立', () => {
    const merged = mergeLifecycleEvent(
      [toast({ requestId: 'a', stream: true })],
      event({ requestId: 'b', phase: 'RECEIVED', stream: false }),
    )

    expect(merged).toHaveLength(2)
    expect(merged.find(t => t.requestId === 'a')?.stream).toBe(true)
    expect(merged.find(t => t.requestId === 'b')?.stream).toBe(false)
  })
})

describe('isTerminalPhase', () => {
  it('四个终态都算终态', () => {
    for (const phase of ['COMPLETED', 'FAILED', 'CANCELED', 'ABORTED'] as CallPhase[]) {
      expect(isTerminalPhase(phase)).toBe(true)
    }
  })

  it('中间阶段不算终态', () => {
    for (const phase of ['RECEIVED', 'CONNECTED', 'CHUNK', 'RETRYING'] as CallPhase[]) {
      expect(isTerminalPhase(phase)).toBe(false)
    }
  })
})
