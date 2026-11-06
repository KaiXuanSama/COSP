<script setup lang="ts">
import { computed, onScopeDispose, ref, shallowRef, watch } from 'vue'
import type { MeasuredTextLine } from './lines'
import type { TextareaScrollPort } from './scrollPorts'

const props = defineProps<{
  port: TextareaScrollPort | null
  /** 真实草稿变化后，连同程序化赋值一起刷新行号。 */
  value: string
}>()

const lines = shallowRef<readonly MeasuredTextLine[]>([])
const scrollTop = ref(0)
const digits = computed(() => Math.max(2, String(props.value.split('\n').length).length))
let unsubscribe = () => {}

function update() {
  lines.value = props.port?.measureLines() ?? []
  scrollTop.value = props.port?.scrollTop() ?? 0
}

watch(() => props.port, (port) => {
  unsubscribe()
  unsubscribe = port?.onScroll(update) ?? (() => {})
  update()
}, { immediate: true, flush: 'post' })

watch(() => props.value, () => {
  props.port?.refresh()
  update()
}, { flush: 'post' })
onScopeDispose(() => unsubscribe())
</script>

<template>
  <div class="textarea-line-numbers" :style="{ '--textarea-line-digits': digits }" aria-hidden="true">
    <div class="textarea-line-numbers__track" :style="{ transform: `translateY(${-scrollTop}px)` }">
      <span
        v-for="line in lines"
        :key="line.line"
        class="textarea-line-numbers__line"
        :data-source-line="line.line"
        :style="{ top: `${line.top}px`, height: `${line.height}px` }"
      >{{ line.line }}</span>
    </div>
  </div>
</template>

<style scoped lang="scss">
.textarea-line-numbers {
  position: relative;
  box-sizing: border-box;
  flex: 0 0 calc(var(--textarea-line-digits, 2) * 1ch + var(--stacked-diff-text-padding, 16px) + 10px);
  min-height: 0;
  height: 100%;
  overflow: hidden;
  color: var(--stacked-diff-muted, #71717a);
  font-family: var(--stacked-diff-text-font, 'Cascadia Code', Consolas, monospace);
  font-size: 11px;
  line-height: inherit;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
  user-select: none;
}

.textarea-line-numbers__track {
  position: relative;
  width: 100%;
  height: 100%;
}

.textarea-line-numbers__line {
  position: absolute;
  inset-inline: 0;
  box-sizing: border-box;
  padding-inline: var(--stacked-diff-text-padding, 16px) 10px;
  white-space: nowrap;
  word-break: normal;
  overflow-wrap: normal;
  text-align: right;
}
</style>
