<script setup lang="ts">
import { onMounted } from 'vue'
import { useStatsStore } from '@/stores/stats'

const statsStore = useStatsStore()

onMounted(() => {
  statsStore.fetchStats()
})

function formatToken(val: number): string {
  if (val >= 1000) {
    return (val / 1000).toFixed(1) + 'k'
  }
  return String(val)
}
</script>

<template>
  <div class="overview-page">
    <!-- 统计卡片 -->
    <div class="stats-grid">
      <div class="stat-card">
        <div class="stat-card-top accent"></div>
        <div class="stat-card-label">API 调用总次数</div>
        <div class="stat-card-value">{{ statsStore.stats?.totalApiCalls ?? '—' }}</div>
        <div class="stat-card-desc">自服务启动以来累计</div>
      </div>
      <div class="stat-card">
        <div class="stat-card-top success"></div>
        <div class="stat-card-label">今日 API 调用次数</div>
        <div class="stat-card-value">{{ statsStore.stats?.todayApiCalls ?? '—' }}</div>
        <div class="stat-card-desc">当日 00:00 ~ 23:59</div>
      </div>
      <div class="stat-card">
        <div class="stat-card-top blue"></div>
        <div class="stat-card-label">今日 Token 消耗</div>
        <div class="stat-card-value stat-card-value--sm">
          <span>{{ statsStore.stats ? formatToken(statsStore.stats.todayInputTokens) : '—' }}</span>
          <span class="stat-card-token-sep">/</span>
          <span>{{ statsStore.stats ? formatToken(statsStore.stats.todayOutputTokens) : '—' }}</span>
        </div>
        <div class="stat-card-desc">输入 / 输出</div>
      </div>
    </div>

    <!-- 加载状态 -->
    <div v-if="statsStore.loading" class="loading-text">加载中...</div>
    <div v-else-if="statsStore.error" class="error-text">{{ statsStore.error }}</div>

    <!-- 关于本服务 -->
    <div class="card info-card">
      <div class="card-title">关于本服务</div>
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
    </div>
  </div>
</template>

<style lang="scss" scoped>
@use 'sass:color';
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
  background: $surface;
  border: 1px solid $border;
  border-radius: $radius-lg;
  padding: $space-lg;
  box-shadow: $shadow-sm;
  transition: transform 0.25s ease, box-shadow 0.25s ease;
  position: relative;
  overflow: hidden;

  &:hover {
    transform: translateY(-2px);
    box-shadow: $shadow-md;
  }
}

.stat-card-top {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;

  &.accent { background: $accent; }
  &.success { background: $success; }
  &.blue { background: $blue; }
}

.stat-card-label {
  font-family: $font-mono;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  color: $text-muted;
  margin-bottom: $space-sm;
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

/* ── 关于卡片 ── */
.info-card {
  margin-top: $space-lg;
}

.info-list {
  display: flex;
  flex-direction: column;
  gap: 0;
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
      color: color.adjust($accent, $lightness: -10%);
    }
  }
}
</style>