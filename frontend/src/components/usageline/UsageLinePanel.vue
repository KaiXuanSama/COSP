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
    label?: string
    loading?: boolean
    failed?: boolean
    emptyText?: string
    loadingText?: string
    errorText?: string
  }>(),
  {
    label: undefined,
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

/**
 * 标题里的范围称呼。
 *
 * <p>缺省由 {@link props.range} 派生，但允许页面层用 {@link props.label} 覆写 ——
 * 时段视图接上日期选择器后，看的可能是历史某一天，此时写「今日时段」是错的。
 * 而「那一天是哪天」只有页面层知道（选择器状态在那里），故不能在本组件内派生。
 *
 * <p>只覆写称呼而非整个标题：「... token 用量」这部分是卡片的固定语义，
 * 放开它只会让调用方反复拼同一串后缀。
 *
 * <h2>天数取自 points 而非写死</h2>
 * 区间形态的跨度可由日期选择器改变（拖手柄能拖出 6 天或 9 天），
 * 写死「近 7 日」会与图上的柱数不符 —— 而这种不符没有任何报错，
 * 只是标题在撒谎。`points` 是实际渲染的那一批点位，用它派生必然同步。
 *
 * <p>`points` 为空（加载中 / 空态）时退回「近期」：那时图表区本就是占位文字，
 * 而「近 0 日」是明显的错话。
 */
const rangeLabel = computed(() => {
  if (props.label) return props.label
  if (props.range !== '7d') return '今日时段'
  return props.points.length > 0 ? `近 ${props.points.length} 日` : '近期'
})

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
  /*
   * 强制等高数字（lining figures）。
   *
   * 标题字体 Cormorant Garamond 默认用旧式数字：3/4/5/7/9 会垂到基线以下，
   * 像小写字母那样带降部。纯西文标题里这很雅致，但「近 7 日 token 用量」
   * 这种中文混排下，汉字端坐基线、数字却往下沉，看起来就像基线错位。
   * 与柱状图面包屑保持同一处理。
   */
  font-variant-numeric: lining-nums;
  font-feature-settings: 'lnum' 1;
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
