<script setup lang="ts">
/**
 * StackedBarChart — 一级视图：按天堆叠的柱状图。
 *
 * <h2>为什么不用多色配色</h2>
 * 段与段的区分靠<strong>圆角 + 间隙</strong>（与热力图风格一致），而非颜色。
 * 识别职责交给「位置（每列按值降序，下方更粗）+ hover 明细 + 点击下钻」，
 * 因此颜色只有一种，不受供应商数量增长影响，也不需要为其持久化色值。
 *
 * <h2>职责边界</h2>
 * 只负责渲染与交互事件，不请求数据、不感知业务维度：
 * 列与段由 {@link StackColumn} 给定，标签文案由数据层经 dimension 配置产出。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { StackColumn, StackSegment } from './usagechart'
import UsageTooltip from './UsageTooltip.vue'

const props = withDefaults(defineProps<{
  columns: StackColumn[]
  /** 所有列的最大总量，用于柱高归一化。 */
  maxTotal: number
  /** 指标单位，用于 tooltip 汇总文案。 */
  unit?: string
  /** 绘图区高度（px），不含日期标签。 */
  height?: number
  /** 段间空隙（px）。 */
  segmentGap?: number
  /** 段的最小可见高度（px），保证有数据的段不会细到看不见。 */
  minSegmentHeight?: number
}>(), {
  unit: '次',
  height: 180,
  segmentGap: 3,
  minSegmentHeight: 4,
})

const emit = defineEmits<{
  (e: 'drill', date: string): void
}>()

const rootRef = ref<HTMLElement | null>(null)
const tooltip = ref({ visible: false, left: 0, top: 0, title: '', summary: '', detail: [] as StackSegment['detail'] })

/** 入场动画：挂载后置为 true，触发各柱自下而上升起。 */
const revealed = ref(false)
let revealTimer: number | null = null

const rootStyle = computed(() => ({
  '--usagechart-plot-height': `${props.height}px`,
  '--usagechart-segment-gap': `${props.segmentGap}px`,
}))

/**
 * 段高（px）。
 *
 * 按占「全局最大列总量」的比例映射，使各列柱高可横向比较；
 * 再对有值的段兜一个最小高度，避免占比极小的段渲染成 0 而在图上消失。
 */
function segmentHeight(segment: StackSegment): number {
  if (props.maxTotal <= 0 || segment.value <= 0) return 0
  const raw = (segment.value / props.maxTotal) * props.height
  return Math.max(props.minSegmentHeight, raw)
}

/** 日期标签只保留「月-日」，避免 7 列挤在一起。 */
function shortDate(date: string): string {
  const parts = date.split('-')
  return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : date
}

function formatValue(value: number): string {
  return value.toLocaleString('zh-CN')
}

/**
 * 显示段的悬浮明细。
 *
 * 定位取段元素中心的容器内相对坐标（与热力图一致），页面滚动时不漂移。
 */
function showSegmentTooltip(event: MouseEvent, column: StackColumn, segment: StackSegment) {
  const target = event.currentTarget as HTMLElement | null
  const root = rootRef.value
  if (!target || !root) return

  const rect = target.getBoundingClientRect()
  const rootRect = root.getBoundingClientRect()
  const ratioText = `${(segment.ratio * 100).toFixed(1)}%`

  tooltip.value = {
    visible: true,
    left: rect.left - rootRect.left + rect.width / 2,
    top: rect.top - rootRect.top,
    title: segment.label,
    summary: `${formatValue(segment.value)} ${props.unit} · ${ratioText}`,
    detail: segment.detail,
  }
}

function hideTooltip() {
  tooltip.value.visible = false
}

/** 点击整列进入二级视图。段上的点击同样冒泡到列，故段与列共用一个下钻入口。 */
function drillIntoDay(date: string) {
  hideTooltip()
  emit('drill', date)
}

// 数据变化后重播入场动画，让「切换时间范围/指标」也有一致的观感。
watch(() => props.columns, async () => {
  revealed.value = false
  hideTooltip()
  await nextTick()
  scheduleReveal()
})

function scheduleReveal() {
  if (revealTimer !== null) window.clearTimeout(revealTimer)
  // 延后一帧，确保初始高度已应用，transition 才会真正播放
  revealTimer = window.setTimeout(() => {
    revealed.value = true
    revealTimer = null
  }, 30)
}

onMounted(scheduleReveal)

onUnmounted(() => {
  if (revealTimer !== null) window.clearTimeout(revealTimer)
})
</script>

<template>
  <div ref="rootRef" class="stacked-bar" :style="rootStyle" @mouseleave="hideTooltip">
    <div class="stacked-bar__plot">
      <div
        v-for="column in columns"
        :key="column.date"
        class="stacked-bar__column"
        role="button"
        tabindex="0"
        :aria-label="`查看 ${column.date} 的构成明细`"
        @click="drillIntoDay(column.date)"
        @keydown.enter.prevent="drillIntoDay(column.date)"
        @keydown.space.prevent="drillIntoDay(column.date)"
      >
        <!-- 柱体：自下而上堆叠，故用 column-reverse -->
        <div class="stacked-bar__stack">
          <div
            v-for="(segment, index) in column.segments"
            :key="segment.primary ?? `other-${index}`"
            class="stacked-bar__segment"
            :class="{ 'stacked-bar__segment--other': segment.isOther }"
            :style="{
              height: revealed ? `${segmentHeight(segment)}px` : '0px',
              transitionDelay: `${index * 40}ms`,
            }"
            @mouseenter="showSegmentTooltip($event, column, segment)"
            @mouseleave="hideTooltip"
          />
        </div>

        <div class="stacked-bar__date">{{ shortDate(column.date) }}</div>
      </div>
    </div>

    <UsageTooltip
      :visible="tooltip.visible"
      :left="tooltip.left"
      :top="tooltip.top"
      :title="tooltip.title"
      :summary="tooltip.summary"
      :detail="tooltip.detail"
    />
  </div>
</template>

<style lang="scss" scoped>
.stacked-bar {
  position: relative;
  width: 100%;
}

.stacked-bar__plot {
  display: flex;
  align-items: flex-end;
  justify-content: space-around;
  gap: 8px;
  min-height: var(--usagechart-plot-height);
}

.stacked-bar__column {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  min-width: 0;
  padding: 4px 2px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.18s ease;

  &:hover,
  &:focus-visible {
    background: var(--usagechart-accent-light, rgba(194, 122, 62, 0.08));
    outline: none;
  }
}

.stacked-bar__stack {
  display: flex;
  /* 自下而上堆叠：值最大的段在最底部 */
  flex-direction: column-reverse;
  justify-content: flex-start;
  gap: var(--usagechart-segment-gap);
  width: 100%;
  max-width: 34px;
  height: var(--usagechart-plot-height);
}

.stacked-bar__segment {
  width: 100%;
  /* 圆角 + 间隙即分段依据，与热力图格子风格统一 */
  border-radius: 4px;
  background: var(--usagechart-accent, #c27a3e);
  /* 升起动画；高度由内联样式在 revealed 翻转时给出 */
  transition: height 0.42s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.18s ease;

  &:hover {
    opacity: 0.82;
  }
}

/* other 段：同色但降低不透明度，暗示「其余」而非某个具体成员 */
.stacked-bar__segment--other {
  background: var(--usagechart-accent-muted, rgba(194, 122, 62, 0.42));
}

.stacked-bar__date {
  margin-top: 8px;
  font-family: var(--usagechart-font-mono, 'DM Mono', monospace);
  font-size: 11px;
  color: var(--usagechart-text-muted, #9a9590);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
</style>
