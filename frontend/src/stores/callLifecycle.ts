import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api'
import { createAuthEventSource, type AuthEventSource } from '@/api/authEventSource'

/** 单次调用的生命周期阶段，与后端 CallPhase 枚举一一对应。 */
export type CallPhase =
  | 'RECEIVED'
  | 'CONNECTED'
  | 'CHUNK'
  | 'RETRYING'
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

}

/** 终态：完成 / 失败 / 客户端断连 / 主动取消，均需安排 Toast 淡出移除。 */
export function isTerminalPhase(phase: CallPhase): boolean {
  return phase === 'COMPLETED' || phase === 'FAILED' || phase === 'CANCELED'
    || phase === 'ABORTED'
}

/**
 * 把一个生命周期事件归并进已有的 Toast。
 *
 * <p>抽成模块级纯函数而非留在 store 内部：它有几条不显而易见的字段级规则
 * （终态不被迟到的 CHUNK 覆盖、stream 是不变量、chunkCount 只增不减），
 * 那些规则靠读代码保不住，靠单测才行。
 *
 * @param toasts 当前 Toast 列表（就地修改元素，需插入时返回新数组）
 * @param event 新到的事件
 * @returns 归并后的列表（可能与入参同一引用）
 */
export function mergeLifecycleEvent(toasts: CallToast[], event: CallLifecycleEvent): CallToast[] {
  const existing = toasts.find((t) => t.requestId === event.requestId)
  if (!existing) {
    return [
      ...toasts,
      {
        requestId: event.requestId,
        phase: event.phase,
        model: event.model,
        stream: event.stream,
        chunkCount: event.chunkCount,
        attempt: event.attempt,
        leaving: false,
      },
    ]
  }

  // 终态事件不应被迟到的中间事件覆盖（节流下终态可能先于最后一个 CHUNK 到达）。
  if (isTerminalPhase(existing.phase) && event.phase === 'CHUNK') {
    return toasts
  }
  existing.phase = event.phase
  existing.model = event.model
  // stream 刻意不更新：它是**整轮不变量**，首帧确定后不该再变。
  //
  // 后端两个控制器当前都自洽（非流式各帧传请求体的 stream，流式各帧传字面 true），
  // 所以无条件覆盖眼下也不会出错。但这个字段是「静默重试菜单项是否显示」的唯一判据
  // （CallToastStack 的 v-if="menuTarget.stream"），一旦某个阶段漏传或传错，
  // 菜单项会在调用途中凭空出现或消失 —— 那种缺陷只在特定时序下复现，很难查。
  //
  // 与下面 chunkCount / attempt 的保护是同一个道理：能表达成不变量的就不要留成可变量。
  if (event.chunkCount > existing.chunkCount) {
    existing.chunkCount = event.chunkCount
  }
  // RETRYING 携带的重试次数需同步；其余阶段 attempt 为 0，不覆盖已有值。
  if (event.attempt > 0) {
    existing.attempt = event.attempt
  }
  return toasts
}

/** SSE 端点路径（相对 http.baseURL）。走认证，token 由 createAuthEventSource 以 Bearer header 附带。 */
const CALLS_STREAM_PATH = '/calls/stream'
/** 终态（COMPLETED/FAILED）Toast 在淡出前的停留时长（毫秒）。 */
const COMPLETED_LINGER = 2200
/** 淡出动画时长（毫秒），需与 Toast 组件 CSS 的 leave 过渡一致。 */
const LEAVE_DURATION = 320
/** 取消端点路径（走认证，http 实例自动附带 Bearer Token）。 */
const CANCEL_PATH = (requestId: string) => `/calls/${requestId}/cancel`
/** 静默重试端点路径（走认证）。 */
const RETRY_PATH = (requestId: string) => `/calls/${requestId}/retry`

export const useCallLifecycleStore = defineStore('callLifecycle', () => {
  /** 当前存活的 Toast 列表，按接收顺序排列（越新的调用越靠前由组件控制）。 */
  const toasts = ref<CallToast[]>([])

  let source: AuthEventSource | null = null
  /** requestId -> 该 Toast 的移除定时器，用于终态延迟移除与去重。 */
  const removalTimers = new Map<string, ReturnType<typeof setTimeout>>()

  /**
   * 建立调用生命周期 SSE 连接。重复调用不会创建多个连接。
   * 在布局挂载时调用一次，连接常驻，跨页面切换不断开。
   */
  function connectStream() {
    if (source) return
    source = createAuthEventSource({
      path: CALLS_STREAM_PATH,
      handlers: {
        call: (data) => {
          try {
            applyEvent(JSON.parse(data) as CallLifecycleEvent)
          } catch {
            // 忽略无法解析的帧。
          }
        },
      },
    })
  }

  /**
   * 将一个生命周期事件应用到 Toast 列表：
   * - 新 requestId：插入一个 Toast；
   * - 已存在：就地更新阶段与 chunk 计数；
   * - 终态（COMPLETED/FAILED/CANCELED）：安排延迟淡出移除。
   */
  function applyEvent(event: CallLifecycleEvent) {
    toasts.value = mergeLifecycleEvent(toasts.value, event)

    if (isTerminalPhase(event.phase)) {
      scheduleRemoval(event.requestId)
    }
  }

  /**
   * 主动断连一次调用：向后端取消端点发 POST（走认证）。
   * 后端触发取消后会推送 ABORTED 事件，Toast 随终态淡出。
   * 调用方负责防重复（如菜单项 loading 态）。
   */
  async function cancelCall(requestId: string) {
    try {
      await http.post(CANCEL_PATH(requestId))
    } catch {
      // 取消请求失败（如调用已自然结束），静默忽略。
    }
  }

  /**
   * 静默重试一次调用：向后端重试端点发 POST（走认证）。
   * 后端只中断当前上游请求并重新发起，下游连接保持打开、无感知。
   * 调用方负责防重复（如菜单项 loading 态）。
   */
  async function retryCall(requestId: string) {
    try {
      await http.post(RETRY_PATH(requestId))
    } catch {
      // 重试请求失败（如调用已自然结束），静默忽略。
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

  /** 主动断开 SSE 连接并清理所有定时器（通常无需调用，连接常驻）。 */
  function disconnectStream() {
    if (source) {
      source.close()
      source = null
    }
    for (const timer of removalTimers.values()) {
      clearTimeout(timer)
    }
    removalTimers.clear()
  }

  return { toasts, connectStream, disconnectStream, cancelCall, retryCall }
})
