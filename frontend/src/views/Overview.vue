<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { NCard, NNumberAnimation } from 'naive-ui'
import ActivityHeatmap from '@/components/heatmap/ActivityHeatmap.vue'
import http from '@/api'
import type { HeatmapModeConfig } from '@/components/heatmap'
import { useStatsStore } from '@/stores/stats'

const statsStore = useStatsStore()
const animActive = ref(true)
const heatmapMode = ref<'calls' | 'output' | 'input' | 'total'>('calls')
const heatmapData = ref<HeatmapDay[]>([])
const heatmapLoading = ref(false)
const heatmapFailed = ref(false)

// 记录刷新前的旧值，作为动画起点
const prev = ref({ total: 0, today: 0, input: 0, output: 0 })

let timer: ReturnType<typeof setInterval> | null = null
let requestInFlight = false

interface HeatmapDay {
  usageDate: string
  callCount: number
  inputTokens: number
  outputTokens: number
}

const HEATMAP_DAYS = 360
const POLL_INTERVAL = 5000
const heatmapModeOrder: Array<'calls' | 'output' | 'input' | 'total'> = ['calls', 'output', 'input', 'total']
const heatmapModes: HeatmapModeConfig<HeatmapDay>[] = [
  {
    key: 'calls',
    display: 'API 调用',
    unit: '次',
    valueKey: 'callCount',
    colors: [
      'var(--heatmap-border-light)',
      'rgba(194, 122, 62, 0.15)',
      'rgba(194, 122, 62, 0.35)',
      'rgba(194, 122, 62, 0.6)',
      'var(--heatmap-accent)',
    ],
  },
  {
    key: 'output',
    display: '输出 Token',
    unit: 'token',
    valueKey: 'outputTokens',
    colors: [
      'var(--heatmap-border-light)',
      'rgba(58, 138, 92, 0.15)',
      'rgba(58, 138, 92, 0.35)',
      'rgba(58, 138, 92, 0.6)',
      '#3a8a5c',
    ],
  },
  {
    key: 'input',
    display: '输入 Token',
    unit: 'token',
    valueKey: 'inputTokens',
    colors: [
      'var(--heatmap-border-light)',
      'rgba(90, 122, 184, 0.15)',
      'rgba(90, 122, 184, 0.35)',
      'rgba(90, 122, 184, 0.6)',
      '#5a7ab8',
    ],
  },
  {
    key: 'total',
    display: '总 Token',
    unit: 'token',
    getValue: (item) => item.inputTokens + item.outputTokens,
    colors: [
      'var(--heatmap-border-light)',
      'rgba(74, 71, 64, 0.15)',
      'rgba(74, 71, 64, 0.35)',
      'rgba(74, 71, 64, 0.6)',
      '#4a4740',
    ],
  },
]

const activeHeatmapMode = computed(() => {
  return heatmapModes.find((mode) => mode.key === heatmapMode.value) ?? heatmapModes[0]
})

onMounted(() => {
  void refreshOverviewData(false)
  timer = setInterval(() => {
    void refreshOverviewData(true)
  }, POLL_INTERVAL)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

async function refreshOverviewData(animateNumbers: boolean) {
  if (requestInFlight) return

  requestInFlight = true
  try {
    if (animateNumbers && statsStore.stats) {
      const s = statsStore.stats
      prev.value = { total: s.totalApiCalls, today: s.todayApiCalls, input: s.todayInputTokens, output: s.todayOutputTokens }
      animActive.value = false
      await nextTick()
    }

    await Promise.all([statsStore.fetchStats(), fetchHeatmap()])
  } finally {
    if (animateNumbers && statsStore.stats) {
      animActive.value = true
    }
    requestInFlight = false
  }
}

async function fetchHeatmap() {
  if (!heatmapData.value.length) {
    heatmapLoading.value = true
  }

  try {
    const response = await http.get<HeatmapDay[]>('/heatmap', { params: { days: HEATMAP_DAYS } })
    heatmapData.value = Array.isArray(response.data) ? response.data : []
    heatmapFailed.value = false
  } catch {
    if (!heatmapData.value.length) {
      heatmapFailed.value = true
    }
  } finally {
    heatmapLoading.value = false
  }
}

function switchHeatmapMode() {
  const currentIndex = heatmapModeOrder.indexOf(heatmapMode.value)
  heatmapMode.value = heatmapModeOrder[(currentIndex + 1) % heatmapModeOrder.length]
}

function toKUnit(value: number): number {
  return value >= 100_000 ? value / 1000 : value
}
</script>

<template>
  <div class="overview-page">
    <div class="stats-grid">
      <n-card class="stat-card" :bordered="true">
        <template #header>
          <div class="stat-card-label">API 调用总次数</div>
        </template>
        <div class="stat-card-value">
          <n-number-animation v-if="statsStore.stats" :active="animActive" :from="prev.total"
            :to="statsStore.stats.totalApiCalls" :duration="800" />
          <span v-else class="stat-card-placeholder">—</span>
        </div>
        <div class="stat-card-desc">自服务启动以来累计</div>
      </n-card>

      <n-card class="stat-card" :bordered="true">
        <template #header>
          <div class="stat-card-label">今日 API 调用次数</div>
        </template>
        <div class="stat-card-value">
          <n-number-animation v-if="statsStore.stats" :active="animActive" :from="prev.today"
            :to="statsStore.stats.todayApiCalls" :duration="800" />
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
            <span class="stat-card-token-group">
              <n-number-animation :active="animActive" :from="toKUnit(prev.input)" :to="toKUnit(statsStore.stats.todayInputTokens)"
                :precision="statsStore.stats.todayInputTokens >= 100_000 ? 2 : 0" :duration="800" />
              <span v-if="statsStore.stats.todayInputTokens >= 100_000" class="stat-card-token-k">k</span>
            </span>
            <span class="stat-card-token-sep">/</span>
            <span class="stat-card-token-group">
              <n-number-animation :active="animActive" :from="toKUnit(prev.output)" :to="toKUnit(statsStore.stats.todayOutputTokens)"
                :precision="statsStore.stats.todayOutputTokens >= 100_000 ? 2 : 0" :duration="800" />
              <span v-if="statsStore.stats.todayOutputTokens >= 100_000" class="stat-card-token-k">k</span>
            </span>
          </template>
          <span v-else class="stat-card-placeholder">—</span>
        </div>
        <div class="stat-card-desc">输入 / 输出</div>
      </n-card>
    </div>

    <n-card class="heatmap-card" :bordered="true">
      <template #header>
        <div class="heatmap-header">
          <div class="heatmap-title">热力图</div>
          <div class="heatmap-mode-switch">
            <span class="heatmap-mode-label">{{ activeHeatmapMode.display }}</span>
            <button type="button" class="heatmap-mode-btn" @click="switchHeatmapMode">切换</button>
          </div>
        </div>
      </template>

      <ActivityHeatmap :data="heatmapData" :modes="heatmapModes" :active-mode="heatmapMode" :loading="heatmapLoading"
        :failed="heatmapFailed" empty-text="热力图暂无数据" loading-text="热力图加载中" error-text="热力图加载失败" />
    </n-card>

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

.stat-card-token-group {
  display: inline-flex;
  align-items: baseline;
}

.stat-card-token-k {
  font-size: 14px;
  color: $text-muted;
  margin-left: 1px;
}

.stat-card-desc {
  font-family: $font-body;
  font-size: 13px;
  color: $text-muted;
}

.heatmap-card {
  --heatmap-font-mono: 'DM Mono', monospace;
  --heatmap-text-muted: #{$text-muted};
  --heatmap-border-light: #{$border-light};
  --heatmap-accent: #{$accent};
  --heatmap-tooltip-bg: #{$sidebar-bg};
  --heatmap-tooltip-text: #{$text-light};

  margin-bottom: $space-lg;

  :deep(.n-card-header) {
    padding-bottom: 0;
  }
}

.heatmap-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-md;
}

.heatmap-title {
  font-family: $font-display;
  font-size: 22px;
  font-weight: 600;
  color: $text-primary;
}

.heatmap-mode-switch {
  display: flex;
  align-items: center;
  gap: $space-sm;
}

.heatmap-mode-label {
  font-family: $font-mono;
  font-size: 10px;
  font-weight: 500;
  color: $text-muted;
  letter-spacing: 0.04em;
}

.heatmap-mode-btn {
  padding: 3px 12px;
  border: 1px solid $border;
  border-radius: 6px;
  background: $surface;
  color: $text-body;
  cursor: pointer;
  font-family: $font-mono;
  font-size: 10px;
  font-weight: 500;
  transition: all 0.2s ease;

  &:hover {
    border-color: $accent;
    color: $accent;
    background: $accent-light;
  }
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

@media (max-width: 768px) {
  .heatmap-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>