<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { NCard, NEmpty, NSpin } from 'naive-ui'
import http, { fetchLogs, fetchLogDetail } from '@/api'
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
/** 进入 CLOSED 状态后的手动重连间隔（毫秒）。 */
const RECONNECT_DELAY = 3000

let eventSource: EventSource | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let manualClose = false
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
 */
function connectStream() {
  if (eventSource) return
  manualClose = false
  openSource()
}

function openSource() {
  const url = `${http.defaults.baseURL ?? ''}${LOG_STREAM_PATH}`
  const source = new EventSource(url)
  eventSource = source

  source.addEventListener('log', () => {
    void syncLatestLogs()
  })

  source.onerror = () => {
    // 浏览器原生 EventSource 网络抖动时会自动重连；
    // 仅当进入 CLOSED（如服务端返回非 2xx）时才手动兜底重连。
    if (source.readyState === EventSource.CLOSED && !manualClose) {
      scheduleReconnect()
    }
  }
}

function scheduleReconnect() {
  cleanupSource()
  if (reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    if (!manualClose) openSource()
  }, RECONNECT_DELAY)
}

function cleanupSource() {
  if (eventSource) {
    eventSource.close()
    eventSource = null
  }
}

function disconnectStream() {
  manualClose = true
  if (reconnectTimer) {
    clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  cleanupSource()
}

onMounted(() => {
  loadFirstPage()
  connectStream()
})

onUnmounted(() => {
  disconnectStream()
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
            <span class="detail-provider">{{ logDetail.provider_key }}</span>
            <span class="detail-model">{{ logDetail.model_name }}</span>
          </div>
          <div class="detail-meta-sub">
            <span class="detail-duration">{{ formatDuration(logDetail.duration_ms) }}</span>
            <span class="detail-time">{{ formatTime(logDetail.created_at) }}</span>
          </div>
        </div>

        <!-- 数据行 -->
        <div class="detail-rows">
          <!-- 请求头 -->
          <div class="detail-row">
            <span class="detail-row-label">请求头</span>
            <span class="detail-row-value">{{ truncate(logDetail.request_headers) }}</span>
            <span class="detail-row-action" @click="openJsonModal('请求头', logDetail.request_headers)">展示</span>
          </div>

          <!-- 请求体 -->
          <div class="detail-row">
            <span class="detail-row-label">请求体</span>
            <span class="detail-row-value">{{ truncate(logDetail.request_body) }}</span>
            <span class="detail-row-action" @click="openJsonModal('请求体', logDetail.request_body, requestBodyCollapseRule)">展示</span>
          </div>

          <!-- 响应头 -->
          <div class="detail-row">
            <span class="detail-row-label">响应头</span>
            <span class="detail-row-value">{{ truncate(logDetail.response_headers) }}</span>
            <span class="detail-row-action" @click="openJsonModal('响应头', logDetail.response_headers)">展示</span>
          </div>

          <!-- 响应体 -->
          <div v-if="hasContent(logDetail.response_body)" class="detail-row">
            <span class="detail-row-label">响应体</span>
            <span class="detail-row-value">{{ truncate(logDetail.response_body) }}</span>
            <span class="detail-row-action" @click="openJsonModal('响应体', logDetail.response_body)">展示</span>
          </div>

          <!-- 流式响应 -->
          <div v-if="hasContent(logDetail.chunks)" class="detail-row">
            <span class="detail-row-label">流式响应</span>
            <span class="detail-row-value">{{ truncate(logDetail.chunks) }}</span>
            <span class="detail-row-action" @click="openChunksModal(logDetail.chunks)">展示</span>
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
  align-items: baseline;
  gap: $space-sm;
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

.detail-duration {
  font-family: $font-body;
  font-size: 13px;
  color: $text-muted;
}

.detail-time {
  font-family: $font-body;
  font-size: 13px;
  color: $text-muted;
  font-variant-numeric: tabular-nums;
}

.detail-rows {
  display: flex;
  flex-direction: column;
}

.detail-row {
  display: flex;
  align-items: center;
  gap: $space-sm;
  padding: $space-sm $space-md;
  border-bottom: 1px solid $border-light;

  &:last-child {
    border-bottom: none;
  }

  &:hover {
    background: $accent-light;
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

.detail-row-action {
  flex-shrink: 0;
  font-family: $font-body;
  font-size: 12px;
  color: $accent;
  cursor: pointer;
  padding: $space-xs $space-sm;
  border: 1px solid $accent-mid;
  border-radius: $radius;
  transition: all 0.2s ease;

  &:hover {
    background: $accent-light;
    border-color: $accent;
  }
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
