<script setup lang="ts">
/**
 * ChunksViewer — 流式响应 chunks 查看器模态框。
 *
 * 支持两种显示模式：
 * - 块显示: 每个 chunk 以卡片形式逐条展示
 * - 规整显示: 将 chunks 聚合为对话片段（思考过程 / 工具调用 / 正文回复）
 */
import { ref, computed } from 'vue'
import { NModal, NRadioGroup, NRadio, NScrollbar } from 'naive-ui'
import { marked } from 'marked'
import JsonNode from './JsonNode.vue'

const props = defineProps<{
  show: boolean
  chunks: string[]
}>()

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void
}>()

const displayMode = ref<'block' | 'clean'>('clean')

interface Segment {
  type: 'thinking' | 'content' | 'tool_calls'
  text: string
  toolCalls?: unknown[]
}

/**
 * 将 chunks 数组聚合为语义片段
 */
function aggregateChunks(chunks: string[]): Segment[] {
  let reasoning = ''
  let content = ''
  const mergedToolCalls = new Map<number, { id: string | null; type: string; function: { name: string | null; arguments: string } }>()

  for (const raw of chunks) {
    if (raw === '[DONE]') continue
    let obj: Record<string, unknown>
    try {
      const parsed = JSON.parse(raw)
      if (!parsed || typeof parsed !== 'object') continue
      obj = parsed
    } catch {
      continue
    }
    // 跳过 usage 之类的非 choices 块
    const choices = obj.choices as Array<Record<string, unknown>> | undefined
    if (!choices || choices.length === 0) continue

    const delta = choices[0]?.delta as Record<string, unknown> | undefined
    if (!delta) continue

    // 累积思考文本（兼容 reasoning_text 和 reasoning_content）
    const rt = delta.reasoning_text ?? delta.reasoning_content
    if (typeof rt === 'string' && rt.length > 0) {
      reasoning += rt
    }
    // 累积正文
    const dc = delta.content
    if (typeof dc === 'string' && dc.length > 0) {
      content += dc
    }
    // 收集工具调用（按 index 合并增量 chunk）
    const tc = delta.tool_calls as Array<Record<string, unknown>> | null | undefined
    if (Array.isArray(tc) && tc.length > 0) {
      for (const call of tc) {
        const idx = call.index as number
        if (!mergedToolCalls.has(idx)) {
          mergedToolCalls.set(idx, {
            id: (call.id as string) ?? null,
            type: (call.type as string) ?? 'function',
            function: {
              name: ((call.function as Record<string, unknown>)?.name as string) ?? null,
              arguments: ((call.function as Record<string, unknown>)?.arguments as string) ?? '',
            },
          })
        } else {
          const existing = mergedToolCalls.get(idx)!
          if (call.id) existing.id = call.id as string
          const fn = call.function as Record<string, unknown> | undefined
          if (fn?.name) existing.function.name = fn.name as string
          if (typeof fn?.arguments === 'string') {
            existing.function.arguments += fn.arguments
          }
        }
      }
    }
  }

  const segments: Segment[] = []
  if (reasoning) segments.push({ type: 'thinking', text: reasoning })
  if (mergedToolCalls.size > 0) {
    const toolCalls = [...mergedToolCalls.values()].map(tc => ({
      id: tc.id,
      type: tc.type,
      function: {
        name: tc.function.name,
        arguments: (() => { try { return JSON.parse(tc.function.arguments) } catch { return tc.function.arguments } })(),
      },
    }))
    segments.push({ type: 'tool_calls', text: '', toolCalls })
  }
  if (content) segments.push({ type: 'content', text: content })
  return segments
}

const segments = computed(() => aggregateChunks(props.chunks))

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
    :style="{ maxWidth: '720px', width: '90vw' }"
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

    <!-- 块显示模式 -->
    <div v-if="displayMode === 'block'" class="chunks-block">
      <n-scrollbar style="max-height: 65vh">
        <div v-for="(chunk, index) in chunks" :key="index" class="chunk-item">
          <div class="chunk-header">
            <span class="chunk-index">#{{ index + 1 }}</span>
          </div>
          <div class="chunk-content">
            <JsonNode v-if="parseChunk(chunk).isJson" :value="parseChunk(chunk).parsed" :depth="0" />
            <pre v-else class="chunk-raw">{{ chunk }}</pre>
          </div>
        </div>
      </n-scrollbar>
    </div>

    <!-- 规整显示模式 -->
    <div v-else-if="segments.length > 0" class="chunks-clean">
      <n-scrollbar style="max-height: 65vh">
        <div class="segments">
          <div v-for="(seg, i) in segments" :key="i" class="segment" :class="'segment-' + seg.type">
            <div class="segment-label">
              <span v-if="seg.type === 'thinking'">💭 思考过程</span>
              <span v-else-if="seg.type === 'tool_calls'">🔧 工具调用</span>
              <span v-else>💬 正文回复</span>
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

.chunk-item {
  background: $bg;
  border: 1px solid $border;
  border-radius: $radius;
  overflow: hidden;
}

.chunk-header {
  display: flex;
  align-items: center;
  padding: $space-xs $space-md;
  border-bottom: 1px solid $border-light;
  background: $surface;
}

.chunk-index {
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  color: $text-muted;
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
