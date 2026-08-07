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
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { NCard, NEmpty, NSpin } from 'naive-ui'
import { fetchUsageLogs, fetchLogDetail } from '@/api'
import { createAuthEventSource, type AuthEventSource } from '@/api/authEventSource'
import { CallLogDetail } from '@/components/calllog'
import type { DetailItem } from '@/types/calllog'

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

// ── 行内展开 ────────────────────────────────────────────

/** 当前展开的行 id；null 表示无展开。同时只允许展开一行，避免表格被撑得难以扫读。 */
const expandedId = ref<number | null>(null)
/** 展开区的详情数据，来自 GET /config/api/logs/{id}。 */
const expandedDetail = ref<DetailItem | null>(null)
const expandedLoading = ref(false)

/**
 * 点击行：展开 / 收起详情。
 *
 * 列表接口刻意不返回 usage_raw 与请求/响应载荷（大字段，只有展开时才需要），
 * 故展开时才按 id 拉一次完整详情，复用调用者视角的同一个端点。
 */
async function toggleRow(id: number) {
  if (expandedId.value === id) {
    expandedId.value = null
    expandedDetail.value = null
    return
  }
  expandedId.value = id
  expandedDetail.value = null
  expandedLoading.value = true
  try {
    const res = await fetchLogDetail(id)
    // 加载期间用户可能已点开别的行或收起，落后的响应不得覆盖当前状态。
    if (expandedId.value !== id) return
    expandedDetail.value = res.data
    await nextTick()
    scrollExpandedIntoView(id)
  } catch (e) {
    console.error('加载调用详情失败:', e)
  } finally {
    if (expandedId.value === id) expandedLoading.value = false
  }
}

/**
 * 展开后把展开区滚进视口。
 *
 * 点击靠底部的行时展开区会落在视口之外，用户看不到刚展开的内容。
 * block: 'nearest' 只在必要时滚动最小距离，不会把已经可见的行强行居中。
 */
function scrollExpandedIntoView(id: number) {
  const el = document.querySelector(`[data-expand-for="${id}"]`)
  el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

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

/** 刷新：整表替换，不走增量高亮。展开态一并收起（原展开行未必还在新的第一页里）。 */
async function refresh() {
  freshIds.value = new Set()
  expandedId.value = null
  expandedDetail.value = null
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
              <!--
                首字/总耗时拆成三列（右对齐 / 斜杠 / 左对齐）：合成一列右对齐时，
                两个变宽数字被整体推到右边界，斜杠位置随内容漂移，纵向扫读找不到锚点。
                拆开后斜杠列固定居中，两侧数字向它靠拢，同类量级自然上下对齐。

                表头同样拆成三列而非 colspan 跨列：跨列表头的文字比三列内容更宽时，
                浏览器会把多余宽度分摊给三列，斜杠列也分到一份并居中，
                于是两侧凭空多出空隙。分列后表头与数据的对齐方式逐列一致，宽度也不被撑开。
              -->
              <th class="col-ttfb" title="首字响应时长">首字</th>
              <!-- 表头也放斜杠：与数据行的分隔符同列同位，三列因此在视觉上重新绑成一组 -->
              <th class="col-timing-sep" aria-hidden="true">/</th>
              <th class="col-total" title="总响应时长">总耗时</th>
              <th class="col-num">输入</th>
              <th class="col-num">输出</th>
              <th class="col-num">缓存占比</th>
            </tr>
          </thead>
          <tbody>
            <!--
              每条记录渲染两个 tr：数据行 + 展开行。用 template 包住而非嵌套 div，
              是因为 tbody 的合法子节点只有 tr，插入其它元素会被浏览器提到表格外。
            -->
            <template v-for="row in rows" :key="row.id">
              <tr
                class="usage-row"
                :class="{ 'row--fresh': freshIds.has(row.id), 'row--expanded': expandedId === row.id }"
                role="button"
                tabindex="0"
                :aria-expanded="expandedId === row.id"
                :aria-label="`展开 ${row.model_name} 的调用详情`"
                @click="toggleRow(row.id)"
                @keydown.enter.prevent="toggleRow(row.id)"
                @keydown.space.prevent="toggleRow(row.id)"
              >
                <td class="col-time">{{ formatTime(row.created_at) }}</td>
                <td class="col-status">
                  <span class="status-dot" :class="statusClass(row.status_code)"></span>
                  <span class="status-text">{{ statusLabel(row.status_code) }}</span>
                </td>
                <td class="col-provider">{{ row.provider_key }}</td>
                <td class="col-model" :title="row.model_name">{{ row.model_name }}</td>
                <td class="col-ttfb">{{ formatDuration(row.ttfb_ms) }}</td>
                <td class="col-timing-sep" aria-hidden="true">/</td>
                <td class="col-total">{{ formatDuration(row.duration_ms) }}</td>
                <td class="col-num">{{ formatTokens(row.prompt_tokens) }}</td>
                <td class="col-num">{{ formatTokens(row.completion_tokens) }}</td>
                <td class="col-num">{{ formatCacheHitRate(row) }}</td>
              </tr>

              <!--
                展开行：colspan 覆盖全部 10 列，让内部布局摆脱表格列宽约束，
                CallLogDetail 因此能按自身网格铺开，与调用者视角完全一致。
              -->
              <tr v-if="expandedId === row.id" class="expand-row" :data-expand-for="row.id">
                <td :colspan="10" class="expand-cell">
                  <!--
                    内层卡片：把详情从表格的网格里“抬”出来，成为一个自成一体的块。
                    没有边界时，四条数据行看上去像是表格自己多长出来的行，层级关系读不出来。
                  -->
                  <div class="expand-card">
                    <div v-if="expandedLoading" class="expand-state">
                      <n-spin size="small" />
                    </div>
                    <CallLogDetail v-else-if="expandedDetail" :detail="expandedDetail" compact />
                    <div v-else class="expand-state expand-state--empty">详情加载失败</div>
                  </div>
                </td>
              </tr>
            </template>
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

  tbody tr:last-child td {
    border-bottom: none;
  }
}

/*
  数据行整行即展开控件：一行只有十个短字段，没有可独立点击的子元素，
  故不必再放展开箭头，整行命中区最大。
 */
.usage-row {
  cursor: pointer;
  transition: background 0.2s ease;

  &:hover,
  &:focus-visible {
    background: $accent-light;
    outline: none;
  }
}

/* 展开态：数据行保持高亮，明示卡片归属于哪一条记录 */
.usage-row.row--expanded {
  background: $accent-light;

  td {
    border-bottom-color: $accent-mid;
  }
}

/*
  展开单元格：只做卡片的容器。底色微沉一档，让上面的白色卡片浮起来。
  选择器带上 .usage-table 是为了在特异性上压过上面的 `.usage-table td`，
  从而无需 !important 就能接管内边距。
 */
.usage-table .expand-cell {
  padding: $space-sm $space-md $space-md;
  background: $bg;
  /* 展开区内部是普通流式布局，不该继承表格单元格的 nowrap */
  white-space: normal;
}

/*
  详情卡片：白底 + 边框 + 阴影，与全局的 n-card 同一套视觉语言。
  左侧强调条改到卡片上（而非单元格），才能跟卡片圆角对齐。

  width: 0 + min-width: 100% 是让卡片不撑宽表格的关键。
  colspan 单元格的内容宽度会参与表格总宽计算，而卡片里的预览文本是长单行，
  不加这两条会把表格撑到数千像素（横向滚动条被拉得很长，其余列也跟着变形）。
  width: 0 使本卡片对表格固有宽度的贡献归零，min-width: 100% 再让它填满解析后的单元格，
  于是表格宽度只由上方十列决定，卡片被动跟随——预览文本的省略号也因此按真实可视宽度打。
 */
.expand-card {
  width: 0;
  min-width: 100%;
  background: $surface;
  border: 1px solid $border-light;
  border-left: 2px solid $accent;
  border-radius: $radius;
  box-shadow: $shadow-sm;
  overflow: hidden;
}

.expand-state {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: $space-md 0;
}

.expand-state--empty {
  color: $text-muted;
  font-size: 13px;
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

/*
  首字 / 总耗时三列组：斜杠列作为固定的视觉锚点，两侧数字向它靠拢。
  三者共用等宽数字，故同量级的值在纵向上仍能对齐。
 */
.col-ttfb,
.col-total {
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-variant-numeric: tabular-nums;
}

/* 右对齐并贴近斜杠：右内边距归零，间隙由斜杠列自己的内边距提供 */
.col-ttfb {
  text-align: right;
  padding-right: 0;
}

/* 左对齐并贴近斜杠 */
.col-total {
  text-align: left;
  padding-left: 0;
}

/*
  斜杠：只作分隔符，弱化颜色、不参与信息层级。
  width: 1% 在表格 auto 布局下等价于「按内容取最小宽度」——
  声明值达不到内容宽度时浏览器以内容为准，多余宽度则让给两侧数字列，
  斜杠因此紧贴两侧数字，整组更紧凑。
 */
.usage-table .col-timing-sep {
  width: 1%;
  padding-left: 2px;
  padding-right: 2px;
  color: $text-muted;
  text-align: center;
  white-space: nowrap;
  user-select: none;
}

/*
  表头对齐随所在列，覆盖 th 的默认左对齐。
  「首字」压在右对齐的数字上方、「总耗时」压在左对齐的数字上方，
  于是表头与数据在视觉上是同一条竖线。
 */
.usage-table th.col-ttfb {
  text-align: right;
}

.usage-table th.col-total {
  text-align: left;
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
