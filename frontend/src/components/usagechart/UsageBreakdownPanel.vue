<script setup lang="ts">
/**
 * UsageBreakdownPanel — 三级下钻柱状图的编排层。
 *
 * <h2>职责边界</h2>
 * - 本组件负责：层级状态机、面包屑导航、层级切换动画、把数据分发给各级图表。
 * - 本组件不负责：请求数据（由页面层拉取后以 rows 传入）、维度与指标的业务定义
 *   （由页面层以 dimension / metric 传入）。
 *
 * <h2>三级结构</h2>
 * <ol>
 *   <li>overview —— 按天堆叠，段为主维度；点某段下钻到该天；</li>
 *   <li>day —— 某天各主维度平铺；点某柱下钻到该主维度；</li>
 *   <li>primary —— 某天某主维度下各次维度平铺，已是最细粒度。</li>
 * </ol>
 *
 * <h2>解耦</h2>
 * 全程只用 primary / secondary 抽象，不出现供应商 / 模型字样；
 * 将来主次维度互换只需页面层换一份 dimension 配置。
 */
import { computed, ref } from 'vue'
import StackedBarChart from './StackedBarChart.vue'
import CategoryBarChart from './CategoryBarChart.vue'
import { useUsageBreakdown } from './useUsageBreakdown'
import type {
  BreakdownDimension,
  BreakdownMetric,
  DrilldownLevel,
  UsageBreakdownRow,
} from './usagechart'

const props = withDefaults(
  defineProps<{
    rows: UsageBreakdownRow[]
    dimension: BreakdownDimension
    metric: BreakdownMetric
    loading?: boolean
    failed?: boolean
    emptyText?: string
    loadingText?: string
    errorText?: string
    /** 层级切换动画时长（毫秒）。 */
    transitionDuration?: number
  }>(),
  {
    loading: false,
    failed: false,
    emptyText: '暂无数据',
    loadingText: '加载中',
    errorText: '加载失败',
    transitionDuration: 260,
  },
)

const rowsRef = computed(() => props.rows)
const dimensionRef = computed(() => props.dimension)
const metricRef = computed(() => props.metric)

const { columns, maxColumnTotal, barsForDate, barsForPrimary } = useUsageBreakdown(
  rowsRef,
  dimensionRef,
  metricRef,
)

/** 当前层级与选中路径。 */
const level = ref<DrilldownLevel>('overview')
const selectedDate = ref<string | null>(null)
const selectedPrimary = ref<string | null>(null)

/**
 * 过渡方向：down 为下探（放大淡出），up 为上浮（缩小淡出）。
 * 由它决定进出动画用哪一组 keyframes，使"钻进去 / 退回来"方向感明确。
 */
const direction = ref<'down' | 'up'>('down')

const transitionName = computed(() =>
  direction.value === 'down' ? 'drill-down' : 'drill-up',
)

const rootStyle = computed(() => ({
  '--usage-chart-transition': `${props.transitionDuration}ms`,
}))

/** 二级数据：某天各主维度。 */
const dayBars = computed(() =>
  selectedDate.value ? barsForDate(selectedDate.value) : [],
)

/** 三级数据：某天某主维度下各次维度。 */
const primaryBars = computed(() =>
  selectedDate.value && selectedPrimary.value
    ? barsForPrimary(selectedDate.value, selectedPrimary.value)
    : [],
)

/** 一级是否有可渲染数据。 */
const hasData = computed(() => columns.value.length > 0)

/**
 * 面包屑节点。
 *
 * 每项都可点击直接跳回对应层级（比单个返回按钮更灵活，也让用户随时知道自己在哪）。
 * 主维度那一节的文案用 dimension.primaryTerm，不写死业务词。
 */
const breadcrumbs = computed(() => {
  const items: Array<{ key: DrilldownLevel; label: string }> = [
    { key: 'overview', label: '总览' },
  ]
  if (selectedDate.value) {
    items.push({ key: 'day', label: formatDateLabel(selectedDate.value) })
  }
  if (selectedPrimary.value) {
    items.push({
      key: 'primary',
      label: dimensionRef.value.primaryLabel?.(selectedPrimary.value) ?? selectedPrimary.value,
    })
  }
  return items
})

/** 把 yyyy-MM-dd 显示为「M月D日」，横轴与面包屑共用。 */
function formatDateLabel(date: string): string {
  const parts = date.split('-')
  if (parts.length !== 3) return date
  return `${Number(parts[1])}月${Number(parts[2])}日`
}

/** 一级 → 二级：点某天的某一段。 */
function drillToDay(date: string) {
  direction.value = 'down'
  selectedDate.value = date
  selectedPrimary.value = null
  level.value = 'day'
}

/** 二级 → 三级：点某个主维度。 */
function drillToPrimary(primary: string) {
  direction.value = 'down'
  selectedPrimary.value = primary
  level.value = 'primary'
}

/** 面包屑跳转：目标层级在当前之前即为上浮。 */
function jumpTo(target: DrilldownLevel) {
  if (target === level.value) return
  const order: DrilldownLevel[] = ['overview', 'day', 'primary']
  direction.value = order.indexOf(target) < order.indexOf(level.value) ? 'up' : 'down'
  level.value = target
  if (target === 'overview') {
    selectedDate.value = null
    selectedPrimary.value = null
  } else if (target === 'day') {
    selectedPrimary.value = null
  }
}

/** 当前层级的副标题，说明纵轴含义与数据口径。 */
const subtitle = computed(() => {
  const metricName = metricRef.value.display
  const { primaryTerm, secondaryTerm } = dimensionRef.value
  if (level.value === 'overview') {
    return `按天堆叠 · 分段为${primaryTerm} · 纵轴为${metricName}`
  }
  if (level.value === 'day') {
    return `${formatDateLabel(selectedDate.value ?? '')} 各${primaryTerm} · 纵轴为${metricName}`
  }
  return `各${secondaryTerm} · 纵轴为${metricName}`
})
</script>

<template>
  <div class="usage-breakdown" :style="rootStyle">
    <!-- 导航与说明 -->
    <div class="usage-breakdown__bar">
      <nav class="usage-breakdown__crumbs" aria-label="下钻层级导航">
        <template v-for="(crumb, index) in breadcrumbs" :key="crumb.key">
          <span v-if="index > 0" class="usage-breakdown__crumb-sep" aria-hidden="true">/</span>
          <button
            type="button"
            class="usage-breakdown__crumb"
            :class="{ 'usage-breakdown__crumb--current': crumb.key === level }"
            :aria-current="crumb.key === level ? 'page' : undefined"
            @click="jumpTo(crumb.key)"
          >
            {{ crumb.label }}
          </button>
        </template>
      </nav>
      <span class="usage-breakdown__subtitle">{{ subtitle }}</span>
    </div>

    <!-- 状态优先级：加载 → 失败 → 空 → 图表 -->
    <div v-if="loading && !hasData" class="usage-breakdown__state">{{ loadingText }}</div>
    <div v-else-if="failed && !hasData" class="usage-breakdown__state">{{ errorText }}</div>
    <div v-else-if="!hasData" class="usage-breakdown__state">{{ emptyText }}</div>

    <!-- 层级切换：下探放大淡出，上浮缩小淡出 -->
    <Transition v-else :name="transitionName" mode="out-in">
      <StackedBarChart
        v-if="level === 'overview'"
        key="overview"
        :columns="columns"
        :max-total="maxColumnTotal"
        :unit="metric.unit"
        @drill="drillToDay"
      />
      <CategoryBarChart
        v-else-if="level === 'day'"
        key="day"
        :bars="dayBars"
        :unit="metric.unit"
        :drillable="true"
        @drill="drillToPrimary"
      />
      <CategoryBarChart
        v-else
        key="primary"
        :bars="primaryBars"
        :unit="metric.unit"
        :drillable="false"
      />
    </Transition>
  </div>
</template>

<style lang="scss" scoped>
.usage-breakdown {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.usage-breakdown__bar {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.usage-breakdown__crumbs {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.usage-breakdown__crumb {
  padding: 0;
  font-family: var(--usage-chart-font-body, inherit);
  font-size: 13px;
  color: var(--usage-chart-accent, #c27a3e);
  background: none;
  border: none;
  cursor: pointer;
  transition: opacity 0.15s ease;

  &:hover {
    opacity: 0.7;
  }

  /* 当前层级不可跳转，降级为普通文字 */
  &--current {
    color: var(--usage-chart-text-primary, #1a1917);
    font-weight: 600;
    cursor: default;

    &:hover {
      opacity: 1;
    }
  }
}

.usage-breakdown__crumb-sep {
  font-size: 12px;
  color: var(--usage-chart-text-muted, #9a9590);
}

.usage-breakdown__subtitle {
  font-family: var(--usage-chart-font-body, inherit);
  font-size: 12px;
  color: var(--usage-chart-text-muted, #9a9590);
}

.usage-breakdown__state {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  font-family: var(--usage-chart-font-body, inherit);
  font-size: 13px;
  color: var(--usage-chart-text-muted, #9a9590);
}

/*
  下探：新层级由略大缩到正常（放大淡入），旧层级放大淡出 —— 视觉上"进入内部"。
  上浮：方向相反，新层级由略小放大到正常 —— 视觉上"退回外层"。
 */
.drill-down-enter-active,
.drill-down-leave-active,
.drill-up-enter-active,
.drill-up-leave-active {
  transition:
    opacity var(--usage-chart-transition, 260ms) ease,
    transform var(--usage-chart-transition, 260ms) ease;
}

.drill-down-enter-from {
  opacity: 0;
  transform: scale(1.04);
}

.drill-down-leave-to {
  opacity: 0;
  transform: scale(1.04);
}

.drill-up-enter-from {
  opacity: 0;
  transform: scale(0.96);
}

.drill-up-leave-to {
  opacity: 0;
  transform: scale(0.96);
}

@media (prefers-reduced-motion: reduce) {
  .drill-down-enter-active,
  .drill-down-leave-active,
  .drill-up-enter-active,
  .drill-up-leave-active {
    transition: none;
  }
}
</style>
