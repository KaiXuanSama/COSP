import { defineStore } from 'pinia'
import { ref } from 'vue'
import http from '@/api'

export interface StatsData {
  totalApiCalls: number
  todayApiCalls: number
  todayInputTokens: number
  todayOutputTokens: number
}

export const useStatsStore = defineStore('stats', () => {
  const stats = ref<StatsData | null>(null)

  async function fetchStats() {
    try {
      const res = await http.get<StatsData>('/stats')
      stats.value = res.data
    } catch {
      // 概览页保留最近一次统计值，避免短暂网络错误造成界面闪烁。
    }
  }

  return { stats, fetchStats }
})