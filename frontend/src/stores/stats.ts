import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api'

export interface StatsData {
  totalApiCalls: number
  todayApiCalls: number
  todayInputTokens: number
  todayOutputTokens: number
}

/** SSE 端点路径。stats 流由后端 permitAll 放行，无需在此附带 Bearer Token。 */
const STATS_STREAM_PATH = '/stats/stream'
/** 进入 CLOSED 状态后的手动重连间隔（毫秒）。 */
const RECONNECT_DELAY = 3000

export const useStatsStore = defineStore('stats', () => {
  const stats = ref<StatsData | null>(null)

  let eventSource: EventSource | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let manualClose = false

  /**
   * 建立统计快照 SSE 连接，替代原先的定时轮询。
   *
   * 首帧立即下发当前快照，之后每次实际 Copilot 调用完成即时推送最新值，
   * 另有后端定时兜底覆盖跨天与丢帧。重复调用不会创建多个连接。
   */
  function connectStream() {
    if (eventSource) return
    manualClose = false
    openSource()
  }

  function openSource() {
    const url = `${http.defaults.baseURL ?? ''}${STATS_STREAM_PATH}`
    const source = new EventSource(url)
    eventSource = source

    source.addEventListener('stats', (event) => {
      try {
        stats.value = JSON.parse((event as MessageEvent).data) as StatsData
      } catch {
        // 忽略无法解析的帧，保留上次快照。
      }
    })

    source.onerror = () => {
      // 浏览器原生 EventSource 在网络抖动时会自动重连；
      // 仅当进入 CLOSED（如服务端返回非 2xx）时才手动兜底重连。
      if (source.readyState === EventSource.CLOSED && !manualClose) {
        scheduleReconnect()
      }
    }
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

  /** 主动断开 SSE 连接（离开概览页时调用）。 */
  function disconnectStream() {
    manualClose = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    cleanupSource()
  }

  /** HTTP 首屏拉取，作为 SSE 尚未建立时的兜底。 */
  async function fetchStats() {
    try {
      const res = await http.get<StatsData>('/stats')
      stats.value = res.data
    } catch {
      // 概览页保留最近一次统计值，避免短暂网络错误造成界面闪烁。
    }
  }

  return { stats, fetchStats, connectStream, disconnectStream }
})