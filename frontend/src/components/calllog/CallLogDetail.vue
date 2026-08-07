<script setup lang="ts">
/**
 * CallLogDetail —— 单条调用日志的详情呈现块。
 *
 * 从 CallLog.vue 的右侧详情面板提取而来，供两个视角复用：
 * 调用者视角把它放在右侧卡片里，消费者视角把它放在表格的行内展开区。
 * 两处交互因此天然一致——同样的 usage 浮窗、同样的请求/响应模态框。
 *
 * 组件自带 JsonViewer / ChunksViewer 与 usage 浮窗的完整状态，
 * 宿主只需传入 detail，不必关心弹窗如何开合。
 */
import { ref, onMounted, onUnmounted } from 'vue'
// 直接按路径导入同目录组件，不走 ./index.ts —— 本组件也由那个 barrel 导出，
// 经它引用会形成 index -> CallLogDetail -> index 的循环依赖。
import JsonViewer from './JsonViewer.vue'
import ChunksViewer from './ChunksViewer.vue'
import type { CollapseRule } from './JsonNode.vue'
import type { DetailItem, UsageDetail } from '@/types/calllog'

const props = withDefaults(
  defineProps<{
    detail: DetailItem
    /**
     * 紧凑模式：收紧间距与字号。
     *
     * 调用者视角把本组件放在一整张卡片里，有充足纵向空间，用默认的舒展排布；
     * 消费者视角把它嵌在表格行内，上下都是密集的数据行，同样的间距会显得松散、
     * 且把下方记录推出视口。故用一个开关切换两套尺度，而不是让宿主用 :deep() 穿透覆盖——
     * 后者会把本组件的内部结构固化进宿主的样式表。
     */
    compact?: boolean
  }>(),
  { compact: false },
)

const jsonModal = ref({ show: false, title: '', content: null as unknown, collapseRule: 'none' as CollapseRule })
const chunksModal = ref({ show: false, chunks: [] as string[] })

// ── 格式化 ──────────────────────────────────────────────

/**
 * 格式化时间（只显示时分秒）
 */
function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  const parts = dateStr.split('T')
  return parts.length > 1 ? parts[1] : dateStr
}

/**
 * 预览文本的字符上限。
 *
 * 视觉上的截断交给 CSS 的 text-overflow: ellipsis —— 它按容器实际宽度裁，
 * 窗口缩放自动跟随，比在 JS 里定一个字符数准确得多（等宽字体下 12px 约 7px/字符，
 * 宽 620px 的容器能放 86 个字符，容器一变这个数就错了）。
 *
 * 这里的上限只为性能兜底：request_body 可能有数 MB，整串塞进文本节点，
 * 浏览器仍要为这一行做完整排版。400 字符远超任何现实容器宽度（约 2880px），
 * 因此不会提前把 CSS 该裁的地方裁掉。
 */
const PREVIEW_MAX_CHARS = 400

/** 取预览文本：只做性能兜底的粗切，视觉截断由 CSS 负责。 */
function preview(str: string | null): string {
  if (!str) return ''
  return str.length > PREVIEW_MAX_CHARS ? str.substring(0, PREVIEW_MAX_CHARS) : str
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

// ── usage 原始数据浮窗 ──────────────────────────────────

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

// ── 查看器 ──────────────────────────────────────────────

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
  // 固定态浮窗需要"点击外部关闭"，故挂文档级监听；用捕获阶段避免被内部 stopPropagation 拦掉。
  document.addEventListener('click', onDocumentClickForPopover, true)
})

onUnmounted(() => {
  document.removeEventListener('click', onDocumentClickForPopover, true)
})
</script>

<template>
  <div class="detail-content" :class="{ 'detail-content--compact': props.compact }">
    <!-- 顶部元信息 -->
    <div class="detail-meta">
      <div class="detail-meta-main">
        <div class="detail-meta-title">
          <span class="detail-provider">{{ props.detail.provider_key }}</span>
          <span class="detail-model">{{ props.detail.model_name }}</span>
        </div>
      </div>
      <div class="detail-meta-sub">
        <span class="detail-timing" title="首字响应时长 / 总响应时长">{{ formatTtfb(props.detail.usage) }} / {{ formatDuration(props.detail.duration_ms) }}</span>
        <span class="detail-time">{{ formatTime(props.detail.created_at) }}</span>
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
      @mouseenter="showUsagePopover($event, props.detail.usage)"
      @mousemove="moveUsagePopover"
      @mouseleave="hideUsagePopover"
      @focus="showUsagePopover($event, props.detail.usage)"
      @blur="hideUsagePopover"
      @click="pinUsagePopover($event, props.detail.usage)"
    >
      <span class="detail-usage-cell">
        <span class="detail-usage-label">输入</span>
        <span class="detail-usage-value">{{ formatTokens(props.detail.usage?.prompt_tokens) }}</span>
      </span>
      <span class="detail-usage-cell">
        <span class="detail-usage-label">输出</span>
        <span class="detail-usage-value">{{ formatTokens(props.detail.usage?.completion_tokens) }}</span>
      </span>
      <span class="detail-usage-cell">
        <span class="detail-usage-label">缓存占比</span>
        <span class="detail-usage-value">{{ formatCacheHitRate(props.detail.usage) }}</span>
      </span>
      <span class="detail-usage-cell">
        <span class="detail-usage-label">总计</span>
        <span class="detail-usage-value">{{ formatTotalTokens(props.detail.usage) }}</span>
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
        @click="openJsonModal('请求头', props.detail.request_headers)"
        @keydown.enter.prevent="openJsonModal('请求头', props.detail.request_headers)"
        @keydown.space.prevent="openJsonModal('请求头', props.detail.request_headers)"
      >
        <span class="detail-row-label">请求头</span>
        <span class="detail-row-value">{{ preview(props.detail.request_headers) }}</span>
        <span class="detail-row-action">展示</span>
      </div>

      <!-- 请求体 -->
      <div
        class="detail-row"
        role="button"
        tabindex="0"
        aria-label="展示请求体完整内容"
        @click="openJsonModal('请求体', props.detail.request_body, requestBodyCollapseRule)"
        @keydown.enter.prevent="openJsonModal('请求体', props.detail.request_body, requestBodyCollapseRule)"
        @keydown.space.prevent="openJsonModal('请求体', props.detail.request_body, requestBodyCollapseRule)"
      >
        <span class="detail-row-label">请求体</span>
        <span class="detail-row-value">{{ preview(props.detail.request_body) }}</span>
        <span class="detail-row-action">展示</span>
      </div>

      <!-- 响应头 -->
      <div
        class="detail-row"
        role="button"
        tabindex="0"
        aria-label="展示响应头完整内容"
        @click="openJsonModal('响应头', props.detail.response_headers)"
        @keydown.enter.prevent="openJsonModal('响应头', props.detail.response_headers)"
        @keydown.space.prevent="openJsonModal('响应头', props.detail.response_headers)"
      >
        <span class="detail-row-label">响应头</span>
        <span class="detail-row-value">{{ preview(props.detail.response_headers) }}</span>
        <span class="detail-row-action">展示</span>
      </div>

      <!-- 响应体 -->
      <div
        v-if="hasContent(props.detail.response_body)"
        class="detail-row"
        role="button"
        tabindex="0"
        aria-label="展示响应体完整内容"
        @click="openJsonModal('响应体', props.detail.response_body)"
        @keydown.enter.prevent="openJsonModal('响应体', props.detail.response_body)"
        @keydown.space.prevent="openJsonModal('响应体', props.detail.response_body)"
      >
        <span class="detail-row-label">响应体</span>
        <span class="detail-row-value">{{ preview(props.detail.response_body) }}</span>
        <span class="detail-row-action">展示</span>
      </div>

      <!-- 流式响应 -->
      <div
        v-if="hasContent(props.detail.chunks)"
        class="detail-row"
        role="button"
        tabindex="0"
        aria-label="展示流式响应完整内容"
        @click="openChunksModal(props.detail.chunks)"
        @keydown.enter.prevent="openChunksModal(props.detail.chunks)"
        @keydown.space.prevent="openChunksModal(props.detail.chunks)"
      >
        <span class="detail-row-label">流式响应</span>
        <span class="detail-row-value">{{ preview(props.detail.chunks) }}</span>
        <span class="detail-row-action">展示</span>
      </div>
    </div>

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
      Teleport 到 body，避免被宿主的滚动容器裁剪。
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

  浮窗被 Teleport 到 body，但 scoped 的 data-v 属性由本组件渲染时写入，
  跟着节点一起搬走，因此写在 scoped 块里依然生效。
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

/*
  预览文本：填满标签与"展示"之间的剩余空间，超出部分由 CSS 按容器实际宽度打省略号。

  min-width: 0 是让 overflow: hidden 真正生效的关键——flex 子项的自动最小尺寸
  默认是内容宽度，没有它时长文本会把行撑宽而不是被裁掉。
 */
.detail-row-value {
  flex: 1;
  min-width: 0;
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

/*
  ── 紧凑变体 ──
  只调间距与字号，不动结构与配色：行内展开需要在有限的纵向空间里放完同样的信息，
  而不是换一套视觉语言。各处压缩幅度按「信息密度 vs 可读性」取舍——
  数值与预览文本保持原字号（它们是主体内容），被压缩的是留白与标签。
 */
.detail-content--compact {
  padding: 0;

  .detail-meta {
    /* 元信息只有一行文字，上下留白收到最小；分隔线保留，它是分区的唯一线索 */
    padding: $space-sm $space-md;
    margin-bottom: $space-sm;
  }

  .detail-provider {
    font-size: 15px;
  }

  .detail-model {
    font-size: 13px;
  }

  .detail-usage {
    margin: 0 $space-md $space-sm;
  }

  .detail-usage-cell {
    /* 标签与数值改为同行横排：竖排两行在紧凑模式下是最大的一块空间浪费 */
    flex-direction: row;
    align-items: baseline;
    justify-content: center;
    gap: $space-xs;
    padding: 6px $space-sm;
  }

  .detail-row {
    padding: 6px $space-md;
  }

  .detail-row-label {
    width: 52px;
    font-size: 12px;
  }

  .detail-row-action {
    padding: 1px 6px;
    font-size: 11px;
  }
}
</style>
