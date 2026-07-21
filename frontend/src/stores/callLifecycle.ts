import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api'

/** 单次调用的生命周期阶段，与后端 CallPhase 枚举一一对应。 */
export type CallPhase =
  | 'RECEIVED'
  | 'CONNECTED'
  | 'CHUNK'
  | 'RETRYING'
  | 'STALLED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELED'
  | 'ABORTED'

/** 后端推送的生命周期事件，与 CallLifecycleEvent DTO 对应。 */
export interface CallLifecycleEvent {
  requestId: string
  phase: CallPhase
  model: string
  stream: boolean
  chunkCount: number
  /** 重试次数（RETRYING 阶段有意义，表示即将进行的第几次重试，其余为 0）。 */
  attempt: number
  timestamp: number
}

/** 前端渲染用的 Toast 状态，按 requestId 分组，随事件流转更新。 */
export interface CallToast {
  requestId: string
  phase: CallPhase
  model: string
  stream: boolean
  chunkCount: number
  /** 当前重试次数（RETRYING 阶段展示用）。 */
  attempt: number
  /** 是否正在退场淡出（COMPLETED/FAILED 后短暂保留再移除）。 */
  leaving: boolean
  /** 是否可取消：等待产出（RECEIVED/CONNECTED）持续超过阈值后置位，展示取消按钮。 */
  canCancel: boolean
  /** 取消请求是否已发出（避免重复点击），置位后按钮进入“取消中”禁用态。 */
  canceling: boolean
}

/** SSE 端点路径。calls 流由后端 permitAll 放行，无需附带 Bearer Token。 */
const CALLS_STREAM_PATH = '/calls/stream'
/** 进入 CLOSED 状态后的手动重连间隔（毫秒）。 */
const RECONNECT_DELAY = 3000
/** 终态（COMPLETED/FAILED）Toast 在淡出前的停留时长（毫秒）。 */
const COMPLETED_LINGER = 2200
/** 淡出动画时长（毫秒），需与 Toast 组件 CSS 的 leave 过渡一致。 */
const LEAVE_DURATION = 320
/** 等待产出超过此时长（毫秒）后，Toast 显示“取消”按钮。与后端无强耦合，纯前端体验阈值。 */
const CANCEL_THRESHOLD = 60000
/** 取消端点路径（走认证，http 实例自动附带 Bearer Token）。 */
const CANCEL_PATH = (requestId: string) => `/calls/${requestId}/cancel`

export const useCallLifecycleStore = defineStore('callLifecycle', () => {
  /** 当前存活的 Toast 列表，按接收顺序排列（越新的调用越靠前由组件控制）。 */
  const toasts = ref<CallToast[]>([])

  let eventSource: EventSource | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let manualClose = false
  /** requestId -> 该 Toast 的移除定时器，用于终态延迟移除与去重。 */
  const removalTimers = new Map<string, ReturnType<typeof setTimeout>>()
  /** requestId -> 等待产出的取消倒计时定时器，超过阈值后置位 canCancel 显示取消按钮。 */
  const cancelTimers = new Map<string, ReturnType<typeof setTimeout>>()

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
   * - 终态（COMPLETED/FAILED/CANCELED）：安排延迟淡出移除。
   */
  function applyEvent(event: CallLifecycleEvent) {
    const existing = toasts.value.find((t) => t.requestId === event.requestId)

    if (existing) {
      // 终态事件不应被迟到的中间事件覆盖（节流下终态可能先于最后一个 CHUNK 到达）。
      if (isTerminalPhase(existing.phase) && event.phase === 'CHUNK') {
        return
      }
      existing.phase = event.phase
      existing.model = event.model
      existing.stream = event.stream
      if (event.chunkCount > existing.chunkCount) {
        existing.chunkCount = event.chunkCount
      }
      // RETRYING 携带的重试次数需同步；其余阶段 attempt 为 0，不覆盖已有值。
      if (event.attempt > 0) {
        existing.attempt = event.attempt
      }
      // 一旦离开“等待产出”状态（收到首个 chunk、完成、或到终态），取消窗口关闭。
      // STALLED 例外：首字后停滞时仍需保留取消按钮，由下方统一处理。
      if (!isWaitingPhase(event.phase) && event.phase !== 'STALLED') {
        existing.canCancel = false
        clearCancelTimer(event.requestId)
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
          attempt: event.attempt,
          leaving: false,
          canCancel: false,
          canceling: false,
        },
      ]
    }

    // 等待产出状态下启动取消倒计时；离开该状态则关闭窗口。
    if (isWaitingPhase(event.phase) && !isTerminalPhase(event.phase)) {
      scheduleCancelWindow(event.requestId)
    }

    // STALLED：首字后停滞。后端已等待阈值（30s）才发此信号，前端直接放开取消按钮，
    // 无需再自行计时。是否断连交由用户判断（避免误杀工具调用整块 chunk 的合理长阻塞）。
    if (event.phase === 'STALLED') {
      const stalled = toasts.value.find((t) => t.requestId === event.requestId)
      if (stalled) stalled.canCancel = true
      clearCancelTimer(event.requestId)
    }

    if (isTerminalPhase(event.phase)) {
      clearCancelTimer(event.requestId)
      scheduleRemoval(event.requestId)
    }
  }

  /** 等待产出超过阈值后放开取消按钮；已存在计时器则不重复安排。 */
  function scheduleCancelWindow(requestId: string) {
    if (cancelTimers.has(requestId)) return
    const timer = setTimeout(() => {
      const target = toasts.value.find((t) => t.requestId === requestId)
      // 仍处于等待产出状态才放开取消（期间若已收到 chunk 则 canCancel 已被关闭）。
      if (target && isWaitingPhase(target.phase)) {
        target.canCancel = true
      }
      cancelTimers.delete(requestId)
    }, CANCEL_THRESHOLD)
    cancelTimers.set(requestId, timer)
  }

  /** 清理某次调用的取消倒计时定时器。 */
  function clearCancelTimer(requestId: string) {
    const timer = cancelTimers.get(requestId)
    if (timer) {
      clearTimeout(timer)
      cancelTimers.delete(requestId)
    }
  }

  /**
   * 主动取消一次调用：向后端取消端点发 POST（走认证），置位 canceling 防重复点击。
   * 后端触发取消后会推送 ABORTED 事件，Toast 随终态淡出。
   */
  async function cancelCall(requestId: string) {
    const target = toasts.value.find((t) => t.requestId === requestId)
    if (!target || target.canceling) return
    target.canceling = true
    try {
      await http.post(CANCEL_PATH(requestId))
    } catch {
      // 取消请求失败（如调用已自然结束）：回滚 canceling，让用户可重试。
      const latest = toasts.value.find((t) => t.requestId === requestId)
      if (latest) latest.canceling = false
    }
  }

  /** 终态：完成 / 失败 / 客户端断连 / 主动取消，均需安排 Toast 淡出移除。 */
  function isTerminalPhase(phase: CallPhase): boolean {
    return phase === 'COMPLETED' || phase === 'FAILED' || phase === 'CANCELED'
      || phase === 'ABORTED'
  }

  /** 等待产出状态：尚未开始产出 chunk、也未到终态，此时“卡住”超过阈值才允许取消。 */
  function isWaitingPhase(phase: CallPhase): boolean {
    return phase === 'RECEIVED' || phase === 'CONNECTED' || phase === 'RETRYING'
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
    for (const timer of cancelTimers.values()) {
      clearTimeout(timer)
    }
    cancelTimers.clear()
    cleanupSource()
  }

  return { toasts, connectStream, disconnectStream, cancelCall }
})
