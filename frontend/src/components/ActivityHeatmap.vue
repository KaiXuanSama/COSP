<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch, type ComponentPublicInstance } from 'vue'
import type { HeatmapModeConfig, HeatmapRecord, HeatmapTooltipPayload } from '@/components/heatmap'

interface NormalizedHeatmapDay {
  date: string
  recordsByMode: Record<string, HeatmapRecord | null>
}

interface MonthSegment {
  key: string
  label: string
  width: number
}

const props = withDefaults(defineProps<{
  data: HeatmapRecord[]
  datasets?: Record<string, HeatmapRecord[]>
  modes: HeatmapModeConfig<any>[]
  activeMode?: string
  loading?: boolean
  failed?: boolean
  emptyText?: string
  loadingText?: string
  errorText?: string
  dayLabels?: string[]
  dateKey?: string
  getDate?: (item: HeatmapRecord) => string
  tooltipFormatter?: (payload: HeatmapTooltipPayload<any>) => string
  cellSize?: number
  gap?: number
  initialRevealDelay?: number
  rippleDelay?: number
  rippleDuration?: number
  flipDelay?: number
  flipDuration?: number
}>(), {
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
const containerWidth = ref(0)
const columnModes = ref<string[]>([])
const columnReadyAt = ref<number[]>([])
const columnElements = ref<Array<HTMLElement | null>>([])
const revealState = ref<'pending' | 'animating' | 'done'>('pending')
const tooltip = ref({ visible: false, left: 0, top: 0, text: '' })

let revealTimer: ReturnType<typeof setTimeout> | null = null
let resizeObserver: ResizeObserver | null = null
let revealStartedAt = 0
const transitionTimers = new Set<ReturnType<typeof setTimeout>>()
const columnAnimations = new Map<number, Animation>()

const rootStyle = computed(() => ({
  '--heatmap-cell-size': `${props.cellSize}px`,
  '--heatmap-gap': `${props.gap}px`,
  '--heatmap-ripple-duration': `${props.rippleDuration}ms`,
  '--heatmap-flip-animation-duration': `${props.flipDuration * 2}ms`,
}))

const sourceDataByMode = computed<Record<string, HeatmapRecord[]>>(() => {
  return props.modes.reduce<Record<string, HeatmapRecord[]>>((accumulator, mode) => {
    accumulator[mode.key] = props.datasets?.[mode.key] ?? props.data
    return accumulator
  }, {})
})

const dataMapByMode = computed<Record<string, Map<string, HeatmapRecord>>>(() => {
  return props.modes.reduce<Record<string, Map<string, HeatmapRecord>>>((accumulator, mode) => {
    const map = new Map<string, HeatmapRecord>()
    for (const item of sourceDataByMode.value[mode.key] ?? []) {
      const date = resolveDate(item)
      if (date) map.set(date, item)
    }
    accumulator[mode.key] = map
    return accumulator
  }, {})
})

const allDates = computed<string[]>(() => {
  const dates = new Set<string>()
  for (const mode of props.modes) {
    for (const item of sourceDataByMode.value[mode.key] ?? []) {
      const date = resolveDate(item)
      if (date) dates.add(date)
    }
  }

  return [...dates].sort((left, right) => left.localeCompare(right))
})

const normalizedDays = computed<NormalizedHeatmapDay[]>(() => {
  return allDates.value.map((date) => ({
    date,
    recordsByMode: props.modes.reduce<Record<string, HeatmapRecord | null>>((accumulator, mode) => {
      accumulator[mode.key] = dataMapByMode.value[mode.key]?.get(date) ?? null
      return accumulator
    }, {}),
  }))
})

const resolvedActiveMode = computed(() => {
  if (props.modes.some((mode) => mode.key === props.activeMode)) return props.activeMode
  return props.modes[0]?.key ?? ''
})

const allWeeks = computed<(NormalizedHeatmapDay | null)[][]>(() => {
  if (!normalizedDays.value.length) return []

  const weeks: Array<Array<NormalizedHeatmapDay | null>> = []
  const firstDow = getDayOfWeek(normalizedDays.value[0].date)
  let currentWeek: Array<NormalizedHeatmapDay | null> = Array.from({ length: firstDow }, () => null)

  for (const day of normalizedDays.value) {
    currentWeek.push(day)
    if (currentWeek.length === 7) {
      weeks.push(currentWeek)
      currentWeek = []
    }
  }

  if (currentWeek.length) {
    while (currentWeek.length < 7) currentWeek.push(null)
    weeks.push(currentWeek)
  }

  return weeks
})

const visibleColumnCount = computed(() => {
  if (!allWeeks.value.length) return 0
  if (!containerWidth.value) return allWeeks.value.length

  const availableWidth = Math.max(0, containerWidth.value - 72)
  const fittedColumns = Math.floor((availableWidth + props.gap) / (props.cellSize + props.gap))
  return Math.max(1, Math.min(allWeeks.value.length, fittedColumns))
})

const visibleWeeks = computed(() => {
  if (!visibleColumnCount.value) return []
  return allWeeks.value.slice(Math.max(0, allWeeks.value.length - visibleColumnCount.value))
})

const modeLookup = computed(() => {
  return props.modes.reduce<Record<string, HeatmapModeConfig<any>>>((accumulator, mode) => {
    accumulator[mode.key] = mode
    return accumulator
  }, {})
})

const modeMax = computed<Record<string, number>>(() => {
  const maxValues = props.modes.reduce<Record<string, number>>((accumulator, mode) => {
    accumulator[mode.key] = 0
    return accumulator
  }, {})

  for (const day of normalizedDays.value) {
    for (const mode of props.modes) {
      maxValues[mode.key] = Math.max(maxValues[mode.key], getModeValue(day.recordsByMode[mode.key], mode.key))
    }
  }

  return maxValues
})

const monthSegments = computed<MonthSegment[]>(() => {
  const segments: MonthSegment[] = []
  let lastMonth = -1
  let startCol = 0

  for (let index = 0; index < visibleWeeks.value.length; index += 1) {
    const anchorDate = getWeekAnchor(visibleWeeks.value[index])
    const month = anchorDate ? getMonthNumber(anchorDate) : -1
    if (month !== lastMonth) {
      if (lastMonth !== -1) {
        segments.push({
          key: `${lastMonth}-${startCol}`,
          label: `${lastMonth}月`,
          width: (index - startCol) * (props.cellSize + props.gap) - props.gap,
        })
      }
      lastMonth = month
      startCol = index
    }
  }

  if (lastMonth !== -1) {
    segments.push({
      key: `${lastMonth}-${startCol}`,
      label: `${lastMonth}月`,
      width: (visibleWeeks.value.length - startCol) * (props.cellSize + props.gap) - props.gap,
    })
  }

  return segments
})

watch(() => normalizedDays.value.length, (dayCount, previousDayCount) => {
  hideTooltip()
  if (!dayCount) {
    clearTransitionTimers()
    stopColumnAnimations()
    columnModes.value = []
    columnReadyAt.value = []
    columnElements.value = []
    revealState.value = 'pending'
    revealStartedAt = 0
    return
  }
  if (!previousDayCount) {
    revealState.value = 'pending'
    revealStartedAt = 0
  }
})

watch([() => normalizedDays.value.length, containerWidth], async ([dayCount, width]) => {
  if (!dayCount || !width || revealState.value !== 'pending') return
  await nextTick()
  startInitialReveal()
})

watch([() => visibleWeeks.value.length, resolvedActiveMode], ([visibleLength, nextMode]) => {
  hideTooltip()

  if (!nextMode || !visibleLength) {
    clearTransitionTimers()
    stopColumnAnimations()
    columnModes.value = []
    columnReadyAt.value = []
    columnElements.value = []
    return
  }

  if (
    columnModes.value.length !== visibleLength
    || columnReadyAt.value.length !== visibleLength
  ) {
    clearTransitionTimers()
    stopColumnAnimations()
    initializeColumns(visibleLength, nextMode)
    return
  }

  if (!hasPendingTransitions() && columnModes.value.every((mode) => mode === nextMode)) return

  if (revealState.value === 'pending') {
    startInitialReveal()
  }

  queueModeSwitch(nextMode)
}, { immediate: true })

onMounted(() => {
  updateContainerWidth()

  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(() => {
      updateContainerWidth()
      hideTooltip()
    })
    if (heatmapRef.value) resizeObserver.observe(heatmapRef.value)
  } else {
    window.addEventListener('resize', updateContainerWidth)
  }
})

onUnmounted(() => {
  if (revealTimer) clearTimeout(revealTimer)
  clearTransitionTimers()
  stopColumnAnimations()
  if (resizeObserver) {
    resizeObserver.disconnect()
  } else {
    window.removeEventListener('resize', updateContainerWidth)
  }
})

function resolveDate(item: HeatmapRecord) {
  if (props.getDate) return props.getDate(item)
  const value = (item as Record<string, unknown>)[props.dateKey]
  return typeof value === 'string' ? value : ''
}

function updateContainerWidth() {
  containerWidth.value = heatmapRef.value?.clientWidth ?? 0
}

function startInitialReveal() {
  if (revealState.value !== 'pending' || !visibleWeeks.value.length) return

  revealState.value = 'animating'
  revealStartedAt = getNow()
  const maxDistance = Math.max(0, visibleWeeks.value.length - 1 + 6)
  const totalDuration = props.initialRevealDelay + maxDistance * props.rippleDelay + props.rippleDuration + 60

  if (revealTimer) clearTimeout(revealTimer)
  revealTimer = setTimeout(() => {
    revealState.value = 'done'
  }, totalDuration)
}

function getNow() {
  return typeof performance !== 'undefined' ? performance.now() : Date.now()
}

function clearTransitionTimers() {
  for (const timer of transitionTimers) clearTimeout(timer)
  transitionTimers.clear()
}

function stopColumnAnimations() {
  for (const animation of columnAnimations.values()) animation.cancel()
  columnAnimations.clear()
}

function scheduleTransition(delay: number, callback: () => void) {
  const timer = setTimeout(() => {
    transitionTimers.delete(timer)
    callback()
  }, Math.max(0, delay))

  transitionTimers.add(timer)
}

function initializeColumns(visibleLength: number, modeKey: string) {
  columnModes.value = Array.from({ length: visibleLength }, () => modeKey)
  columnReadyAt.value = Array.from({ length: visibleLength }, () => 0)
  columnElements.value = Array.from({ length: visibleLength }, (_, index) => columnElements.value[index] ?? null)
}

function hasPendingTransitions() {
  const now = getNow()
  return columnReadyAt.value.some((readyAt) => readyAt > now)
}

function getColumnRevealReadyAt(columnIndex: number) {
  if (revealState.value === 'done' || revealStartedAt === 0) return 0
  return revealStartedAt + (columnIndex + 6) * props.rippleDelay + props.rippleDuration
}

function queueModeSwitch(nextMode: string) {
  if (!props.modes.length || !nextMode || !columnModes.value.length) return

  const now = getNow()
  const flipAnimationDuration = props.flipDuration * 2

  for (let index = 0; index < columnModes.value.length; index += 1) {
    const plannedStartAt = now + index * props.flipDelay
    const startAt = Math.max(
      plannedStartAt,
      columnReadyAt.value[index] ?? 0,
      getColumnRevealReadyAt(index),
    )
    const startDelay = startAt - now
    const swapDelay = startDelay + props.flipDuration

    columnReadyAt.value[index] = startAt + flipAnimationDuration

    scheduleTransition(startDelay, () => {
      playColumnFlip(index)
    })

    scheduleTransition(swapDelay, () => {
      columnModes.value[index] = nextMode
    })
  }
}

function setColumnRef(element: Element | ComponentPublicInstance | null, columnIndex: number) {
  if (element instanceof HTMLElement) {
    columnElements.value[columnIndex] = element
    return
  }

  const componentRoot = element && typeof element === 'object' && '$el' in element ? element.$el : null
  columnElements.value[columnIndex] = componentRoot instanceof HTMLElement ? componentRoot : null
}

function playColumnFlip(columnIndex: number) {
  const element = columnElements.value[columnIndex]
  if (!element || typeof element.animate !== 'function') return

  columnAnimations.get(columnIndex)?.cancel()

  const animation = element.animate([
    { transform: 'rotateY(0deg) scale(1)', offset: 0 },
    { transform: 'rotateY(90deg) scale(0.96)', offset: 0.5 },
    { transform: 'rotateY(0deg) scale(1)', offset: 1 },
  ], {
    duration: props.flipDuration * 2,
    easing: 'ease-in-out',
    fill: 'none',
  })

  columnAnimations.set(columnIndex, animation)

  animation.addEventListener('finish', () => {
    if (columnAnimations.get(columnIndex) === animation) {
      columnAnimations.delete(columnIndex)
    }
  }, { once: true })

  animation.addEventListener('cancel', () => {
    if (columnAnimations.get(columnIndex) === animation) {
      columnAnimations.delete(columnIndex)
    }
  }, { once: true })
}

function getDayOfWeek(dateStr: string) {
  const [year, month, day] = dateStr.split('-').map(Number)
  return new Date(year, month - 1, day).getDay()
}

function getWeekAnchor(week: Array<NormalizedHeatmapDay | null>) {
  return week.find((day) => day)?.date ?? ''
}

function getMonthNumber(dateStr: string) {
  return Number(dateStr.split('-')[1])
}

function getModeConfig(modeKey: string) {
  return modeLookup.value[modeKey] ?? props.modes[0]
}

function getModeValue(item: HeatmapRecord | null, modeKey: string) {
  const mode = getModeConfig(modeKey)
  if (!mode || !item) return 0

  if (mode.getValue) {
    const value = mode.getValue(item)
    return Number.isFinite(value) ? value : 0
  }

  if (mode.valueKey) {
    const value = Number((item as Record<string, unknown>)[mode.valueKey])
    return Number.isFinite(value) ? value : 0
  }

  return 0
}

function getLevel(value: number, maxValue: number) {
  if (value === 0 || maxValue === 0) return 0
  const ratio = value / maxValue
  if (ratio <= 0.25) return 1
  if (ratio <= 0.5) return 2
  if (ratio <= 0.75) return 3
  return 4
}

function getColumnMode(columnIndex: number) {
  return columnModes.value[columnIndex] ?? resolvedActiveMode.value
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
    style.animationDelay = `${(columnIndex + (6 - rowIndex)) * props.rippleDelay}ms`
  }

  return style
}

function getColumnKey(week: Array<NormalizedHeatmapDay | null>, columnIndex: number) {
  return `${getWeekAnchor(week)}-${columnIndex}`
}

function formatTooltip(day: NormalizedHeatmapDay, columnIndex: number) {
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
    })
  }

  return `${day.date} · ${value.toLocaleString('zh-CN')} ${mode.unit}`
}

function showTooltip(event: MouseEvent, day: NormalizedHeatmapDay, columnIndex: number) {
  const target = event.currentTarget as HTMLElement | null
  const root = heatmapRef.value
  if (!target || !root) return

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
      <div class="activity-heatmap__days">
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