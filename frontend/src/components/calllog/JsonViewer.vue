<script setup lang="ts">
/**
 * JsonViewer — 格式化 JSON 查看器模态框。
 *
 * 接收原始 JSON 字符串或对象，在模态框中以可折叠的树形结构展示。
 */
import { computed, ref } from 'vue'
import { NModal, NScrollbar, NTooltip, useMessage } from 'naive-ui'
import JsonNode from './JsonNode.vue'
import type { CollapseRule } from './JsonNode.vue'
import { copyToClipboard } from '@/utils/clipboard'

const props = withDefaults(
  defineProps<{
    show: boolean
    title: string
    content: unknown
    collapseRule?: CollapseRule
  }>(),
  { collapseRule: 'none' },
)

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void
}>()

const parsed = computed(() => {
  if (props.content == null) return null
  if (typeof props.content === 'string') {
    try {
      return JSON.parse(props.content)
    } catch {
      return props.content
    }
  }
  return props.content
})

const isJson = computed(() => {
  return parsed.value !== null && typeof parsed.value === 'object'
})

const message = useMessage()

/** 复制成功后的临时反馈标志，2 秒后自动复位（与 ChunksViewer 行为一致）。 */
const copied = ref(false)

/**
 * 待复制的文本。
 *
 * JSON 内容复制为缩进两格的格式化文本（与查看器展示形态一致，便于粘贴阅读）；
 * 非 JSON 内容按原文复制。
 */
const copyText = computed(() => {
  if (parsed.value == null) return ''
  if (isJson.value) {
    try {
      return JSON.stringify(parsed.value, null, 2)
    } catch {
      return String(parsed.value)
    }
  }
  return String(parsed.value)
})

/** 复制当前查看的内容到剪贴板。 */
async function handleCopy() {
  const ok = await copyToClipboard(copyText.value)
  if (ok) {
    message.success('已复制到剪贴板')
    copied.value = true
    setTimeout(() => (copied.value = false), 2000)
  } else {
    message.error('复制失败，请手动选择文本复制')
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    :title="title"
    :style="{ maxWidth: '720px', width: '90vw' }"
    closable
    :mask-closable="true"
    @update:show="(val: boolean) => emit('update:show', val)"
  >
    <!-- 标题栏右侧：一键复制当前内容（与 ChunksViewer 的分块复制保持一致的交互与样式） -->
    <template #header-extra>
      <n-tooltip :show="copied" placement="bottom" :duration="0">
        <template #trigger>
          <button
            class="copy-btn"
            type="button"
            :aria-label="`复制${title}内容`"
            @click="handleCopy"
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor"
              stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
              <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
            </svg>
            <span class="copy-btn-label">{{ copied ? '已复制' : '复制' }}</span>
          </button>
        </template>
        {{ copied ? '已复制到剪贴板' : `复制${title}的完整内容` }}
      </n-tooltip>
    </template>

    <n-scrollbar style="max-height: 65vh">
      <!-- JSON 树形结构（可折叠） -->
      <div v-if="isJson" class="json-tree-container">
        <JsonNode :value="parsed" :depth="0" :collapse-rule="collapseRule" />
      </div>
      <!-- 非 JSON 原样展示 -->
      <pre v-else class="json-viewer-pre">{{ parsed }}</pre>
    </n-scrollbar>
  </n-modal>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

/* 复制按钮：与 ChunksViewer 的分块复制按钮保持一致的视觉 */
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

.json-tree-container {
  padding: $space-md;
  background: $bg;
  border: 1px solid $border;
  border-radius: $radius;
  overflow-x: auto;
}

.json-viewer-pre {
  margin: 0;
  padding: $space-md;
  background: $bg;
  border: 1px solid $border;
  border-radius: $radius;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.6;
  color: $text-primary;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
