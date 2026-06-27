<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { NCard, NEmpty, NSpin } from 'naive-ui'
import { fetchLogs, fetchLogDetail } from '@/api'
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
const currentPage = ref(1)
const totalPages = ref(0)
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

const hasMore = computed(() => currentPage.value < totalPages.value)

const selectedLogId = ref<number | null>(null)
const logDetail = ref<DetailItem | null>(null)
const detailLoading = ref(false)

const jsonModal = ref({ show: false, title: '', content: null as unknown, collapseRule: 'none' as CollapseRule })
const chunksModal = ref({ show: false, chunks: [] as string[] })

/**
 * 加载第一页日志
 */
async function loadFirstPage() {
  loading.value = true
  initialLoading.value = true
  try {
    const res = await fetchLogs(1, pageSize)
    logs.value = res.data.items || []
    currentPage.value = res.data.currentPage
    totalPages.value = res.data.totalPages
  } catch (e) {
    console.error('加载日志失败:', e)
  } finally {
    loading.value = false
    initialLoading.value = false
  }
}

/**
 * 刷新日志列表（清空后重新加载第一页）
 */
async function refreshLogs() {
  logs.value = []
  currentPage.value = 1
  totalPages.value = 0
  await loadFirstPage()
}

/**
 * 加载更多日志（追加到列表末尾）
 */
async function loadMore() {
  if (!hasMore.value || loadingMore.value) return

  loadingMore.value = true
  try {
    const nextPage = currentPage.value + 1
    const res = await fetchLogs(nextPage, pageSize)
    const newItems = res.data.items || []
    logs.value = [...logs.value, ...newItems]
    currentPage.value = res.data.currentPage
    totalPages.value = res.data.totalPages
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

onMounted(() => {
  loadFirstPage()
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
        <div v-for="log in logs" :key="log.id" class="log-item" :class="{ active: selectedLogId === log.id }" @click="selectLog(log.id)">
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

        <!-- 加载更多 -->
        <div class="log-load-more">
          <div v-if="loadingMore" class="load-more-loading">
            <n-spin size="small" />
            <span>加载中...</span>
          </div>
          <div v-else-if="hasMore" class="load-more-btn" @click="loadMore">
            显示更多条目
          </div>
          <div v-else class="load-more-end">
            已经到底了
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
</style>
