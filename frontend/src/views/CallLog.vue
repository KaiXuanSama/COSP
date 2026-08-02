<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { NCard, NEmpty, NSpin } from 'naive-ui'
import { fetchLogs, fetchLogDetail } from '@/api'
import { createAuthEventSource, type AuthEventSource } from '@/api/authEventSource'
import { JsonViewer, ChunksViewer } from '@/components/calllog'
import type { CollapseRule } from '@/components/calllog/JsonNode.vue'

interface LogItem {
  id: number
  provider_key: string
  model_name: string
  is_stream: number
  status_code: number
  created_at: string
}

const logs = ref<LogItem[]>([])
const nextCursor = ref<number | null>(null)
const hasMore = ref(false)
const pageSize = 50
const loading = ref(false)
const loadingMore = ref(false)
const initialLoading = ref(true)

/**
 * 调用 token 用量（来自独立的 api_call_usage 表，经详情端点的 usage 字段回传）。
 *
 * token 字段遵循 null vs 0 语义：null 表示上游未提供该数据，0 表示上游报告了真实零值。
 * 展示时不得把 null 渲染成 0。
 */
interface UsageDetail {
  id: number
  log_id: number | null
  provider_key: string | null
  model_name: string | null
  is_stream: number
  usage_raw: string | null
  prompt_tokens: number | null
  completion_tokens: number | null
  cached_tokens: number | null
  ttfb_ms: number | null
  created_at: string
}

interface DetailItem {
  id: number
  provider_key: string
  model_name: string
  is_stream: number
  status_code: number
  request_headers: string | null
  request_body: string | null
  response_headers: string | null
  response_body: string | null
  chunks: string | null
  duration_ms: number | null
  created_at: string
  /** 该次调用的 token 用量；null 表示未查询到（旧日志 / 失败调用 / 上游未返回 usage）。 */
  usage: UsageDetail | null
}

const selectedLogId = ref<number | null>(null)
const logDetail = ref<DetailItem | null>(null)
const detailLoading = ref(false)

const jsonModal = ref({ show: false, title: '', content: null as unknown, collapseRule: 'none' as CollapseRule })
const chunksModal = ref({ show: false, chunks: [] as string[] })

/**
 * SSE 新日志的淡入动画时长（毫秒），需与 CSS 中 .log-enter-* 的 transition 时长保持一致。
 * 队列每插入一条新记录后等待该时长再插下一条，实现“上一个动画播完再播下一个”。
 */
const ANIM_DURATION = 420
/** 已入场动画播放中的新记录 id，模板据此附加高亮类。 */
const animatingId = ref<number | null>(null)
/** 待逐条播放淡入动画的新记录队列（按 id 升序，逐条 shift 出队）。 */
const enterQueue: LogItem[] = []
/** 队列消费中标志，避免并发 flush 造成多条同时入场。 */
let flushingQueue = false
/**
 * 已知的最大日志 id 水位线，涵盖“已在列表中”和“已排入队列尚未入场”的记录。
 * 增量同步据此去重，避免动画播放期间又来 SSE 导致同一条重复入队。
 */
let knownMaxId = 0

/**
 * 加载第一页日志
 */
async function loadFirstPage() {
  loading.value = true
  initialLoading.value = true
  try {
    const res = await fetchLogs(null, pageSize)
    logs.value = res.data.items || []
    nextCursor.value = res.data.nextCursor ?? null
    hasMore.value = Boolean(res.data.hasMore)
    resetKnownMaxId()
  } catch (e) {
    console.error('加载日志失败:', e)
  } finally {
    loading.value = false
    initialLoading.value = false
  }
}

/** 用当前列表首条（最新）记录重置水位线，并清空未播放的入场队列。 */
function resetKnownMaxId() {
  enterQueue.length = 0
  knownMaxId = logs.value.length ? logs.value[0].id : 0
}

/**
 * 刷新日志列表（清空后重新加载第一页）。
 *
 * 手动刷新是整表替换，不走逐条淡入动画：先清空未播放的入场队列并停止当前动画标记，
 * 由 loadFirstPage 内的 resetKnownMaxId 重置水位线。
 */
async function refreshLogs() {
  enterQueue.length = 0
  animatingId.value = null
  logs.value = []
  nextCursor.value = null
  hasMore.value = false
  await loadFirstPage()
}

/**
 * 加载更多日志（追加到列表末尾）
 */
async function loadMore() {
  if (!hasMore.value || loadingMore.value) return

  loadingMore.value = true
  try {
    const res = await fetchLogs(nextCursor.value, pageSize)
    const newItems = res.data.items || []
    logs.value = [...logs.value, ...newItems]
    nextCursor.value = res.data.nextCursor ?? null
    hasMore.value = Boolean(res.data.hasMore)
  } catch (e) {
    console.error('加载更多日志失败:', e)
  } finally {
    loadingMore.value = false
  }
}

/**
 * 格式化时间（只显示时分秒）
 */
function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  const parts = dateStr.split('T')
  return parts.length > 1 ? parts[1] : dateStr
}

/**
 * 获取状态码对应的颜色类名
 */
function getStatusClass(code: number): string {
  if (code >= 200 && code < 300) return 'success'
  if (code >= 400 && code < 500) return 'warning'
  if (code >= 500) return 'error'
  return 'default'
}

/**
 * 截断文本
 */
function truncate(str: string | null, maxLen = 50): string {
  if (!str) return ''
  return str.length > maxLen ? str.substring(0, maxLen) + '...' : str
}

/**
 * 判断字段是否有内容
 */
function hasContent(value: unknown): boolean {
  if (value == null) return false
  if (typeof value === 'string') return value.length > 0 && value !== '[]'
  return true
}

/**
 * 格式化耗时
 */
function formatDuration(ms: number | null): string {
  if (ms == null) return ''
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

/**
 * 格式化 token 数量。
 *
 * null（上游未提供）显示为 '—'；0 是上游报告的真实零值，照实显示为 '0'。
 * 千分位便于阅读大数值（如 206,925）。
 */
function formatTokens(value: number | null | undefined): string {
  if (value == null) return '—'
  return value.toLocaleString('en-US')
}

/**
 * 总 token = 输入 + 输出。
 *
 * 不落库为独立列（是派生值），此处前端计算。任一侧为 null 时无法得出总量，显示 '—'。
 */
function formatTotalTokens(usage: UsageDetail | null): string {
  if (!usage) return '—'
  const { prompt_tokens: prompt, completion_tokens: completion } = usage
  if (prompt == null || completion == null) return '—'
  return (prompt + completion).toLocaleString('en-US')
}

/** 首字时长；null（非流式或未测得）显示为 '—'。 */
function formatTtfb(usage: UsageDetail | null): string {
  if (!usage || usage.ttfb_ms == null) return '—'
  return formatDuration(usage.ttfb_ms)
}

/**
 * 缓存命中占比 = 缓存命中 token / 输入 token。
 *
 * 不落库为独立列（派生值），此处前端计算。严格区分 null 与 0：
 * - 缓存或输入任一为 null（上游未提供）→ '—'，表示无从计算，而非 0%；
 * - 缓存为 0 且输入有值 → '0%'，这是上游报告的真实未命中。
 * 输入为 0 时无法做除法，同样显示 '—'。
 */
function formatCacheHitRate(usage: UsageDetail | null): string {
  if (!usage) return '—'
  const { cached_tokens: cached, prompt_tokens: prompt } = usage
  if (cached == null || prompt == null || prompt === 0) return '—'
  return `${((cached / prompt) * 100).toFixed(1)}%`
}

/**
 * usage 悬浮浮窗：展示上游返回的完整 usage 原始对象。
 *
 * 原始对象零损失保留了本项未提列的字段（reasoning_tokens、cache_creation_tokens 等），
 * 悬浮即可查看，无需打开弹窗。浮窗定位在鼠标位置的左下角。
 */
/**
 * usage 浮窗状态。
 *
 * - `pinned: false` —— 悬浮态：跟随鼠标移动，移出即隐藏，不接收鼠标事件；
 * - `pinned: true` —— 固定态（点击表格进入）：位置锁定、可接收鼠标事件以便框选与滚动，
 *   点击浮窗外区域才关闭。
 */
const usagePopover = ref({ show: false, pinned: false, x: 0, y: 0, text: '' })

/** 格式化 usage 原始 JSON 供浮窗展示；无法解析时原样返回。 */
function formatUsageRaw(raw: string | null): string {
  if (!raw) return '无原始 usage 数据'
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

/**
 * 移入用量表格（或键盘聚焦）：以悬浮态显示浮窗。
 *
 * 已处于固定态（点击锁定）时不响应悬浮，避免覆盖用户正在交互的浮窗。
 * 鼠标事件按光标位置定位；键盘 focus 无光标坐标，回退到表格自身右下角（可访问性）。
 */
function showUsagePopover(event: MouseEvent | FocusEvent, usage: UsageDetail | null) {
  if (usagePopover.value.pinned) return
  let x: number
  let y: number
  if (event instanceof MouseEvent) {
    x = event.clientX
    y = event.clientY
  } else {
    // 鼠标按下也会让带 tabindex 的表格获得 focus，但此时不应按元素定位——
    // 否则浮窗会先跳到表格右下角，松开后再被 click 拉回光标处，产生闪现。
    // :focus-visible 只在键盘聚焦时匹配，据此排除鼠标带来的 focus。
    const target = event.currentTarget as HTMLElement
    if (!target.matches(':focus-visible')) return
    const rect = target.getBoundingClientRect()
    x = rect.right
    y = rect.bottom
  }
  usagePopover.value = {
    show: true,
    pinned: false,
    x,
    y,
    text: formatUsageRaw(usage?.usage_raw ?? null),
  }
}

/** 鼠标随表格移动时同步浮窗位置；固定态下位置锁定不再跟随。 */
function moveUsagePopover(event: MouseEvent) {
  if (!usagePopover.value.show || usagePopover.value.pinned) return
  usagePopover.value.x = event.clientX
  usagePopover.value.y = event.clientY
}

/** 鼠标移出：隐藏浮窗；固定态下保持显示，供鼠标移入框选。 */
function hideUsagePopover() {
  if (usagePopover.value.pinned) return
  usagePopover.value.show = false
}

/**
 * 点击用量表格：把浮窗切到固定态。
 *
 * 固定态下浮窗位置锁定、可接收鼠标事件（可框选、滚动），
 * 点击浮窗外任意区域即关闭（见 onDocumentClickForPopover）。
 */
function pinUsagePopover(event: MouseEvent, usage: UsageDetail | null) {
  usagePopover.value = {
    show: true,
    pinned: true,
    x: event.clientX,
    y: event.clientY,
    text: formatUsageRaw(usage?.usage_raw ?? null),
  }
}

/** 关闭浮窗并解除固定态。 */
function closeUsagePopover() {
  usagePopover.value.show = false
  usagePopover.value.pinned = false
}

/**
 * 文档级点击：固定态下点击浮窗外区域关闭浮窗。
 *
 * 浮窗自身与触发用的用量表格内部点击不关闭——前者是用户正在框选，
 * 后者由 pinUsagePopover 处理（否则会先关再开导致闪烁）。
 */
function onDocumentClickForPopover(event: MouseEvent) {
  if (!usagePopover.value.pinned) return
  const target = event.target as HTMLElement | null
  if (target?.closest('.usage-popover') || target?.closest('.detail-usage')) return
  closeUsagePopover()
}

/**
 * 点击日志项，加载详情
 */
async function selectLog(id: number) {
  selectedLogId.value = id
  detailLoading.value = true
  logDetail.value = null
  try {
    const res = await fetchLogDetail(id)
    logDetail.value = res.data
  } catch (e) {
    console.error('加载日志详情失败:', e)
  } finally {
    detailLoading.value = false
  }
}

/**
 * 请求体折叠规则：折叠 messages、tools 及其内部数组元素（最后一个除外）
 */
const requestBodyCollapseRule: CollapseRule = (key, depth, parentKey, index, total) => {
  // 折叠顶层的 messages 和 tools
  if (depth === 1 && (key === 'messages' || key === 'tools')) return true
  // 折叠 messages/tools 数组内的元素（最后一个除外）
  if (depth === 2 && (parentKey === 'messages' || parentKey === 'tools')) return index < total - 1
  return false
}

/**
 * 打开 JSON 查看器
 */
function openJsonModal(title: string, content: unknown, collapseRule: CollapseRule = 'none') {
  jsonModal.value = { show: true, title, content, collapseRule }
}

/**
 * 打开 chunks 查看器
 */
function openChunksModal(rawChunks: string | null) {
  if (!rawChunks) return
  let parsed: string[] = []
  try {
    parsed = JSON.parse(rawChunks)
  } catch {
    parsed = [rawChunks]
  }
  chunksModal.value = { show: true, chunks: parsed }
}

/** 日志变更信号 SSE 端点路径。信号不携带数据，收到后走带 Token 的 /logs 拉取。 */
const LOG_STREAM_PATH = '/logs/stream'

let logStreamSource: AuthEventSource | null = null
let syncing = false

/**
 * 收到“有新日志”信号后的增量同步。
 *
 * 只拉第一页，把 id 超过当前水位线的新记录按 id 升序排入入场队列，
 * 再交由 {@link flushEnterQueue} 逐条播放淡入动画。列表原有分页与选中详情保持不变。
 *
 * 用 knownMaxId 而非列表首条 id 去重：动画播放期间新记录尚未进入 logs，
 * 若以列表首条为准会导致同一条被重复入队。
 */
async function syncLatestLogs() {
  if (syncing) return
  syncing = true
  try {
    const res = await fetchLogs(null, pageSize)
    const latest: LogItem[] = res.data.items || []
    if (!latest.length) return

    // 首次填充（列表为空且无待播队列）：直接铺满，不走逐条动画。
    if (!logs.value.length && !enterQueue.length && !flushingQueue) {
      logs.value = latest
      nextCursor.value = res.data.nextCursor ?? null
      hasMore.value = Boolean(res.data.hasMore)
      resetKnownMaxId()
      return
    }

    // 只取超过水位线的新记录，按 id 升序排队（越新越后入场，最终最新的排在最顶部）。
    const fresh = latest
      .filter((item) => item.id > knownMaxId)
      .sort((a, b) => a.id - b.id)
    if (!fresh.length) return

    for (const item of fresh) {
      enterQueue.push(item)
      knownMaxId = Math.max(knownMaxId, item.id)
    }
    void flushEnterQueue()
  } catch (e) {
    console.error('同步最新日志失败:', e)
  } finally {
    syncing = false
  }
}

/**
 * 串行消费入场队列：每次 shift 一条 prepend 到列表顶部并标记为入场中，
 * 等待一个完整动画时长后再处理下一条，实现“上一个淡入播完再播下一个”。
 *
 * 期间新到的 SSE 只会往 enterQueue 追加，不会打断当前节奏（flushingQueue 保证单实例消费）。
 */
async function flushEnterQueue() {
  if (flushingQueue) return
  flushingQueue = true
  try {
    while (enterQueue.length) {
      const item = enterQueue.shift() as LogItem
      // 头部插入新项的同时移除末尾最老一项（无动画），保持列表长度不随实时流无限增长。
      // slice(0, -1) 对空数组/单元素数组均安全；此处 logs 必非空（首次填充走另一分支）。
      logs.value = [item, ...logs.value.slice(0, -1)]
      animatingId.value = item.id
      await nextTick()
      await sleep(ANIM_DURATION)
    }
  } finally {
    animatingId.value = null
    flushingQueue = false
  }
}

/** Promise 化的定时等待，用于队列逐条播放间隔。 */
function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

/**
 * 建立日志变更信号 SSE 连接，替代手动点刷新。
 * 收到 log 事件即触发一次增量同步；重复调用不会创建多个连接。
 * token 由 createAuthEventSource 以 Bearer header 附带，断线自动重连。
 */
function connectStream() {
  if (logStreamSource) return
  logStreamSource = createAuthEventSource({
    path: LOG_STREAM_PATH,
    handlers: {
      log: () => {
        void syncLatestLogs()
      },
    },
  })
}

function disconnectStream() {
  if (logStreamSource) {
    logStreamSource.close()
    logStreamSource = null
  }
}

onMounted(() => {
  loadFirstPage()
  connectStream()
  // 固定态浮窗需要"点击外部关闭"，故挂文档级监听；用捕获阶段避免被内部 stopPropagation 拦掉。
  document.addEventListener('click', onDocumentClickForPopover, true)
})

onUnmounted(() => {
  disconnectStream()
  document.removeEventListener('click', onDocumentClickForPopover, true)
})
</script>

<template>
  <div class="call-log-page">
    <!-- 左侧：调用列表（窄列） -->
    <n-card title="调用记录" :bordered="true" class="call-log-list" content-scrollable>
      <template #header-extra>
        <div class="refresh-btn" :class="{ disabled: loading }" @click="refreshLogs">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 2v6h-6" />
            <path d="M3 12a9 9 0 0 1 15-6.7L21 8" />
            <path d="M3 22v-6h6" />
            <path d="M21 12a9 9 0 0 1-15 6.7L3 16" />
          </svg>
        </div>
      </template>
      <!-- 加载中状态 -->
      <div v-if="initialLoading" class="call-log-loading">
        <n-spin size="medium" />
      </div>

      <!-- 空状态 -->
      <div v-else-if="logs.length === 0" class="call-log-empty">
        <n-empty description="暂无调用记录" />
      </div>

      <!-- 日志列表 -->
      <div v-else class="log-list">
        <transition-group name="log" tag="div" class="log-list-items">
          <div v-for="log in logs" :key="log.id" class="log-item"
            :class="{ active: selectedLogId === log.id, 'log-item--fresh': animatingId === log.id }"
            @click="selectLog(log.id)">
            <div class="log-item-top" :class="getStatusClass(log.status_code)"></div>
            <div class="log-item-content">
              <div class="log-item-header">
                <span class="log-provider">{{ log.provider_key }}</span>
                <span class="log-status">{{ log.status_code }}</span>
              </div>
              <div class="log-item-body">
                <span class="log-model">{{ log.model_name }}</span>
                <span class="log-time">{{ formatTime(log.created_at) }}</span>
              </div>
            </div>
          </div>
        </transition-group>

        <!-- 加载更多 -->
        <div class="log-load-more">
          <div v-if="loadingMore" class="load-more-loading">
            <n-spin size="small" />
            <span>加载中...</span>
          </div>
          <div v-else-if="hasMore" class="load-more-btn" @click="loadMore">
            更多
          </div>
          <div v-else class="load-more-end">
            到底了
          </div>
        </div>
      </div>
    </n-card>

    <!-- 右侧：详细信息（宽列） -->
    <n-card title="详细信息" :bordered="true" class="call-log-detail" content-scrollable>
      <!-- 无选中状态 -->
      <div v-if="!selectedLogId" class="call-log-empty">
        <n-empty description="点击左侧以查看详细信息" />
      </div>

      <!-- 加载中 -->
      <div v-else-if="detailLoading" class="call-log-loading">
        <n-spin size="medium" />
      </div>

      <!-- 详情内容 -->
      <div v-else-if="logDetail" class="detail-content">
        <!-- 顶部元信息 -->
        <div class="detail-meta">
          <div class="detail-meta-main">
            <div class="detail-meta-title">
              <span class="detail-provider">{{ logDetail.provider_key }}</span>
              <span class="detail-model">{{ logDetail.model_name }}</span>
            </div>
          </div>
          <div class="detail-meta-sub">
            <span class="detail-timing" title="首字响应时长 / 总响应时长">{{ formatTtfb(logDetail.usage) }} / {{ formatDuration(logDetail.duration_ms) }}</span>
            <span class="detail-time">{{ formatTime(logDetail.created_at) }}</span>
          </div>
        </div>

        <!--
          token 用量：独占一行、横向铺满父容器，输入 / 输出 / 缓存占比 / 总计四等分居中。
          整个区块即悬浮触发区，鼠标移入显示完整原始 usage 浮窗（无需额外问号图标）。
        -->
        <div
          class="detail-usage"
          role="button"
          tabindex="0"
          aria-label="查看完整 usage 原始数据"
          @mouseenter="showUsagePopover($event, logDetail.usage)"
          @mousemove="moveUsagePopover"
          @mouseleave="hideUsagePopover"
          @focus="showUsagePopover($event, logDetail.usage)"
          @blur="hideUsagePopover"
          @click="pinUsagePopover($event, logDetail.usage)"
        >
          <span class="detail-usage-cell">
            <span class="detail-usage-label">输入</span>
            <span class="detail-usage-value">{{ formatTokens(logDetail.usage?.prompt_tokens) }}</span>
          </span>
          <span class="detail-usage-cell">
            <span class="detail-usage-label">输出</span>
            <span class="detail-usage-value">{{ formatTokens(logDetail.usage?.completion_tokens) }}</span>
          </span>
          <span class="detail-usage-cell">
            <span class="detail-usage-label">缓存占比</span>
            <span class="detail-usage-value">{{ formatCacheHitRate(logDetail.usage) }}</span>
          </span>
          <span class="detail-usage-cell">
            <span class="detail-usage-label">总计</span>
            <span class="detail-usage-value">{{ formatTotalTokens(logDetail.usage) }}</span>
          </span>
        </div>

        <!--
          数据行：整行即点击控件（预览文本本身就是内容入口，比瞄准右侧小按钮更易命中）。
          "展示"保留为视觉提示，不再单独承担点击。
        -->
        <div class="detail-rows">
          <!-- 请求头 -->
          <div
            class="detail-row"
            role="button"
            tabindex="0"
            aria-label="展示请求头完整内容"
            @click="openJsonModal('请求头', logDetail.request_headers)"
            @keydown.enter.prevent="openJsonModal('请求头', logDetail.request_headers)"
            @keydown.space.prevent="openJsonModal('请求头', logDetail.request_headers)"
          >
            <span class="detail-row-label">请求头</span>
            <span class="detail-row-value">{{ truncate(logDetail.request_headers) }}</span>
            <span class="detail-row-action">展示</span>
          </div>

          <!-- 请求体 -->
          <div
            class="detail-row"
            role="button"
            tabindex="0"
            aria-label="展示请求体完整内容"
            @click="openJsonModal('请求体', logDetail.request_body, requestBodyCollapseRule)"
            @keydown.enter.prevent="openJsonModal('请求体', logDetail.request_body, requestBodyCollapseRule)"
            @keydown.space.prevent="openJsonModal('请求体', logDetail.request_body, requestBodyCollapseRule)"
          >
            <span class="detail-row-label">请求体</span>
            <span class="detail-row-value">{{ truncate(logDetail.request_body) }}</span>
            <span class="detail-row-action">展示</span>
          </div>

          <!-- 响应头 -->
          <div
            class="detail-row"
            role="button"
            tabindex="0"
            aria-label="展示响应头完整内容"
            @click="openJsonModal('响应头', logDetail.response_headers)"
            @keydown.enter.prevent="openJsonModal('响应头', logDetail.response_headers)"
            @keydown.space.prevent="openJsonModal('响应头', logDetail.response_headers)"
          >
            <span class="detail-row-label">响应头</span>
            <span class="detail-row-value">{{ truncate(logDetail.response_headers) }}</span>
            <span class="detail-row-action">展示</span>
          </div>

          <!-- 响应体 -->
          <div
            v-if="hasContent(logDetail.response_body)"
            class="detail-row"
            role="button"
            tabindex="0"
            aria-label="展示响应体完整内容"
            @click="openJsonModal('响应体', logDetail.response_body)"
            @keydown.enter.prevent="openJsonModal('响应体', logDetail.response_body)"
            @keydown.space.prevent="openJsonModal('响应体', logDetail.response_body)"
          >
            <span class="detail-row-label">响应体</span>
            <span class="detail-row-value">{{ truncate(logDetail.response_body) }}</span>
            <span class="detail-row-action">展示</span>
          </div>

          <!-- 流式响应 -->
          <div
            v-if="hasContent(logDetail.chunks)"
            class="detail-row"
            role="button"
            tabindex="0"
            aria-label="展示流式响应完整内容"
            @click="openChunksModal(logDetail.chunks)"
            @keydown.enter.prevent="openChunksModal(logDetail.chunks)"
            @keydown.space.prevent="openChunksModal(logDetail.chunks)"
          >
            <span class="detail-row-label">流式响应</span>
            <span class="detail-row-value">{{ truncate(logDetail.chunks) }}</span>
            <span class="detail-row-action">展示</span>
          </div>
        </div>
      </div>
    </n-card>

    <!-- JSON 查看器 -->
    <JsonViewer
      v-model:show="jsonModal.show"
      :title="jsonModal.title"
      :content="jsonModal.content"
      :collapse-rule="jsonModal.collapseRule"
    />

    <!-- Chunks 查看器 -->
    <ChunksViewer
      v-model:show="chunksModal.show"
      :chunks="chunksModal.chunks"
    />

    <!--
      usage 原始数据浮窗：右上角对齐光标，整体落在鼠标位置的左下方。
      悬浮态纯展示（不接收鼠标事件，避免抢光标导致闪烁）；
      固定态（点击表格进入）可接收鼠标事件，支持框选与滚动，点击窗外关闭。
      Teleport 到 body，避免被详情卡片的滚动容器裁剪。
    -->
    <Teleport to="body">
      <div
        v-if="usagePopover.show"
        class="usage-popover"
        :class="{ 'usage-popover--pinned': usagePopover.pinned }"
        :aria-hidden="!usagePopover.pinned"
        :style="{ left: `${usagePopover.x}px`, top: `${usagePopover.y}px` }"
      >
        <pre class="usage-popover-content">{{ usagePopover.text }}</pre>
      </div>
    </Teleport>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.call-log-page {
  display: flex;
  gap: $space-lg;
  height: calc(100vh - #{$header-height} - #{$space-2xl});
  overflow: hidden;
}

.call-log-list {
  flex: 1;
  min-width: 0;
  height: 100%;
}

.call-log-detail {
  flex: 2;
  min-width: 0;
  height: 100%;
}

.call-log-loading,
.call-log-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 200px;
}

.log-list {
  display: flex;
  flex-direction: column;
  gap: $space-sm;
}

.log-list-items {
  display: flex;
  flex-direction: column;
  gap: $space-sm;
}

.log-item {
  background: $surface;
  border: 1px solid $border;
  border-radius: $radius;
  position: relative;
  overflow: hidden;
  transition: all 0.25s ease;
  cursor: pointer;

  &:hover {
    transform: translateY(-1px);
    box-shadow: $shadow-sm;
  }

  &.active {
    border-color: $accent;
    box-shadow: 0 0 0 1px $accent-mid;
  }
}

/*
 * SSE 新日志入场动画。
 * enter-from -> enter-to：从上方淡入下滑（透明 + 上移 + 高度收拢 -> 完全展开）。
 * log-move：下方现有项通过 FLIP 平滑下移让位，形成“向下挤压”的自然效果。
 * enter 与 move 的时长需与脚本 ANIM_DURATION 对齐（420ms）。
 */
.log-enter-from {
  opacity: 0;
  transform: translateY(-12px);
}

.log-enter-active {
  transition: opacity 0.42s ease, transform 0.42s cubic-bezier(0.22, 1, 0.36, 1);
  // 入场项在动画期间不占据布局排挤计算之外的额外空间，确保下方项平滑跟随。
  will-change: opacity, transform;
}

.log-move {
  transition: transform 0.42s cubic-bezier(0.22, 1, 0.36, 1);
}

/* 新入场记录的一次性高亮，动画结束后由脚本移除 log-item--fresh 类自然淡出。 */
.log-item--fresh {
  animation: log-fresh-highlight 1.2s ease;
}

@keyframes log-fresh-highlight {
  0% {
    box-shadow: 0 0 0 1px $accent-mid, 0 0 12px rgba(194, 122, 62, 0.35);
  }
  100% {
    box-shadow: 0 0 0 0 transparent;
  }
}

.log-item-top {
  height: 3px;
  width: 100%;

  &.success {
    background: $success;
  }

  &.warning {
    background: $warning;
  }

  &.error {
    background: $danger;
  }

  &.default {
    background: $border;
  }
}

.log-item-content {
  padding: $space-sm $space-md;
}

.log-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: $space-xs;
}

.log-provider {
  font-family: $font-display;
  font-size: 15px;
  font-weight: 600;
  color: $text-primary;
}

.log-status {
  font-family: $font-body;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: $text-muted;
}

.log-item-body {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.log-model {
  font-family: $font-body;
  font-size: 13px;
  color: $text-body;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 60%;
}

.log-time {
  font-family: $font-body;
  font-size: 12px;
  color: $text-muted;
  font-variant-numeric: tabular-nums;
}

.log-load-more {
  text-align: center;
  padding: $space-md 0 $space-sm;
}

.load-more-loading {
  display: inline-flex;
  align-items: center;
  gap: $space-sm;
  color: $text-muted;
  font-family: $font-body;
  font-size: 13px;
}

.load-more-btn {
  display: inline-block;
  color: $accent;
  font-family: $font-body;
  font-size: 13px;
  cursor: pointer;
  padding: $space-xs $space-md;
  border: 1px solid $accent-mid;
  border-radius: $radius;
  transition: all 0.2s ease;

  &:hover {
    background: $accent-light;
    border-color: $accent;
  }
}

// ── 详情面板样式 ──

.detail-content {
  padding: $space-sm 0;
}

.detail-meta {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: $space-sm $space-md $space-md;
  border-bottom: 1px solid $border-light;
  margin-bottom: $space-md;
}

.detail-meta-main {
  display: flex;
  flex-direction: column;
  gap: $space-sm;
  /* 占满剩余宽度，使下方 token 表格能三等分铺开，不再挤压靠左 */
  flex: 1;
  min-width: 0;
}

.detail-meta-title {
  display: flex;
  align-items: baseline;
  gap: $space-sm;
  min-width: 0;
}

.detail-provider {
  font-family: $font-display;
  font-size: 17px;
  font-weight: 600;
  color: $text-primary;
}

.detail-model {
  font-family: $font-body;
  font-size: 14px;
  color: $text-body;
}

.detail-meta-sub {
  display: flex;
  align-items: center;
  gap: $space-md;
}

.detail-time {
  font-family: $font-body;
  font-size: 13px;
  color: $text-muted;
  font-variant-numeric: tabular-nums;
}

/* 时长：首字 / 总响应 合并为一组，斜杠分隔以保持一致性 */
.detail-timing {
  font-family: $font-body;
  font-size: 13px;
  color: $text-muted;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/*
  token 用量表格：独占一行，四等分列（输入 / 输出 / 缓存占比 / 总计），单元格内居中。
  作为 .detail-meta 的兄弟节点而非其子节点，才能横向占满父容器整幅宽度。
  整块作为悬浮触发区（替代问号），悬浮即显示完整原始 usage。
 */
.detail-usage {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  margin: 0 $space-md $space-md;
  border: 1px solid $border-light;
  border-radius: $radius;
  overflow: hidden;
  cursor: help;
  transition: all 0.2s ease;

  &:hover,
  &:focus-visible {
    background: $accent-light;
    border-color: $accent-mid;
    outline: none;
  }
}

.detail-usage-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: $space-xs $space-sm;

  /* 列分隔线：仅列间，避免最右侧多一条 */
  & + & {
    border-left: 1px solid $border-light;
  }
}

.detail-usage-label {
  font-family: $font-body;
  font-size: 11px;
  color: $text-muted;
}

.detail-usage-value {
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  color: $text-body;
  font-variant-numeric: tabular-nums;
}

/*
  完整 usage 原始 JSON 浮窗。
  定位：left/top 设为光标坐标后，translateX(-100%) 把自身右边缘拉到光标处，
  于是浮窗右上角与光标重叠、整体落在鼠标位置的左下方。
 */
.usage-popover {
  position: fixed;
  z-index: 3000;
  transform: translateX(-100%);
  max-width: 420px;
  max-height: 320px;
  overflow: auto;
  margin: 0;
  padding: $space-sm $space-md;
  background: $surface;
  border: 1px solid $border;
  border-radius: $radius;
  box-shadow: $shadow-lg;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: $text-body;
  white-space: pre-wrap;
  word-break: break-word;
  /* 悬浮态不接收鼠标事件，避免浮窗抢走光标导致 mouseleave 抖动 */
  pointer-events: none;
  user-select: none;
}

/* 固定态：可交互，支持框选文本与滚动查看长 JSON */
.usage-popover--pinned {
  pointer-events: auto;
  user-select: text;
  border-color: $accent-mid;
}

.usage-popover-content {
  margin: 0;
  font: inherit;
  color: inherit;
  white-space: pre-wrap;
  word-break: break-word;
}

.detail-rows {
  display: flex;
  flex-direction: column;
}

/* 整行作为点击控件：点击任意位置（含预览文本）即打开查看器 */
.detail-row {
  display: flex;
  align-items: center;
  gap: $space-sm;
  padding: $space-sm $space-md;
  border-bottom: 1px solid $border-light;
  cursor: pointer;
  transition: background 0.2s ease;

  &:last-child {
    border-bottom: none;
  }

  &:hover,
  &:focus-visible {
    background: $accent-light;
    outline: none;
  }

  /* 整行 hover / 聚焦时，"展示"标记同步高亮，提示可点击 */
  &:hover .detail-row-action,
  &:focus-visible .detail-row-action {
    background: $accent-light;
    border-color: $accent;
  }
}

.detail-row-label {
  flex-shrink: 0;
  width: 60px;
  font-family: $font-body;
  font-size: 13px;
  font-weight: 600;
  color: $text-primary;
}

.detail-row-value {
  flex: 1;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  color: $text-body;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
  "展示"降级为纯视觉提示：点击由整行承担，故不再单独绑定事件。
  pointer-events: none 让鼠标事件穿透到整行，避免出现两套 hover 状态。
 */
.detail-row-action {
  flex-shrink: 0;
  font-family: $font-body;
  font-size: 12px;
  color: $accent;
  padding: $space-xs $space-sm;
  border: 1px solid $accent-mid;
  border-radius: $radius;
  transition: all 0.2s ease;
  pointer-events: none;
  user-select: none;
}

.load-more-end {
  color: $text-muted;
  font-family: $font-body;
  font-size: 13px;
}

.refresh-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: $radius;
  color: $text-muted;
  cursor: pointer;
  transition: all 0.2s ease;

  &:hover {
    color: $accent;
    background: $accent-light;
  }

  &.disabled {
    pointer-events: none;
    opacity: 0.5;
  }

  svg {
    width: 16px;
    height: 16px;
  }
}

/* ── 移动端：上下布局 + 横向滚动列表 ── */
@media (max-width: 650px) {
  .call-log-page {
    flex-direction: column;
    height: auto;
    min-height: calc(100vh - #{$header-height} - #{$space-2xl});
  }

  .call-log-list {
    flex: 0 0 auto;
    height: auto;
    max-height: none;

    /* 让 NCard 的 content-scrollable 的内部滚动容器变为横向 */
    :deep(.n-scrollbar-container) {
      overflow-x: auto !important;
      overflow-y: hidden !important;
    }

    :deep(.n-scrollbar-content) {
      display: inline-flex;
      min-width: 100%;
    }
  }

  .log-list {
    flex-direction: row;
    gap: $space-sm;
    padding-bottom: $space-xs;
  }

  .log-item {
    flex: 0 0 200px;
    min-width: 200px;
  }

  .log-model {
    max-width: 100px;
  }

  .log-load-more {
    display: flex;
    align-items: center;
    padding: 0 $space-sm;
    flex-shrink: 0;
  }

  .load-more-btn,
  .load-more-end,
  .load-more-loading {
    white-space: nowrap;
  }

  .call-log-detail {
    flex: 1;
    min-height: 300px;
  }

  .call-log-empty {
    min-height: 120px;
  }
}
</style>
