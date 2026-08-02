<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'
import { NCard, NNumberAnimation } from 'naive-ui'
import ActivityHeatmap from '@/components/heatmap/ActivityHeatmap.vue'
import DiscreteRangeSlider from '@/components/rangeslider/DiscreteRangeSlider.vue'
import UsageBreakdownPanel from '@/components/usagechart/UsageBreakdownPanel.vue'
import UsageLinePanel from '@/components/usageline/UsageLinePanel.vue'
import http from '@/api'
import { createAuthEventSource, type AuthEventSource } from '@/api/authEventSource'
import type { HeatmapModeConfig } from '@/components/heatmap'
import {
  canPageNext,
  canPagePrev,
  normalizePagedWindow,
  pendingPageDelta,
  resolvePagedConfig,
  shiftPagedWindow,
  toLocalReachable,
  toLocalSelection,
  withManualSelection,
  type PagedWindowState,
  type RangeSelection,
  type RangeSliderTick,
} from '@/components/rangeslider'
import type { BreakdownDimension, BreakdownMetric } from '@/components/usagechart'
import type { TimelineRange, UsageTimelinePoint } from '@/components/usageline'
import {
  BREAKDOWN_DAYS,
  buildDailyPoints,
  buildHourlyPoints,
  collectHourlySeriesResponse,
  collectHourlySnapshot,
  dayOffsetBetween,
  hourlyWindowStartOf,
  isClockAligned,
  liveAnchorDate,
  liveAnchorOffset,
  mergeBreakdownDelta,
  mergeHourlyDelta,
  nextFutureBoundary,
  parseLocalDay,
  resolveSelectableAxis,
  resolveWindowStart,
  selectHourlyTotals,
  windowDates,
  windowDatesBetween,
  windowKeyOf,
  windowParamsOf,
  type HourlyHistoryCache,
  type TokenTotals,
  type UsageBreakdownPage,
  type UsageBreakdownRow,
  type UsageDateRangeMeta,
  type UsageHourlyPoint,
  type UsageHourlySeriesResponse,
  type UsageRecordDelta,
} from '@/features/usage-series'
import { createDwellTrigger } from '@/utils/dwell'
import { useStatsStore, type StatsData } from '@/stores/stats'

const statsStore = useStatsStore()
const animActive = ref(true)
const heatmapMode = ref<'calls' | 'output' | 'input' | 'total'>('calls')
const heatmapData = ref<HeatmapDay[]>([])
const heatmapLoading = ref(false)
const heatmapFailed = ref(false)

/**
 * 按日明细 —— 柱状图与「近 N 日」折线共用的<strong>实时栈</strong>。
 *
 * <p>只由 SSE 喂养（`breakdown` 快照帧整体替换、`usage-delta` 增量累加），
 * <strong>永远不被 HTTP 触碰</strong>。这是双栈的一半，与 {@link hourlyTotals}
 * 同理：若切回实时窗口时重新 HTTP 拉一遍，请求与增量帧的到达顺序不可控，
 * 会静默多算或少算。另一半是 {@link breakdownHistory}，分流见
 * {@link readDisplayWindow}。
 */
const breakdownRows = ref<UsageBreakdownRow[]>([])

/**
 * 整点桶 → token 累计量 —— <strong>实时栈</strong>。
 *
 * <p>只由 SSE 喂养（`hourly` 快照帧打底、`usage-delta` 增量累加），
 * <strong>永远不被 HTTP 触碰</strong>。这是双栈的一半，理由见
 * `features/usage-series/hourlyDay.ts` 的模块说明：若切回今天时重新 HTTP 拉一遍，
 * 请求与增量帧的到达顺序不可控，会静默多算或少算。
 */
const hourlyTotals = ref<Map<string, TokenTotals>>(new Map())

/**
 * 历史日期的桶表缓存 —— 双栈的另一半，只由 HTTP 喂养。
 *
 * <p>不含当前时刻的窗口数据已固化，故缓存永不失效，来回滑动时同一天只请求一次。
 * 实时窗口不进这里 —— 它一直在变，缓存它等于把它冻住。
 */
const hourlyHistory = ref<HourlyHistoryCache>(new Map())

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
 * 两个窗口的口径<strong>不同</strong>：柱状图与近 N 日按自然日，今日时段按 5 点分界。
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
 * 后端给出的用量日期范围 —— 选择器软墙的唯一数据源。
 *
 * <p>null 表示尚未加载或加载失败，此时 {@link resolveSelectableAxis} 放开整个池。
 */
const usageDateRange = ref<UsageDateRangeMeta | null>(null)

/**
 * 由后端范围换算出的轴约束（软墙位置与跨度下限）。
 *
 * <p>两个选择器共用这一份 —— 「往前能拖到哪一天」是数据事实，不因图表而异；
 * 至于各自停在哪一段，那才是两个选择器独立的部分。
 *
 * <p>依赖 {@link now} 是必要的：跨过午夜后原点前移一天，软墙的相对位置随之改变。
 */
const selectableAxis = computed(() =>
  resolveSelectableAxis(usageDateRange.value, now.value, {
    preferredSpan: BREAKDOWN_DAYS,
    poolDays: DATE_RANGE_POOL_DAYS,
  }),
)

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
 * 折线图的点位。
 *
 * <p>「近 N 日」按<strong>显示窗口</strong>（而非选中窗口）取明细并归约 ——
 * 与今日时段同一套「显示滞后」逻辑，避免滑到未加载的区间时先清零再长回来。
 * 「今日时段」则按显示日从双栈中取出对应桶表，再归约成 25 个点位。
 *
 * <p>`future` 标记仍按真实时刻算，故历史日期不会有任何点被判成未来
 * （那一天早已过完），这是自然结果而非特例。
 */
const timelinePoints = computed<UsageTimelinePoint[]>(() => {
  if (timelineRange.value === '1d') {
    return buildHourlyPoints(
      hourlyDisplay.value.totals,
      hourlyDisplayWindowStart.value,
      now.value,
    )
  }
  return buildDailyPoints(timelineDisplay.value.rows, timelineDisplay.value.dates)
})

/**
 * 日期范围选择器的绝对轴 —— 以「今天」为 0，往前为负。
 *
 * <p>翻页让可见的 15 天池在这条无界轴上平移，故池的位置不能用「距今天几天」
 * 这种相对量表达，必须有一个固定原点。选今天作原点是因为右侧硬墙恰好是
 * `0`（明天及之后还没发生），式子最简洁。
 *
 * <p><strong>当前仍是样式落位</strong>：翻页与拖动只改本组件状态，不驱动图表、
 * 不发请求。接线要等三个分页端点在前端接上 —— 已接上的只有软墙位置
 * （`/config/api/usage-date-range`）。
 */
/**
 * 日期选择器是否处于单点形态。
 *
 * <p>接线后应由折线图的范围切换驱动：「今日时段」看的是某<strong>一天</strong>
 * 的时段分布，选择器该退化成单点；「近 N 日」看的是一段区间，选择器是区间形态。
 * 组件靠 `maxSpan === 1` 自动切换形态并播放交叉淡化，故这里只需给出跨度约束。
 */
const dateSinglePoint = ref(false)

/**
 * 两个选择器共用的轴配置工厂。
 *
 * <p>软墙与跨度下限都来自 {@link selectableAxis}，只有「是否单点」与硬墙位置
 * 因图表而异。抽成函数是为了让差异只剩这两个参数 —— 各写一遍时，
 * 将来改软墙来源漏掉一处不会有编译错误，只会让两个选择器的可达区间悄悄分叉。
 *
 * @param singlePoint  是否为单点形态
 * @param reachableEnd 硬墙位置（绝对索引），默认 0 即今天
 */
function buildAxisConfig(singlePoint: boolean, reachableEnd = 0) {
  const axis = selectableAxis.value
  return resolvePagedConfig({
    poolSize: DATE_RANGE_POOL_DAYS,
    // 单点形态跨度恒为 1；区间形态用 axis.minSpan —— 正常是 7，
    // 但后端只有不足 7 天数据时它会收缩为实际可选天数。
    minSpan: singlePoint ? 1 : axis.minSpan,
    maxSpan: singlePoint ? 1 : DATE_RANGE_POOL_DAYS,
    // 软墙：可选的最早一天，由后端的最早记录日期与回看深度共同决定。
    // 更早的刻度仍会渲染，只是标成不可达（空心点）—— 删掉它们会让用户
    // 以为轴就这么长，看不出「更早的数据查不了」这个事实。
    reachableStart: axis.reachableStart,
    // 硬墙：默认今天。明天及之后还没发生，池不该越过它露出一片未来的灰点。
    // 刻意<strong>不</strong>用后端的 latestDate —— 今天没有调用不等于今天不可选，
    // 它随时可能产生第一条记录，且是默认视图的右端。
    //
    // 时段视图会传入实时窗口的锚定日：凌晨 5 点前那一轮锚在昨天，
    // 此时「今天」的窗口整段都在未来，选它只会得到一张全零的图。
    reachableEnd,
  })
}

const dateAxisConfig = computed(() => buildAxisConfig(dateSinglePoint.value))

/**
 * 窗口状态（绝对索引）。
 *
 * <p>初值是「池贴着今天、期望区间为最近 7 天」，与后端 `size=7, offset=0` 一致。
 * 它按<strong>未加载</strong>时的宽松轴（整个池可达）建立，随后由
 * {@link dateAxisConfig} 的 watch 收敛到实际约束下。
 *
 * <p>状态里<strong>没有块</strong>：块是「期望区间 ∩ 可用区间」的派生结果。
 * 翻页只移动池，期望区间原样不动 —— 于是「选中的日期」在翻页时尽量保持，
 * 而块在池中的相对位置随之改变。这与「块跟着池一起平移」正好相反，
 * 后者会让选中的日期随翻页而改变。
 */
const dateWindow = ref<PagedWindowState>({
  poolStart: -(DATE_RANGE_POOL_DAYS - 1),
  desiredStart: -(BREAKDOWN_DAYS - 1),
  desiredEnd: 0,
})

/**
 * 轴约束变化后把窗口收敛到新约束下。
 *
 * <p>触发时机有两个：日期范围响应到达（软墙从池左端收窄到实际位置），
 * 以及跨过午夜（原点前移）。不收敛的话期望区间可能落在软墙之外，
 * 组件会自行钳制显示，但下一次翻页的基准仍是那个越界的旧值 ——
 * 状态与显示随即脱节。
 *
 * <p>`immediate` 是必需的：初值就是要被收敛的对象，而轴可能永不变化
 * （请求失败时 `usageDateRange` 一直是 null）。与折线图那个 watch 同理。
 */
watch(
  dateAxisConfig,
  (config) => {
    dateWindow.value = normalizePagedWindow(dateWindow.value, config)
  },
  { immediate: true },
)

/**
 * 绝对索引 → 日期串。
 *
 * <p>0 是今天，−1 是昨天。用 {@link DAY_MS} 做算术而非 `setDate`，
 * 与 `features/usage-series/localTime` 的做法一致。
 */
function dateAtOffset(offset: number): string {
  const day = new Date(now.value)
  day.setHours(0, 0, 0, 0)
  day.setDate(day.getDate() + offset)
  const month = `${day.getMonth() + 1}`.padStart(2, '0')
  const date = `${day.getDate()}`.padStart(2, '0')
  return `${day.getFullYear()}-${month}-${date}`
}

/**
 * 池内的刻度 —— 由池位置从绝对轴上切出来。
 *
 * <p>不给 `label`：滑块用默认的 `edges` 模式，只在两个手柄下方显示端点日期，
 * 文案由 {@link formatDateRangeEdge} 单独给出。`label` 是给 `all` 模式用的，
 * 那种模式需要隔位标注才不至于挤成一团。
 */
const dateRangeTicks = computed(() => {
  const ticks: RangeSliderTick[] = []
  for (let i = 0; i < DATE_RANGE_POOL_DAYS; i += 1) {
    ticks.push({ key: dateAtOffset(dateWindow.value.poolStart + i) })
  }
  return ticks
})

/**
 * 日期串 → `M/D`。
 *
 * <p>去掉年份与前导零：可选范围只有最近十几天，年份永远是当前年，写出来只是占位；
 * 前导零在横向排布里也只是加宽。需要完整日期的地方（选择器下方的摘要）另行给出。
 */
function formatMonthDay(date: string): string {
  return `${Number(date.slice(5, 7))}/${Number(date.slice(8, 10))}`
}

/** 端点日期文案：`M/D`，横向才放得下。 */
function formatDateRangeEdge(tick: { key: string }): string {
  return formatMonthDay(tick.key)
}

/** 块在池内的局部下标 —— 滑块的 `modelValue`。由期望区间与可用区间派生。 */
const dateRange = computed(() => toLocalSelection(dateWindow.value, dateAxisConfig.value))

/** 可达区间的局部下标。可能越界，滑块会自行钳制。 */
const dateReachable = computed(() => toLocalReachable(dateWindow.value, dateAxisConfig.value))

/**
 * 手动拖动 / 键盘操作后写回。
 *
 * <p>走 {@link withManualSelection} 而非直接赋值 —— 它会把局部下标换成绝对索引
 * 并刷新<strong>期望区间</strong>，那是期望区间唯一的写入点。翻页绝不能走这里，
 * 否则块被挤压一次期望就永久变窄了，往返便不可逆。
 */
function onDateRangeUpdate(next: RangeSelection) {
  dateWindow.value = withManualSelection(dateWindow.value, next, dateAxisConfig.value)
}

/**
 * 翻页。
 *
 * <p>`single` 走一格，`page` 补齐到整页 —— 后者是在<strong>已走一格之后</strong>
 * 补上剩余位移（组件的单击不等双击判定窗口，见它的 `page` 事件说明），
 * 故用 {@link pendingPageDelta} 而非直接翻一页。位移可叠加使这个技巧成立。
 */
function onDateRangePage(payload: { direction: -1 | 1; step: 'single' | 'page' }) {
  const config = dateAxisConfig.value
  const delta =
    payload.step === 'single'
      ? payload.direction
      : pendingPageDelta(dateWindow.value, payload.direction, config)
  dateWindow.value = shiftPagedWindow(dateWindow.value, delta, config)
}

const canDatePagePrev = computed(() => canPagePrev(dateWindow.value, dateAxisConfig.value))
const canDatePageNext = computed(() => canPageNext(dateWindow.value, dateAxisConfig.value))

/**
 * 把选择读成人话 —— 供滑块的 `formatValueText` 使用（无障碍 `aria-valuetext`）。
 *
 * <p>视觉上不再有摘要行（见模板里的说明），但屏幕阅读器仍需要一句完整描述 ——
 * 光报索引号读不出「选的是哪几天」。
 */
function describeDateRange(selection: RangeSelection): string {
  const ticks = dateRangeTicks.value
  const from = ticks[selection.start]?.key ?? ''
  const to = ticks[selection.end]?.key ?? ''
  return `${from} 至 ${to}，共 ${selection.end - selection.start + 1} 天`
}

/**
 * 折线图下方那个选择器的窗口状态 —— 与柱状图那个<strong>各自独立</strong>。
 *
 * <p>两者服务不同的图表，用户可能想让柱状图停在某一段而折线图看另一段，
 * 共用一份状态会让两个视图互相牵制。接线后若确认「一份状态驱动全部图表」
 * 更符合预期，再合并即可 —— 现在保持独立是更小的假设。
 *
 * <p><strong>形态随折线图的范围切换联动</strong>：
 * 「今日时段」看的是某一天的时段分布，选择器退化为单点；
 * 「近 N 日」看的是一段区间，选择器是区间形态。
 * 组件靠 `maxSpan === 1` 自动切形态并播放交叉淡化。
 */
const timelineSinglePoint = computed(() => timelineRange.value === '1d')

/**
 * 时段视图「最新可看的那一天」相对今天的偏移 —— 0 或 −1。
 *
 * <p>一天从 05:00 起算，故凌晨 5 点前那一轮锚定在昨天。此时「今天」对应的窗口
 * `[今天 05:00, 明天 05:00)` 整段都在未来，选它只会得到一张全零的图，
 * 故它该是不可达的。
 *
 * <p>只在单点形态下生效：区间形态（近 N 日）按日历日聚合，今天有意义。
 */
const timelineReachableEnd = computed(() =>
  timelineSinglePoint.value ? liveAnchorOffset(now.value) : 0,
)

const timelineAxisConfig = computed(() =>
  buildAxisConfig(timelineSinglePoint.value, timelineReachableEnd.value),
)

/**
 * 折线图选择器的窗口状态。
 *
 * <p>初值与柱状图那个一致（池贴着今天、期望区间为最近 7 天），同样按未加载时的
 * 宽松轴建立，由下面的 watch 收敛。切到单点形态时期望区间会被
 * `normalizePagedWindow` 收成一天 —— 收在<strong>右端</strong>
 * （`desiredEnd` 不动、`desiredStart` 跟上来），
 * 因为「今日时段」关心的是最近那一天而非区间的起点。
 */
const timelineWindow = ref<PagedWindowState>({
  poolStart: -(DATE_RANGE_POOL_DAYS - 1),
  desiredStart: -(BREAKDOWN_DAYS - 1),
  desiredEnd: 0,
})

/**
 * 形态切换后把窗口收敛到新约束下。
 *
 * <p>必须显式做这一步：`resolvePagedConfig` 变了，但 `timelineWindow` 里的
 * 期望区间还是旧的（比如 7 天），而单点形态只允许 1 天。
 * 不收敛的话组件会拿到一个超宽的 `modelValue`，虽然它自己会钳制，
 * 但下一次翻页的基准仍是那个旧值 —— 状态与显示随即脱节。
 *
 * <p>收敛方向：`normalizePagedWindow` 在跨度超上限时<strong>从右侧收</strong>，
 * 于是 7 天区间会退化成它的<strong>起点</strong>那一天。这与「今日时段该看最近一天」
 * 的直觉相反，故先把期望区间对齐到右端再交给它。
 *
 * <h2>单点 → 区间时池也要贴回右端</h2>
 * 单点形态的池位置边界与区间形态<strong>不同</strong>：凌晨 5 点前
 * `reachableEnd = -1`，单点形态的 `poolStartMax` 随之变成 `-15`，
 * 挂载时初始 `poolStart = -14` 超出上界被钳到 `-15` —— 池右端只到 `-1`，
 * 池里不包含绝对 0（今天）。这个位置在单点形态下是贴右端的、完全正确；
 * 但切到区间形态后 `poolStartMax` 回到 `-14`，而 `-15` 仍落在新边界内，
 * 于是池<strong>不会</strong>自动移回来 —— 结果就是「近 7 日」看不到今天，
 * 块显示成 07-26~08-01 而非 07-27~08-02，还毫无迹象。
 *
 * <p>故切到区间形态时把池右端贴回硬墙（`poolStart = poolStartMax`）：
 * 区间视图语义就是「最近 N 天」，右端本就该是今天。只在<strong>单点 → 区间</strong>
 * 这个方向做 —— 用户翻页停留在历史区间后切换形态，不该被强行拉回右端。
 *
 * <h2>为什么必须 immediate</h2>
 * `timelineRange` 的初值就是 `'1d'`（单点形态），也就是说组件<strong>一挂载</strong>
 * 就该是单点。非 immediate 的 watch 要等 config 第一次<strong>变化</strong>才触发，
 * 而它此后可能永远不变（用户不切范围）—— 那样初始窗口就一直是那份 7 天的旧值，
 * 显示出来是「区间的起点那一天」而非今天。
 *
 * <p>这与柱状图 `useStackMorph` 那个 watch 缺 `immediate` 的 bug 是同一类：
 * 「初始值已经是目标状态」时，只监听变化的 watch 永远不会为它跑一次。
 */
watch(
  timelineAxisConfig,
  (config, previousConfig) => {
    const previous = timelineWindow.value
    const wasSingle = previousConfig ? previousConfig.maxSpan === 1 : false
    const anchored = config.maxSpan === 1
      ? { ...previous, desiredStart: previous.desiredEnd }
      : wasSingle
        ? { ...previous, poolStart: config.poolStartMax }
        : previous
    timelineWindow.value = normalizePagedWindow(anchored, config)
  },
  { immediate: true },
)

const timelineTicks = computed(() => {
  const ticks: RangeSliderTick[] = []
  for (let i = 0; i < DATE_RANGE_POOL_DAYS; i += 1) {
    ticks.push({ key: dateAtOffset(timelineWindow.value.poolStart + i) })
  }
  return ticks
})

const timelineSelection = computed(() =>
  toLocalSelection(timelineWindow.value, timelineAxisConfig.value),
)

const timelineReachable = computed(() =>
  toLocalReachable(timelineWindow.value, timelineAxisConfig.value),
)

function onTimelineRangeUpdate(next: RangeSelection) {
  timelineWindow.value = withManualSelection(timelineWindow.value, next, timelineAxisConfig.value)
}

function onTimelineRangePage(payload: { direction: -1 | 1; step: 'single' | 'page' }) {
  const config = timelineAxisConfig.value
  const delta =
    payload.step === 'single'
      ? payload.direction
      : pendingPageDelta(timelineWindow.value, payload.direction, config)
  timelineWindow.value = shiftPagedWindow(timelineWindow.value, delta, config)
}

const canTimelinePagePrev = computed(() =>
  canPagePrev(timelineWindow.value, timelineAxisConfig.value),
)
const canTimelinePageNext = computed(() =>
  canPageNext(timelineWindow.value, timelineAxisConfig.value),
)

/* ---------- 「今日时段」按日期回看 ---------- */

/**
 * 选中的锚定日 —— 时段视图的数据键。
 *
 * <p>取选择的<strong>右端</strong>：单点形态下两端相同，而切换形态的那一两帧里
 * 期望区间可能还是 7 天宽，取右端得到的是「最近那一天」，与形态收敛的方向一致。
 */
const hourlySelectedDate = computed(() => {
  const ticks = timelineTicks.value
  return ticks[timelineSelection.value.end]?.key ?? liveAnchorDate(now.value)
})

/** 实时窗口的锚定日 —— 双栈的分流依据。凌晨 5 点前是昨天。 */
const hourlyLiveDate = computed(() => liveAnchorDate(now.value))

/**
 * <strong>当前正在显示</strong>的锚定日 —— 与 {@link hourlySelectedDate} 分离。
 *
 * <h2>为什么不直接渲染选中日</h2>
 * 那样滑到一个尚未加载的日期时，图表会先整片归零、等响应到达才长回来。
 * 而滑动是连续动作，一路上每个点都要闪一次白 —— 读起来像「这几天都没有用量」，
 * 而实情是「还没加载」。两者在折线上长得一模一样（都是贴底的直线），
 * 无法靠观察区分。
 *
 * <p>故显示日<strong>滞后</strong>于选中日：只在新数据就绪的那一刻才跟上，
 * 之前一直保持上一份可用数据。代价是加载途中图与选择器短暂不同步 ——
 * 相比每滑一格都闪一次空白，这是更小的代价（停留触发只需几百毫秒，
 * 且缓存命中时是瞬时的）。
 *
 * <h2>为什么存日期而不是快照</h2>
 * 存一份 totals 快照会让实时窗口<strong>冻住</strong> —— SSE 继续往实时栈里累加，
 * 而画面停在快照那一刻。只存日期，渲染时再经 {@link selectHourlyTotals} 取表，
 * 实时窗口的响应式链路就完整保留了。
 */
const hourlyDisplayDate = ref<string>(liveAnchorDate(new Date()))

/**
 * 选中日的窗口起点 `date 05:00` —— 折线横轴的基准。
 *
 * <p>用<strong>显示日</strong>而非选中日：横轴与折线必须同源，否则加载途中会出现
 * 「轴已经是新的一天、数据还是旧那天」的错位，而图照样能画。
 *
 * <p>与冻结的 {@link hourlyWindowStart} 也不同：那个是<strong>实时</strong>窗口的起点，
 * 只用于过滤 SSE 帧（窗口冻结的语义在那里）。三者在显示实时窗口时相等。
 */
const hourlyDisplayWindowStart = computed(
  () => hourlyWindowStartOf(hourlyDisplayDate.value) ?? hourlyWindowStart.value,
)

/**
 * 从双栈中取出<strong>选中日</strong>的桶表 —— 用于判断该不该加载。
 *
 * <p>选中日等于实时锚定日 → 读实时栈，<strong>不发任何请求</strong>；
 * 否则读历史缓存，未命中则 `missing` 为 true，由下面的停留触发去加载。
 *
 * <p>注意这不是渲染源，渲染走 {@link hourlyDisplay}。
 */
const hourlyReadResult = computed(() =>
  selectHourlyTotals(
    hourlySelectedDate.value,
    hourlyLiveDate.value,
    hourlyTotals.value,
    hourlyHistory.value,
  ),
)

/** 渲染源 —— 显示日对应的桶表。 */
const hourlyDisplay = computed(() =>
  selectHourlyTotals(
    hourlyDisplayDate.value,
    hourlyLiveDate.value,
    hourlyTotals.value,
    hourlyHistory.value,
  ),
)

/** 显示日是否落后于选中日 —— 即「正在等新数据」。 */
const hourlyPending = computed(() => hourlySelectedDate.value !== hourlyDisplayDate.value)

/**
 * 正在加载的历史日期。null 表示没有进行中的请求。
 *
 * <p>存日期而非布尔量，使「响应回来时选中日已经变了」这种情况可判定 ——
 * 那份数据仍要进缓存（下次滑回去就有了），但不该影响当前的加载态。
 */
const hourlyLoadingDate = ref<string | null>(null)

/** 加载失败的日期集合。用于失败态判定，不阻止重试（挪动选择器即重试）。 */
const hourlyFailedDates = ref<Set<string>>(new Set())

/**
 * 时段视图的加载态。
 *
 * <p>只在<strong>连一份旧数据都没有</strong>时为真 —— 那才是真正的空屏。
 * 通常只出现在首屏（此时选中日就是实时锚定日，实时栈还没收到快照帧）。
 *
 * <p>滑动到未加载的日期时<strong>不</strong>置加载态：显示日仍停在上一份数据上，
 * 图表照常渲染。若置为 true，图表会被占位文字整块替掉，滑动一路上就闪一路 ——
 * 那正是要避免的。
 */
const hourlyLoading = computed(
  () => hourlyLoadingDate.value !== null && hourlyDisplay.value.missing,
)

/**
 * 时段视图的失败态。
 *
 * <p>同样要求「没有旧数据可显示」：某一天加载失败时若已有上一天的图，
 * 保留它比把整块换成错误文字更有用 —— 那一天的数据本就与这一次失败无关。
 */
const hourlyFailed = computed(
  () => hourlyFailedDates.value.has(hourlySelectedDate.value) && hourlyDisplay.value.missing,
)

/**
 * 停留触发器 —— 在某个离散点上停留 {@link SELECTOR_DWELL_MS} 后才发请求。
 *
 * <p>今日时段（单点）与区间窗口（柱状图 / 近 N 日折线）<strong>共用</strong>这个延迟：
 * 都是「拖动经过若干离散点，只为最终停下的那个点加载」的同一种手势，
 * 分开调只会让两个场景的手感不一致。
 *
 * <h2>为什么不是节流也不是「松手才发」</h2>
 * 节流按固定速率放行，快速划过的点会各自触发一次请求，而那些点用户根本没停留。
 * 「松手才发」则让拖动过程中什么都看不到 —— 而滑动的价值恰恰在于
 * 逐点扫过去、边滑边看。
 *
 * <p>停留触发（debounce）两者兼得：每次换点都重置计时，快速划过 10 个点只产生
 * 1 次请求；在某点停住足够久就自动加载，不必松手。
 */
const SELECTOR_DWELL_MS = 100

const hourlyDwell = createDwellTrigger<string>(SELECTOR_DWELL_MS, (date) => {
  void fetchHourlySeries(date)
})

/**
 * 选中日变化时决定是否加载，并在数据就绪时推进显示日。
 *
 * <h2>显示日的推进时机</h2>
 * 数据就绪（`!missing`）就立刻跟上 —— 缓存命中与实时窗口都属于这一类，
 * 故滑回已看过的日期是<strong>瞬时</strong>的，不必等停留计时。
 * 只有真正缺数据时显示日才停在原处，等响应到达。
 *
 * <p>这也是「响应到达后自动补上」的实现：响应写进缓存 → `missing` 变 false →
 * 本 watch 再跑一次 → 显示日跟上。不需要在请求回调里手工同步，
 * 那种写法会漏掉「响应期间用户又滑走了」的情形。
 *
 * <p>`immediate` 是必需的：挂载时选中日就已确定（默认为实时锚定日），
 * 那一帧若不判断，用户不动选择器就永远不会加载 —— 而默认那天恰好是实时栈，
 * 现在看起来正常，将来改默认值就会静默失效。
 */
watch(
  [hourlySelectedDate, () => hourlyReadResult.value.missing],
  ([date, missing]) => {
    if (!missing) {
      // 已有数据可显示，取消待发的请求 —— 用户可能是滑回了已缓存的那天。
      hourlyDwell.cancel()
      hourlyDisplayDate.value = date
      return
    }
    if (hourlyLoadingDate.value === date) return
    hourlyDwell.schedule(date)
  },
  { immediate: true },
)

/**
 * 拉取某一天的时段用量。
 *
 * <h2>绝不用于实时窗口</h2>
 * 入口处挡掉实时锚定日：那份数据在实时栈里连续累加，HTTP 拉一遍会与增量帧
 * 抢同一份状态，多算或少算取决于到达顺序。这个判断是双栈的最后一道防线 ——
 * 上面的 watch 已经不会为实时日排队，但直接调用本函数的路径将来可能出现。
 *
 * <h2>缓存键用响应里的日期</h2>
 * 请求越界或畸形时后端会<strong>收敛</strong>日期，此时数据属于另一天。
 * 按请求参数缓存会把 A 天的数据存到 B 天名下，而图照样能画。
 *
 * <p>若收敛后的结果正好是实时窗口（`isCurrentWindow`），则整份丢弃 ——
 * 那一天该由实时栈负责，进缓存只会制造一份永不更新的影子副本。
 */
async function fetchHourlySeries(date: string) {
  if (date === hourlyLiveDate.value) return
  if (hourlyHistory.value.has(date)) return

  hourlyLoadingDate.value = date
  try {
    const response = await http.get<UsageHourlySeriesResponse>('/usage-hourly/series', {
      params: { date },
    })
    const series = response.data
    if (!series?.windowStart) throw new Error('malformed series')

    if (!series.isCurrentWindow) {
      const totals = collectHourlySeriesResponse(series)
      // 换引用而非原地 set：Map 的原地修改不触发响应式。
      const next = new Map(hourlyHistory.value)
      next.set(series.date, totals)
      hourlyHistory.value = next
    }

    if (hourlyFailedDates.value.has(date)) {
      const next = new Set(hourlyFailedDates.value)
      next.delete(date)
      hourlyFailedDates.value = next
    }
  } catch {
    const next = new Set(hourlyFailedDates.value)
    next.add(date)
    hourlyFailedDates.value = next
  } finally {
    // 只在自己仍是「当前请求」时清除 —— 期间可能已经发起了另一天的请求。
    if (hourlyLoadingDate.value === date) hourlyLoadingDate.value = null
  }
}

/* ---------- 区间窗口（柱状图 + 近 N 日折线） ---------- */

/**
 * 历史窗口的明细行缓存 —— 双栈的另一半，只由 HTTP 喂养。
 *
 * <p>与今日时段的 {@link hourlyHistory} 同理：不含今天的窗口数据已固化，
 * 缓存永不失效，来回滑动时同一窗口只请求一次。实时窗口（最近 N 天）由 SSE
 * 的 {@link breakdownRows} 维护，<strong>不进这里</strong> —— 它一直在变，
 * 缓存一份影子副本只会让「今天的数字停在某个时刻不再涨」。
 * 分流见 {@link readDisplayWindow}。
 */
const breakdownHistory = ref<Map<string, UsageBreakdownRow[]>>(new Map())

/** 实时窗口的 key —— SSE 快照对应的那几天（`breakdownWindow` 首尾）。 */
const breakdownLiveKey = computed(() => {
  const dates = breakdownWindow.value
  return dates.length >= 2 ? windowKeyOf(dates[0], dates[dates.length - 1]) : ''
})

/**
 * 区间窗口的双栈分流点 —— 由显示窗口 key 取出「该渲染的日期序列 + 明细行」。
 *
 * <p>实时 key → SSE 的 `breakdownRows`（快照 + 已累加的增量），历史 key →
 * 缓存里对应窗口的行。历史行按显示窗口<strong>过滤</strong>：后端会把请求的
 * `size` 钳到 `[7, 15]`（前端临时为 5 天时会被放宽），rows 可能多出两天，
 * 而柱状图的横轴完全由 rows 的日期派生，不滤掉会多画两根柱。
 *
 * <p>key 为 null（未就绪）时回退到实时窗口 —— 那是「什么都不知道」时的
 * 唯一合理默认。
 */
function readDisplayWindow(key: string | null): { dates: string[]; rows: UsageBreakdownRow[] } {
  if (!key || key === breakdownLiveKey.value) {
    return { dates: breakdownWindow.value, rows: breakdownRows.value }
  }
  const [start, end] = key.split('~')
  const dates = windowDatesBetween(start, end)
  const wanted = new Set(dates)
  return {
    dates,
    rows: (breakdownHistory.value.get(key) ?? []).filter((row) => wanted.has(row.date)),
  }
}

/** {@link createRangeLoader} 的产物。 */
interface RangeLoader {
  /** 显示窗口的 key —— 滞后于选中窗口，见 {@link createRangeLoader}。 */
  displayKey: Ref<string | null>
  /** 选中窗口是否缺数据（非实时且缓存未命中）。 */
  missing: Ref<boolean>
  /** 正在加载的窗口 key，null 表示空闲。 */
  loadingKey: Ref<string | null>
  /** 选中窗口与显示窗口不一致 —— 图表还停在上一份数据上。 */
  pending: Ref<boolean>
  /** 停留触发器，卸载时需 cancel。 */
  dwell: ReturnType<typeof createDwellTrigger<string>>
}

/**
 * 为一张「按区间展示」的图建一个窗口加载器。
 *
 * <p>与今日时段的「显示日滞后于选中日」是同一套模式，抽成工厂是为了
 * 让柱状图与近 N 日折线共用这份逻辑 —— 它们数据同源（breakdown 明细），
 * 只是各自维护自己的选中 / 显示窗口。
 *
 * <p>推进规则：数据就绪（实时或缓存命中）就<strong>立即</strong>把显示窗口
 * 跟上选中窗口 —— 滑回看过的区间是瞬时的，不必等停留计时；只有真正缺数据时
 * 显示窗口才停在原处，等 dwell 触发请求、响应写进缓存后自动补上。
 *
 * @param selectedKey 选中窗口 key 的读取函数。单点形态下应返回 null（不参与）
 * @param fetch       加载一个窗口（写 {@link breakdownHistory}）
 */
function createRangeLoader(options: {
  selectedKey: () => string | null
  fetch: (key: string) => Promise<void>
}): RangeLoader {
  const displayKey = ref<string | null>(null)
  const loadingKey = ref<string | null>(null)

  const missing = computed(() => {
    const key = options.selectedKey()
    if (!key || key === breakdownLiveKey.value) return false
    return !breakdownHistory.value.has(key)
  })

  const pending = computed(() => options.selectedKey() !== displayKey.value)

  const dwell = createDwellTrigger<string>(SELECTOR_DWELL_MS, (key) => {
    loadingKey.value = key
    void options.fetch(key).finally(() => {
      if (loadingKey.value === key) loadingKey.value = null
    })
  })

  watch(
    [options.selectedKey, missing],
    ([key, miss]) => {
      if (!key) return
      if (!miss) {
        // 已有数据可显示，取消待发的请求 —— 用户可能是滑回了已缓存的那个窗口。
        dwell.cancel()
        displayKey.value = key
        return
      }
      if (loadingKey.value === key) return
      dwell.schedule(key)
    },
    { immediate: true },
  )

  return { displayKey, missing, loadingKey, pending, dwell }
}

/** 柱状图选择器的选中窗口 key。 */
const dateSelectedKey = computed(() => {
  const ticks = dateRangeTicks.value
  const sel = dateRange.value
  const from = ticks[sel.start]?.key
  const to = ticks[sel.end]?.key
  return from && to ? windowKeyOf(from, to) : null
})

/** 折线图选择器的选中窗口 key —— 单点形态（今日时段）不参与区间加载。 */
const timelineSelectedKey = computed(() => {
  if (timelineSinglePoint.value) return null
  const ticks = timelineTicks.value
  const sel = timelineSelection.value
  const from = ticks[sel.start]?.key
  const to = ticks[sel.end]?.key
  return from && to ? windowKeyOf(from, to) : null
})

/**
 * 拉取一个历史窗口的明细。
 *
 * <h2>绝不用于实时窗口</h2>
 * 入口处挡掉实时 key：那份数据在 SSE 里连续累加，HTTP 拉一遍会与增量帧抢状态。
 *
 * <h2>缓存键用选中窗口而非响应窗口</h2>
 * 后端会钳制 `size`/`offset`，响应里的窗口可能比选中的宽。若按响应窗口缓存，
 * 用户下一次选到原窗口时仍会未命中；按选中窗口缓存则命中，多余的行在读取时
 * 已被 {@link readDisplayWindow} 滤掉（钳制只会放大窗口，故请求窗口 ⊆ 响应窗口，
 * 过滤后数据完整）。
 *
 * <h2>含今天的窗口也照常缓存</h2>
 * 用户拖动手柄从实时窗口出发时，只能<strong>扩大</strong>窗口（起点前移、右端
 * 顶住今天），得到的必然是一批「包含今天但 ≠ 实时 key」的窗口。若像早先那样把
 * `includesToday` 的响应整份丢弃，这些窗口就永远加载不出来 —— 表现为
 * 「拖手柄没反应、hint 一直停在加载中」，而拖动整块（右端离开今天）却正常。
 *
 * <p>这些窗口是用户<strong>主动选择</strong>的，缓存其快照即可显示。代价是
 * SSE 增量只落默认实时栈（{@link breakdownRows}），该窗口的数字停在拉取时刻 ——
 * 与今日时段滑到历史日期一致；把窗口拖回默认实时窗口即恢复实时。
 */
async function fetchBreakdownWindow(key: string) {
  if (key === breakdownLiveKey.value) return
  if (breakdownHistory.value.has(key)) return

  const [start, end] = key.split('~')
  const startIdx = dayOffsetBetween(now.value, parseLocalDay(start) ?? now.value)
  const endIdx = dayOffsetBetween(now.value, parseLocalDay(end) ?? now.value)
  const { size, offset } = windowParamsOf(startIdx, endIdx)

  const response = await http.get<UsageBreakdownPage>('/usage-breakdown/page', {
    params: { size, offset },
  })
  const page = response.data
  if (!page || !Array.isArray(page.rows)) return

  const next = new Map(breakdownHistory.value)
  next.set(key, page.rows)
  breakdownHistory.value = next
}

const dateLoader = createRangeLoader({
  selectedKey: () => dateSelectedKey.value,
  fetch: fetchBreakdownWindow,
})

const timelineLoader = createRangeLoader({
  selectedKey: () => timelineSelectedKey.value,
  fetch: fetchBreakdownWindow,
})

/** 柱状图该渲染的窗口（显示窗口，滞后于选中）。 */
const dateDisplay = computed(() => readDisplayWindow(dateLoader.displayKey.value))

/** 近 N 日折线该渲染的窗口。 */
const timelineDisplay = computed(() => readDisplayWindow(timelineLoader.displayKey.value))

/**
 * 折线图选择器的选择描述 —— 供滑块的 `formatValueText` 使用（无障碍）。
 *
 * <p>单点形态下只给一个日期 —— 缺省的「X 至 X，共 1 天」在单点场景下是废话。
 *
 * <p>视觉上不再有摘要行，但屏幕阅读器仍需要这一句：光报索引号读不出选了哪天。
 */
function describeTimelineRange(selection: RangeSelection): string {
  const ticks = timelineTicks.value
  const from = ticks[selection.start]?.key ?? ''
  if (timelineSinglePoint.value) return from
  const to = ticks[selection.end]?.key ?? ''
  return `${from} 至 ${to}，共 ${selection.end - selection.start + 1} 天`
}

/**
 * 折线图卡片的标题称呼。
 *
 * <p>时段视图接上日期选择器后「今日时段」不再总是对的 —— 选到 7 月 28 日时
 * 那个标题是错的，而图照样能画。故实时窗口写「今日时段」，历史日期写 `M/D`。
 *
 * <p>去掉年份：可选范围只有最近十几天，年份永远是当前年（或跳年那两天的上一年），
 * 写出来只是占位。完整日期可从手柄下方的 `M/D` 标签与滑块的 `aria-valuetext` 读出。
 *
 * <p>标题跟<strong>显示日</strong>而非选中日：标题描述的是图表里画的那一天，
 * 加载途中提前换成新日期就成了误标。
 *
 * <p>凌晨 5 点前的实时窗口锚在昨天，此时「今日时段」指的是昨天那一轮 ——
 * 这正是 5 点分界的语义，标题保持「今日时段」是对的。
 *
 * <p>区间形态返回 undefined，让组件用自己的缺省称呼。
 */
const timelinePanelLabel = computed(() => {
  if (!timelineSinglePoint.value) return undefined
  if (hourlyDisplay.value.live) return '今日时段'
  return `${formatMonthDay(hourlyDisplayDate.value)} 时段`
})


onMounted(() => {
  // 热力图历史数据首屏全量拉取一次；统计卡先 HTTP 兜底一次，随后交给 SSE 实时推送。
  void fetchHeatmap()
  // 日期选择器的软墙位置。不阻塞其余请求 —— 未加载时选择器放开整个池，
  // 响应到达后收窄，比先塌成一个点再展开要平顺。
  void fetchUsageDateRange()
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
  // 未触发的停留计时必须取消：回调会写响应式状态，组件卸载后写入即内存泄漏。
  hourlyDwell.cancel()
  dateLoader.dwell.cancel()
  timelineLoader.dwell.cancel()
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

/**
 * 拉取用量记录的日期范围 —— 日期选择器软墙的唯一数据源。
 *
 * <h2>失败不设错误态</h2>
 * 这是<strong>元信息</strong>而非图表数据：拿不到只意味着软墙位置未知，
 * 此时放开整个池（{@link resolveSelectableAxis} 的行为），用户仍能拖动，
 * 拖到没数据的日期只是看到空图。为它加一个错误横幅会把「一个附属请求失败」
 * 说成「概览坏了」。
 *
 * <h2>只在挂载时拉一次</h2>
 * 边界只会随「今天推进」和「第一条记录产生」而变，两者都不频繁；
 * 前者由 {@link now} 的半点推进吸收（软墙是相对位置，原点一动它自动重算），
 * 后者只影响空库那一次。跨天后的精确边界由刷新页面得到。
 */
async function fetchUsageDateRange() {
  try {
    const response = await http.get<UsageDateRangeMeta>('/usage-date-range')
    const meta = response.data
    // 后端保证 earliestSelectable 非空；缺字段说明代理或版本不匹配，按未加载处理。
    if (!meta || typeof meta.earliestSelectable !== 'string') return
    usageDateRange.value = meta
    if (!isClockAligned(meta, new Date())) {
      // 两端「今天」不一致时整条时间轴会整体错位一天，而图表照样能画 ——
      // 每根柱子都贴错日期却毫无迹象。这类问题只能靠显式告警暴露。
      console.warn(
        `[overview] 服务端与浏览器的「今天」不一致：服务端 ${meta.today}，` +
          '日期轴可能整体错位一天。请检查两端时区与系统时钟。',
      )
    }
  } catch {
    // 保持 null —— 选择器放开整个池，见上面的说明。
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
 * - `breakdown` —— 按日期聚合的完整明细，整体替换。喂柱状图与「近 N 日」折线。
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
 * 柱状图与「近 N 日」按自然日，「今日时段」按 5 点分界。同一帧可能被一个接受、
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
      <UsageBreakdownPanel :rows="dateDisplay.rows" :dimension="activeBreakdownDimension" :metric="callCountMetric"
        :days="dateDisplay.dates.length" :loading="chartsLoading" :failed="chartsFailed">
        <template #actions>
          <div class="breakdown-mode-switch">
            <span class="breakdown-mode-label">{{ activeBreakdownDimension.primaryTerm }}视图</span>
            <button type="button" class="breakdown-mode-btn" @click="switchBreakdownDimension">切换</button>
          </div>
        </template>
      </UsageBreakdownPanel>

      <!--
        日期范围选择器 —— 柱状图的数据窗口。

        已接线：拖动 / 翻页切换窗口，实时窗口（含今天）读 SSE 明细，
        历史窗口走 `/usage-breakdown/page` 并按窗口永久缓存；显示窗口
        滞后于选中窗口，加载途中图表保持上一份数据不清零。

        可达区间（`/usage-date-range`）也已接线：早于最早记录日期的刻度
        渲染成不可达空心点，可选天数不足时跨度下限随之收缩。

        放在柱状图下方而非卡片 header：它控制的是横轴范围，紧贴横轴才让
        「拖它 → 轴变」这层因果关系一眼可见；放到标题行则与视图切换按钮抢位置。

        下方<strong>不再有摘要与提示行</strong>：选中区间已由手柄下方的 `M/D`
        标签给出，横轴本身也在变，再加一行文字只是重复。加载途中的滞后状态
        同样不再文字说明 —— 图表在数据到达后自动更新，中间保持上一份不清零。
        无障碍描述改由滑块的 `formatValueText`（`aria-valuetext`）承担。
      -->
      <div class="breakdown-range">
        <DiscreteRangeSlider
          :model-value="dateRange"
          :ticks="dateRangeTicks"
          :min-span="dateAxisConfig.minSpan"
          :max-span="dateAxisConfig.maxSpan"
          :min-index="dateReachable.minIndex"
          :max-index="dateReachable.maxIndex"
          pageable
          :can-page-prev="canDatePagePrev"
          :can-page-next="canDatePageNext"
          page-prev-label="向前翻（单击一天，双击一页）"
          page-next-label="向后翻（单击一天，双击一页）"
          click-to-jump
          aria-label="日期范围"
          :format-value-text="describeDateRange"
          :format-edge-label="formatDateRangeEdge"
          @update:model-value="onDateRangeUpdate"
          @page="onDateRangePage"
        />
      </div>
    </n-card>

    <!--
      token 用量折线。与柱状图分开成卡：折线的横轴是时间（连续量），
      柱状图下钻后横轴变成供应商 / 模型（类别），把类别连成线会暗示不存在的顺序关系。
      两者回答的问题也不同 —— 折线看趋势，柱状图看构成。
    -->
    <n-card class="usage-line-card" :bordered="true">
      <!--
        状态态按范围分开：「今日时段」有自己的 HTTP 请求（按日期回看），
        而「近 N 日」仍走 SSE 快照。用同一组状态会让一边的失败显示到另一边。
      -->
      <UsageLinePanel
        v-model:range="timelineRange"
        :points="timelinePoints"
        :label="timelinePanelLabel"
        :loading="timelineSinglePoint ? hourlyLoading : chartsLoading"
        :failed="timelineSinglePoint ? hourlyFailed : chartsFailed"
        :empty-text="timelineSinglePoint ? '这一天没有调用记录' : '暂无用量数据'"
      />

      <!--
        折线图的日期选择器。形态随上方的范围切换按钮联动 ——
        「今日时段」是单点（看某一天的时段分布），「近 N 日」是区间。

        <strong>已接线</strong>：单点形态滑到哪一天就看那一天的时段曲线
        （实时窗口读 SSE、历史日期走 HTTP 并永久缓存）；
        区间形态切换窗口时复用柱状图那份历史缓存 —— 两者数据同源
        （breakdown 明细），只是各自维护选中 / 显示窗口。

        与柱状图那个各自独立：两者服务不同图表，用户可能想让它们停在不同区间。
        但可达区间是<strong>共用</strong>的（`selectableAxis`）——
        「往前能拖到哪一天」是数据事实，不因图表而异。
      -->
      <div class="breakdown-range">
        <DiscreteRangeSlider
          :model-value="timelineSelection"
          :ticks="timelineTicks"
          :min-span="timelineAxisConfig.minSpan"
          :max-span="timelineAxisConfig.maxSpan"
          :min-index="timelineReachable.minIndex"
          :max-index="timelineReachable.maxIndex"
          pageable
          :can-page-prev="canTimelinePagePrev"
          :can-page-next="canTimelinePageNext"
          page-prev-label="向前翻（单击一天，双击一页）"
          page-next-label="向后翻（单击一天，双击一页）"
          click-to-jump
          :aria-label="timelineSinglePoint ? '日期' : '日期范围'"
          :format-value-text="describeTimelineRange"
          :format-edge-label="formatDateRangeEdge"
          @update:model-value="onTimelineRangeUpdate"
          @page="onTimelineRangePage"
        />
      </div>
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