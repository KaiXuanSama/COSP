<script setup lang="ts">
/**
 * UsageLog —— 消费者视角的调用日志。
 *
 * 与调用者视角（CallLog.vue）并行的另一种读法：那边关心「这次请求成功了吗」，
 * 以卡片列表 + 右侧详情呈现单次调用的完整请求/响应；这里关心「这次请求花了多少 token」，
 * 故以宽表平铺，一行一次调用，九个字段横向对齐便于纵向扫读与比较。
 *
 * 数据源是 `/config/api/usage-logs` —— 主表仍为 api_call_log（V8.5 起它是
 * api_call_usage 的超集），token 用量作为附属列附带。因此失败调用同样在列表中，
 * 只是 token 列显示为「—」。
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { NCard, NEmpty, NSpin } from 'naive-ui'
import { fetchUsageLogs } from '@/api'
import { createAuthEventSource, type AuthEventSource } from '@/api/authEventSource'

/**
 * 消费者视角的一行。
 *
 * token 与 ttfb 字段遵循 null vs 0 语义：null 表示上游未提供该数据（或本次调用
 * 根本没有用量行，如失败调用），0 表示上游报告了真实零值。展示时不得把 null 渲染成 0。
 */
interface UsageLogItem {
  id: number
  provider_key: string
  model_name: string
  is_stream: number
  status_code: number
  duration_ms: number | null
  payload_trimmed: number
  created_at: string
  prompt_tokens: number | null
  completion_tokens: number | null
  cached_tokens: number | null
  ttfb_ms: number | null
}

const rows = ref<UsageLogItem[]>([])
const nextCursor = ref<number | null>(null)
const hasMore = ref(false)
const pageSize = 50
const initialLoading = ref(true)
const loadingMore = ref(false)

/** 已知的最大 id 水位线，SSE 增量同步据此去重。 */
let knownMaxId = 0
/** 新到行的一次性高亮标记，动画播完由 CSS 自然淡出。 */
const freshIds = ref<Set<number>>(new Set())

/** 加载第一页。 */
async function loadFirstPage() {
  initialLoading.value = true
  try {
    const res = await fetchUsageLogs(null, pageSize)
    rows.value = res.data.items || []
    nextCursor.value = res.data.nextCursor ?? null
    hasMore.value = Boolean(res.data.hasMore)
    knownMaxId = rows.value.length ? rows.value[0].id : 0
  } catch (e) {
    console.error('加载消费记录失败:', e)
  } finally {
    initialLoading.value = false
  }
}

/** 刷新：整表替换，不走增量高亮。 */
async function refresh() {
  freshIds.value = new Set()
  rows.value = []
  nextCursor.value = null
  hasMore.value = false
  await loadFirstPage()
}

/** 加载更多，追加到表尾。 */
async function loadMore() {
  if (!hasMore.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const res = await fetchUsageLogs(nextCursor.value, pageSize)
    rows.value = [...rows.value, ...(res.data.items || [])]
    nextCursor.value = res.data.nextCursor ?? null
    hasMore.value = Boolean(res.data.hasMore)
  } catch (e) {
    console.error('加载更多消费记录失败:', e)
  } finally {
    loadingMore.value = false
  }
}

/**
 * 收到「有新日志」信号后的增量同步。
 *
 * 只拉第一页，把 id 超过水位线的新行整批插到表顶并标记高亮。
 * 与调用者视角的逐条入场队列不同：宽表的行高一致、信息密度高，
 * 逐条播放动画反而让视线难以跟随，整批插入 + 一次性高亮更清晰。
 */
let syncing = false
async function syncLatest() {
  if (syncing) return
  syncing = true
  try {
    const res = await fetchUsageLogs(null, pageSize)
    const latest: UsageLogItem[] = res.data.items || []
    if (!latest.length) return

    if (!rows.value.length) {
      rows.value = latest
      nextCursor.value = res.data.nextCursor ?? null
      hasMore.value = Boolean(res.data.hasMore)
      knownMaxId = latest[0].id
      return
    }

    const fresh = latest.filter((item) => item.id > knownMaxId)
    if (!fresh.length) return

    // 头部插入的同时从表尾截掉同样数量，保持表长不随实时流无限增长。
    rows.value = [...fresh, ...rows.value].slice(0, rows.value.length)
    knownMaxId = Math.max(knownMaxId, ...fresh.map((item) => item.id))

    const marked = new Set(fresh.map((item) => item.id))
    freshIds.value = marked
    setTimeout(() => {
      // 只清除本批标记：期间可能又来了新的一批，直接清空会打断它们的高亮。
      const next = new Set(freshIds.value)
      marked.forEach((id) => next.delete(id))
      freshIds.value = next
    }, 1400)
  } catch (e) {
    console.error('同步最新消费记录失败:', e)
  } finally {
    syncing = false
  }
}

// ── 格式化 ──────────────────────────────────────────────

/** 只显示时分秒，与调用者视角一致。 */
function formatTime(value: string): string {
  if (!value) return ''
  const parts = value.split('T')
  return parts.length > 1 ? parts[1] : value
}

function formatDuration(ms: number | null): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

/** null（上游未提供）显示 '—'；0 是真实零值，照实显示。千分位便于读大数值。 */
function formatTokens(value: number | null): string {
  if (value == null) return '—'
  return value.toLocaleString('en-US')
}

/**
 * 缓存命中占比 = 缓存命中 token / 输入 token。
 *
 * 严格区分 null 与 0：任一为 null（上游未提供）→ '—'，表示无从计算而非 0%；
 * 缓存为 0 且输入有值 → '0%'，那是上游报告的真实未命中。输入为 0 无法做除法，同样 '—'。
 */
function formatCacheHitRate(row: UsageLogItem): string {
  const { cached_tokens: cached, prompt_tokens: prompt } = row
  if (cached == null || prompt == null || prompt === 0) return '—'
  return `${((cached / prompt) * 100).toFixed(1)}%`
}

function statusClass(code: number): string {
  if (code >= 200 && code < 300) return 'success'
  if (code >= 400 && code < 500) return 'warning'
  if (code >= 500) return 'error'
  return 'default'
}

/** 状态码 -1 是空响应兜底的占位值，直接显示数字会让人误以为是 HTTP 码。 */
function statusLabel(code: number): string {
  return code === -1 ? '空响应' : String(code)
}

// ── SSE ─────────────────────────────────────────────────

/** 复用调用者视角的日志信号流：两个视角的数据源同一张主表，信号语义完全一致。 */
const LOG_STREAM_PATH = '/logs/stream'
let streamSource: AuthEventSource | null = null

function connectStream() {
  if (streamSource) return
  streamSource = createAuthEventSource({
    path: LOG_STREAM_PATH,
    handlers: {
      log: () => {
        void syncLatest()
      },
    },
  })
}

function disconnectStream() {
  streamSource?.close()
  streamSource = null
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
  <div class="usage-log-page">
    <n-card title="消费记录" :bordered="true" class="usage-log-card" content-scrollable>
      <template #header-extra>
        <div class="refresh-btn" :class="{ disabled: initialLoading }" title="刷新" @click="refresh">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
            stroke-linejoin="round">
            <path d="M21 2v6h-6" />
            <path d="M3 12a9 9 0 0 1 15-6.7L21 8" />
            <path d="M3 22v-6h6" />
            <path d="M21 12a9 9 0 0 1-15 6.7L3 16" />
          </svg>
        </div>
      </template>

      <div v-if="initialLoading" class="usage-log-state">
        <n-spin size="medium" />
      </div>

      <div v-else-if="rows.length === 0" class="usage-log-state">
        <n-empty description="暂无消费记录" />
      </div>

      <div v-else class="usage-table-wrap">
        <table class="usage-table">
          <thead>
            <tr>
              <th class="col-time">时间</th>
              <th class="col-status">状态</th>
              <th class="col-provider">供应商</th>
              <th class="col-model">模型</th>
              <th class="col-num" title="首字响应时长 / 总响应时长">首字 / 总耗时</th>
              <th class="col-num">输入</th>
              <th class="col-num">输出</th>
              <th class="col-num">缓存占比</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id" :class="{ 'row--fresh': freshIds.has(row.id) }">
              <td class="col-time">{{ formatTime(row.created_at) }}</td>
              <td class="col-status">
                <span class="status-dot" :class="statusClass(row.status_code)"></span>
                <span class="status-text">{{ statusLabel(row.status_code) }}</span>
              </td>
              <td class="col-provider">{{ row.provider_key }}</td>
              <td class="col-model" :title="row.model_name">{{ row.model_name }}</td>
              <td class="col-num">
                {{ formatDuration(row.ttfb_ms) }} / {{ formatDuration(row.duration_ms) }}
              </td>
              <td class="col-num">{{ formatTokens(row.prompt_tokens) }}</td>
              <td class="col-num">{{ formatTokens(row.completion_tokens) }}</td>
              <td class="col-num">{{ formatCacheHitRate(row) }}</td>
            </tr>
          </tbody>
        </table>

        <div class="load-more">
          <div v-if="loadingMore" class="load-more-loading">
            <n-spin size="small" />
            <span>加载中...</span>
          </div>
          <div v-else-if="hasMore" class="load-more-btn" @click="loadMore">更多</div>
          <div v-else class="load-more-end">到底了</div>
        </div>
      </div>
    </n-card>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.usage-log-page {
  height: calc(100vh - #{$header-height} - #{$space-2xl});
  overflow: hidden;
}

.usage-log-card {
  height: 100%;
}

.usage-log-state {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 200px;
}

/* 窄屏时整表横向滚动，而不是压缩列宽把数字挤成折行 */
.usage-table-wrap {
  overflow-x: auto;
}

.usage-table {
  width: 100%;
  border-collapse: collapse;
  font-family: $font-body;
  font-size: 13px;

  th {
    padding: $space-xs $space-sm;
    text-align: left;
    font-size: 11px;
    font-weight: 600;
    color: $text-muted;
    letter-spacing: 0.04em;
    border-bottom: 1px solid $border;
    white-space: nowrap;
  }

  td {
    padding: $space-xs $space-sm;
    color: $text-body;
    border-bottom: 1px solid $border-light;
    white-space: nowrap;
  }

  tbody tr {
    transition: background 0.2s ease;

    &:hover {
      background: $accent-light;
    }

    &:last-child td {
      border-bottom: none;
    }
  }
}

/* 数值列右对齐 + 等宽数字，纵向扫读时同位数字上下对齐 */
.col-num {
  text-align: right;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-variant-numeric: tabular-nums;
}

th.col-num {
  text-align: right;
}

.col-time {
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-variant-numeric: tabular-nums;
  color: $text-muted;
}

.col-provider {
  font-weight: 600;
  color: $text-primary;
}

/* 模型名可能很长，限宽省略，完整值在 title 里 */
.col-model {
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.col-status {
  white-space: nowrap;
}

.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;

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

.status-text {
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-variant-numeric: tabular-nums;
  font-size: 12px;
  color: $text-muted;
}

/* SSE 新到行的一次性高亮，与调用者视角的入场高亮同一视觉语言 */
.row--fresh {
  animation: usage-row-fresh 1.4s ease;
}

@keyframes usage-row-fresh {
  0% {
    background: rgba(194, 122, 62, 0.18);
  }
  100% {
    background: transparent;
  }
}

.load-more {
  text-align: center;
  padding: $space-md 0 $space-sm;
}

.load-more-loading {
  display: inline-flex;
  align-items: center;
  gap: $space-sm;
  color: $text-muted;
  font-size: 13px;
}

.load-more-btn {
  display: inline-block;
  color: $accent;
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

.load-more-end {
  color: $text-muted;
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
