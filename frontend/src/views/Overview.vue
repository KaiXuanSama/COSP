<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { NCard, NNumberAnimation } from 'naive-ui'
import ActivityHeatmap from '@/components/heatmap/ActivityHeatmap.vue'
import DiscreteRangeSlider from '@/components/rangeslider/DiscreteRangeSlider.vue'
import UsageBreakdownPanel from '@/components/usagechart/UsageBreakdownPanel.vue'
import UsageLinePanel from '@/components/usageline/UsageLinePanel.vue'
import http from '@/api'
import { createAuthEventSource, type AuthEventSource } from '@/api/authEventSource'
import type { HeatmapModeConfig } from '@/components/heatmap'
import type { RangeSelection } from '@/components/rangeslider'
import type { BreakdownDimension, BreakdownMetric } from '@/components/usagechart'
import type { TimelineRange, UsageTimelinePoint } from '@/components/usageline'
import {
  BREAKDOWN_DAYS,
  buildDailyPoints,
  buildHourlyPoints,
  collectHourlySnapshot,
  mergeBreakdownDelta,
  mergeHourlyDelta,
  nextFutureBoundary,
  resolveWindowStart,
  windowDates,
  type TokenTotals,
  type UsageBreakdownRow,
  type UsageHourlyPoint,
  type UsageRecordDelta,
} from '@/features/usage-series'
import { useStatsStore, type StatsData } from '@/stores/stats'

const statsStore = useStatsStore()
const animActive = ref(true)
const heatmapMode = ref<'calls' | 'output' | 'input' | 'total'>('calls')
const heatmapData = ref<HeatmapDay[]>([])
const heatmapLoading = ref(false)
const heatmapFailed = ref(false)

/**
 * 明细行 —— 柱状图与「近 7 日」折线共用。
 *
 * 由 `breakdown` 快照帧整体替换，随后被 `usage-delta` 增量原地累加。
 */
const breakdownRows = ref<UsageBreakdownRow[]>([])

/** 整点桶 → token 累计量，供「今日时段」折线归约成 25 个点位。 */
const hourlyTotals = ref<Map<string, TokenTotals>>(new Map())

/**
 * 图表数据的加载 / 失败态 —— 三张图共用一条流，故共用一组状态。
 *
 * 首屏数据由流的快照帧下发，没有 HTTP 请求可供 catch，失败态只能由
 * {@link createAuthEventSource} 的 onError 告知。
 */
const chartsLoading = ref(false)
const chartsFailed = ref(false)

/** 图表流的连接句柄。非响应式，仅用于生命周期管理。 */
let usageSource: AuthEventSource | null = null

/**
 * 折线图默认展示今日时段。
 *
 * 打开概览时最关心的是「现在用得怎么样」，今日曲线直接回答这个问题；
 * 近 7 日更适合回溯，作为切换后的第二视图。
 */
const timelineRange = ref<TimelineRange>('1d')

/**
 * 两个视图的窗口，在挂载时算定后<strong>冻结</strong>。
 *
 * 冻结是刻意的：跨过午夜（或 5 点）后的新数据属于下一轮，不该挤进用户此刻
 * 看的窗口 —— 那会让横轴凭空变长。刷新后窗口自然重算，新的一轮才出现。
 *
 * 两个窗口的口径<strong>不同</strong>：柱状图与近 7 日按自然日，今日时段按 5 点分界。
 * 因此同一条增量帧可能被一个视图接受、被另一个丢弃（明天凌晨 2 点的调用即是），
 * 两者各自过滤，不共用判定。
 */
const breakdownWindow = ref<string[]>([])
const hourlyWindowStart = ref<Date>(new Date())

/**
 * 驱动「尚未到来」推进的当前时刻。
 *
 * 只在跨过每个半点时更新一次 —— 点位以整点为中心、覆盖前后各半小时，
 * 故已发生点位的集合恰好只在 `HH:30` 变化。这不是把周期放宽的近似，
 * 而是精确命中唯一会让图变化的时刻；中间任何时刻重绘都是纯浪费。
 */
const now = ref<Date>(new Date())

/** 半点推进的定时器句柄。 */
let futureTimer: ReturnType<typeof setTimeout> | null = null

// 记录刷新前的旧值，作为动画起点
const prev = ref({ total: 0, today: 0, input: 0, output: 0 })

interface HeatmapDay {
  usageDate: string
  callCount: number
  inputTokens: number
  outputTokens: number
}

const HEATMAP_DAYS = 360

/**
 * 日期范围选择器的可回看深度（天）—— 与后端 `SlidingDateWindow.MAX_SIZE` 一致。
 *
 * 后端把窗口整体限制在「最近 15 天」这个池子里，宽度上限同样是 15
 * （一个常量兼两职，故约束收敛为 `size + offset <= 15`）。前端刻度数必须等于它，
 * 否则用户能拖到后端会静默钳回来的位置 —— 那种不一致表现为「拖了没反应」。
 */
const DATE_RANGE_POOL_DAYS = 15

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

/**
 * 折线图的点位 —— 两种范围都由同一份流数据派生。
 *
 * 这是纯 computed：增量帧只需改动 `breakdownRows` 或 `hourlyTotals`，
 * 点位、补零、`future` 标记全部自动重算，切换范围也不必重新请求。
 */
const timelinePoints = computed<UsageTimelinePoint[]>(() => {
  if (timelineRange.value === '1d') {
    return buildHourlyPoints(hourlyTotals.value, hourlyWindowStart.value, now.value)
  }
  return buildDailyPoints(breakdownRows.value, breakdownWindow.value)
})

/**
 * 日期范围选择器的可选刻度 —— 最近 {@link DATE_RANGE_POOL_DAYS} 天，升序。
 *
 * <p><strong>当前仅为样式落位</strong>：拖动只更新本组件的状态，不驱动任何图表、
 * 也不发请求。接线要等三个分页端点（`/usage-breakdown/page`、`/usage-daily/page`、
 * `/usage-hourly/series`）在前端接上之后再做。
 *
 * <p>不给 `label` —— 滑块用默认的 `edges` 模式，只在两个手柄下方显示端点日期，
 * 文案由 {@link formatDateRangeEdge} 单独给出。`label` 是给 `all` 模式用的，
 * 那种模式需要隔位标注才不至于挤成一团。
 */
const dateRangeTicks = computed(() =>
  windowDates(now.value, DATE_RANGE_POOL_DAYS).map((date) => ({ key: date })),
)

/** 端点日期文案：`M/D`，横向才放得下。 */
function formatDateRangeEdge(tick: { key: string }): string {
  return `${Number(tick.key.slice(5, 7))}/${Number(tick.key.slice(8, 10))}`
}

/**
 * 当前选中的日期窗口，闭区间下标。
 *
 * 初值是「最靠右的 7 天」，与后端 `size=7, offset=0` 的默认窗口一致 ——
 * 那个窗口的右端是今天，对应下标池的末尾。
 */
const dateRange = ref<RangeSelection>({
  start: DATE_RANGE_POOL_DAYS - BREAKDOWN_DAYS,
  end: DATE_RANGE_POOL_DAYS - 1,
})

/** 把选择读成人话，供无障碍与摘要文案复用。 */
function describeDateRange(selection: RangeSelection): string {
  const ticks = dateRangeTicks.value
  const from = ticks[selection.start]?.key ?? ''
  const to = ticks[selection.end]?.key ?? ''
  return `${from} 至 ${to}，共 ${selection.end - selection.start + 1} 天`
}

const dateRangeText = computed(() => describeDateRange(dateRange.value))


onMounted(() => {
  // 热力图历史数据首屏全量拉取一次；统计卡先 HTTP 兜底一次，随后交给 SSE 实时推送。
  void fetchHeatmap()
  // 三张图表都不发首屏 HTTP：流自带两帧全量快照，
  // 再拉一次只是把同一份数据取两遍，还要处理两者的到达顺序。
  // fetchBreakdown / fetchHourly 保留备用 —— 将来窗口切到不含今日的历史区间时，
  // 那种数据已经固化，开实时流无意义。
  resetWindows()
  connectUsageStream()
  scheduleFutureTick()
  void statsStore.fetchStats()
  statsStore.connectStream()
})

onUnmounted(() => {
  statsStore.disconnectStream()
  disconnectUsageStream()
  clearFutureTick()
})

/**
 * 重算两个视图的窗口，并清空已有数据。
 *
 * 只在挂载时调用 —— 窗口此后冻结，跨天由刷新处理。
 */
function resetWindows() {
  const current = new Date()
  now.value = current
  breakdownWindow.value = windowDates(current, BREAKDOWN_DAYS)
  hourlyWindowStart.value = resolveWindowStart(current)
}

/**
 * 在下一个半点唤醒，推进「尚未到来」的判定。
 *
 * 点位以整点为中心、覆盖前后各半小时，故已发生点位的集合只在时钟跨过 `HH:30`
 * 时变化。定时器因此对准那一刻，而非按固定周期轮询 —— 后者一小时里有 119 次是白跑的。
 *
 * 递归重排而非 `setInterval`：`setTimeout` 的实际唤醒时刻会有漂移，
 * 每次都重新计算到下一个半点的间隔可避免误差累积。
 */
function scheduleFutureTick() {
  clearFutureTick()
  const current = new Date()
  const delay = nextFutureBoundary(current).getTime() - current.getTime()
  futureTimer = setTimeout(() => {
    now.value = new Date()
    scheduleFutureTick()
  }, delay)
}

function clearFutureTick() {
  if (futureTimer !== null) {
    clearTimeout(futureTimer)
    futureTimer = null
  }
}

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
 * 拉取按日期聚合的用量明细。
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
    chartsLoading.value = true
  }

  try {
    const response = await http.get<UsageBreakdownRow[]>('/usage-breakdown', {
      params: { days: BREAKDOWN_DAYS },
    })
    breakdownRows.value = Array.isArray(response.data) ? response.data : []
    chartsFailed.value = false
  } catch {
    if (!breakdownRows.value.length) {
      chartsFailed.value = true
    }
  } finally {
    chartsLoading.value = false
  }
}

/**
 * 拉取今日时段的整点用量。与 {@link fetchBreakdown} 同为历史区间预留，当前不调用。
 */
async function fetchHourly() {
  try {
    const response = await http.get<UsageHourlyPoint[]>('/usage-hourly')
    const points = Array.isArray(response.data) ? response.data : []
    hourlyTotals.value = collectHourlySnapshot(points, hourlyWindowStart.value)
    chartsFailed.value = false
  } catch {
    if (!hourlyTotals.value.size) {
      chartsFailed.value = true
    }
  }
}

/**
 * 建立图表流的 SSE 连接 —— 三张图表共用这一条。
 *
 * 下发三种帧：
 * - `breakdown` —— 按日期聚合的完整明细，整体替换。喂柱状图与「近 7 日」折线。
 * - `hourly` —— 今日时段的整点用量，整体替换。喂「今日时段」折线。
 * - `usage-delta` —— 单条用量记录，按各视图口径累加。
 *
 * 两帧快照必定先于增量到达（后端用 concat 保证），否则增量会被随后的快照覆盖。
 * 断线重连自然重走一遍快照，重连间隙的遗漏由此补齐。
 */
function connectUsageStream() {
  if (usageSource) return
  // 首屏数据改由流的快照帧来，故 loading 态也在建流时置上（而非 HTTP 请求前）。
  if (!breakdownRows.value.length) chartsLoading.value = true
  usageSource = createAuthEventSource({
    path: `/usage/stream?days=${BREAKDOWN_DAYS}`,
    handlers: {
      breakdown: (data) => {
        try {
          const rows = JSON.parse(data) as UsageBreakdownRow[]
          if (!Array.isArray(rows)) return
          breakdownRows.value = rows
          chartsFailed.value = false
          chartsLoading.value = false
        } catch {
          // 忽略坏帧，保留上一份明细，避免图表闪空。
        }
      },
      hourly: (data) => {
        try {
          const points = JSON.parse(data) as UsageHourlyPoint[]
          if (!Array.isArray(points)) return
          hourlyTotals.value = collectHourlySnapshot(points, hourlyWindowStart.value)
          chartsFailed.value = false
          chartsLoading.value = false
        } catch {
          // 同上，保留上一份桶表。
        }
      },
      'usage-delta': (data) => {
        try {
          const delta = JSON.parse(data) as UsageRecordDelta
          if (!delta?.createdAt) return
          applyUsageDelta(delta)
        } catch {
          // 坏帧只损失这一次计数，下次重连的快照会补齐。
        }
      },
    },
    // 流既是首屏渠道又是实时渠道，失败态只能由它告知；
    // 已有数据时不报错 —— 断线重连不应让已渲染的图表变成错误页。
    onError: () => {
      chartsLoading.value = false
      if (!breakdownRows.value.length) chartsFailed.value = true
    },
  })
}

/**
 * 把一帧增量分发给两个视图。
 *
 * <h2>两个窗口各自判定</h2>
 * 柱状图与「近 7 日」按自然日，「今日时段」按 5 点分界。同一帧可能被一个接受、
 * 被另一个丢弃 —— 明天凌晨 02:00 的调用不在柱状图冻结的 7 个日历日里，
 * 却仍落在今日时段窗口内，两边都是对的。故这里调两个独立的合并函数，
 * 不共用一个「是否在窗口内」的判定。
 *
 * 排序、「其余」合并、三级 pivot、补零与 `future` 标记全部由 computed 派生，
 * 故这里只管把数字加对。
 */
function applyUsageDelta(delta: UsageRecordDelta) {
  mergeBreakdownDelta(breakdownRows.value, delta, breakdownWindow.value)
  if (mergeHourlyDelta(hourlyTotals.value, delta, hourlyWindowStart.value)) {
    // Map 的原地修改不触发响应式，换一个引用让 computed 重算。
    hourlyTotals.value = new Map(hourlyTotals.value)
  }
}

/** 断开图表流（离开概览页时调用）。 */
function disconnectUsageStream() {
  if (usageSource) {
    usageSource.close()
    usageSource = null
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
        :days="BREAKDOWN_DAYS" :loading="chartsLoading" :failed="chartsFailed">
        <template #actions>
          <div class="breakdown-mode-switch">
            <span class="breakdown-mode-label">{{ activeBreakdownDimension.primaryTerm }}视图</span>
            <button type="button" class="breakdown-mode-btn" @click="switchBreakdownDimension">切换</button>
          </div>
        </template>
      </UsageBreakdownPanel>

      <!--
        日期范围选择器。

        当前<strong>只是样式落位</strong>：拖动改变的只有本组件的选择状态，
        柱状图仍按流下发的固定 7 天窗口渲染。接线要等三个分页端点在前端接上。

        放在柱状图下方而非卡片 header：它控制的是横轴范围，紧贴横轴才让
        「拖它 → 轴变」这层因果关系一眼可见；放到标题行则与视图切换按钮抢位置。
      -->
      <div class="breakdown-range">
        <DiscreteRangeSlider
          v-model="dateRange"
          :ticks="dateRangeTicks"
          :min-span="BREAKDOWN_DAYS"
          :max-span="DATE_RANGE_POOL_DAYS"
          aria-label="日期范围"
          :format-value-text="describeDateRange"
          :format-edge-label="formatDateRangeEdge"
        />
        <div class="breakdown-range-meta">
          <span class="breakdown-range-text">{{ dateRangeText }}</span>
          <span class="breakdown-range-hint">拖动端点调整跨度，拖动色块整体平移</span>
        </div>
      </div>
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
        :loading="chartsLoading"
        :failed="chartsFailed"
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

/*
  日期范围选择器所在的一条。

  上边框把它与柱状图分开：两者是「控件」与「被控对象」的关系，
  一条细线足以表明这不是图表的一部分，又不至于像另开一张卡那样割裂。
 */
.breakdown-range {
  margin-top: $space-md;
  padding-top: $space-md;
  border-top: 1px solid $border-light;
}

/*
  窗口摘要与操作提示。

  摘要给出精确日期 —— 刻度只隔位标注，光看滑块读不出确切区间。
  提示文案说明三种手势，因为「色块本身可拖」在视觉上没有明显线索。
 */
.breakdown-range-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: $space-md;
  margin-top: $space-sm;
}

.breakdown-range-text {
  font-family: $font-mono;
  font-size: 11px;
  font-weight: 500;
  color: $text-body;
}

.breakdown-range-hint {
  font-family: $font-mono;
  font-size: 10px;
  color: $text-muted;
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