<script setup lang="ts">
/**
 * ChunksViewer — 流式响应 chunks 查看器模态框。
 *
 * 支持两种显示模式：
 * - 块显示: 每个 chunk 以卡片形式逐条展示；跨协议时并排两列对照
 * - 规整显示: 将 chunks 聚合为对话片段（思考过程 / 工具调用 / 正文回复）
 *
 * <h2>为何只有块显示并排，规整显示不并排</h2>
 * 并排的价值在于看<strong>帧数不对等</strong>（实测 26 个上游事件 → 20 个下游 chunk），
 * 而规整显示展示的是<strong>语义内容</strong> —— 翻译不应改变语义，
 * 两侧规整后本应完全一致。并排两份相同内容没有信息量，反而挤占宽度；
 * 真正有价值的是一致性提示（不一致就是翻译 bug）。
 */
import { ref, computed, watch } from 'vue'
import { NModal, NRadioGroup, NRadio, NScrollbar, NTooltip, useMessage } from 'naive-ui'
import { marked } from 'marked'
import JsonNode from './JsonNode.vue'
import { copyToClipboard } from '@/utils/clipboard'
import { aggregateChunks, type ChunkSegment, type WireProtocol } from './chunkAggregation'
import { upstreamProtocolOf } from './chunkViews'

const props = defineProps<{
  show: boolean
  chunks: string[]
  /**
   * 决定 chunk 解析规则的协议。
   *
   * 用**下游**协议而非上游：落库的 chunk 是下游实际收到的形态，
   * 跨协议翻译时它已被译成下游协议的形状（见 `aggregateChunks` 的注释）。
   */
  downstreamProtocol: WireProtocol
  /**
   * 上游原始事件；仅跨协议翻译时存在。
   *
   * `null` 表示直连 —— 两侧协议相同，没有可对照的第二份。
   */
  upstreamChunks?: string[] | null
}>()

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void
}>()

const displayMode = ref<'block' | 'clean'>('clean')

/** 是否存在可对照的上游视图（跨协议翻译）。 */
const hasComparison = computed(() =>
  Array.isArray(props.upstreamChunks) && props.upstreamChunks.length > 0,
)

/** 上游那一侧的解析协议，用于规整视图的一致性校验。 */
const upstreamProtocol = computed(() => upstreamProtocolOf(props.downstreamProtocol))

/**
 * 块显示模式的分批渲染。
 *
 * chunks 数据已全量在前端，卡顿只来自一次性渲染过多卡片（每块还带一棵 JsonNode 树）。
 * 故仅限制渲染数量：首批 25 条，点"展示更多"再追加一批，直到全部渲染完。
 */
const BLOCK_PAGE_SIZE = 25

/** 当前已渲染的块数量。 */
const visibleBlockCount = ref(BLOCK_PAGE_SIZE)

/**
 * 当前应渲染的块（含预解析结果）。
 *
 * 解析在此处一次完成并缓存，避免模板中 v-if 判断与传值各调一次 parseChunk
 * 导致每次重渲染都重复解析 JSON。
 */
const visibleBlocks = computed(() => toBlocks(props.chunks))

/** 对照视图里上游那一侧的块。 */
const visibleUpstreamBlocks = computed(() => toBlocks(props.upstreamChunks ?? []))

/**
 * 分批截取并预解析。
 *
 * 两侧共用同一个 `visibleBlockCount`：分别计数会让「展示更多」在两栏之间
 * 产生错位，而对照的前提是两栏进度一致。
 */
function toBlocks(source: string[]) {
  return source.slice(0, visibleBlockCount.value).map((chunk, index) => {
    const { isJson, parsed } = parseChunk(chunk)
    return { chunk, index, isJson, parsed }
  })
}

/**
 * 是否还有未渲染的块。
 *
 * 取两侧的较大值：翻译路线下上游事件通常更多，只看下游会让上游那栏
 * 提前显示「到底了」而实际还有内容。
 */
const totalBlockCount = computed(() =>
  Math.max(props.chunks.length, props.upstreamChunks?.length ?? 0),
)

const hasMoreBlocks = computed(() => visibleBlockCount.value < totalBlockCount.value)

/** 追加渲染下一批块。 */
function showMoreBlocks() {
  visibleBlockCount.value = Math.min(
    visibleBlockCount.value + BLOCK_PAGE_SIZE,
    totalBlockCount.value,
  )
}

/**
 * 重置渲染进度。
 *
 * 换一条日志（chunks 变化）或重新打开模态框时都要复位，
 * 否则会残留上次展开的数量，等于没有分批效果。
 */
watch(
  () => [props.show, props.chunks, props.upstreamChunks] as const,
  () => {
    visibleBlockCount.value = BLOCK_PAGE_SIZE
  },
)

const message = useMessage()

/** 跟踪被点击的"已复制"按钮 key，2 秒后自动复位 */
const copiedKeys = ref<Set<string>>(new Set())

/**
 * 复制任意文本到剪贴板，复制成功后按钮临时显示"已复制"反馈。
 */
async function handleCopy(text: string, key: string) {
  const ok = await copyToClipboard(text)
  if (ok) {
    message.success('已复制到剪贴板')
    copiedKeys.value.add(key)
    setTimeout(() => copiedKeys.value.delete(key), 2000)
  } else {
    message.error('复制失败，请手动选择文本复制')
  }
}

/**
 * 块显示模式：复制当前 chunk 的原始字符串（[DONE] 也按原文复制）
 *
 * @param side 对照视图下区分两栏，避免两侧同下标的按钮共用「已复制」状态
 */
function copyBlockChunk(chunk: string, index: number, side: 'down' | 'up' = 'down') {
  return () => handleCopy(chunk, `block-${side}-${index}`)
}

/**
 * 规整显示模式：复制指定片段的内容。
 *  - 思考 / 正文：复制纯文本
 *  - 工具调用：复制 JSON 字符串
 */
function copySegment(seg: ChunkSegment, index: number) {
  const text =
    seg.type === 'tool_calls' ? JSON.stringify(seg.toolCalls ?? [], null, 2) : seg.text
  return () => handleCopy(text, `segment-${index}`)
}

const segments = computed(() => aggregateChunks(props.chunks, props.downstreamProtocol))

/** 上游侧规整结果，仅用于一致性校验，不单独展示。 */
const upstreamSegments = computed(() =>
  hasComparison.value
    ? aggregateChunks(props.upstreamChunks ?? [], upstreamProtocol.value)
    : [],
)

/**
 * 两侧规整后的语义是否一致。
 *
 * 翻译不应改变语义，因此这里不一致就说明翻译丢了或改了内容 —— 这比并排展示
 * 两份相同的内容更能直接回答「翻译有没有问题」。
 *
 * <p>只比较文本，不比较工具调用的 JSON：`arguments` 在两侧的序列化形态本就不同
 * （Anthropic 是对象、OpenAI 是字符串），逐字比较必然不等。
 */
const semanticConsistency = computed(() => {
  if (!hasComparison.value) return null
  const left = textOf(upstreamSegments.value)
  const right = textOf(segments.value)
  return left === right ? 'match' : 'differ'
})

function textOf(list: ChunkSegment[]): string {
  return list
    .filter(seg => seg.type !== 'tool_calls')
    .map(seg => `${seg.type}:${seg.text}`)
    .join('\n')
}

/**
 * 渲染 Markdown
 */
function renderMd(text: string): string {
  try {
    return marked.parse(text) as string
  } catch {
    return text
  }
}

/**
 * 尝试将 chunk 字符串解析为 JSON 对象
 */
function parseChunk(chunk: string): { parsed: unknown; isJson: boolean } {
  try {
    const obj = JSON.parse(chunk)
    if (obj !== null && typeof obj === 'object') {
      return { parsed: obj, isJson: true }
    }
    return { parsed: chunk, isJson: false }
  } catch {
    return { parsed: chunk, isJson: false }
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="流式响应"
    :style="{ maxWidth: hasComparison && displayMode === 'block' ? '1180px' : '720px', width: '90vw' }"
    closable
    :mask-closable="true"
    @update:show="(val: boolean) => emit('update:show', val)"
  >
    <template #header-extra>
      <n-radio-group v-model:value="displayMode" size="small">
        <n-radio value="block">块显示</n-radio>
        <n-radio value="clean">规整显示</n-radio>
      </n-radio-group>
    </template>

    <!--
      块显示模式：分批渲染，避免 chunk 过多时一次性渲染造成卡顿。
      跨协议翻译时并排两列，因为这里的核心价值是看帧数不对等。
    -->
    <div v-if="displayMode === 'block'" class="chunks-block">
      <!-- 并排对照：左上游、右下游 -->
      <div v-if="hasComparison" class="chunks-compare">
        <div class="chunks-compare-head">
          <div class="chunks-compare-col-title">
            <span class="protocol-tag protocol-tag--upstream">{{ upstreamProtocol }}</span>
            <span class="chunks-compare-hint">上游原始事件 · {{ upstreamChunks?.length ?? 0 }} 条</span>
          </div>
          <div class="chunks-compare-col-title">
            <span class="protocol-tag protocol-tag--downstream">{{ downstreamProtocol }}</span>
            <span class="chunks-compare-hint">下游实际收到 · {{ chunks.length }} 条</span>
          </div>
        </div>
        <n-scrollbar style="max-height: 62vh">
          <div class="chunks-compare-body">
            <!-- 两栏各自独立成列，不做逐帧对齐：帧数不对等是常态，
                 强行对齐需要后端给出映射关系，当前先让两侧可比对总量与顺序。 -->
            <div class="chunks-compare-col">
              <div v-for="block in visibleUpstreamBlocks" :key="`up-${block.index}`" class="chunk-item">
                <div class="chunk-header">
                  <span class="chunk-index">#{{ block.index + 1 }}</span>
                  <button
                    class="copy-btn"
                    type="button"
                    aria-label="复制此上游事件内容"
                    @click="copyBlockChunk(block.chunk, block.index, 'up')()"
                  >
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                      stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                    </svg>
                    <span class="copy-btn-label">
                      {{ copiedKeys.has(`block-up-${block.index}`) ? '已复制' : '复制' }}
                    </span>
                  </button>
                </div>
                <div class="chunk-content">
                  <JsonNode v-if="block.isJson" :value="block.parsed" :depth="0" />
                  <pre v-else class="chunk-raw">{{ block.chunk }}</pre>
                </div>
              </div>
            </div>
            <div class="chunks-compare-col">
              <div v-for="block in visibleBlocks" :key="`down-${block.index}`" class="chunk-item">
                <div class="chunk-header">
                  <span class="chunk-index">#{{ block.index + 1 }}</span>
                  <button
                    class="copy-btn"
                    type="button"
                    aria-label="复制此 chunk 内容"
                    @click="copyBlockChunk(block.chunk, block.index, 'down')()"
                  >
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                      stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                    </svg>
                    <span class="copy-btn-label">
                      {{ copiedKeys.has(`block-down-${block.index}`) ? '已复制' : '复制' }}
                    </span>
                  </button>
                </div>
                <div class="chunk-content">
                  <JsonNode v-if="block.isJson" :value="block.parsed" :depth="0" />
                  <pre v-else class="chunk-raw">{{ block.chunk }}</pre>
                </div>
              </div>
            </div>
          </div>

          <div class="chunks-load-more">
            <div
              v-if="hasMoreBlocks"
              class="chunks-load-more-btn"
              role="button"
              tabindex="0"
              @click="showMoreBlocks"
              @keydown.enter.prevent="showMoreBlocks"
              @keydown.space.prevent="showMoreBlocks"
            >
              展示更多（{{ Math.min(visibleBlockCount, totalBlockCount) }} / {{ totalBlockCount }}）
            </div>
            <div v-else class="chunks-load-more-end">
              到底了（上游 {{ upstreamChunks?.length ?? 0 }} · 下游 {{ chunks.length }}）
            </div>
          </div>
        </n-scrollbar>
      </div>

      <!-- 单栏：直连路线，两侧协议相同，没有可对照的第二份 -->
      <n-scrollbar v-else style="max-height: 65vh">
        <div v-for="block in visibleBlocks" :key="block.index" class="chunk-item">
          <div class="chunk-header">
            <span class="chunk-index">#{{ block.index + 1 }}</span>
            <n-tooltip :show="copiedKeys.has(`block-down-${block.index}`)" placement="bottom" :duration="0">
              <template #trigger>
                <button
                  class="copy-btn"
                  type="button"
                  aria-label="复制此 chunk 内容"
                  @click="copyBlockChunk(block.chunk, block.index)()"
                >
                  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                    stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                  </svg>
                  <span class="copy-btn-label">{{ copiedKeys.has(`block-${block.index}`) ? '已复制' : '复制' }}</span>
                </button>
              </template>
              {{ copiedKeys.has(`block-${block.index}`) ? '已复制到剪贴板' : '复制此 chunk 的原始 JSON' }}
            </n-tooltip>
          </div>
          <div class="chunk-content">
            <JsonNode v-if="block.isJson" :value="block.parsed" :depth="0" />
            <pre v-else class="chunk-raw">{{ block.chunk }}</pre>
          </div>
        </div>

        <!-- 渲染进度：还有未渲染的块时可继续追加，全部渲染完则提示到底 -->
        <div class="chunks-load-more">
          <div
            v-if="hasMoreBlocks"
            class="chunks-load-more-btn"
            role="button"
            tabindex="0"
            @click="showMoreBlocks"
            @keydown.enter.prevent="showMoreBlocks"
            @keydown.space.prevent="showMoreBlocks"
          >
            展示更多（{{ visibleBlocks.length }} / {{ chunks.length }}）
          </div>
          <div v-else class="chunks-load-more-end">到底了（共 {{ chunks.length }} 块）</div>
        </div>
      </n-scrollbar>
    </div>

    <!--
      规整显示模式：单栏。

      不并排两侧 —— 翻译不改变语义，两侧规整后本应完全一致，并排两份相同内容
      没有信息量。真正有价值的是下面那条一致性提示：不一致就说明翻译丢了内容。
    -->
    <div v-else-if="segments.length > 0" class="chunks-clean">
      <div v-if="semanticConsistency" class="consistency-banner"
        :class="`consistency-banner--${semanticConsistency}`">
        <template v-if="semanticConsistency === 'match'">
          ✓ 上下游语义一致（{{ upstreamProtocol }} → {{ downstreamProtocol }} 翻译无损）
        </template>
        <template v-else>
          ⚠ 上下游语义不一致，翻译可能丢失或改写了内容 —— 切到块显示可并排对照
        </template>
      </div>
      <n-scrollbar style="max-height: 65vh">
        <div class="segments">
          <div v-for="(seg, i) in segments" :key="i" class="segment" :class="'segment-' + seg.type">
            <div class="segment-label">
              <span v-if="seg.type === 'thinking'">💭 思考过程</span>
              <span v-else-if="seg.type === 'tool_calls'">🔧 工具调用</span>
              <span v-else>💬 正文回复</span>
              <n-tooltip :show="copiedKeys.has(`segment-${i}`)" placement="bottom" :duration="0">
                <template #trigger>
                  <button
                    class="copy-btn"
                    type="button"
                    :aria-label="seg.type === 'tool_calls' ? '复制工具调用 JSON' : '复制文本内容'"
                    @click="copySegment(seg, i)()"
                  >
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                      stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
                    </svg>
                    <span class="copy-btn-label">{{ copiedKeys.has(`segment-${i}`) ? '已复制' : '复制' }}</span>
                  </button>
                </template>
                {{ copiedKeys.has(`segment-${i}`)
                  ? '已复制到剪贴板'
                  : (seg.type === 'tool_calls' ? '复制工具调用的 JSON' : '复制此段文本') }}
              </n-tooltip>
            </div>
            <!-- 思考过程 / 正文回复 -->
            <div v-if="seg.text" class="segment-body markdown-body" v-html="renderMd(seg.text)" />
            <!-- 工具调用 -->
            <div v-if="seg.type === 'tool_calls' && seg.toolCalls" class="segment-body">
              <div v-for="(tc, j) in seg.toolCalls" :key="j" class="tool-call-item">
                <JsonNode :value="tc" :depth="0" collapse-rule="none" />
              </div>
            </div>
          </div>
        </div>
      </n-scrollbar>
    </div>

    <!-- 空数据 -->
    <div v-else class="chunks-clean">
      <span class="empty-hint">无可解析的响应数据</span>
    </div>
  </n-modal>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

// ── 块显示 ──

.chunks-block {
  :deep(.n-scrollbar-content) {
    display: flex;
    flex-direction: column;
    gap: $space-sm;
    padding: 2px;
  }
}

// ── 并排对照（仅跨协议翻译） ──

/**
 * 两栏等宽。
 *
 * 用 grid 而非 flex：两栏必须严格等宽才好对照，而 flex 子项会按内容宽度
 * 产生细微差异（上游的 JSON 通常更短）。
 */
.chunks-compare-head,
.chunks-compare-body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: $space-md;
}

.chunks-compare-head {
  padding: 0 2px $space-sm;
  border-bottom: 1px solid $border-light;
  margin-bottom: $space-sm;
}

.chunks-compare-col-title {
  display: flex;
  align-items: center;
  gap: $space-sm;
  min-width: 0;
}

/** 协议标签：用色彩区分两侧，避免只靠文字辨认。 */
.protocol-tag {
  flex-shrink: 0;
  padding: 1px 6px;
  border-radius: 4px;
  font-family: $font-mono;
  font-size: 10px;
  letter-spacing: 0.06em;

  &--upstream {
    background: $border-light;
    color: $text-muted;
  }

  &--downstream {
    background: $accent-light;
    color: $accent;
  }
}

.chunks-compare-hint {
  font-size: 11px;
  color: $text-muted;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/**
 * 单栏内部纵向排列。
 *
 * 两栏各自独立成列、不做逐帧对齐 —— 帧数不对等是常态（实测 26 → 20），
 * 强行对齐需要后端给出「第 i 个上游事件产出了哪几帧」的映射关系。
 * 当前先让两侧可比对总量与顺序。
 */
.chunks-compare-col {
  display: flex;
  flex-direction: column;
  gap: $space-sm;
  min-width: 0;
}

/** 对照模式下外层 scrollbar 不再纵向排列（改由 grid 接管）。 */
.chunks-compare {
  :deep(.n-scrollbar-content) {
    display: block;
    padding: 2px;
  }
}

// ── 语义一致性提示 ──

/**
 * 规整视图顶部的翻译校验横幅。
 *
 * 翻译不应改变语义，因此这条提示比并排展示两份相同内容更能直接回答
 * 「翻译有没有问题」。
 */
.consistency-banner {
  margin-bottom: $space-sm;
  padding: $space-xs $space-md;
  border-radius: $radius;
  font-size: 12px;
  line-height: 1.6;

  &--match {
    background: $accent-light;
    color: $accent;
  }

  &--differ {
    background: rgba($danger, 0.08);
    color: $danger;
  }
}

.chunk-item {
  background: $bg;
  border: 1px solid $border;
  border-radius: $radius;
  overflow: hidden;
}

.chunk-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $space-xs $space-md;
  border-bottom: 1px solid $border-light;
  background: $surface;
}

.chunk-index {
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  color: $text-muted;
}

/* 分批渲染的底部控件：与调用列表的"更多 / 到底了"保持一致的视觉语言 */
.chunks-load-more {
  display: flex;
  justify-content: center;
  padding: $space-sm 0 2px;
}

.chunks-load-more-btn {
  font-family: $font-body;
  font-size: 13px;
  color: $accent;
  cursor: pointer;
  padding: $space-xs $space-md;
  border: 1px solid $accent-mid;
  border-radius: $radius;
  transition: all 0.2s ease;
  user-select: none;

  &:hover {
    background: $accent-light;
    border-color: $accent;
  }

  &:focus-visible {
    outline: 2px solid $accent;
    outline-offset: 2px;
  }
}

.chunks-load-more-end {
  font-family: $font-body;
  font-size: 13px;
  color: $text-muted;
  padding: $space-xs $space-md;
  user-select: none;
}

.copy-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  font-family: $font-body;
  font-size: 11px;
  color: $text-muted;
  background: transparent;
  border: 1px solid $border;
  border-radius: 4px;
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease, background 0.15s ease;

  svg {
    flex-shrink: 0;
  }

  .copy-btn-label {
    line-height: 1;
  }

  &:hover {
    color: $accent;
    border-color: $accent;
    background: rgba(194, 122, 62, 0.06);
  }

  &:active {
    transform: translateY(0.5px);
  }
}

.chunk-content {
  padding: $space-sm $space-md;
}

.chunk-raw {
  margin: 0;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.5;
  color: $text-body;
  white-space: pre-wrap;
  word-break: break-all;
}

// ── 规整显示 ──

.chunks-clean {
  .empty-hint {
    display: block;
    text-align: center;
    color: $text-muted;
    font-family: $font-body;
    font-size: 14px;
    padding: $space-2xl 0;
  }
}

.segments {
  display: flex;
  flex-direction: column;
  gap: $space-md;
  padding: 2px;
}

.segment {
  border: 1px solid $border;
  border-radius: $radius;
  overflow: hidden;
}

.segment-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: $space-sm $space-md;
  font-family: $font-body;
  font-size: 13px;
  font-weight: 600;
  color: $text-primary;
  border-bottom: 1px solid $border-light;
}

.segment-thinking .segment-label {
  background: rgba(90, 122, 184, 0.06);
}

.segment-tool_calls .segment-label {
  background: rgba(194, 122, 62, 0.06);
}

.segment-content .segment-label {
  background: rgba(58, 138, 92, 0.06);
}

.segment-body {
  padding: $space-md;
  background: $surface;
}

// ── Markdown 渲染样式 ──

.markdown-body {
  font-family: $font-body;
  font-size: 14px;
  line-height: 1.7;
  color: $text-body;
  word-break: break-word;

  :deep(p) {
    margin: 0 0 $space-sm;
    &:last-child { margin-bottom: 0; }
  }

  :deep(h1), :deep(h2), :deep(h3), :deep(h4) {
    margin: $space-md 0 $space-sm;
    color: $text-primary;
    font-weight: 600;
  }

  :deep(h1) { font-size: 1.3em; }
  :deep(h2) { font-size: 1.15em; }
  :deep(h3) { font-size: 1.05em; }

  :deep(code) {
    font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
    font-size: 0.9em;
    background: $bg;
    border: 1px solid $border-light;
    border-radius: 3px;
    padding: 1px 4px;
  }

  :deep(pre) {
    margin: $space-sm 0;
    padding: $space-md;
    background: $bg;
    border: 1px solid $border;
    border-radius: $radius;
    overflow-x: auto;

    code {
      background: none;
      border: none;
      padding: 0;
      font-size: 13px;
    }
  }

  :deep(ul), :deep(ol) {
    padding-left: 1.5em;
    margin: $space-sm 0;
  }

  :deep(blockquote) {
    margin: $space-sm 0;
    padding: $space-xs $space-md;
    border-left: 3px solid $accent-mid;
    color: $text-muted;
  }

  :deep(table) {
    border-collapse: collapse;
    margin: $space-sm 0;
    width: 100%;

    th, td {
      border: 1px solid $border;
      padding: $space-xs $space-sm;
      text-align: left;
    }

    th {
      background: $bg;
      font-weight: 600;
    }
  }

  :deep(hr) {
    border: none;
    border-top: 1px solid $border;
    margin: $space-md 0;
  }

  :deep(a) {
    color: $accent;
    text-decoration: none;
    &:hover { text-decoration: underline; }
  }
}

// ── 工具调用 ──

.tool-call-item {
  background: $bg;
  border: 1px solid $border;
  border-radius: $radius;
  padding: $space-sm $space-md;
  margin-bottom: $space-sm;

  &:last-child {
    margin-bottom: 0;
  }
}
</style>
