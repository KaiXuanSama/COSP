import { describe, expect, it } from 'vitest'
import {
  formatElapsed,
  isTerminalPhase,
  mergeLifecycleEvent,
  protocolAbbreviation,
  protocolPathLabel,
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
    downstreamProtocol: '',
    upstreamProtocol: '',
    startTimestamp: 0,
    endTimestamp: null,
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

  /**
   * 协议信息是从无到有的单向填充：后端只在调度完成时补发一次带齐两个协议的事件，
   * 其余帧（provider 层发的 CONNECTED / CHUNK）都不带协议字段。
   * 若写成每帧覆盖，补写后的下一帧就会把它清空 —— Tag 闪一下就没。
   */
  it('协议信息一旦写入就不再被不带协议的事件清空', () => {
    const toasts = [toast({ downstreamProtocol: 'OPENAI', upstreamProtocol: 'ANTHROPIC' })]

    mergeLifecycleEvent(toasts, event({ phase: 'CHUNK', chunkCount: 4, upstreamProtocol: null }))

    expect(toasts[0].downstreamProtocol).toBe('OPENAI')
    expect(toasts[0].upstreamProtocol).toBe('ANTHROPIC')
  })

  it('协议信息可从空补写为完整值', () => {
    const merged = mergeLifecycleEvent([], event({
      phase: 'RECEIVED',
      downstreamProtocol: 'OPENAI',
      upstreamProtocol: 'ANTHROPIC',
    }))

    expect(merged[0].downstreamProtocol).toBe('OPENAI')
    expect(merged[0].upstreamProtocol).toBe('ANTHROPIC')
  })

  /**
   * 起始时间是整轮不变量：它是「流存在时间」的计时起点，
   * 被后续帧刷新会让一条已经跑了十几秒的调用显示成刚刚开始。
   */
  it('startTimestamp 取首帧且不被后续事件改写', () => {
    const toasts = [toast({ startTimestamp: 1000 })]

    mergeLifecycleEvent(toasts, event({ phase: 'CHUNK', timestamp: 9000, chunkCount: 2 }))

    expect(toasts[0].startTimestamp).toBe(1000)
  })

  it('新 Toast 以首帧时间戳作为起始时间', () => {
    const merged = mergeLifecycleEvent([], event({ phase: 'RECEIVED', timestamp: 1700 }))

    expect(merged[0].startTimestamp).toBe(1700)
  })

  /** 终态定格结束时刻：Toast 在淡出前还要停留两秒多，不定格时间会一直涨。 */
  it('终态定格 endTimestamp，且不被后续终态改写', () => {
    const toasts = [toast({ endTimestamp: null })]

    mergeLifecycleEvent(toasts, event({ phase: 'COMPLETED', timestamp: 5200 }))
    expect(toasts[0].endTimestamp).toBe(5200)

    // 迟到的第二个终态事件（如 CANCELED 紧跟 COMPLETED）不得把定格时间往后推。
    mergeLifecycleEvent(toasts, event({ phase: 'CANCELED', timestamp: 9999 }))
    expect(toasts[0].endTimestamp).toBe(5200)
  })

  it('新 Toast 的首帧若已是终态则直接定格', () => {
    const merged = mergeLifecycleEvent([], event({ phase: 'FAILED', timestamp: 3300 }))

    expect(merged[0].endTimestamp).toBe(3300)
  })

  it('非终态不写 endTimestamp', () => {
    const merged = mergeLifecycleEvent([], event({ phase: 'CHUNK', timestamp: 800, chunkCount: 1 }))

    expect(merged[0].endTimestamp).toBeNull()
  })
})

describe('formatElapsed', () => {
  it('分钟与秒都补足两位', () => {
    expect(formatElapsed(0)).toBe('00:00')
    expect(formatElapsed(7_000)).toBe('00:07')
    expect(formatElapsed(67_000)).toBe('01:07')
    expect(formatElapsed(3_600_000)).toBe('60:00')
  })

  it('超过一小时不进位到时，分钟位继续增长', () => {
    // 面板看的是「这次跑了多久」，多两位恒为 0 的小时只会挤占空间。
    expect(formatElapsed(75 * 60_000 + 30_000)).toBe('75:30')
  })

  it('毫秒向下取整，不足一秒显示 00:00', () => {
    expect(formatElapsed(999)).toBe('00:00')
    expect(formatElapsed(1_999)).toBe('00:01')
  })

  /** 服务端与浏览器时钟不同步时差值可能为负，归零避免出现 `-01:-30` 这类乱码。 */
  it('负数归零', () => {
    expect(formatElapsed(-5_000)).toBe('00:00')
  })
})

describe('protocolPathLabel', () => {
  it('同协议直连只显示单个字母', () => {
    expect(protocolPathLabel('OPENAI', 'OPENAI')).toBe('O')
    expect(protocolPathLabel('ANTHROPIC', 'ANTHROPIC')).toBe('A')
  })

  /**
   * 箭头方向是**请求翻译的方向**（下游 → 上游），与后端 O2A / A2O 命名同向。
   * 反了会让人以为是响应翻译方向，排查线路问题时读到的结论正好相反。
   */
  it('跨协议显示下游→上游，即请求翻译方向', () => {
    expect(protocolPathLabel('OPENAI', 'ANTHROPIC')).toBe('O→A')
    expect(protocolPathLabel('ANTHROPIC', 'OPENAI')).toBe('A→O')
  })

  it('上游未知时只显示下游字母，不出现半截箭头', () => {
    expect(protocolPathLabel('OPENAI', '')).toBe('O')
  })

  it('下游未知时返回空串（整个标记不渲染）', () => {
    expect(protocolPathLabel('', 'ANTHROPIC')).toBe('')
    expect(protocolPathLabel('', '')).toBe('')
  })

  it('未知协议取首字母，后端加新协议时前端不必同步改动', () => {
    expect(protocolAbbreviation('RESPONSES')).toBe('R')
    expect(protocolPathLabel('RESPONSES', 'OPENAI')).toBe('R→O')
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
