<script setup lang="ts">
import { onMounted } from 'vue'
import { useStatsStore } from '@/stores/stats'

const statsStore = useStatsStore()

onMounted(() => {
  statsStore.fetchStats()
})
</script>

<template>
  <div class="api-stats">
    <h2 class="section-title">API 概览</h2>

    <div v-if="statsStore.loading" class="loading">加载中...</div>
    <div v-else-if="statsStore.error" class="error">{{ statsStore.error }}</div>
    <div v-else-if="statsStore.stats" class="stats-grid">
      <div class="stat-card">
        <div class="stat-label">今日调用次数</div>
        <div class="stat-value highlight">{{ statsStore.stats.todayApiCalls }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">累计调用次数</div>
        <div class="stat-value">{{ statsStore.stats.totalApiCalls }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日输入 Token</div>
        <div class="stat-value">{{ statsStore.stats.todayInputTokens.toLocaleString() }}</div>
      </div>
      <div class="stat-card">
        <div class="stat-label">今日输出 Token</div>
        <div class="stat-value">{{ statsStore.stats.todayOutputTokens.toLocaleString() }}</div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.api-stats {
  margin-bottom: 24px;
}

.section-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 16px;
  color: $text-color;
}

.loading {
  color: $text-secondary;
  padding: 16px 0;
}

.error {
  color: $error-color;
  padding: 16px 0;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}

.stat-card {
  background: $card-bg;
  border-radius: $border-radius;
  box-shadow: $card-shadow;
  padding: 20px;
  text-align: center;
  transition: transform 0.2s;

  &:hover {
    transform: translateY(-2px);
  }
}

.stat-label {
  font-size: 13px;
  color: $text-secondary;
  margin-bottom: 8px;
}

.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: $text-color;

  &.highlight {
    color: $primary-color;
  }
}
</style>