<script setup lang="ts">
/** 单个内容容器：只呈现前后景状态，层级归组合组件统一决定。 */
import { ref, useId } from 'vue'

defineProps<{
  title: string
  foreground: boolean
}>()

const emit = defineEmits<{
  (event: 'activate'): void
  (event: 'focusin', value: FocusEvent): void
  (event: 'focusout', value: FocusEvent): void
  (event: 'compositionstart'): void
  (event: 'compositionend'): void
}>()

defineSlots<{
  default(props: { foreground: boolean }): any
  title?(props: { foreground: boolean }): any
  actions?(props: { foreground: boolean }): any
  notice?(props: { foreground: boolean }): any
}>()

const id = useId()
const titleId = `${id}-title`
const contentId = `${id}-content`
const titleButton = ref<HTMLButtonElement | null>(null)

function focusTitle() {
  titleButton.value?.focus({ preventScroll: true })
}

defineExpose({ focusTitle })
</script>

<template>
  <section
    class="layered-panel"
    :class="foreground ? 'layered-panel--front' : 'layered-panel--back'"
    :aria-labelledby="titleId"
    @focusin="emit('focusin', $event)"
    @focusout="emit('focusout', $event)"
    @compositionstart="emit('compositionstart')"
    @compositionend="emit('compositionend')"
  >
    <header class="layered-panel__header">
      <button
        :id="titleId"
        ref="titleButton"
        class="layered-panel__title"
        type="button"
        :aria-label="title"
        :aria-pressed="foreground"
        :aria-controls="contentId"
        @click="emit('activate')"
      >
        <svg class="layered-panel__icon" viewBox="0 0 20 20" fill="none" aria-hidden="true">
          <rect x="3" y="3" width="11" height="11" rx="2" />
          <path d="M7 16.5h7.5a2 2 0 0 0 2-2V7" />
        </svg>
        <span class="layered-panel__title-text"><slot name="title" :foreground="foreground">{{ title }}</slot></span>
      </button>
      <div v-if="$slots.actions" class="layered-panel__actions">
        <slot name="actions" :foreground="foreground" />
      </div>
    </header>
    <!-- 只读内容也可键盘进入；是否锁层级由组合组件的 editableSide 决定。 -->
    <div :id="contentId" class="layered-panel__content" tabindex="0" :aria-label="title">
      <slot :foreground="foreground" />
    </div>
    <div v-if="$slots.notice" class="layered-panel__notice">
      <slot name="notice" :foreground="foreground" />
    </div>
  </section>
</template>

<style scoped lang="scss">
.layered-panel {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--stacked-diff-border, rgb(99 103 114 / 20%));
  border-radius: var(--stacked-diff-radius, 14px);
  color: var(--stacked-diff-color, #3f3f46);
  font-family: inherit;
  background: rgb(var(--stacked-diff-surface-rgb, 255 255 255) / var(--panel-alpha));
  transform-origin: var(--stacked-diff-origin, center top);
  transition:
    background-color var(--stacked-diff-duration, 260ms) var(--stacked-diff-easing, ease),
    box-shadow var(--stacked-diff-duration, 260ms) var(--stacked-diff-easing, ease),
    filter var(--stacked-diff-duration, 260ms) var(--stacked-diff-easing, ease),
    transform var(--stacked-diff-duration, 260ms) var(--stacked-diff-easing, ease);
}

.layered-panel--front {
  --panel-alpha: var(--stacked-diff-front-alpha, 0.7);
  filter: blur(0);
  transform: scale(1);
  box-shadow: var(--stacked-diff-front-shadow, 0 8px 24px rgb(40 45 55 / 12%));
}

.layered-panel--back {
  --panel-alpha: var(--stacked-diff-back-alpha, 0.88);
  filter: blur(var(--stacked-diff-back-blur, 2px));
  transform: scale(var(--stacked-diff-back-scale, 0.985));
  box-shadow: var(--stacked-diff-back-shadow, 0 2px 8px rgb(40 45 55 / 5%));
}

.layered-panel__header {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  min-width: 0;
  min-height: 44px;
  padding: 6px 12px;
  border-bottom: 1px solid var(--stacked-diff-border, rgb(99 103 114 / 20%));
}

.layered-panel__title {
  display: flex;
  align-items: center;
  flex: 1 1 auto;
  gap: 7px;
  min-width: 0;
  padding: 4px 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 13px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
}

.layered-panel__title-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.layered-panel__icon {
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
  color: var(--stacked-diff-accent, #596b8a);
  stroke: currentColor;
  stroke-width: 1.4;
}

.layered-panel__actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: 8px;
}

.layered-panel__content {
  display: flex;
  flex: 1 1 0;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

.layered-panel__notice {
  flex: 0 0 auto;
  padding: 7px 12px;
  border-top: 1px solid var(--stacked-diff-border, rgb(99 103 114 / 20%));
  color: var(--stacked-diff-muted, #71717a);
  font-size: 12px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.layered-panel__title:focus-visible,
.layered-panel__content:focus-visible {
  outline: 2px solid var(--stacked-diff-accent, #596b8a);
  outline-offset: -3px;
}

@media (prefers-reduced-motion: reduce) {
  .layered-panel {
    transform: none;
    transition: none;
  }
}
</style>
