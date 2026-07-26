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
}>(), {
  unit: '次',
  height: 180,
  drillable: false,
  minBarHeight: 4,
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
}))

/** 本视图内的最大值，用于柱高归一化（各级图独立归一化，不跨级比较）。 */
const maxValue = computed(() => props.bars.reduce((max, bar) => Math.max(max, bar.value), 0))

function barHeight(bar: CategoryBar): number {
  if (maxValue.value <= 0 || bar.value <= 0) return 0
  const raw = (bar.value / maxValue.value) * props.height
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

watch(() => props.bars, async () => {
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
    <div class="category-bar__plot">
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

.category-bar__plot {
  display: flex;
  align-items: flex-end;
  justify-content: space-around;
  gap: 10px;
  min-height: var(--usagechart-plot-height);
}

.category-bar__item {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  min-width: 0;
  padding: 4px 2px;
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
  display: flex;
  align-items: flex-end;
  width: 100%;
  max-width: 38px;
  height: var(--usagechart-plot-height);
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
