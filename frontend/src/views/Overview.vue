<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { NCard, NNumberAnimation } from 'naive-ui'
import ActivityHeatmap from '@/components/heatmap/ActivityHeatmap.vue'
import UsageBreakdownPanel from '@/components/usagechart/UsageBreakdownPanel.vue'
import UsageLinePanel from '@/components/usageline/UsageLinePanel.vue'
import http from '@/api'
import { createAuthEventSource, type AuthEventSource } from '@/api/authEventSource'
import type { HeatmapModeConfig } from '@/components/heatmap'
import type {
  BreakdownDimension,
  BreakdownMetric,
  UsageBreakdownDelta,
  UsageBreakdownRow,
} from '@/components/usagechart'
import type { TimelineRange, UsageTimelinePoint } from '@/components/usageline'
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

const timelinePoints = ref<UsageTimelinePoint[]>([])
const timelineLoading = ref(false)
const timelineFailed = ref(false)
/**
 * 折线图默认展示今日时段。
 *
 * 打开概览时最关心的是「现在用得怎么样」，今日曲线直接回答这个问题；
 * 近 7 日更适合回溯，作为切换后的第二视图。
 */
const timelineRange = ref<TimelineRange>('1d')

/** 用量折线的 SSE 连接句柄。 */
let timelineSource: AuthEventSource | null = null

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
 * 维度配置：以供应商为主维度、模型为次维度。
 *
 * 一级按供应商分层，二级横轴为供应商、柱内按模型分层，三级为模型明细。
 */
const providerDimension: BreakdownDimension = {
  key: 'provider-model',
  primaryOf: (row) => row.providerKey,
  secondaryOf: (row) => row.modelName,
  primaryTerm: '供应商',
  secondaryTerm: '模型',
}

/**
 * 维度配置：主次互换 —— 以模型为主维度、供应商为次维度。
 *
 * 这是同一份数据的另一种切法，回答的是「哪个模型用得多、它分散在哪些供应商上」，
 * 而供应商视图回答的是「哪个供应商用得多、它下面跑了哪些模型」。
 *
 * 两份配置只有取值函数与称呼不同：数据层全程经 primaryOf / secondaryOf 取值，
 * 不认业务字段，因此互换主次即得另一套完整的三级下钻，pivot 逻辑与图表零改动。
 */
const modelDimension: BreakdownDimension = {
  key: 'model-provider',
  primaryOf: (row) => row.modelName,
  secondaryOf: (row) => row.providerKey,
  primaryTerm: '模型',
  secondaryTerm: '供应商',
}

/** 两种视图按此顺序轮换。 */
const breakdownDimensions: BreakdownDimension[] = [providerDimension, modelDimension]

/** 当前视图：默认供应商视图。 */
const breakdownDimensionKey = ref<string>(providerDimension.key)

const activeBreakdownDimension = computed(
  () => breakdownDimensions.find((item) => item.key === breakdownDimensionKey.value) ?? providerDimension,
)

/** 按 breakdownDimensions 的顺序轮换到下一个视图，再增加维度也无需改这里。 */
function switchBreakdownDimension() {
  const index = breakdownDimensions.findIndex((item) => item.key === breakdownDimensionKey.value)
  breakdownDimensionKey.value = breakdownDimensions[(index + 1) % breakdownDimensions.length].key
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
  // 柱状图<strong>不</strong>发首屏 HTTP：它的流自带一帧全量快照，
  // 再拉一次只是把同一份数据取两遍，还要处理两者的到达顺序。
  // fetchBreakdown 保留备用 —— 将来窗口切到不含今日的历史区间时，那种窗口不需要实时流。
  connectBreakdownStream()
  // 折线图同样是「HTTP 首屏兜底 + SSE 接管」
  void fetchTimeline()
  connectTimelineStream()
  void statsStore.fetchStats()
  statsStore.connectStream()
})

onUnmounted(() => {
  statsStore.disconnectStream()
  disconnectBreakdownStream()
  disconnectTimelineStream()
})

/**
 * 范围一变就重拉并重建 SSE。
 *
 * 范围写在流的 URL 里，故无法复用旧连接。先断后建而非反过来：
 * 服务端有连接数上限，先建新的会瞬时占用两个名额。
 *
 * 旧点位<strong>不清空</strong>：清空会让折线先消失、待新数据到达再凭空出现，
 * 中间那一帧的空白是无从补救的硬切。留着旧数据则新旧两批之间是一次直接替换，
 * 图表层至少有机会在两者之间做过渡。
 */
watch(timelineRange, () => {
  disconnectTimelineStream()
  void fetchTimeline()
  connectTimelineStream()
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
 *
 * 当前概览页<strong>不调用</strong>它 —— 含今日的窗口由 SSE 的快照帧供首屏数据。
 * 保留是为了将来的日期区间选择器：选到不含今日的历史窗口时，那份数据已经固化，
 * 开实时流无意义，走一次 HTTP 即可。
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
 * 这条流下发两种帧：
 * - `breakdown` —— 完整明细快照，整体替换。每次订阅（含断线重连）只在最开始来一次，
 *   它同时充当首屏数据，故不再另发 HTTP。
 * - `breakdown-delta` —— 单次调用的那一行，累加进已有明细。
 *
 * 快照必定先于增量到达（后端用 concat 保证），否则增量会被随后的快照覆盖。
 */
function connectBreakdownStream() {
  if (breakdownSource) return
  // 首屏数据改由流的快照帧来，故 loading 态也在建流时置上（而非 HTTP 请求前）。
  if (!breakdownRows.value.length) breakdownLoading.value = true
  breakdownSource = createAuthEventSource({
    path: `/usage-breakdown/stream?days=${BREAKDOWN_DAYS}`,
    handlers: {
      breakdown: (data) => {
        try {
          const rows = JSON.parse(data) as UsageBreakdownRow[]
          if (!Array.isArray(rows)) return
          breakdownRows.value = rows
          breakdownFailed.value = false
          breakdownLoading.value = false
        } catch {
          // 忽略坏帧，保留上一份明细，避免图表闪空。
        }
      },
      'breakdown-delta': (data) => {
        try {
          const delta = JSON.parse(data) as UsageBreakdownDelta
          if (!delta?.date) return
          mergeBreakdownDelta(delta)
        } catch {
          // 坏帧只损失这一次计数，下次重连的快照会补齐。
        }
      },
    },
    // 流既是首屏渠道又是实时渠道，失败态只能由它告知；
    // 已有数据时不报错 —— 断线重连不应让已渲染的图表变成错误页。
    onError: () => {
      breakdownLoading.value = false
      if (!breakdownRows.value.length) breakdownFailed.value = true
    },
  })
}

/**
 * 把一帧增量累加进当前明细。
 *
 * <h2>窗口语义优先</h2>
 * 帧的日期不在当前窗口内时<strong>直接丢弃</strong>，而不是把这一天补进去。
 * 典型场景是跨过午夜：新数据属于「明天」，而用户此刻看的窗口并不包含明天，
 * 擅自加一根柱子会让横轴凭空变长。刷新后窗口自然重算，新的一天就出现了。
 *
 * 判定依据是「这一天是否已在窗口里」而非「组合是否已存在」 ——
 * 某供应商今天首次被调用时，它的行本来就不存在，那种情况要插入而非丢弃。
 *
 * 排序、「其余」合并、三级 pivot 全部由 useUsageBreakdown 的 computed 派生，
 * 故这里只管把数字加对，不必关心展示顺序。
 */
function mergeBreakdownDelta(delta: UsageBreakdownDelta) {
  const hit = breakdownRows.value.find(
    (row) =>
      row.date === delta.date &&
      row.providerKey === delta.providerKey &&
      row.modelName === delta.modelName,
  )
  if (hit) {
    hit.callCount += delta.callCount
    return
  }
  if (!breakdownRows.value.some((row) => row.date === delta.date)) return
  breakdownRows.value.push({
    date: delta.date,
    providerKey: delta.providerKey,
    modelName: delta.modelName,
    callCount: delta.callCount,
  })
}

/** 断开柱状图 SSE 连接（离开概览页时调用）。 */
function disconnectBreakdownStream() {
  if (breakdownSource) {
    breakdownSource.close()
    breakdownSource = null
  }
}

/**
 * 拉取 token 用量时间线。
 *
 * 补零与「不补未来」都由后端完成，前端拿到的点位可直接按序绘制。
 */
async function fetchTimeline() {
  if (!timelinePoints.value.length) {
    timelineLoading.value = true
  }

  try {
    const response = await http.get<UsageTimelinePoint[]>('/usage-timeline', {
      params: { range: timelineRange.value },
    })
    timelinePoints.value = Array.isArray(response.data) ? response.data : []
    timelineFailed.value = false
  } catch {
    if (!timelinePoints.value.length) {
      timelineFailed.value = true
    }
  } finally {
    timelineLoading.value = false
  }
}

/**
 * 建立折线图的 SSE 连接。
 *
 * 与统计卡、柱状图由同一批调用事件驱动，三处视图同步刷新。今日时段范围另有一层收益：
 * 后端的定时兜底会随时间推进「当前所在时段」，即使没有新调用，时间轴也会向前延伸。
 */
function connectTimelineStream() {
  if (timelineSource) return
  timelineSource = createAuthEventSource({
    path: `/usage-timeline/stream?range=${timelineRange.value}`,
    handlers: {
      timeline: (data) => {
        try {
          const points = JSON.parse(data) as UsageTimelinePoint[]
          if (!Array.isArray(points)) return
          timelinePoints.value = points
          // 推送成功即视为链路正常：清掉首屏 HTTP 可能留下的失败态。
          timelineFailed.value = false
          timelineLoading.value = false
        } catch {
          // 忽略坏帧，保留上一份点位，避免折线闪空。
        }
      },
    },
  })
}

/** 断开折线图 SSE 连接。 */
function disconnectTimelineStream() {
  if (timelineSource) {
    timelineSource.close()
    timelineSource = null
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
      <!--
        视图切换：供应商视图 / 模型视图，两者是同一份数据的主次维度互换。

        不放在 n-card 的 header 插槽里 —— 面包屑本身已兼作卡片标题，
        再加一行标题就成了双标题。故通过具名插槽塞进面包屑那一行，
        与副标题并列，样式沿用热力图卡片的「当前项 + 切换」范式。
      -->
      <UsageBreakdownPanel :rows="breakdownRows" :dimension="activeBreakdownDimension" :metric="callCountMetric"
        :days="BREAKDOWN_DAYS" :loading="breakdownLoading" :failed="breakdownFailed">
        <template #actions>
          <div class="breakdown-mode-switch">
            <span class="breakdown-mode-label">{{ activeBreakdownDimension.primaryTerm }}视图</span>
            <button type="button" class="breakdown-mode-btn" @click="switchBreakdownDimension">切换</button>
          </div>
        </template>
      </UsageBreakdownPanel>
    </n-card>

    <!--
      token 用量折线。与柱状图分开成卡：折线的横轴是时间（连续量），
      柱状图下钻后横轴变成供应商 / 模型（类别），把类别连成线会暗示不存在的顺序关系。
      两者回答的问题也不同 —— 折线看趋势，柱状图看构成。
    -->
    <n-card class="usage-line-card" :bordered="true">
      <UsageLinePanel
        v-model:range="timelineRange"
        :points="timelinePoints"
        :loading="timelineLoading"
        :failed="timelineFailed"
      />
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

/*
  用量折线卡片：主题色由页面注入，尺寸由组件自算，
  与 .breakdown-card / .heatmap-card 保持同一套约定。
 */
.usage-line-card {
  --usage-line-font-mono: 'DM Mono', monospace;
  --usage-line-font-body: #{$font-body};
  --usage-line-font-display: #{$font-display};
  --usage-line-text-primary: #{$text-primary};
  --usage-line-text-body: #{$text-body};
  --usage-line-text-muted: #{$text-muted};
  --usage-line-border: #{$border};
  --usage-line-accent: #{$accent};
  --usage-line-accent-light: #{$accent-light};
  --usage-line-accent-mid: #{$accent-mid};
  --usage-line-surface: #{$surface};
  --usage-line-tooltip-bg: #{$sidebar-bg};
  --usage-line-tooltip-text: #{$text-light};

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

/*
  用量构成的视图切换。沿用热力图的「当前项 + 切换」范式，
  但不带标题 —— 面包屑已兼作该卡片的标题。
 */
.breakdown-mode-switch {
  display: flex;
  align-items: center;
  gap: $space-sm;
}

.breakdown-mode-label {
  font-family: $font-mono;
  font-size: 10px;
  font-weight: 500;
  color: $text-muted;
  letter-spacing: 0.04em;
}

.breakdown-mode-btn {
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