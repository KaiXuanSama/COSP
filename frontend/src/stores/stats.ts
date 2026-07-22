import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api'
import { createAuthEventSource, type AuthEventSource } from '@/api/authEventSource'

export interface StatsData {
  totalApiCalls: number
  todayApiCalls: number
  todayInputTokens: number
  todayOutputTokens: number
}

/** SSE 端点路径（相对 http.baseURL）。走认证，token 由 createAuthEventSource 以 Bearer header 附带。 */
const STATS_STREAM_PATH = '/stats/stream'

export const useStatsStore = defineStore('stats', () => {
  const stats = ref<StatsData | null>(null)

  let source: AuthEventSource | null = null

  /**
   * 建立统计快照 SSE 连接，替代原先的定时轮询。
   *
   * 首帧立即下发当前快照，之后每次实际 Copilot 调用完成即时推送最新值，
   * 另有后端定时兜底覆盖跨天与丢帧。重复调用不会创建多个连接。
   */
  function connectStream() {
    if (source) return
    source = createAuthEventSource({
      path: STATS_STREAM_PATH,
      handlers: {
        stats: (data) => {
          try {
            stats.value = JSON.parse(data) as StatsData
          } catch {
            // 忽略无法解析的帧，保留上次快照。
          }
        },
      },
    })
  }

  /** 主动断开 SSE 连接（离开概览页时调用）。 */
  function disconnectStream() {
    if (source) {
      source.close()
      source = null
    }
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