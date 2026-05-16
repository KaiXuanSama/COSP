<script setup lang="ts">
/**
 * 热力图组件使用说明
 *
 * 1. 组件职责：
 *    - 负责热力图的最终渲染、tooltip 展示和容器宽度测量。
 *    - 不负责请求数据，也不负责业务模式切换按钮；这些由页面层处理。
 *
 * 2. 最小接入方式：
 *    - 传入 data 和 modes。
 *    - 通过 activeMode 指定当前模式。
 *    - 页面层在模式切换时只更新 activeMode，组件内部会负责开场动画与列翻转队列。
 *
 * 3. 多数据组模式：
 *    - 若各模式共用同一份原始数据，只传 data 即可。
 *    - 若某些模式需要独立数据集，可通过 datasets[mode.key] 单独覆盖。
 *
 * 4. 模块结构：
 *    - ActivityHeatmap.vue: 组件壳，负责模板、tooltip 和尺寸测量。
 *    - useHeatmapData.ts: 日期归一化、周列切片、月份标签、数值映射。
 *    - useHeatmapTimeline.ts: 开场 reveal、列翻转排队、动画 fallback。
 *
 * 5. 维护约定：
 *    - 不要把页面业务请求逻辑重新放回组件内部。
 *    - 如果要调整动画行为，优先修改时间线 composable，而不是在模板层叠加状态。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { ActivityHeatmapProps, HeatmapRecord, HeatmapTooltipPayload, NormalizedHeatmapDay } from './heatmap'
import { useHeatmapData } from './useHeatmapData'
import { useHeatmapTimeline } from './useHeatmapTimeline'

const DEFAULT_DAY_LABELS_WIDTH = 72

const props = withDefaults(defineProps<ActivityHeatmapProps>(), {
  activeMode: '',
  loading: false,
  failed: false,
  emptyText: '暂无数据',
  loadingText: '加载中',
  errorText: '加载失败',
  dayLabels: () => ['日', '一', '二', '三', '四', '五', '六'],
  dateKey: 'usageDate',
  cellSize: 16,
  gap: 4,
  initialRevealDelay: 900,
  rippleDelay: 25,
  rippleDuration: 400,
  flipDelay: 50,
  flipDuration: 300,
})

const heatmapRef = ref<HTMLElement | null>(null)
const dayLabelsRef = ref<HTMLElement | null>(null)
const containerWidth = ref(0)
const dayLabelsWidth = ref(DEFAULT_DAY_LABELS_WIDTH)
const tooltip = ref({ visible: false, left: 0, top: 0, text: '' })

let resizeObserver: ResizeObserver | null = null
let observedDayLabelsEl: HTMLElement | null = null

// 样式变量只保留给模板真正需要的尺寸与动画节奏。
const rootStyle = computed(() => ({
  '--heatmap-cell-size': `${props.cellSize}px`,
  '--heatmap-gap': `${props.gap}px`,
  '--heatmap-ripple-duration': `${props.rippleDuration}ms`,
}))

// 数据 composable 负责把任意输入数据整理成统一时间轴与可见列。
const {
  normalizedDays,
  structuralSignature,
  resolvedActiveMode,
  visibleWeeks,
  visibleStateSignature,
  monthSegments,
  modeMax,
  getModeConfig,
  getModeValue,
  getLevel,
  getWeekAnchor,
} = useHeatmapData(props, containerWidth, dayLabelsWidth)

// 时间线 composable 只关心 reveal 与 flip，不关心具体业务值长什么样。
const { revealState, setColumnRef, getColumnMode } = useHeatmapTimeline({
  props,
  structuralSignature,
  visibleStateSignature,
  resolvedActiveMode,
  normalizedDayCount: computed(() => normalizedDays.value.length),
  visibleWeeks,
  containerWidth,
  hideTooltip,
})

watch(() => visibleWeeks.value.length, async () => {
  // 星期标签列只有在网格真正出现后才会挂载，因此需要在可见列变化后重新测量一次。
  await nextTick()
  syncDayLabelsObserver()
  updateContainerWidth()
}, { flush: 'post' })

onMounted(() => {
  updateContainerWidth()

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      updateContainerWidth()
      hideTooltip()
    })

    if (heatmapRef.value) resizeObserver.observe(heatmapRef.value)
    syncDayLabelsObserver()
  } else {
    window.addEventListener('resize', updateContainerWidth)
  }
})

onUnmounted(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
  } else {
    window.removeEventListener('resize', updateContainerWidth)
  }
})

function updateContainerWidth() {
  // 左侧星期标签区会直接影响可见列数，所以和容器宽度一起测量。
  containerWidth.value = heatmapRef.value?.clientWidth ?? 0
  dayLabelsWidth.value = dayLabelsRef.value?.clientWidth ?? DEFAULT_DAY_LABELS_WIDTH
}

function syncDayLabelsObserver() {
  if (!resizeObserver) return

  if (observedDayLabelsEl && observedDayLabelsEl !== dayLabelsRef.value) {
    resizeObserver.unobserve(observedDayLabelsEl)
    observedDayLabelsEl = null
  }

  if (dayLabelsRef.value && observedDayLabelsEl !== dayLabelsRef.value) {
    resizeObserver.observe(dayLabelsRef.value)
    observedDayLabelsEl = dayLabelsRef.value
  }
}

function getLevelColor(modeKey: string, level: number) {
  const mode = getModeConfig(modeKey)
  if (!mode) return 'transparent'
  return mode.colors[level] ?? mode.colors[mode.colors.length - 1]
}

function getCellClasses(day: NormalizedHeatmapDay | null) {
  return [
    'activity-heatmap__cell',
    day ? null : 'activity-heatmap__cell--empty',
    revealState.value === 'done' ? 'activity-heatmap__cell--visible' : revealState.value === 'animating' ? 'activity-heatmap__cell--ripple' : null,
  ]
}

function getCellStyle(day: NormalizedHeatmapDay | null, columnIndex: number, rowIndex: number) {
  if (!day) {
    // 空单元格只参与 reveal 节奏，不参与数值着色。
    return revealState.value === 'animating'
      ? { animationDelay: `${(columnIndex + (6 - rowIndex)) * props.rippleDelay}ms` }
      : undefined
  }

  const mode = getColumnMode(columnIndex)
  const value = getModeValue(day.recordsByMode[mode] ?? null, mode)
  const level = getLevel(value, modeMax.value[mode] ?? 0)
  const style: Record<string, string> = {
    backgroundColor: getLevelColor(mode, level),
  }

  if (revealState.value === 'animating') {
    // reveal 阶段按“列 + 行”形成斜向波纹，这样首屏入场更接近旧版实现。
    style.animationDelay = `${(columnIndex + (6 - rowIndex)) * props.rippleDelay}ms`
  }

  return style
}

function getColumnKey(week: Array<NormalizedHeatmapDay | null>, columnIndex: number) {
  return `${getWeekAnchor(week)}-${columnIndex}`
}

function formatTooltip(day: NormalizedHeatmapDay, columnIndex: number) {
  // tooltip 总是以“当前列正在显示的模式”为准，而不是外部最新 activeMode。
  const modeKey = getColumnMode(columnIndex)
  const mode = getModeConfig(modeKey)
  const item = day.recordsByMode[modeKey] ?? null
  const value = getModeValue(item, modeKey)

  if (props.tooltipFormatter) {
    return props.tooltipFormatter({
      item,
      date: day.date,
      value,
      mode,
    } as HeatmapTooltipPayload<any>)
  }

  return `${day.date} · ${value.toLocaleString('zh-CN')} ${mode.unit}`
}

function showTooltip(event: MouseEvent, day: NormalizedHeatmapDay, columnIndex: number) {
  const target = event.currentTarget as HTMLElement | null
  const root = heatmapRef.value
  if (!target || !root) return

  // tooltip 使用热力图容器内坐标，避免页面滚动或卡片偏移时定位漂移。
  const rect = target.getBoundingClientRect()
  const rootRect = root.getBoundingClientRect()

  tooltip.value = {
    visible: true,
    left: rect.left - rootRect.left + rect.width / 2,
    top: rect.top - rootRect.top,
    text: formatTooltip(day, columnIndex),
  }
}

function hideTooltip() {
  tooltip.value.visible = false
}
</script>

<template>
  <div ref="heatmapRef" class="activity-heatmap" :style="rootStyle" @mouseleave="hideTooltip">
    <div v-if="loading && !normalizedDays.length" class="activity-heatmap__empty">{{ loadingText }}</div>
    <div v-else-if="failed && !normalizedDays.length" class="activity-heatmap__empty">{{ errorText }}</div>
    <div v-else-if="!visibleWeeks.length" class="activity-heatmap__empty">{{ emptyText }}</div>
    <div v-else class="activity-heatmap__scroll">
      <div ref="dayLabelsRef" class="activity-heatmap__days">
        <span v-for="day in dayLabels" :key="day" class="activity-heatmap__day-label">{{ day }}</span>
      </div>

      <div class="activity-heatmap__grid-area">
        <div class="activity-heatmap__months">
          <span v-for="segment in monthSegments" :key="segment.key" class="activity-heatmap__month-label"
            :style="{ width: `${segment.width}px` }">
            {{ segment.label }}
          </span>
        </div>

        <div class="activity-heatmap__grid">
          <div v-for="(week, columnIndex) in visibleWeeks" :key="getColumnKey(week, columnIndex)"
            :ref="(element) => setColumnRef(element, columnIndex)" class="activity-heatmap__col">
            <div v-for="(day, rowIndex) in week" :key="day?.date ?? `empty-${columnIndex}-${rowIndex}`"
              :class="getCellClasses(day)" :style="getCellStyle(day, columnIndex, rowIndex)"
              @mouseenter="day && showTooltip($event, day, columnIndex)" @mouseleave="hideTooltip" />
          </div>
        </div>
      </div>
    </div>

    <div v-if="tooltip.visible" class="activity-heatmap__tooltip"
      :style="{ left: `${tooltip.left}px`, top: `${tooltip.top}px` }">
      {{ tooltip.text }}
    </div>
  </div>
</template>

<style scoped>
.activity-heatmap {
  position: relative;
  min-height: 188px;
}

.activity-heatmap__empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 160px;
  color: var(--heatmap-text-muted, #8f8a83);
  font-family: var(--heatmap-font-mono, 'DM Mono', monospace);
  font-size: 11px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.activity-heatmap__scroll {
  display: flex;
  overflow: hidden;
  padding-bottom: 12px;
}

.activity-heatmap__days {
  display: flex;
  flex-direction: column;
  gap: var(--heatmap-gap);
  flex-shrink: 0;
  padding-top: calc(var(--heatmap-cell-size) + var(--heatmap-gap));
  padding-right: 8px;
}

.activity-heatmap__day-label,
.activity-heatmap__month-label,
.activity-heatmap__tooltip {
  font-family: var(--heatmap-font-mono, 'DM Mono', monospace);
}

.activity-heatmap__day-label {
  height: var(--heatmap-cell-size);
  line-height: var(--heatmap-cell-size);
  color: var(--heatmap-text-muted, #8f8a83);
  font-size: 11px;
  font-weight: 500;
}

.activity-heatmap__grid-area {
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}

.activity-heatmap__months {
  display: flex;
  height: var(--heatmap-cell-size);
  margin-bottom: 6px;
}

.activity-heatmap__month-label {
  flex-shrink: 0;
  color: var(--heatmap-text-muted, #8f8a83);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.08em;
  line-height: var(--heatmap-cell-size);
  text-align: left;
  white-space: nowrap;
}

.activity-heatmap__grid {
  display: flex;
  gap: var(--heatmap-gap);
  flex-shrink: 0;
  perspective: 800px;
}

.activity-heatmap__col {
  display: flex;
  flex-direction: column;
  gap: var(--heatmap-gap);
  flex-shrink: 0;
  position: relative;
  transform-origin: center center;
  transform-style: preserve-3d;
  will-change: transform;
}

.activity-heatmap__cell {
  width: var(--heatmap-cell-size);
  height: var(--heatmap-cell-size);
  flex-shrink: 0;
  border-radius: 4px;
  opacity: 0;
  transform: scale(0.3);
  pointer-events: none;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.activity-heatmap__cell:hover {
  position: relative;
  z-index: 1;
  transform: scale(1.25);
  box-shadow: 0 0 0 1px rgba(26, 25, 23, 0.2);
}

.activity-heatmap__cell--visible {
  opacity: 1;
  transform: scale(1);
  pointer-events: auto;
}

.activity-heatmap__cell--ripple {
  animation: activity-heatmap-ripple var(--heatmap-ripple-duration) ease-out forwards;
}

.activity-heatmap__cell--empty {
  background: transparent !important;
  pointer-events: none;
}

.activity-heatmap__tooltip {
  position: absolute;
  z-index: 10;
  padding: 4px 10px;
  border-radius: 4px;
  background: var(--heatmap-tooltip-bg, #1a1917);
  color: var(--heatmap-tooltip-text, #f5f3ee);
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.04em;
  white-space: nowrap;
  pointer-events: none;
  box-shadow: 0 4px 12px rgba(26, 25, 23, 0.2);
  transform: translate(-50%, calc(-100% - 8px));
}

@keyframes activity-heatmap-col-flip {
  0% {
    transform: rotateY(0deg) scale(1);
  }

  50% {
    transform: rotateY(90deg) scale(0.96);
  }

  100% {
    transform: rotateY(0deg) scale(1);
  }
}

@keyframes activity-heatmap-ripple {
  0% {
    opacity: 0;
    transform: scale(0.3);
  }

  60% {
    opacity: 1;
    transform: scale(1.12);
  }

  100% {
    opacity: 1;
    transform: scale(1);
  }
}
</style>