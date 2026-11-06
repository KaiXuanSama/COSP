<script setup lang="ts">
/** 两张卡片持续挂载，统一协调前景、焦点与稳定的露出区域。 */
import { ref } from 'vue'
import LayeredPanel from './LayeredPanel.vue'
import { useStackedLayers } from './useStackedLayers'
import type { LayerChange, StackedSide } from './layers'

const props = withDefaults(defineProps<{
  sourceTitle?: string
  resultTitle?: string
  initialSide?: StackedSide
  hoverSwitch?: boolean
  ariaLabel?: string
}>(), {
  sourceTitle: '原始文本',
  resultTitle: '转换后文本',
  initialSide: 'source',
  hoverSwitch: true,
  ariaLabel: '双文本对比',
})

const emit = defineEmits<{
  (event: 'change', value: LayerChange): void
}>()

defineSlots<{
  source(props: { foreground: boolean }): any
  result(props: { foreground: boolean }): any
  'source-title'?(props: { foreground: boolean }): any
  'result-title'?(props: { foreground: boolean }): any
  'source-actions'?(props: { foreground: boolean }): any
  'result-actions'?(props: { foreground: boolean }): any
  'source-notice'?(props: { foreground: boolean }): any
  'result-notice'?(props: { foreground: boolean }): any
}>()

const root = ref<HTMLElement | null>(null)
const sourcePanel = ref<InstanceType<typeof LayeredPanel> | null>(null)
const resultPanel = ref<InstanceType<typeof LayeredPanel> | null>(null)
const layers = useStackedLayers({ initialSide: props.initialSide, onChange: (value) => emit('change', value) })
const { activeSide, lockedSide } = layers

function activate(side: StackedSide) {
  layers.activate(side)
  // 主动切换才移动焦点；hover 从不触碰当前文本的焦点或选区。
  const panel = side === 'source' ? sourcePanel.value : resultPanel.value
  panel?.focusTitle()
}

function hover(side: StackedSide, event: PointerEvent) {
  if (!props.hoverSwitch || event.pointerType === 'touch' || event.buttons !== 0) return
  layers.hover(side)
}

function sideOf(target: EventTarget | null): StackedSide | null {
  const container = root.value
  if (!container || !(target instanceof Element) || !container.contains(target)) return null
  const owner = target.closest<HTMLElement>('[data-stacked-side]')
  // 内嵌另一组叠放组件时，内部的 side 不作为外部卡片的身份。
  const layer = [...container.children].find((child) => child.contains(target)) as HTMLElement | undefined
  const side = layer?.dataset.stackedSide ?? owner?.dataset.stackedSide
  return side === 'source' || side === 'result' ? side : null
}

function onFocusOut(side: StackedSide, event: FocusEvent) {
  const nextSide = sideOf(event.relatedTarget)
  if (nextSide === side) return
  if (nextSide) {
    layers.focus(nextSide)
    return
  }
  if (event.relatedTarget !== null) {
    layers.blur(side)
    return
  }
  // relatedTarget 可能为空；等浏览器完成焦点迁移，再核对真实焦点。
  // reducer 按侧清理，晚到的旧 blur 不会解除另一侧刚获得的锁。
  queueMicrotask(() => {
    const focusedSide = sideOf(root.value?.ownerDocument.activeElement ?? null)
    if (focusedSide) layers.focus(focusedSide)
    else layers.blur(side)
  })
}

defineExpose({ activeSide, lockedSide, activate })
</script>

<template>
  <div
    ref="root"
    class="stacked-diff"
    role="group"
    :aria-label="ariaLabel"
    :data-active-side="activeSide"
    :data-locked-side="lockedSide || undefined"
  >
    <div
      class="stacked-diff__layer stacked-diff__layer--source"
      :class="{ 'stacked-diff__layer--front': activeSide === 'source' }"
      data-stacked-side="source"
    >
      <LayeredPanel
        ref="sourcePanel"
        :title="sourceTitle"
        :foreground="activeSide === 'source'"
        @activate="activate('source')"
        @focusin="layers.focus('source')"
        @focusout="onFocusOut('source', $event)"
        @compositionstart="layers.compositionStart('source')"
        @compositionend="layers.compositionEnd('source')"
      >
        <template v-if="$slots['source-title']" #title="scope"><slot name="source-title" v-bind="scope" /></template>
        <template v-if="$slots['source-actions']" #actions="scope"><slot name="source-actions" v-bind="scope" /></template>
        <template #default="scope"><slot name="source" v-bind="scope" /></template>
        <template v-if="$slots['source-notice']" #notice="scope"><slot name="source-notice" v-bind="scope" /></template>
      </LayeredPanel>
    </div>
    <div
      class="stacked-diff__layer stacked-diff__layer--result"
      :class="{ 'stacked-diff__layer--front': activeSide === 'result' }"
      data-stacked-side="result"
    >
      <LayeredPanel
        ref="resultPanel"
        :title="resultTitle"
        :foreground="activeSide === 'result'"
        @activate="activate('result')"
        @focusin="layers.focus('result')"
        @focusout="onFocusOut('result', $event)"
        @compositionstart="layers.compositionStart('result')"
        @compositionend="layers.compositionEnd('result')"
      >
        <template v-if="$slots['result-title']" #title="scope"><slot name="result-title" v-bind="scope" /></template>
        <template v-if="$slots['result-actions']" #actions="scope"><slot name="result-actions" v-bind="scope" /></template>
        <template #default="scope"><slot name="result" v-bind="scope" /></template>
        <template v-if="$slots['result-notice']" #notice="scope"><slot name="result-notice" v-bind="scope" /></template>
      </LayeredPanel>
    </div>
    <!-- 固定左右边缘的命中区域不参与浮沉缩放，交换层级不会在静止指针下反复互换。 -->
    <button
      v-show="activeSide !== 'source'"
      class="stacked-diff__peek stacked-diff__peek--source"
      type="button"
      tabindex="-1"
      :aria-label="sourceTitle"
      :title="sourceTitle"
      @pointerenter="hover('source', $event)"
      @click="activate('source')"
    >{{ sourceTitle }}</button>
    <button
      v-show="activeSide !== 'result'"
      class="stacked-diff__peek stacked-diff__peek--result"
      type="button"
      tabindex="-1"
      :aria-label="resultTitle"
      :title="resultTitle"
      @pointerenter="hover('result', $event)"
      @click="activate('result')"
    >{{ resultTitle }}</button>
  </div>
</template>

<style scoped lang="scss">
.stacked-diff {
  --stacked-diff-effective-offset: clamp(28px, var(--stacked-diff-offset, 12%), 25%);
  position: relative;
  isolation: isolate;
  box-sizing: border-box;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: var(--stacked-diff-min-height, 22rem);
}

.stacked-diff__layer {
  position: absolute;
  top: 0;
  bottom: 0;
  z-index: 1;
  width: calc(100% - var(--stacked-diff-effective-offset));
  min-width: 0;
}

.stacked-diff__layer--source {
  --stacked-diff-origin: left top;
  left: 0;
}

.stacked-diff__layer--result {
  --stacked-diff-origin: right top;
  right: 0;
}

.stacked-diff__layer--front {
  z-index: 2;
}

.stacked-diff__peek {
  position: absolute;
  top: 0;
  bottom: 0;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  width: var(--stacked-diff-effective-offset);
  padding: 12px 4px;
  overflow: hidden;
  border: 0;
  border-radius: var(--stacked-diff-radius, 14px);
  background: transparent;
  color: var(--stacked-diff-muted, #71717a);
  font: inherit;
  font-size: 12px;
  letter-spacing: 0.12em;
  writing-mode: vertical-rl;
  cursor: pointer;
  touch-action: pan-y;
}

.stacked-diff__peek--source {
  left: 0;
}

.stacked-diff__peek--result {
  right: 0;
}

.stacked-diff__peek:focus-visible {
  outline: 2px solid var(--stacked-diff-accent, #596b8a);
  outline-offset: -3px;
}
</style>
