<script setup lang="ts">
/**
 * JsonViewer — 格式化 JSON 查看器模态框。
 *
 * 接收原始 JSON 字符串或对象，在模态框中以格式化等宽文本展示。
 */
import { computed } from 'vue'
import { NModal } from 'naive-ui'

const props = defineProps<{
  show: boolean
  title: string
  content: unknown
}>()

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void
}>()

const formatted = computed(() => {
  if (props.content == null) return ''
  if (typeof props.content === 'string') {
    try {
      return JSON.stringify(JSON.parse(props.content), null, 2)
    } catch {
      return props.content
    }
  }
  try {
    return JSON.stringify(props.content, null, 2)
  } catch {
    return String(props.content)
  }
})
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
    <pre class="json-viewer-pre">{{ formatted }}</pre>
  </n-modal>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

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
  max-height: 65vh;
  overflow-y: auto;
}
</style>
