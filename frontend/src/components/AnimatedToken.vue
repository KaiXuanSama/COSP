<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  from: number
  to: number
  active: boolean
  duration?: number
}>(), {
  from: 0,
  to: 0,
  active: true,
  duration: 800,
})

const display = ref(formatTokenValue(props.from))
const lastDisplayedValue = ref(props.from)
let frameId = 0

watch(
  () => [props.active, props.to] as const,
  ([active, to]) => {
    cancelAnimationFrame(frameId)

    if (!active) {
      display.value = formatTokenValue(to)
      return
    }

    const from = lastDisplayedValue.value
    if (from === to) return

    const start = performance.now()

    function step(now: number) {
      const progress = Math.min((now - start) / props.duration, 1)
      const eased = 1 - Math.pow(1 - progress, 3)
      const current = Math.round(from + (to - from) * eased)
      display.value = formatTokenValue(current)

      if (progress < 1) {
        frameId = requestAnimationFrame(step)
      } else {
        lastDisplayedValue.value = to
      }
    }

    frameId = requestAnimationFrame(step)
  },
  { immediate: true },
)

onUnmounted(() => {
  cancelAnimationFrame(frameId)
})

function formatTokenValue(value: number): string {
  if (value >= 100_000) {
    return (value / 1000).toFixed(2) + 'k'
  }
  return value.toLocaleString('zh-CN')
}
</script>

<template>
  <span class="animated-token">{{ display }}</span>
</template>

<style scoped>
.animated-token {
  font-variant-numeric: tabular-nums;
}
</style>