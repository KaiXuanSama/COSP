<script setup lang="ts">
/**
 * UsageLog —— 消费者视角的调用日志。
 *
 * 与调用者视角（CallLog.vue）并行的另一种读法：那边关心「这次请求成功了吗」，
 * 以卡片列表 + 右侧详情呈现单次调用的完整请求/响应；这里关心「这次请求花了多少 token」，
 * 故以宽表平铺，一行一次调用，九个字段横向对齐便于纵向扫读与比较。
 *
 * 数据源与调用者视角同为 `/config/api/logs` —— 那个端点每行都附带 token 用量，
 * 本视图多读 4 列而已。主表是 api_call_log（V8.5 起它是 api_call_usage 的超集），
 * 用量以 LEFT JOIN 附属，因此失败调用同样在列表中，只是 token 列显示为「—」。
 */
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { NCard, NEmpty, NSpin } from 'naive-ui'
import { fetchLogs, fetchLogDetail } from '@/api'
import { prependWithCursorShift } from '@/features/call-log/pagination'
import { createCoalescingSync } from '@/features/call-log/sync'
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
  downstream_protocol: 'OPENAI' | 'ANTHROPIC'
  upstream_protocol: 'OPENAI' | 'ANTHROPIC'
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

/**
 * 展开区进出场动画时长（毫秒），需与样式中 .expand-enter-active / .expand-leave-active 一致。
 *
 * 显式传给 Transition 的 :duration 而非让 Vue 自动探测：过渡声明在内层的 .expand-anim 上，
 * 被 Transition 直接控制的 tr 自身没有 transition，Vue 探测不到时长会立刻移除元素。
 */
const EXPAND_ANIM_DURATION = 260

/** 当前展开的行 id；null 表示无展开。同时只允许展开一行，避免表格被撑得难以扫读。 */
const expandedId = ref<number | null>(null)
/** 正在加载详情的行 id；null 表示没有请求在飞。 */
const loadingId = ref<number | null>(null)

/**
 * 已加载的详情，按 log id 缓存。
 *
 * 按 id 存而不是只留一个 expandedDetail，是收起动画的正确性要求：收起时
 * expandedId 立刻变为 null，但那一行还要播 260ms 的离场动画。若同时把详情清空，
 * 正在离场的行会瞬间掉到「加载失败」分支闪一下；切换到另一行时同理，
 * 离场的旧行会闪出新行的 loading 态。每行只读自己的那份，这两个问题都不存在。
 *
 * 副作用是重新展开同一行无需再请求。
 */
const detailCache = ref<Map<number, DetailItem>>(new Map())

/**
 * 详情缓存上限。
 *
 * 单条详情含完整请求/响应载荷，可达数 MB，不能无上限地留在内存里。
 * 满了就淘汰最早插入的那条（Map 保证插入序），够覆盖「收起动画期间仍需读到旧数据」
 * 与「来回对比几条记录」这两个实际用途。
 */
const DETAIL_CACHE_LIMIT = 8

/**
 * 点击行：展开 / 收起详情。
 *
 * 列表接口刻意不返回 usage_raw 与请求/响应载荷（大字段，只有展开时才需要），
 * 故展开时才按 id 拉一次完整详情，复用调用者视角的同一个端点。
 */
async function toggleRow(id: number) {
  if (expandedId.value === id) {
    expandedId.value = null
    return
  }
  expandedId.value = id

  // 命中缓存直接展开，不闪 loading。
  if (detailCache.value.has(id)) {
    await nextTick()
    scrollExpandedIntoView(id)
    return
  }

  loadingId.value = id
  try {
    const res = await fetchLogDetail(id)
    // 加载期间用户可能已点开别的行或收起，落后的响应不得覆盖当前状态。
    if (expandedId.value !== id) return
    putDetail(id, res.data)
    await nextTick()
    scrollExpandedIntoView(id)
  } catch (e) {
    console.error('加载调用详情失败:', e)
  } finally {
    if (loadingId.value === id) loadingId.value = null
  }
}

/** 写入详情缓存，超出上限时淘汰最早插入的一条。 */
function putDetail(id: number, detail: DetailItem) {
  const next = new Map(detailCache.value)
  next.set(id, detail)
  while (next.size > DETAIL_CACHE_LIMIT) {
    const oldest = next.keys().next().value
    if (oldest === undefined) break
    next.delete(oldest)
  }
  detailCache.value = next
}

/**
 * 展开后把展开区滚进视口。
 *
 * 点击靠底部的行时展开区会落在视口之外，用户看不到刚展开的内容。
 * block: 'nearest' 只在必要时滚动最小距离，不会把已经可见的行强行居中。
 *
 * 等展开动画播完再滚：动画期间行高还在长，此刻算出的目标位置会在滚动过程中失效，
 * 平滑滚动与高度增长同时进行会互相拉扯。
 */
function scrollExpandedIntoView(id: number) {
  setTimeout(() => {
    // 延迟期间可能已收起或切换到别的行，此时不该再滚动。
    if (expandedId.value !== id) return
    const el = document.querySelector(`[data-expand-for="${id}"]`)
    el?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }, EXPAND_ANIM_DURATION)
}

/** 加载第一页。 */
async function loadFirstPage() {
  initialLoading.value = true
  try {
    const res = await fetchLogs(null, pageSize)
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
  detailCache.value = new Map()
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
    const res = await fetchLogs(nextCursor.value, pageSize)
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
 *
 * 包在 {@link createCoalescingSync} 里：保证不并发拉取，且拉取期间到来的信号不被丢掉。
 */
const syncLatest = createCoalescingSync(async () => {
  try {
    const res = await fetchLogs(null, pageSize)
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

    // 头部插入的同时从表尾截掉同样数量，保持表长不随实时流无限增长；
    // 游标同步前移到新尾行，使被截掉的那批正好是下一页的开头，翻页即可拿回。
    const shifted = prependWithCursorShift(rows.value, fresh, nextCursor.value, hasMore.value)
    rows.value = shifted.rows
    nextCursor.value = shifted.nextCursor
    hasMore.value = shifted.hasMore
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
  }
})

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

function formatCallType(row: UsageLogItem): string {
  const upstream = row.upstream_protocol === 'ANTHROPIC' ? 'A' : 'O'
  const downstream = row.downstream_protocol === 'ANTHROPIC' ? 'A' : 'O'
  const protocol = upstream === downstream
    ? (upstream === 'A' ? 'Anthropic' : 'OpenAI')
    : `${upstream}→${downstream}`
  return `${row.is_stream ? '流式' : '非流'}: ${protocol}`
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
              <th class="col-call-type">类型</th>
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
                <td
                  class="col-call-type"
                  :title="`上游 ${row.upstream_protocol} → 下游 ${row.downstream_protocol}`"
                >
                  <span class="call-type-tag" :class="{ 'call-type-tag--stream': row.is_stream }">
                    {{ formatCallType(row) }}
                  </span>
                </td>
                <td class="col-ttfb">{{ formatDuration(row.ttfb_ms) }}</td>
                <td class="col-timing-sep" aria-hidden="true">/</td>
                <td class="col-total">{{ formatDuration(row.duration_ms) }}</td>
                <td class="col-num">{{ formatTokens(row.prompt_tokens) }}</td>
                <td class="col-num">{{ formatTokens(row.completion_tokens) }}</td>
                <td class="col-num">{{ formatCacheHitRate(row) }}</td>
              </tr>

              <!--
                展开行：colspan 覆盖全部 11 列，让内部布局摆脱表格列宽约束，
                CallLogDetail 因此能按自身网格铺开，与调用者视角完全一致。
              -->
              <!--
                展开行的进出场动画。

                Transition 的过渡类加在 tr 上，但真正被动画的是内层的 .expand-anim——
                tr 是 display: table-row，其 height 过渡在各浏览器上行为不一致，
                故通过后代选择器驱动内层元素（见样式中的 .expand-enter-from .expand-anim）。
                也正因为 tr 自身没有 transition，Vue 无从探测时长，必须显式给 :duration。
              -->
              <Transition name="expand" :duration="EXPAND_ANIM_DURATION">
                <tr v-if="expandedId === row.id" class="expand-row" :data-expand-for="row.id">
                  <td :colspan="11" class="expand-cell">
                    <!--
                      三层结构各有分工：
                      anim 用 grid-template-rows 承担高度动画，inner 提供内边距并裁掉溢出，
                      card 只管视觉边界。内边距必须落在 inner 而非 td，否则高度收拢到 0 时
                      仍会残留一段内边距高度。
                    -->
                    <div class="expand-anim">
                      <div class="expand-anim-inner">
                        <!--
                          内层卡片：把详情从表格的网格里“抬”出来，成为一个自成一体的块。
                          没有边界时，四条数据行看上去像是表格自己多长出来的行，层级关系读不出来。
                        -->
                        <div class="expand-card">
                          <CallLogDetail
                            v-if="detailCache.get(row.id)"
                            :detail="detailCache.get(row.id)!"
                            compact
                          />
                          <div v-else-if="loadingId === row.id" class="expand-state">
                            <n-spin size="small" />
                          </div>
                          <div v-else class="expand-state expand-state--empty">详情加载失败</div>
                        </div>
                      </div>
                    </div>
                  </td>
                </tr>
              </Transition>
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
  /* 内边距下移到 .expand-anim-inner：留在此处的话，高度收拢到 0 时会残留一段内边距高度 */
  padding: 0;
  background: $bg;
  /* 展开区内部是普通流式布局，不该继承表格单元格的 nowrap */
  white-space: normal;
}

/*
  高度动画层：用 grid-template-rows 的 0fr → 1fr 过渡实现「按内容高度」的展开，
  无需 JS 测量再写死 max-height（内容高度随请求头长短变化，写死的值要么截断要么留空）。
  子元素必须 min-height: 0，否则它的自动最小尺寸会顶住 0fr 使收拢无效。

  width: 0 + min-width: 100% 从卡片移到这里：colspan 单元格的内容宽度参与表格总宽计算，
  而里面的预览文本是长单行，不加这两条会把表格撑到数千像素（横向滚动条拉得很长，其余列变形）。
  width: 0 让本层对表格固有宽度的贡献归零，min-width: 100% 再让它填满解析后的单元格，
  于是表格宽度只由上方十列决定——预览文本的省略号也因此按真实可视宽度打。
 */
.expand-anim {
  display: grid;
  grid-template-rows: 1fr;
  width: 0;
  min-width: 100%;
}

/*
  裁剪层：只能有 min-height 与 overflow，不能带内边距。

  内边距不参与 grid 轨道的高度插值——轨道到 0fr 时只把内容高度压到 0，
  padding-top / padding-bottom 仍原样加在盒模型外侧。收起时高度会先平滑降到
  内边距之和（曾经是 24px）停住，直到元素被移除才一次性消失，看上去是一段
  固定高度的空白突然塔陷。故间距一律交给卡片的外边距（见 .expand-card）。
 */
.expand-anim-inner {
  min-height: 0;
  overflow: hidden;
}

/*
  详情卡片：白底 + 边框 + 阴影，与全局的 n-card 同一套视觉语言。
  左侧强调条改到卡片上（而非单元格），才能跟卡片圆角对齐。

  四周间距用外边距而非父层的内边距：外边距计入裁剪容器的内容高度，
  会跟着 grid 轨道一起插值到 0，收起因此能一直平滑到底。
  裁剪层的 overflow: hidden 同时立了 BFC，上外边距不会向外塔陷。
 */
.expand-card {
  margin: $space-sm $space-md $space-md;
  background: $surface;
  border: 1px solid $border-light;
  border-left: 2px solid $accent;
  border-radius: $radius;
  box-shadow: $shadow-sm;
  overflow: hidden;
}

/*
  ── 展开 / 收起动画 ──

  高度用 grid-template-rows 过渡（0fr ↔ 1fr），卡片同时做轻微的淡入与上移，
  两者叠加后是「向下生长」的观感，而非生硬的高度撑开。

  缓动选 cubic-bezier(0.22, 1, 0.36, 1)（easeOutQuint）与列表入场动画保持一致：
  起步快、收尾缓，高度变化在末段几乎静止，视线容易跟上。

  时长需与脚本中的 EXPAND_ANIM_DURATION 对齐（260ms）。
 */
.expand-enter-active .expand-anim {
  transition: grid-template-rows 0.26s cubic-bezier(0.22, 1, 0.36, 1);
}

/*
  收起用对称缓动，不复用 easeOutQuint。
  同一条曲线用在收起方向上是「起步极快、长尾收束」：实测高度在 155ms 就已归零，
  余下 105ms 元素以零高度空转，观感是动画早就结束了却还卡着一帧。
  cubic-bezier(0.4, 0, 0.2, 1) 两端对称，高度变化铺满整个时长。
 */
.expand-leave-active .expand-anim {
  transition: grid-template-rows 0.26s cubic-bezier(0.4, 0, 0.2, 1);
}

.expand-enter-from .expand-anim,
.expand-leave-to .expand-anim {
  grid-template-rows: 0fr;
}

/* 卡片自身的淡入上移比高度略快收尾，使内容先"就位"再由高度补齐余量 */
.expand-enter-active .expand-card {
  transition: opacity 0.22s ease, transform 0.26s cubic-bezier(0.22, 1, 0.36, 1);
}

/* 收起时卡片先淡出（比高度快一档），高度收拢期间不再有内容可见，避免内容被"压扁" */
.expand-leave-active .expand-card {
  transition: opacity 0.16s ease, transform 0.26s cubic-bezier(0.4, 0, 0.2, 1);
}

.expand-enter-from .expand-card,
.expand-leave-to .expand-card {
  opacity: 0;
  transform: translateY(-6px);
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

.col-call-type {
  width: 1%;
  white-space: nowrap;
}

.call-type-tag {
  display: inline-block;
  padding: 1px 6px;
  font-size: 11px;
  font-weight: 500;
  border-radius: 999px;
  background: rgba($text-muted, 0.12);
  color: $text-muted;
  white-space: nowrap;

  &--stream {
    background: rgba($accent, 0.15);
    color: $accent;
  }
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
