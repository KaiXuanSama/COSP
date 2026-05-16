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
  const loading = ref(false)
  const error = ref('')

  async function fetchStats() {
    loading.value = true
    error.value = ''
    try {
      const res = await http.get<StatsData>('/stats')
      stats.value = res.data
    } catch (e: any) {
      error.value = '获取统计数据失败: ' + e.message
    } finally {
      loading.value = false
    }
  }

  return { stats, loading, error, fetchStats }
})