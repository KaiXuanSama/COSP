<script setup lang="ts">
/**
 * UsageLinePanel — token 用量折线图的编排层。
 *
 * <h2>职责边界</h2>
 * - 负责：时间范围的选择状态、状态态（加载/失败/空）的优先级、把范围变化通知上层。
 * - 不负责：请求数据（由页面层拉取后以 points 传入，与 {@link UsageBreakdownPanel} 一致）、
 *   坐标换算（在 {@link useTimelineSeries}）。
 */
import { computed } from 'vue'
import UsageLineChart from './UsageLineChart.vue'
import type { TimelineRange, UsageTimelinePoint } from './usageline'

const props = withDefaults(
  defineProps<{
    points: UsageTimelinePoint[]
    range: TimelineRange
    loading?: boolean
    failed?: boolean
    emptyText?: string
    loadingText?: string
    errorText?: string
  }>(),
  {
    loading: false,
    failed: false,
    emptyText: '暂无用量数据',
    loadingText: '加载中',
    errorText: '加载失败',
  },
)

const emit = defineEmits<{
  (e: 'update:range', value: TimelineRange): void
}>()

/** 两种范围轮换。放在编排层而非页面层，使卡片自成一个完整的交互单元。 */
function toggleRange() {
  emit('update:range', props.range === '7d' ? '1d' : '7d')
}

const rangeLabel = computed(() => (props.range === '7d' ? '近 7 日' : '今日时段'))

/**
 * 副标题：说明纵轴含义与数据口径。
 *
 * 特别标注「5:00 起算」与「以整点为中心」—— 非自然日的窗口若不说明，
 * 使用者会按 0 点去对数字；而每点覆盖前后各半小时也需要交代，
 * 否则会以为 07:00 只含 07:00 之后的那一小时。
 */
const subtitle = computed(() => {
  if (props.range === '7d') {
    return '按天汇总 · 纵轴为 token 数'
  }
  return '05:00 起算 24 小时 · 每点含前后半小时 · 纵轴为 token 数'
})

const hasData = computed(() => props.points.length > 0)
</script>

<template>
  <div class="usage-line-panel">
    <!-- 导航与说明。标题兼作范围指示，与热力图卡片的「当前项 + 切换」范式一致 -->
    <div class="usage-line-panel__bar">
      <div class="usage-line-panel__heading">
        <span class="usage-line-panel__title">{{ rangeLabel }} token 用量</span>
        <span class="usage-line-panel__subtitle">{{ subtitle }}</span>
      </div>

      <div class="usage-line-panel__actions">
        <button type="button" class="usage-line-panel__switch" @click="toggleRange">切换</button>
      </div>
    </div>

    <!-- 状态优先级：加载 → 失败 → 空 → 图表。有旧数据时不被状态态遮挡，避免图表闪空 -->
    <div v-if="loading && !hasData" class="usage-line-panel__state">{{ loadingText }}</div>
    <div v-else-if="failed && !hasData" class="usage-line-panel__state">{{ errorText }}</div>
    <div v-else-if="!hasData" class="usage-line-panel__state">{{ emptyText }}</div>

    <UsageLineChart v-else :points="points" :range="range" />
  </div>
</template>

<style lang="scss" scoped>
.usage-line-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.usage-line-panel__bar {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}

.usage-line-panel__heading {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px;
}

/* 与柱状图卡片的面包屑当前项同字体同字号，两张卡片的标题层级才一致 */
.usage-line-panel__title {
  font-family: var(--usage-line-font-display, inherit);
  font-size: 22px;
  font-weight: 600;
  color: var(--usage-line-text-primary, #1a1917);
}

.usage-line-panel__subtitle {
  font-family: var(--usage-line-font-body, inherit);
  font-size: 12px;
  color: var(--usage-line-text-muted, #9a9590);
}

.usage-line-panel__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.usage-line-panel__switch {
  padding: 3px 12px;
  border: 1px solid var(--usage-line-border, #e8e5de);
  border-radius: 6px;
  background: var(--usage-line-surface, #fff);
  color: var(--usage-line-text-body, #4a4740);
  cursor: pointer;
  font-family: var(--usage-line-font-mono, 'DM Mono', monospace);
  font-size: 10px;
  font-weight: 500;
  transition: all 0.2s ease;

  &:hover {
    border-color: var(--usage-line-accent, #c27a3e);
    color: var(--usage-line-accent, #c27a3e);
    background: var(--usage-line-accent-light, rgba(194, 122, 62, 0.08));
  }
}

.usage-line-panel__state {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  font-family: var(--usage-line-font-body, inherit);
  font-size: 13px;
  color: var(--usage-line-text-muted, #9a9590);
}
</style>
