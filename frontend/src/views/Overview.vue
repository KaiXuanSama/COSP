<script setup lang="ts">
import { onMounted } from 'vue'
import { NCard, NNumberAnimation, NTag, NSkeleton } from 'naive-ui'
import { useStatsStore } from '@/stores/stats'

const statsStore = useStatsStore()

onMounted(() => {
  statsStore.fetchStats()
})
</script>

<template>
  <div class="overview-page">
    <div class="stats-grid">
      <n-card class="stat-card" :bordered="true">
        <template #header>
          <div class="stat-card-label">API 调用总次数</div>
        </template>
        <div class="stat-card-value">
          <n-number-animation v-if="statsStore.stats" :from="0" :to="statsStore.stats.totalApiCalls" :duration="800" />
          <span v-else class="stat-card-placeholder">—</span>
        </div>
        <div class="stat-card-desc">自服务启动以来累计</div>
      </n-card>

      <n-card class="stat-card" :bordered="true">
        <template #header>
          <div class="stat-card-label">今日 API 调用次数</div>
        </template>
        <div class="stat-card-value">
          <n-number-animation v-if="statsStore.stats" :from="0" :to="statsStore.stats.todayApiCalls" :duration="800" />
          <span v-else class="stat-card-placeholder">—</span>
        </div>
        <div class="stat-card-desc">当日 00:00 ~ 23:59</div>
      </n-card>

      <n-card class="stat-card" :bordered="true">
        <template #header>
          <div class="stat-card-label">今日 Token 消耗</div>
        </template>
        <div class="stat-card-value stat-card-value--sm">
          <template v-if="statsStore.stats">
            <n-number-animation :from="0" :to="statsStore.stats.todayInputTokens" :duration="800" />
            <span class="stat-card-token-sep">/</span>
            <n-number-animation :from="0" :to="statsStore.stats.todayOutputTokens" :duration="800" />
          </template>
          <span v-else class="stat-card-placeholder">—</span>
        </div>
        <div class="stat-card-desc">输入 / 输出</div>
      </n-card>
    </div>

    <div v-if="statsStore.loading" class="loading-area">
      <n-skeleton text :repeat="3" />
    </div>
    <div v-else-if="statsStore.error" class="error-area">
      <n-tag type="error">{{ statsStore.error }}</n-tag>
    </div>

    <n-card title="关于本服务" class="info-card" :bordered="true">
      <div class="info-list">
        <div class="info-row">
          <span class="info-row-label">项目</span>
          <span class="info-row-value">COSP (Copilot Ollama SpringBoot Proxy)</span>
        </div>
        <div class="info-row">
          <span class="info-row-label">描述</span>
          <span class="info-row-value">专为 GitHub Copilot 设计的 Ollama 代理中转服务，支持多供应商切换与 API 调用统计</span>
        </div>
        <div class="info-row">
          <span class="info-row-label">技术栈</span>
          <span class="info-row-value">Spring Boot · Spring Security · Vue 3 · SQLite</span>
        </div>
        <div class="info-row">
          <span class="info-row-label">开源地址</span>
          <span class="info-row-value"><a href="https://github.com/" target="_blank" rel="noopener">GitHub</a></span>
        </div>
        <div class="info-row">
          <span class="info-row-label">作者</span>
          <span class="info-row-value">KaiXuan</span>
        </div>
      </div>
    </n-card>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.stats-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: $space-md;
  margin-bottom: $space-lg;

  @media (max-width: 768px) {
    grid-template-columns: 1fr;
  }
}

.stat-card {
  :deep(.n-card-header) {
    padding-bottom: 0;
  }
}

.stat-card-label {
  font-family: $font-mono;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: $text-muted;
}

.stat-card-value {
  font-family: $font-mono;
  font-size: 28px;
  font-weight: 500;
  color: $text-primary;
  margin-bottom: $space-xs;

  &.stat-card-value--sm {
    font-size: 22px;
  }
}

.stat-card-placeholder {
  color: $text-muted;
}

.stat-card-token-sep {
  color: $text-muted;
  margin: 0 4px;
  font-size: 18px;
}

.stat-card-desc {
  font-family: $font-body;
  font-size: 13px;
  color: $text-muted;
}

.info-card {
  margin-top: $space-lg;
}

.info-list {
  display: flex;
  flex-direction: column;
}

.info-row {
  display: flex;
  padding: 12px 0;
  border-bottom: 1px solid $border-light;

  &:last-child {
    border-bottom: none;
  }
}

.info-row-label {
  flex: 0 0 100px;
  font-family: $font-mono;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: $text-muted;
}

.info-row-value {
  flex: 1;
  font-family: $font-body;
  font-size: 14px;
  color: $text-body;

  a {
    color: $accent;
    text-decoration: underline;
    text-underline-offset: 2px;

    &:hover {
      color: #9c6231;
    }
  }
}

.loading-area {
  padding: $space-lg 0;
}

.error-area {
  padding: $space-lg 0;
}
</style>