<script setup lang="ts">
/**
 * UsageBarChart — 三级视图共用的唯一柱状图。
 *
 * <h2>为什么只有一个组件</h2>
 * 分类柱状图本质是「只有一段的堆叠柱状图」：一级每柱由多个主维度段堆叠而成，
 * 二 / 三级每柱只有一段。既然只是段数不同，就不必准备两套结构与两个组件 ——
 * 统一之后纵轴、网格线、柱顶读数、tooltip、键盘可达性都只有一份实现，
 * 不会再出现「同一视觉概念在两处各写一份、值悄悄漂移」的问题。
 *
 * 更关键的是动效：层级切换时若换组件，柱子只能整批淡出再淡入；
 * 同一组件内换数据则可按 {@link StackBar.key} 复用 DOM，
 * 让柱子平滑长高 / 缩短 / 位移，得到 morph 而非 fade 的观感。
 *
 * <h2>职责边界</h2>
 * 只负责渲染与交互事件，不请求数据、不感知业务维度：
 * 柱与段由 {@link StackBar} 给定，标签文案由数据层经 dimension 配置产出。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { StackBar, StackSegment } from './usagechart'
import { AXIS_WIDTH, buildAxisTicks, COLUMN_PAD_Y, formatTickValue, VALUE_LABEL_SPACE } from './axisTicks'
import UsageTooltip from './UsageTooltip.vue'

const props = withDefaults(defineProps<{
  bars: StackBar[]
  /**
   * 柱高归一化基准。
   *
   * 一级传「所有天的最大总量」使各天可横向比较；二 / 三级不传，
   * 由本视图最大值自行归一（各级独立成图，不跨级比较高度）。
   */
  maxTotal?: number
  /** 是否可继续下钻（三级为最细粒度，应传 false）。 */
  drillable?: boolean
  /** 下钻提示文案的动词部分，用于 aria-label。 */
  drillHint?: string
  /** 指标单位，用于 tooltip 汇总文案。 */
  unit?: string
  /** 绘图区高度（px），不含横轴标签。 */
  height?: number
  /** 段间空隙（px）。 */
  segmentGap?: number
  /** 段的最小可见高度（px），保证有数据的段不会细到看不见。 */
  minSegmentHeight?: number
  /** 单柱最大宽度（px）。 */
  barWidth?: number
  /** 纵轴刻度栏宽度（px），需容纳最长的刻度读数。 */
  axisWidth?: number
}>(), {
  maxTotal: 0,
  drillable: true,
  drillHint: '查看',
  unit: '次',
  height: 180,
  segmentGap: 3,
  minSegmentHeight: 4,
  barWidth: 36,
  axisWidth: AXIS_WIDTH,
})

const emit = defineEmits<{
  (e: 'drill', key: string): void
}>()

const rootRef = ref<HTMLElement | null>(null)
const tooltip = ref({ visible: false, left: 0, top: 0, title: '', summary: '', detail: [] as StackSegment['detail'] })

/** 入场动画：挂载后置为 true，触发各柱自下而上升起。 */
const revealed = ref(false)
let revealTimer: number | null = null

const rootStyle = computed(() => ({
  '--usagechart-plot-height': `${props.height}px`,
  '--usagechart-segment-gap': `${props.segmentGap}px`,
  '--usagechart-axis-width': `${props.axisWidth}px`,
  '--usagechart-bar-width': `${props.barWidth}px`,
  // 柱列的纵向内边距（hover 高亮的呼吸空间）。轴与网格线要按同一值下移，
  // 否则柱底会比 0 刻度线低出这段距离。
  '--usagechart-column-pad-y': `${COLUMN_PAD_Y}px`,
  // 柱顶读数的容身之处，防止接近轴上限的柱子把读数顶出图表外。
  '--usagechart-value-space': `${VALUE_LABEL_SPACE}px`,
}))

/**
 * 归一化参照值。
 *
 * maxTotal 有值时以它为准（一级：跨天可比）；否则取本视图最大值
 * （二 / 三级：独立成图）。这是三级唯一的行为差异，故只用一个可选 prop 表达。
 */
const referenceMax = computed(() =>
  props.maxTotal > 0
    ? props.maxTotal
    : props.bars.reduce((max, bar) => Math.max(max, bar.total), 0),
)

/** 纵轴刻度。以取整后的上限为基准，使刻度读数是 10、20、50 这类整数。 */
const axisTicks = computed(() => buildAxisTicks(referenceMax.value))

/**
 * 柱高归一化基准 —— 取整后的轴上限，而非数据真实最大值。
 *
 * 必须与刻度同一基准：否则最高的柱会顶到绘图区顶部，
 * 而顶部刻度标的是取整后的更大值，柱高与刻度线就对不上了。
 */
const heightBasis = computed(() => {
  const ticks = axisTicks.value
  return ticks.length ? ticks[ticks.length - 1].value : referenceMax.value
})

/**
 * 段高（px）。
 *
 * 按占「轴上限」的比例映射，使柱高与刻度线对齐；
 * 再对有值的段兜一个最小高度，避免占比极小的段渲染成 0 而在图上消失。
 */
function segmentHeight(segment: StackSegment): number {
  const basis = heightBasis.value
  if (basis <= 0 || segment.value <= 0) return 0
  const raw = (segment.value / basis) * props.height
  return Math.max(props.minSegmentHeight, raw)
}

/**
 * 整柱的实际像素高度 —— 各段高之和，外加段间空隙。
 *
 * 不能用 total / basis 直接算：段高对小值做了 minSegmentHeight 兜底，
 * 加上 segmentGap 的累计，实际柱顶会略高于按比例的理论值。
 * 顶部读数要贴合真实柱顶，必须与渲染用的同一套高度口径。
 */
function barHeight(bar: StackBar): number {
  const visible = bar.segments.filter((segment) => segment.value > 0)
  if (!visible.length) return 0
  const stacked = visible.reduce((sum, segment) => sum + segmentHeight(segment), 0)
  return stacked + props.segmentGap * (visible.length - 1)
}

function formatValue(value: number): string {
  return value.toLocaleString('zh-CN')
}

/** 下钻入口的无障碍描述，附带总量便于读屏用户获得同样的信息量。 */
function barAriaLabel(bar: StackBar): string | undefined {
  if (!props.drillable) return undefined
  return `${props.drillHint} ${bar.fullLabel} 的明细，共 ${formatValue(bar.total)} ${props.unit}`
}

/**
 * 显示段的悬浮明细。
 *
 * 定位取段元素中心的容器内相对坐标（与热力图一致），页面滚动时不漂移。
 */
function showSegmentTooltip(event: MouseEvent, segment: StackSegment) {
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

/** 点击整柱进入下一级。段上的点击同样冒泡到柱，故段与柱共用一个下钻入口。 */
function drillInto(bar: StackBar) {
  if (!props.drillable) return
  hideTooltip()
  emit('drill', bar.key)
}

/**
 * 结构指纹：柱的身份序列 + 各柱的段身份。
 *
 * 只用于判断「是不是同一批柱子」，不含数值，因此实时推送导致的纯数值变化不会改变它。
 */
const structureKey = computed(() =>
  props.bars
    .map((bar) => `${bar.key}:${bar.segments.map((s) => s.primary ?? '~other').join(',')}`)
    .join('|'),
)

/**
 * 仅在结构变化时重播入场动画。
 *
 * SSE 实时推送会高频替换数据，但绝大多数时候只是某些段的数值在涨；
 * 若每帧都把 revealed 打回 false，柱子会不停地归零重长、图表持续抖动。
 * 数值变化交给段上的 CSS height transition 自然过渡即可，观感是「柱子平滑长高」。
 *
 * 结构变化（切换层级、出现新的成员或日期）才重播，保留原有的入场观感。
 */
watch(structureKey, async () => {
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
  <div ref="rootRef" class="usage-bar" :style="rootStyle" @mouseleave="hideTooltip">
    <div class="usage-bar__body">
      <!-- 纵轴：刻度读数 + 与之对齐的网格线，让柱高可被量化读出 -->
      <div class="usage-bar__axis" aria-hidden="true">
        <span
          v-for="tick in axisTicks"
          :key="tick.value"
          class="usage-bar__tick"
          :style="{ bottom: `${tick.ratio * 100}%` }"
        >
          {{ formatTickValue(tick.value) }}
        </span>
      </div>

      <div class="usage-bar__plot">
        <div class="usage-bar__grid" aria-hidden="true">
          <span
            v-for="tick in axisTicks"
            :key="tick.value"
            class="usage-bar__gridline"
            :class="{ 'usage-bar__gridline--base': tick.value === 0 }"
            :style="{ bottom: `${tick.ratio * 100}%` }"
          />
        </div>

        <div
          v-for="(bar, index) in bars"
          :key="bar.key"
          class="usage-bar__column"
          :class="{ 'usage-bar__column--drillable': drillable }"
          :role="drillable ? 'button' : undefined"
          :tabindex="drillable ? 0 : undefined"
          :aria-label="barAriaLabel(bar)"
          @click="drillInto(bar)"
          @keydown.enter.prevent="drillInto(bar)"
          @keydown.space.prevent="drillInto(bar)"
        >
          <!-- 柱体：自下而上堆叠，故用 column-reverse -->
          <div class="usage-bar__stack">
            <!--
              柱顶总量读数。位置由柱高驱动（bottom = 柱高），因此入场动画期间
              会跟着柱顶一起上升；aria-hidden 是因为柱的 aria-label 已含同一数字。
            -->
            <span
              v-if="bar.total > 0"
              class="usage-bar__value"
              aria-hidden="true"
              :style="{
                bottom: revealed ? `${barHeight(bar)}px` : '0px',
                opacity: revealed ? 1 : 0,
                transitionDelay: `${index * 40}ms`,
              }"
            >
              {{ formatValue(bar.total) }}
            </span>

            <div
              v-for="(segment, segIndex) in bar.segments"
              :key="segment.primary ?? `other-${segIndex}`"
              class="usage-bar__segment"
              :class="{ 'usage-bar__segment--other': segment.isOther }"
              :style="{
                height: revealed ? `${segmentHeight(segment)}px` : '0px',
                transitionDelay: `${index * 40 + segIndex * 40}ms`,
              }"
              @mouseenter="showSegmentTooltip($event, segment)"
              @mouseleave="hideTooltip"
            />
          </div>

          <div class="usage-bar__label" :title="bar.fullLabel">{{ bar.label }}</div>
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
.usage-bar {
  position: relative;
  width: 100%;
}

/*
 * 轴区与绘图区并排；轴宽固定，绘图区吃掉剩余空间。
 *
 * padding-top 为柱顶读数留白：柱高最多可达绘图区满高（数据最大值落在顶端刻度时），
 * 此时读数浮在柱顶之上就会溢出容器、与卡片外的元素重叠。
 * 留白加在 body 上而非绘图区内部，可让轴与网格线一起下移，不破坏基线对齐。
 */
.usage-bar__body {
  display: flex;
  align-items: stretch;
  gap: 8px;
  padding-top: var(--usagechart-value-space);
}

/*
 * 纵轴刻度栏。
 *
 * 高度只占绘图区（不含横轴标签），故与 __stack 等高；
 * 每个刻度用 bottom 百分比定位，天然与绘图区内的网格线对齐。
 * 上边距与柱列一致，保证刻度、网格线、柱体三者共享同一条基准线。
 */
.usage-bar__axis {
  position: relative;
  flex: 0 0 auto;
  width: var(--usagechart-axis-width, 36px);
  height: var(--usagechart-plot-height);
  margin-top: var(--usagechart-column-pad-y);
}

.usage-bar__tick {
  position: absolute;
  right: 0;
  /* 上移半个行高，使读数中线压在刻度线上 */
  transform: translateY(50%);
  font-family: var(--usagechart-font-mono, 'DM Mono', monospace);
  font-size: 10px;
  line-height: 1;
  color: var(--usagechart-text-muted, #9a9590);
  white-space: nowrap;
}

.usage-bar__plot {
  position: relative;
  display: flex;
  flex: 1;
  align-items: flex-end;
  justify-content: space-around;
  gap: 8px;
  min-width: 0;
  min-height: var(--usagechart-plot-height);
}

/*
 * 网格线层：与刻度同高，置于柱体之下，不拦截鼠标。
 *
 * top 要与柱列的上内边距一致 —— 列有 padding-top 把柱体整体下推，
 * 若网格线仍从 0 起算，柱底就会比 0 刻度线低出这段内边距。
 */
.usage-bar__grid {
  position: absolute;
  top: var(--usagechart-column-pad-y);
  right: 0;
  left: 0;
  height: var(--usagechart-plot-height);
  pointer-events: none;
}

.usage-bar__gridline {
  position: absolute;
  right: 0;
  left: 0;
  border-top: 1px dashed var(--usagechart-gridline, rgba(154, 149, 144, 0.22));
}

/* 基线（0 刻度）用实线，稍重一点，作为柱子的落脚线 */
.usage-bar__gridline--base {
  border-top-style: solid;
  border-top-color: var(--usagechart-gridline-base, rgba(154, 149, 144, 0.4));
}

.usage-bar__column {
  position: relative;
  /* 压在网格线之上，避免虚线穿过柱体 */
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

.usage-bar__column--drillable {
  cursor: pointer;

  &:hover,
  &:focus-visible {
    background: var(--usagechart-accent-light, rgba(194, 122, 62, 0.08));
    outline: none;
  }
}

.usage-bar__stack {
  /* 作为柱顶读数的定位上下文 */
  position: relative;
  display: flex;
  /* 自下而上堆叠：值最大的段在最底部 */
  flex-direction: column-reverse;
  justify-content: flex-start;
  gap: var(--usagechart-segment-gap);
  width: 100%;
  max-width: var(--usagechart-bar-width);
  height: var(--usagechart-plot-height);
}

/*
 * 柱顶总量读数。
 *
 * bottom 由柱高驱动并加一段间距，故随入场动画一起上升；
 * 用 translateX(-50%) 居中而不靠 flex，避免影响堆叠布局。
 * 数字可能比柱子宽，故不裁剪、不换行。
 */
.usage-bar__value {
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

.usage-bar__segment {
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
.usage-bar__segment--other {
  background: var(--usagechart-accent-muted, rgba(194, 122, 62, 0.42));
}

/*
 * 横轴标签。一级是「M/D」日期，二 / 三级是分类名（可能很长，故截断）。
 * tabular-nums 让日期数字等宽，多列并排时对齐更整齐。
 */
.usage-bar__label {
  width: 100%;
  margin-top: 8px;
  font-family: var(--usagechart-font-mono, 'DM Mono', monospace);
  font-size: 11px
;
  color: var(--usagechart-text-muted, #9a9590);
  font-variant-numeric: tabular-nums;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
