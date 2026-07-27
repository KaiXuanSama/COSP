<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { NCard, NNumberAnimation } from 'naive-ui'
import ActivityHeatmap from '@/components/heatmap/ActivityHeatmap.vue'
import UsageBreakdownPanel from '@/components/usagechart/UsageBreakdownPanel.vue'
import http from '@/api'
import { createAuthEventSource, type AuthEventSource } from '@/api/authEventSource'
import type { HeatmapModeConfig } from '@/components/heatmap'
import type { BreakdownDimension, BreakdownMetric, UsageBreakdownRow } from '@/components/usagechart'
import { useStatsStore, type StatsData } from '@/stores/stats'

const statsStore = useStatsStore()
const animActive = ref(true)
const heatmapMode = ref<'calls' | 'output' | 'input' | 'total'>('calls')
const heatmapData = ref<HeatmapDay[]>([])
const heatmapLoading = ref(false)
const heatmapFailed = ref(false)

const breakdownRows = ref<UsageBreakdownRow[]>([])
const breakdownLoading = ref(false)
const breakdownFailed = ref(false)

/** 下钻明细的 SSE 连接句柄。非响应式，仅用于生命周期管理。 */
let breakdownSource: AuthEventSource | null = null

// 记录刷新前的旧值，作为动画起点
const prev = ref({ total: 0, today: 0, input: 0, output: 0 })

interface HeatmapDay {
  usageDate: string
  callCount: number
  inputTokens: number
  outputTokens: number
}

const HEATMAP_DAYS = 360

/** 下钻柱状图回看天数。第一版固定 7 天，后续版本再开放切换。 */
const BREAKDOWN_DAYS = 7

/**
 * 维度配置：第一版以供应商为主维度、模型为次维度。
 *
 * 将来要做"以模型为主维度"的视图时，只需把 primaryOf / secondaryOf 与两个 term 对调，
 * 数据层与图表组件均无需改动。
 */
const providerDimension: BreakdownDimension = {
  key: 'provider-model',
  primaryOf: (row) => row.providerKey,
  secondaryOf: (row) => row.modelName,
  primaryTerm: '供应商',
  secondaryTerm: '模型',
}

/** 指标：第一版只有调用次数。 */
const callCountMetric: BreakdownMetric = {
  key: 'calls',
  display: '调用次数',
  unit: '次',
  valueOf: (row) => row.callCount,
}

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
  // 热力图历史数据首屏全量拉取一次；统计卡先 HTTP 兜底一次，随后交给 SSE 实时推送。
  void fetchHeatmap()
  // 柱状图同样是「HTTP 首屏兜底 + SSE 接管」：先拉一次保证立刻有内容，
  // 再建流实时刷新。三级视图仍由前端 pivot，下钻过程中不产生任何请求。
  void fetchBreakdown()
  connectBreakdownStream()
  void statsStore.fetchStats()
  statsStore.connectStream()
})

onUnmounted(() => {
  statsStore.disconnectStream()
  disconnectBreakdownStream()
})

// SSE 每次推送新快照时：驱动数字动画（记录旧值作为起点），并把“今日”单格同步进热力图。
watch(
  () => statsStore.stats,
  async (next, prevStats) => {
    if (!next) return
    if (prevStats) {
      prev.value = {
        total: prevStats.totalApiCalls,
        today: prevStats.todayApiCalls,
        input: prevStats.todayInputTokens,
        output: prevStats.todayOutputTokens,
      }
      // 先关动画、下一帧再开，强制 NNumberAnimation 从 prev 重新滚动到新值。
      animActive.value = false
      await nextTick()
      animActive.value = true
    }
    syncTodayHeatmapCell(next)
  },
  { deep: true },
)

/**
 * 用最新统计快照更新热力图“今日”单格，避免为了单格变化重新全量拉取 360 天数据。
 * 若今日单格尚未在数据集中（如刚跨天），则追加一格。
 */
function syncTodayHeatmapCell(snapshot: StatsData) {
  if (!heatmapData.value.length) return
  const now = new Date()
  const todayStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  const cell: HeatmapDay = {
    usageDate: todayStr,
    callCount: snapshot.todayApiCalls,
    inputTokens: snapshot.todayInputTokens,
    outputTokens: snapshot.todayOutputTokens,
  }
  const index = heatmapData.value.findIndex((day) => day.usageDate === todayStr)
  if (index >= 0) {
    heatmapData.value = heatmapData.value.map((day, i) => (i === index ? cell : day))
  } else {
    heatmapData.value = [...heatmapData.value, cell]
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

/**
 * 拉取下钻柱状图的用量明细。
 *
 * 只请求一次最细粒度数据（日期 × 供应商 × 模型），三级视图与 hover 明细
 * 全部由前端 pivot 得出，因此下钻过程中不再发起请求。
 */
async function fetchBreakdown() {
  if (!breakdownRows.value.length) {
    breakdownLoading.value = true
  }

  try {
    const response = await http.get<UsageBreakdownRow[]>('/usage-breakdown', {
      params: { days: BREAKDOWN_DAYS },
    })
    breakdownRows.value = Array.isArray(response.data) ? response.data : []
    breakdownFailed.value = false
  } catch {
    if (!breakdownRows.value.length) {
      breakdownFailed.value = true
    }
  } finally {
    breakdownLoading.value = false
  }
}

/**
 * 建立柱状图明细的 SSE 连接，让柱子随调用实时增长。
 *
 * 每帧是完整明细列表，整体替换即可，无需增量合并；后端已用 distinctUntilChanged
 * 抑制内容未变的帧，因此这里每次赋值都代表数据真的变了。
 *
 * 与统计卡由同一批调用事件驱动，两处视图不会出现「卡片已涨、柱子还旧」的错位。
 */
function connectBreakdownStream() {
  if (breakdownSource) return
  breakdownSource = createAuthEventSource({
    path: `/usage-breakdown/stream?days=${BREAKDOWN_DAYS}`,
    handlers: {
      breakdown: (data) => {
        try {
          const rows = JSON.parse(data) as UsageBreakdownRow[]
          if (!Array.isArray(rows)) return
          breakdownRows.value = rows
          // 推送成功即视为链路正常：清掉首屏 HTTP 可能留下的失败态。
          breakdownFailed.value = false
          breakdownLoading.value = false
        } catch {
          // 忽略坏帧，保留上一份明细，避免图表闪空。
        }
      },
    },
  })
}

/** 断开柱状图 SSE 连接（离开概览页时调用）。 */
function disconnectBreakdownStream() {
  if (breakdownSource) {
    breakdownSource.close()
    breakdownSource = null
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
        :failed="heatmapFailed" :cell-size="25" empty-text="热力图暂无数据" loading-text="热力图加载中"
        error-text="热力图加载失败" />
    </n-card>

    <n-card class="breakdown-card" :bordered="true">
      <UsageBreakdownPanel :rows="breakdownRows" :dimension="providerDimension" :metric="callCountMetric"
        :days="BREAKDOWN_DAYS" :loading="breakdownLoading" :failed="breakdownFailed" />
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

/*
  下钻柱状图卡片：主题色由页面注入、尺寸由组件自行计算，
  与 .heatmap-card 保持同一套约定。
 */
.breakdown-card {
  --usage-chart-font-mono: 'DM Mono', monospace;
  --usage-chart-font-body: #{$font-body};
  /* 面包屑当前层级要与 .heatmap-title 同字体同字号，故一并注入展示字体 */
  --usage-chart-font-display: #{$font-display};
  --usage-chart-text-primary: #{$text-primary};
  --usage-chart-text-body: #{$text-body};
  --usage-chart-text-muted: #{$text-muted};
  --usage-chart-border-light: #{$border-light};
  --usage-chart-accent: #{$accent};
  --usage-chart-accent-light: #{$accent-light};
  --usage-chart-accent-mid: #{$accent-mid};
  --usage-chart-surface: #{$surface};
  --usage-chart-tooltip-bg: #{$sidebar-bg};
  --usage-chart-tooltip-text: #{$text-light};

  margin-bottom: $space-lg;
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