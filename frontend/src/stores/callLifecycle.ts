import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api'

/** 单次调用的生命周期阶段，与后端 CallPhase 枚举一一对应。 */
export type CallPhase = 'RECEIVED' | 'CONNECTED' | 'CHUNK' | 'COMPLETED' | 'FAILED'

/** 后端推送的生命周期事件，与 CallLifecycleEvent DTO 对应。 */
export interface CallLifecycleEvent {
  requestId: string
  phase: CallPhase
  model: string
  stream: boolean
  chunkCount: number
  timestamp: number
}

/** 前端渲染用的 Toast 状态，按 requestId 分组，随事件流转更新。 */
export interface CallToast {
  requestId: string
  phase: CallPhase
  model: string
  stream: boolean
  chunkCount: number
  /** 是否正在退场淡出（COMPLETED/FAILED 后短暂保留再移除）。 */
  leaving: boolean
}

/** SSE 端点路径。calls 流由后端 permitAll 放行，无需附带 Bearer Token。 */
const CALLS_STREAM_PATH = '/calls/stream'
/** 进入 CLOSED 状态后的手动重连间隔（毫秒）。 */
const RECONNECT_DELAY = 3000
/** 终态（COMPLETED/FAILED）Toast 在淡出前的停留时长（毫秒）。 */
const COMPLETED_LINGER = 2200
/** 淡出动画时长（毫秒），需与 Toast 组件 CSS 的 leave 过渡一致。 */
const LEAVE_DURATION = 320

export const useCallLifecycleStore = defineStore('callLifecycle', () => {
  /** 当前存活的 Toast 列表，按接收顺序排列（越新的调用越靠前由组件控制）。 */
  const toasts = ref<CallToast[]>([])

  let eventSource: EventSource | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let manualClose = false
  /** requestId -> 该 Toast 的移除定时器，用于终态延迟移除与去重。 */
  const removalTimers = new Map<string, ReturnType<typeof setTimeout>>()

  /**
   * 建立调用生命周期 SSE 连接。重复调用不会创建多个连接。
   * 在布局挂载时调用一次，连接常驻，跨页面切换不断开。
   */
  function connectStream() {
    if (eventSource) return
    manualClose = false
    openSource()
  }

  function openSource() {
    const url = `${http.defaults.baseURL ?? ''}${CALLS_STREAM_PATH}`
    const source = new EventSource(url)
    eventSource = source

    source.addEventListener('call', (event) => {
      try {
        const data = JSON.parse((event as MessageEvent).data) as CallLifecycleEvent
        applyEvent(data)
      } catch {
        // 忽略无法解析的帧。
      }
    })

    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED && !manualClose) {
        scheduleReconnect()
      }
    }
  }

  /**
   * 将一个生命周期事件应用到 Toast 列表：
   * - 新 requestId：插入一个 Toast；
   * - 已存在：就地更新阶段与 chunk 计数；
   * - 终态（COMPLETED/FAILED）：安排延迟淡出移除。
   */
  function applyEvent(event: CallLifecycleEvent) {
    const existing = toasts.value.find((t) => t.requestId === event.requestId)

    if (existing) {
      // 终态事件不应被迟到的中间事件覆盖（节流下 COMPLETED 可能先于最后一个 CHUNK 到达）。
      if ((existing.phase === 'COMPLETED' || existing.phase === 'FAILED') && event.phase === 'CHUNK') {
        return
      }
      existing.phase = event.phase
      existing.model = event.model
      existing.stream = event.stream
      if (event.chunkCount > existing.chunkCount) {
        existing.chunkCount = event.chunkCount
      }
    } else {
      toasts.value = [
        ...toasts.value,
        {
          requestId: event.requestId,
          phase: event.phase,
          model: event.model,
          stream: event.stream,
          chunkCount: event.chunkCount,
          leaving: false,
        },
      ]
    }

    if (event.phase === 'COMPLETED' || event.phase === 'FAILED') {
      scheduleRemoval(event.requestId)
    }
  }

  /** 为终态 Toast 安排延迟淡出与移除；重复终态事件只保留最初的定时器。 */
  function scheduleRemoval(requestId: string) {
    if (removalTimers.has(requestId)) return

    const linger = setTimeout(() => {
      const target = toasts.value.find((t) => t.requestId === requestId)
      if (target) target.leaving = true
      const remove = setTimeout(() => {
        toasts.value = toasts.value.filter((t) => t.requestId !== requestId)
        removalTimers.delete(requestId)
      }, LEAVE_DURATION)
      removalTimers.set(requestId, remove)
    }, COMPLETED_LINGER)

    removalTimers.set(requestId, linger)
  }

  function scheduleReconnect() {
    cleanupSource()
    if (reconnectTimer) return
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      if (!manualClose) openSource()
    }, RECONNECT_DELAY)
  }

  function cleanupSource() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  /** 主动断开 SSE 连接并清理所有定时器（通常无需调用，连接常驻）。 */
  function disconnectStream() {
    manualClose = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    for (const timer of removalTimers.values()) {
      clearTimeout(timer)
    }
    removalTimers.clear()
    cleanupSource()
  }

  return { toasts, connectStream, disconnectStream }
})
