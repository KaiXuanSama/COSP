<script setup lang="ts">
/**
 * CategoryBarChart — 二级与三级视图共用的分类柱状图。
 *
 * <h2>为什么一个组件服务两级</h2>
 * 二级（某天各主维度）与三级（某天某主维度下各次维度）结构完全同构：
 * 横轴一组分类、纵轴同一指标。差别只在数据来源与是否可继续下钻，
 * 因此由 {@code drillable} 控制是否发出下钻事件即可复用同一份渲染逻辑。
 *
 * 组件不感知业务维度：分类身份与标签均由数据层经 dimension 配置产出。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { CategoryBar } from './usagechart'
import { buildAxisTicks, COLUMN_PAD_Y, formatTickValue, VALUE_LABEL_SPACE } from './axisTicks'
import UsageTooltip from './UsageTooltip.vue'

const props = withDefaults(defineProps<{
  bars: CategoryBar[]
  /** 指标单位，用于 tooltip 汇总文案。 */
  unit?: string
  /** 绘图区高度（px），不含分类标签。 */
  height?: number
  /** 是否可继续下钻（三级为最细粒度，应传 false）。 */
  drillable?: boolean
  /** 柱最小可见高度（px）。 */
  minBarHeight?: number
  /** 纵轴期望刻度数（含 0），实际数量会取整到好看的刻度值。 */
  tickCount?: number
  /** 纵轴刻度栏宽度（px），需容纳最长的刻度读数。 */
  axisWidth?: number
}>(), {
  unit: '次',
  height: 180,
  drillable: false,
  minBarHeight: 4,
  tickCount: 4,
  axisWidth: 36,
})

const emit = defineEmits<{
  (e: 'drill', key: string): void
}>()

const rootRef = ref<HTMLElement | null>(null)
const tooltip = ref({ visible: false, left: 0, top: 0, title: '', summary: '', detail: [] as CategoryBar['detail'] })

const revealed = ref(false)
let revealTimer: number | null = null

const rootStyle = computed(() => ({
  '--usagechart-plot-height': `${props.height}px`,
  '--usagechart-axis-width': `${props.axisWidth}px`,
  // 柱列的纵向内边距。轴与网格线按同一值下移，柱底才会正好落在 0 刻度线上。
  '--usagechart-column-pad-y': `${COLUMN_PAD_Y}px`,
  // 柱顶读数的容身之处，防止接近轴上限的柱子把读数顶出图表外。
  '--usagechart-value-space': `${VALUE_LABEL_SPACE}px`,
}))

/** 本视图内的最大值（各级图独立归一化，不跨级比较）。 */
const maxValue = computed(() => props.bars.reduce((max, bar) => Math.max(max, bar.value), 0))

/** 纵轴刻度。 */
const axisTicks = computed(() => buildAxisTicks(maxValue.value, props.tickCount))

/**
 * 归一化基准取刻度顶值而非数据最大值。
 *
 * 若仍按数据最大值归一，最高的柱会顶到绘图区上沿、与顶端刻度线错位，
 * 纵轴读数就失去了意义。改用刻度顶值后，柱高与刻度线严格对应。
 */
const axisMax = computed(() => axisTicks.value[axisTicks.value.length - 1]?.value ?? 0)

function barHeight(bar: CategoryBar): number {
  if (axisMax.value <= 0 || bar.value <= 0) return 0
  const raw = (bar.value / axisMax.value) * props.height
  return Math.max(props.minBarHeight, raw)
}

function formatValue(value: number): string {
  return value.toLocaleString('zh-CN')
}

function showBarTooltip(event: MouseEvent, bar: CategoryBar) {
  const target = event.currentTarget as HTMLElement | null
  const root = rootRef.value
  if (!target || !root) return

  const rect = target.getBoundingClientRect()
  const rootRect = root.getBoundingClientRect()

  tooltip.value = {
    visible: true,
    left: rect.left - rootRect.left + rect.width / 2,
    top: rect.top - rootRect.top,
    title: bar.label,
    summary: `${formatValue(bar.value)} ${props.unit} · ${(bar.ratio * 100).toFixed(1)}%`,
    detail: bar.detail,
  }
}

function hideTooltip() {
  tooltip.value.visible = false
}

function drillInto(bar: CategoryBar) {
  if (!props.drillable) return
  hideTooltip()
  emit('drill', bar.key)
}

/** 结构指纹：柱的身份序列，不含数值，故实时推送的数值变化不会改变它。 */
const structureKey = computed(() => props.bars.map((bar) => bar.key).join('|'))

/**
 * 仅在结构变化时重播入场动画。
 *
 * 理由同 StackedBarChart：SSE 推送只让数值增长时，重置 revealed 会导致柱子
 * 归零重长、图表持续抖动；纯数值变化交给 CSS height transition 平滑过渡。
 */
watch(structureKey, async () => {
  revealed.value = false
  hideTooltip()
  await nextTick()
  scheduleReveal()
})

function scheduleReveal() {
  if (revealTimer !== null) window.clearTimeout(revealTimer)
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
  <div ref="rootRef" class="category-bar" :style="rootStyle" @mouseleave="hideTooltip">
    <div class="category-bar__body">
      <!-- 纵轴：刻度读数 + 对齐的网格线 -->
      <div class="category-bar__axis" aria-hidden="true">
        <span
          v-for="tick in axisTicks"
          :key="tick.value"
          class="category-bar__tick"
          :style="{ bottom: `${tick.ratio * 100}%` }"
        >
          {{ formatTickValue(tick.value) }}
        </span>
      </div>

      <div class="category-bar__plot">
        <div class="category-bar__grid" aria-hidden="true">
          <span
            v-for="tick in axisTicks"
            :key="tick.value"
            class="category-bar__gridline"
            :class="{ 'category-bar__gridline--base': tick.value === 0 }"
            :style="{ bottom: `${tick.ratio * 100}%` }"
          />
        </div>

        <div
          v-for="(bar, index) in bars"
          :key="bar.key"
          class="category-bar__item"
          :class="{ 'category-bar__item--drillable': drillable }"
          :role="drillable ? 'button' : undefined"
          :tabindex="drillable ? 0 : undefined"
          :aria-label="drillable ? `查看 ${bar.label} 的明细` : undefined"
          @click="drillInto(bar)"
          @keydown.enter.prevent="drillInto(bar)"
          @keydown.space.prevent="drillInto(bar)"
        >
          <div class="category-bar__track">
            <!--
              柱顶读数。bottom 跟随柱高，故随入场动画一起上升；
              aria-hidden 是因为该数值已在 tooltip 与 aria-label 中提供。
            -->
            <span
              v-if="bar.value > 0"
              class="category-bar__value"
              aria-hidden="true"
              :style="{
                bottom: revealed ? `${barHeight(bar)}px` : '0px',
                opacity: revealed ? 1 : 0,
                transitionDelay: `${index * 40}ms`,
              }"
            >
              {{ formatValue(bar.value) }}
            </span>

            <div
              class="category-bar__fill"
              :style="{
                height: revealed ? `${barHeight(bar)}px` : '0px',
                transitionDelay: `${index * 40}ms`,
              }"
              @mouseenter="showBarTooltip($event, bar)"
              @mouseleave="hideTooltip"
            />
          </div>
          <div class="category-bar__label" :title="bar.label">{{ bar.label }}</div>
        </div>
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
.category-bar {
  position: relative;
  width: 100%;
}

/*
 * 轴与绘图区并排；轴宽固定，绘图区占满剩余空间。
 *
 * 顶部留白供柱顶读数落脚：柱高最大时会顶到绘图区上沿，
 * 读数在其上方，若无留白就会溢出卡片、与外部元素重叠。
 * 留白加在 body 上（而非绘图区内），轴与网格线一同下移，不影响基线对齐。
 */
.category-bar__body {
  display: flex;
  align-items: stretch;
  gap: 8px;
  padding-top: var(--usagechart-value-space);
}

/*
 * 刻度按比例绝对定位，故容器高度须与绘图区严格一致（不含标签行）。
 *
 * 上边距与柱列的上内边距同值：列有 padding-top 把柱体整体下推，
 * 轴与网格线必须跟着下移同样的距离，否则柱底会比 0 刻度线低出这段。
 */
.category-bar__axis {
  position: relative;
  flex: 0 0 auto;
  width: var(--usagechart-axis-width, 36px);
  height: var(--usagechart-plot-height);
  margin-top: var(--usagechart-column-pad-y);
}

.category-bar__tick {
  position: absolute;
  right: 0;
  /* 让读数的视觉中心落在刻度线上 */
  transform: translateY(50%);
  font-family: var(--usagechart-font-mono, 'DM Mono', monospace);
  font-size: 10px;
  line-height: 1;
  color: var(--usagechart-text-muted, #9a9590);
  white-space: nowrap;
}

.category-bar__plot {
  position: relative;
  display: flex;
  flex: 1;
  align-items: flex-end;
  justify-content: space-around;
  gap: 10px;
  min-width: 0;
  min-height: var(--usagechart-plot-height);
}

/*
 * 网格线层：不参与 flex 布局，高度严格等于绘图区（不含标签行）。
 *
 * 用 top + height 而非 inset 的 bottom 偏移 —— 后者依赖对标签行高度的估算，
 * 字号或行高一变就会错位；直接锁定绘图区高度更稳。
 */
.category-bar__grid {
  position: absolute;
  top: var(--usagechart-column-pad-y);
  right: 0;
  left: 0;
  height: var(--usagechart-plot-height);
  pointer-events: none;
}

.category-bar__gridline {
  position: absolute;
  left: 0;
  width: 100%;
  border-top: 1px dashed var(--usagechart-grid, rgba(154, 149, 144, 0.22));
}

/* 基线（0 刻度）用实线，视觉上收住整个绘图区 */
.category-bar__gridline--base {
  border-top-style: solid;
  border-top-color: var(--usagechart-grid-base, rgba(154, 149, 144, 0.4));
}

.category-bar__item {
  position: relative;
  /* 压在网格线之上 */
  z-index: 1;
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  min-width: 0;
  padding: var(--usagechart-column-pad-y) 2px;
  border-radius: 8px;
  transition: background 0.18s ease;
}

.category-bar__item--drillable {
  cursor: pointer;

  &:hover,
  &:focus-visible {
    background: var(--usagechart-accent-light, rgba(194, 122, 62, 0.08));
    outline: none;
  }
}

.category-bar__track {
  /* 作为柱顶读数的定位上下文 */
  position: relative;
  display: flex;
  align-items: flex-end;
  width: 100%;
  max-width: 38px;
  height: var(--usagechart-plot-height);
}

/* 柱顶读数：与堆叠图同一套视觉，随柱高上升 */
.category-bar__value {
  position: absolute;
  left: 50%;
  margin-bottom: 5px;
  transform: translateX(-50%);
  font-family: var(--usagechart-font-mono, 'DM Mono', monospace);
  font-size: 11px;
  line-height: 1;
  color: var(--usagechart-text-muted, #9a9590);
  white-space: nowrap;
  pointer-events: none;
  transition: bottom 0.42s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.28s ease;
}

.category-bar__fill {
  width: 100%;
  border-radius: 4px;
  background: var(--usagechart-accent, #c27a3e);
  transition: height 0.42s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.18s ease;

  &:hover {
    opacity: 0.82;
  }
}

.category-bar__label {
  width: 100%;
  margin-top: 8px;
  font-family: var(--usagechart-font-body, 'Crimson Pro', serif);
  font-size: 11px;
  color: var(--usagechart-text-muted, #9a9590);
  text-align: center;
  /* 分类名可能很长（如模型全名），截断避免撑破布局 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
