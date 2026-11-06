<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import type { DiffDisplayLine, LineScrollPort } from './lines'
import { createDisplayScrollPort } from './scrollPorts'

const props = withDefaults(defineProps<{
  lines: readonly DiffDisplayLine[]
  ariaLabel?: string
}>(), { ariaLabel: '差异文本' })
const emit = defineEmits<{ (event: 'port', value: LineScrollPort | null): void }>()
const element = ref<HTMLElement | null>(null)
const scrollPort = shallowRef<LineScrollPort | null>(null)
onMounted(() => {
  if (!element.value) return
  scrollPort.value = createDisplayScrollPort(element.value)
  emit('port', scrollPort.value)
})
watch(() => props.lines, async () => {
  await nextTick()
  scrollPort.value?.refresh()
})
onBeforeUnmount(() => {
  scrollPort.value?.dispose()
  emit('port', null)
})
defineExpose({ scrollPort })
</script>

<template>
  <div ref="element" class="diff-text-view" role="region" :aria-label="ariaLabel" tabindex="0">
    <div
      v-for="line in lines"
      :key="line.id"
      class="diff-text-view__line"
      :class="`diff-text-view__line--${line.status}`"
      :data-line-number="line.lineNumber ?? undefined"
      :data-source-line="line.sourceLineNumber ?? undefined"
    >
      <span class="diff-text-view__number" aria-hidden="true">{{ line.lineNumber ?? '−' }}</span>
      <span class="diff-text-view__text">{{ line.text || '\u200b' }}</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.diff-text-view {
  box-sizing: border-box;
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
  width: 100%;
  height: 100%;
  overflow: auto;
  padding: var(--stacked-diff-text-padding, 16px);
  background: transparent;
  color: inherit;
  font-family: var(--stacked-diff-text-font, 'Cascadia Code', Consolas, monospace);
  font-size: var(--stacked-diff-text-size, 13px);
  line-height: var(--stacked-diff-text-line-height, 1.75);
  tab-size: 2;
  scrollbar-width: thin;
}
.diff-text-view__line {
  display: flex;
  position: relative;
  min-height: 1lh;
}
.diff-text-view__number {
  flex: 0 0 3ch;
  padding-right: 10px;
  color: var(--stacked-diff-muted, #71717a);
  text-align: right;
  user-select: none;
  font-size: 11px;
}
.diff-text-view__text {
  min-width: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.diff-text-view__line--changed,
.diff-text-view__line--added {
  color: var(--stacked-diff-accent, #596b8a);
}
.diff-text-view__line--deleted {
  color: var(--stacked-diff-deleted, #b84a4a);
  text-decoration: line-through;
}
.diff-text-view:focus-visible {
  outline: 2px solid var(--stacked-diff-accent, #596b8a);
  outline-offset: -3px;
}
</style>
