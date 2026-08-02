<script setup lang="ts">
/**
 * UsageTooltip — 下钻柱状图共用的悬浮明细浮窗。
 *
 * <p>被一级堆叠柱与二/三级分类柱共用，因此不感知任何业务维度，只渲染
 * {@link SegmentDetail} 结构：
 * <ul>
 *   <li>单层 —— 普通段/柱子，列出次维度在其内部的占比；</li>
 *   <li>双层 —— other 段，先列被合并的成员及其占 other 的比例，
 *       再在其下缩进列出各成员内部的次维度占比。</li>
 * </ul>
 *
 * <p>定位采用「容器内相对坐标」而非鼠标跟随（与热力图 tooltip 一致），
 * 页面滚动或卡片偏移时不会漂移。纯展示，故 `pointer-events: none`，
 * 避免浮窗抢走鼠标导致 hover 闪烁或遮挡点击下钻。
 */
import type { SegmentDetail } from './usagechart'

withDefaults(defineProps<{
  visible: boolean
  /** 相对图表容器的坐标（px）。 */
  left: number
  top: number
  title: string
  /** 标题右侧的汇总值，如「234 次 · 91.8%」。 */
  summary?: string
  detail: SegmentDetail[]
  /**
   * 向左展开（右缘对齐锚点）。光标靠近容器右缘时置 true，否则浮框会溢出。
   */
  alignEnd?: boolean
  /**
   * 落在锚点下方。光标靠近容器上缘时置 true，否则向上展开会溢出。
   */
  below?: boolean
}>(), {
  alignEnd: false,
  below: false,
})

/** 占比统一保留一位小数；极小值不显示为 0.0% 以免误解为无调用。 */
function formatRatio(ratio: number): string {
  const percent = ratio * 100
  if (percent > 0 && percent < 0.1) return '<0.1%'
  return `${percent.toFixed(1)}%`
}
</script>

<template>
  <div
    v-if="visible"
    class="usage-tooltip"
    :class="{
      'usage-tooltip--end': alignEnd,
      'usage-tooltip--below': below,
    }"
    aria-hidden="true"
    :style="{ left: `${left}px`, top: `${top}px` }"
  >
    <div class="usage-tooltip__head">
      <span class="usage-tooltip__title">{{ title }}</span>
      <span v-if="summary" class="usage-tooltip__summary">{{ summary }}</span>
    </div>

    <ul v-if="detail.length" class="usage-tooltip__list">
      <li v-for="item in detail" :key="item.label" class="usage-tooltip__item">
        <div class="usage-tooltip__row">
          <span class="usage-tooltip__label">{{ item.label }}</span>
          <span class="usage-tooltip__ratio">{{ formatRatio(item.ratio) }}</span>
        </div>

        <!-- 第二层：仅 other 段有 children，缩进展示成员内部的次维度构成 -->
        <ul v-if="item.children?.length" class="usage-tooltip__sublist">
          <li v-for="child in item.children" :key="child.label" class="usage-tooltip__row usage-tooltip__row--sub">
            <span class="usage-tooltip__label">{{ child.label }}</span>
            <span class="usage-tooltip__ratio">{{ formatRatio(child.ratio) }}</span>
          </li>
        </ul>
      </li>
    </ul>
  </div>
</template>

<style lang="scss" scoped>
/*
 * 跟随光标定位。
 *
 * left/top 由父组件给出光标的容器内坐标，本组件只负责相对该点的展开方向：
 * 默认向右上展开（不遮挡光标下方的图形），贴近容器边缘时由修饰类翻转。
 *
 * 两个方向拆成独立的自定义属性，而非写四条组合规则 —— 水平与垂直是两个
 * 互不相干的决策，写成组合会让同一个位移值重复出现在多处。
 */
.usage-tooltip {
  position: absolute;
  z-index: 20;
  transform: translate(var(--usage-tooltip-x, -50%), var(--usage-tooltip-y, calc(-100% - 14px)));
  min-width: 160px;
  max-width: 280px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--usagechart-tooltip-bg, #1a1917);
  color: var(--usagechart-tooltip-text, #f5f3ee);
  font-family: var(--usagechart-font-mono, 'DM Mono', monospace);
  font-size: 11px;
  line-height: 1.6;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18);
  /* 纯展示：不接收鼠标事件，避免遮挡下钻点击与造成 hover 抖动 */
  pointer-events: none;
  white-space: nowrap;
}

/* 光标靠近右缘：右缘对齐光标，向左展开 */
.usage-tooltip--end {
  --usage-tooltip-x: calc(-100% + 14px);
}

/* 光标靠近上缘：翻到光标下方 */
.usage-tooltip--below {
  --usage-tooltip-y: 14px;
}

.usage-tooltip__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 4px;
  margin-bottom: 4px;
  border-bottom: 1px solid rgba(245, 243, 238, 0.18);
}

.usage-tooltip__title {
  font-weight: 600;
}

.usage-tooltip__summary {
  opacity: 0.7;
}

.usage-tooltip__list,
.usage-tooltip__sublist {
  list-style: none;
  margin: 0;
  padding: 0;
}

.usage-tooltip__sublist {
  /* 缩进体现层级：成员 → 其内部次维度 */
  padding-left: 10px;
  opacity: 0.72;
}

.usage-tooltip__item + .usage-tooltip__item {
  margin-top: 2px;
}

.usage-tooltip__row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.usage-tooltip__row--sub::before {
  content: '›';
  margin-right: 4px;
  opacity: 0.6;
}

.usage-tooltip__label {
  overflow: hidden;
  text-overflow: ellipsis;
}

.usage-tooltip__ratio {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
</style>
