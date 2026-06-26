<script setup lang="ts">
/**
 * JsonViewer — 格式化 JSON 查看器模态框。
 *
 * 接收原始 JSON 字符串或对象，在模态框中以可折叠的树形结构展示。
 */
import { computed } from 'vue'
import { NModal, NScrollbar } from 'naive-ui'
import JsonNode from './JsonNode.vue'

const props = withDefaults(
  defineProps<{
    show: boolean
    title: string
    content: unknown
    defaultCollapsedKeys?: string[]
  }>(),
  { defaultCollapsedKeys: () => [] },
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
    <n-scrollbar style="max-height: 65vh">
      <!-- JSON 树形结构（可折叠） -->
      <div v-if="isJson" class="json-tree-container">
        <JsonNode :value="parsed" :depth="0" :default-collapsed-keys="defaultCollapsedKeys" />
      </div>
      <!-- 非 JSON 原样展示 -->
      <pre v-else class="json-viewer-pre">{{ parsed }}</pre>
    </n-scrollbar>
  </n-modal>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

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
