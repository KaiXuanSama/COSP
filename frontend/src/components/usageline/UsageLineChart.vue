<script setup lang="ts">
/**
 * UsageLineChart — token 用量折线图。
 *
 * <h2>职责边界</h2>
 * 只负责把已换算好的坐标画成线，不请求数据、不感知时间范围的业务含义。
 * 分桶、补零、跨夜日期归属等规则全在 {@link useTimelineSeries} 里，
 * 因此这里没有任何需要单测的分支。
 *
 * <h2>为什么用 SVG 而非 CSS</h2>
 * 折线是连续路径，CSS 只能拼接线段并逐段旋转，接缝处会有明显的锯齿与断口。
 * SVG 的 `polyline` 天然支持连接处圆滑（`stroke-linejoin`），且坐标用
 * `viewBox` 归一化后完全不必关心容器实际像素尺寸。
 *
 * <h2>为什么只画一条线</h2>
 * 输入 token 占总量的绝大部分，输出则贴着横轴 —— 三条线画出来是
 * 「总量与输入几乎重合、输出压成一条直线」，两条附加线都读不出独立走势，
 * 只是让图变脏。故图上只留总量表达趋势，输入与输出的绝对值由悬停浮框给出：
 * 需要看构成时精确可读，不需要时不占视觉带宽。
 *
 * <p>三者仍共用一个纵轴（总量恒等于输入加输出，同量纲），
 * 将来若某个系列值得单独画出，把 {@code SeriesConfig.drawn} 置为 true 即可。
 */
import { computed, ref, watch } from 'vue'
import { AXIS_WIDTH, COLUMN_PAD_Y, formatTickValue, VALUE_LABEL_SPACE } from '../usagechart/axisTicks'
import { useAxisScale, buildAnimatedAxisTicks } from '../usagechart/useAxisScale'
import { anchorFromCursor } from '../usagechart/tooltipAnchor'
import { toPolylinePoints, useTimelineSeries } from './useTimelineSeries'
import type { TimelineRange, UsageTimelinePoint } from './usageline'

const props = withDefaults(defineProps<{
  points: UsageTimelinePoint[]
  range: TimelineRange
  /** 绘图区高度（px），不含横轴标签。 */
  height?: number
  /** 纵轴刻度栏宽度（px）。与柱状图同值，两卡片的绘图区左边界才能对齐。 */
  axisWidth?: number
  /**
   * 纵轴标尺换算的动画时长（ms）。
   *
   * 与柱状图同值，使两处的「量纲变化」看起来是同一种运动。
   */
  scaleDuration?: number
}>(), {
  height: 180,
  axisWidth: AXIS_WIDTH,
  scaleDuration: 420,
})

/** viewBox 的逻辑尺寸。取值本身无意义，只用于把 0~1 的比例放大成整数坐标。 */
const VIEW_WIDTH = 1000
const VIEW_HEIGHT = 300

/** 当前悬停的点序号；null 表示未悬停。 */
const hoverIndex = ref<number | null>(null)

/**
 * 浮框的落点与展开方向 —— 跟随光标。
 *
 * 不锚在数据点上：折线上下起伏，锚在点上会让浮框随光标横移而忽上忽下；
 * 跟随光标则运动平稳，且始终在视线焦点附近。
 */
const anchor = ref({ left: 0, top: 0, alignEnd: false, below: false })

const pointsRef = computed(() => props.points)
const rangeRef = computed(() => props.range)

const { ceiling, axisTicks: targetTicks, series, axisLabels, dateSegments, drawableCount } =
  useTimelineSeries(pointsRef, rangeRef)

/**
 * 动画中的标尺。
 *
 * 切换时间范围时轴上限会剧变（一天的累计 vs 单个时段的量），若刻度硬切，
 * 使用者察觉不到量纲已变，会把新旧两屏的线高直接比较而误读趋势。
 * 与柱状图复用同一个 composable：刻度在纵轴上真实地聚拢或散开。
 */
const targetCeiling = computed(() => ceiling.value)
const { displayScale, rescaling, progress } = useAxisScale(targetCeiling, {
  duration: props.scaleDuration,
})

const previousTicks = ref(targetTicks.value)
watch(targetTicks, (_next, previous) => {
  previousTicks.value = previous
})

/** 当前要渲染的刻度：静止时用精确占比，换算中则按动画标尺实时投影。 */
const renderTicks = computed(() => {
  if (!rescaling.value) {
    return targetTicks.value.map((tick, index) => ({
      ...tick,
      key: `static-${index}`,
      opacity: 1,
    }))
  }
  return buildAnimatedAxisTicks(
    previousTicks.value,
    targetTicks.value,
    progress.value,
    displayScale.value,
  )
})

const rootStyle = computed(() => ({
  '--usage-line-plot-height': `${props.height}px`,
  '--usage-line-axis-width': `${props.axisWidth}px`,
  '--usage-line-pad-y': `${COLUMN_PAD_Y}px`,
  '--usage-line-value-space': `${VALUE_LABEL_SPACE}px`,
}))

/** 需要画成折线的系列的 SVG `points` 属性。输入与输出只在浮框里出现。 */
const polylines = computed(() =>
  series.value
    .filter((item) => item.config.drawn)
    .map((item) => ({
      key: item.config.key,
      color: item.config.color,
      dash: item.config.dash,
      points: toPolylinePoints(item.points, VIEW_WIDTH, VIEW_HEIGHT),
    })),
)

/** 悬停时的垂直参考线位置（占宽度的比例）。 */
const hoverX = computed(() => {
  if (hoverIndex.value === null) return null
  const first = series.value[0]
  return first?.points[hoverIndex.value]?.x ?? null
})

/**
 * 折线上的数据端点。
 *
 * 显式画出端点是为了让「哪里是一个采样点」可见 —— 只有折线时，
 * 平缓段落里根本看不出中间有几个点，也就无从判断相邻两点跨了多久。
 *
 * 默认空心（描边取线色、内部填卡片底色），悬停那一点转为实心，
 * 于是「当前正在读哪一点」不必依赖参考线也能看清。
 */
const seriesDots = computed(() =>
  series.value
    .filter((item) => item.config.drawn)
    .flatMap((item) =>
      item.points.map((point, index) => ({
        key: `${item.config.key}-${index}`,
        color: item.config.color,
        x: point.x,
        y: point.y,
        active: index === hoverIndex.value,
      })),
    ),
)

/** 悬停时段的三项数值，供浮框列出。未绘制的系列同样在列 —— 这是它们唯一的出场处。 */
const hoverRows = computed(() => {
  if (hoverIndex.value === null) return []
  return series.value.map((item) => ({
    key: item.config.key,
    label: item.config.label,
    color: item.config.color,
    dash: item.config.dash,
    value: item.points[hoverIndex.value as number]?.value ?? 0,
  }))
})

const hoverLabel = computed(() =>
  hoverIndex.value === null ? '' : props.points[hoverIndex.value]?.bucket ?? '',
)

/**
 * 依鼠标横向位置定位最近的点，并同步浮框落点。
 *
 * 折线上的点很细，要求精确命中会让 hover 极难触发；按横坐标就近吸附则
 * 只要鼠标在绘图区内横向移动，读数就连续跟随，这也是三值同显的前提。
 */
function updateHover(event: MouseEvent) {
  const plot = event.currentTarget as HTMLElement | null
  if (!plot || !drawableCount.value) return

  const rect = plot.getBoundingClientRect()
  const ratio = (event.clientX - rect.left) / rect.width
  const total = props.points.length
  const nearest = total === 1 ? 0 : Math.round(ratio * (total - 1))
  // 上界取「已发生的点数」而非全部点数：未来段读出来是一串 0，
  // 那是「还没发生」而非真实用量，悬停到那里只会误导
  hoverIndex.value = Math.max(0, Math.min(drawableCount.value - 1, nearest))

  // 基准取绘图区而非组件根节点：浮框是绘图区的绝对定位子元素，
  // 用根节点算会多算出一段顶部内边距，浮框整体偏下。
  anchor.value = anchorFromCursor(event, plot, { edgeMargin: 150, topMargin: 100 })
}

function clearHover() {
  hoverIndex.value = null
}

/** 大数用千分位；token 量级动辄数万，不分组几乎无法读。 */
function formatValue(value: number): string {
  return value.toLocaleString('zh-CN')
}
</script>

<template>
  <div class="usage-line" :style="rootStyle">
    <div class="usage-line__body">
      <!-- 纵轴：刻度读数，与网格线对齐 -->
      <div class="usage-line__axis" aria-hidden="true">
        <span
          v-for="tick in renderTicks"
          :key="'axis-' + tick.key"
          class="usage-line__tick"
          :style="{ bottom: `${tick.ratio * 100}%`, opacity: tick.opacity }"
        >
          {{ formatTickValue(Math.round(tick.value)) }}
        </span>
      </div>

      <div class="usage-line__main">
        <div
          class="usage-line__plot"
          @mousemove="updateHover"
          @mouseleave="clearHover"
        >
          <!-- 网格线，置于折线之下 -->
          <div class="usage-line__grid" aria-hidden="true">
            <span
              v-for="tick in renderTicks"
              :key="'grid-' + tick.key"
              class="usage-line__gridline"
              :class="{ 'usage-line__gridline--base': tick.value === 0 }"
              :style="{ bottom: `${tick.ratio * 100}%`, opacity: tick.opacity }"
            />
          </div>

          <!-- 悬停参考线：贯穿绘图区，标出正在读的是哪个时段 -->
          <span
            v-if="hoverX !== null"
            class="usage-line__cursor"
            aria-hidden="true"
            :style="{ left: `${hoverX * 100}%` }"
          />

          <!--
            折线层。viewBox 把 0~1 的比例放大成整数坐标，
            preserveAspectRatio="none" 让它随容器自由拉伸 ——
            线宽用 vector-effect 保持不变，否则横向拉伸会把线压扁。
          -->
          <svg
            class="usage-line__svg"
            :viewBox="`0 0 ${VIEW_WIDTH} ${VIEW_HEIGHT}`"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <polyline
              v-for="line in polylines"
              :key="line.key"
              class="usage-line__path"
              :points="line.points"
              :stroke="line.color"
              :stroke-dasharray="line.dash"
            />
          </svg>

          <!--
            数据端点。用 div 而非 SVG 圆：viewBox 被非等比拉伸，
            画在里面的圆会跟着变成椭圆。

            默认空心、悬停那一点转实心，故「当前读的是哪一点」有独立于参考线的提示。
          -->
          <span
            v-for="dot in seriesDots"
            :key="'dot-' + dot.key"
            class="usage-line__dot"
            :class="{ 'usage-line__dot--active': dot.active }"
            aria-hidden="true"
            :style="{
              left: `${dot.x * 100}%`,
              bottom: `${dot.y * 100}%`,
              borderColor: dot.color,
              '--usage-line-dot-fill': dot.color,
            }"
          />

          <!--
            悬停明细浮框，跟随光标。图上只有总量一条线，输入与输出的绝对值全靠这里给出，
            故它不是可选的补充说明，而是构成信息的唯一出口。
          -->
          <div
            v-if="hoverRows.length"
            class="usage-line__tooltip"
            :class="{
              'usage-line__tooltip--end': anchor.alignEnd,
              'usage-line__tooltip--below': anchor.below,
            }"
            aria-hidden="true"
            :style="{ left: `${anchor.left}px`, top: `${anchor.top}px` }"
          >
            <div class="usage-line__tooltip-head">{{ hoverLabel }}</div>
            <div v-for="row in hoverRows" :key="row.key" class="usage-line__tooltip-row">
              <svg class="usage-line__tooltip-mark" viewBox="0 0 14 8" aria-hidden="true">
                <line
                  x1="0" y1="4" x2="14" y2="4"
                  :stroke="row.color"
                  :stroke-dasharray="row.dash"
                  stroke-width="2"
                />
              </svg>
              <span class="usage-line__tooltip-label">{{ row.label }}</span>
              <span class="usage-line__tooltip-value">{{ formatValue(row.value) }}</span>
            </div>
          </div>
        </div>

        <!-- 横轴第一行：时刻或日期 -->
        <div class="usage-line__labels" aria-hidden="true">
          <span
            v-for="label in axisLabels"
            :key="label.key"
            class="usage-line__label"
            :class="{
              'usage-line__label--hidden': !label.visible,
              'usage-line__label--future': label.future,
            }"
            :style="{ left: `${label.x * 100}%` }"
          >
            {{ label.text }}
          </span>
        </div>

        <!--
          横轴第二行：日期分段，仅今日时段范围有。
          行高恒定保留，避免切换范围时卡片高度跳动。
        -->
        <div class="usage-line__dates" aria-hidden="true">
          <span
            v-for="segment in dateSegments"
            :key="segment.label"
            class="usage-line__date"
            :style="{ left: `${segment.start * 100}%`, width: `${segment.width * 100}%` }"
          >
            {{ segment.label }}
          </span>

          <!--
            分段之间的短竖线。
            两个日期文字之间若只有空白，读起来像两个并列标签；
            加一道分隔线，「各自管辖一段区间」的意思才明确。
          -->
          <span
            v-for="segment in dateSegments.slice(1)"
            :key="'divider-' + segment.label"
            class="usage-line__date-divider"
            :style="{ left: `${segment.start * 100}%` }"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.usage-line {
  position: relative;
  width: 100%;
}

/*
 * 轴区与绘图区并排。padding-top 与柱状图取同一常量，
 * 使两张卡片的绘图区上边界处于同一水平线。
 */
.usage-line__body {
  display: flex;
  align-items: stretch;
  gap: 8px;
  padding-top: var(--usage-line-value-space);
}

.usage-line__axis {
  position: relative;
  flex: 0 0 auto;
  width: var(--usage-line-axis-width, 36px);
  height: var(--usage-line-plot-height);
  margin-top: var(--usage-line-pad-y);
}

.usage-line__tick {
  position: absolute;
  right: 0;
  /* 上移半个行高，使读数中线压在刻度线上 */
  transform: translateY(50%);
  font-family: var(--usage-line-font-mono, 'DM Mono', monospace);
  font-size: 10px;
  line-height: 1;
  color: var(--usage-line-text-muted, #9a9590);
  white-space: nowrap;
}

.usage-line__main {
  position: relative;
  flex: 1;
  min-width: 0;
}

.usage-line__plot {
  position: relative;
  height: var(--usage-line-plot-height);
  margin-top: var(--usage-line-pad-y);
}

.usage-line__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.usage-line__gridline {
  position: absolute;
  right: 0;
  left: 0;
  border-top: 1px dashed var(--usage-line-gridline, rgba(154, 149, 144, 0.22));
}

/* 基线（0 刻度）用实线，作为折线的落脚参考 */
.usage-line__gridline--base {
  border-top-style: solid;
  border-top-color: var(--usage-line-gridline-base, rgba(154, 149, 144, 0.4));
}

/* 悬停参考线：细实线，比网格线略重以便与之区分 */
.usage-line__cursor {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 1px;
  background: var(--usage-line-accent-mid, rgba(194, 122, 62, 0.35));
  pointer-events: none;
}

.usage-line__svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
}

/*
 * vector-effect 让线宽不随 viewBox 的非等比拉伸而变形 ——
 * preserveAspectRatio="none" 会横向拉伸坐标系，不加这条线会被压成扁带。
 */
.usage-line__path {
  fill: none;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
}

.usage-line__labels,
.usage-line__dates {
  position: relative;
  height: 18px;
  margin-top: 6px;
}

.usage-line__label {
  position: absolute;
  transform: translateX(-50%);
  font-family: var(--usage-line-font-mono, 'DM Mono', monospace);
  font-size: 10px;
  line-height: 1;
  color: var(--usage-line-text-muted, #9a9590);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* 隐藏而非移除：保留元素使标签位置在稀疏度变化时保持稳定 */
.usage-line__label--hidden {
  visibility: hidden;
}

/*
 * 未来时段的标签淡化。
 *
 * 保留而非移除：它们标出「今天还剩多少时间」，正是完整显示一天的意义所在。
 * 但淡化能让「折线止于此处是因为还没发生」这件事不言自明。
 */
.usage-line__label--future {
  opacity: 0.42;
}

/*
 * 日期分段：整段居中显示，明确"这一段时刻属于哪一天"。
 * 即使当前范围没有分段，本行仍占位，避免切换时卡片高度跳动。
 */
.usage-line__dates {
  height: 16px;
  margin-top: 2px;
}

.usage-line__date {
  position: absolute;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--usage-line-font-mono, 'DM Mono', monospace);
  font-size: 10px;
  line-height: 1;
  color: var(--usage-line-text-muted, #9a9590);
  font-variant-numeric: tabular-nums;
  opacity: 0.75;
}

/*
 * 分段之间的短竖线 —— 标出两个日期各自管辖的区间边界。
 *
 * 只在段与段之间出现（首段左侧不画），因此渲染时从第二段起遍历。
 * 高度取满行、颜色比日期文字更淡：它是分隔符而非内容，不该抢注意力。
 */
.usage-line__date-divider {
  position: absolute;
  top: 2px;
  bottom: 2px;
  width: 1px;
  background: var(--usage-line-text-muted, #9a9590);
  opacity: 0.32;
}

/*
 * 悬停读数浮框，跟随光标。
 *
 * 与柱状图的 tooltip 同一套视觉语言（深底、圆角、等宽字体），但结构不同 ——
 * 那个是为「占比明细」设计的单/双层列表，这里要并列三条线的绝对值。
 *
 * 不锚在数据点上：折线上下起伏，锚在点上会让浮框随光标横移而忽上忽下；
 * 跟随光标则运动平稳。pointer-events 关掉，避免浮框抢走鼠标造成 hover 闪烁。
 */
.usage-line__tooltip {
  position: absolute;
  z-index: 20;
  /* 两个方向拆成独立变量：贴边与顶格是两个互不相干的决策 */
  transform: translate(var(--usage-line-tip-x, -50%), var(--usage-line-tip-y, calc(-100% - 14px)));
  min-width: 132px;
  padding: 7px 9px;
  border-radius: 8px;
  background: var(--usage-line-tooltip-bg, #1a1917);
  color: var(--usage-line-tooltip-text, #f5f3ee);
  font-family: var(--usage-line-font-mono, 'DM Mono', monospace);
  font-size: 11px;
  line-height: 1.5;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.18);
  pointer-events: none;
  white-space: nowrap;
}

/* 光标靠近右缘：右缘对齐光标，向左展开 */
.usage-line__tooltip--end {
  --usage-line-tip-x: calc(-100% + 14px);
}

/* 光标靠近上缘：翻到光标下方 */
.usage-line__tooltip--below {
  --usage-line-tip-y: 14px;
}

.usage-line__tooltip-head {
  padding-bottom: 4px;
  margin-bottom: 4px;
  border-bottom: 1px solid rgba(245, 243, 238, 0.18);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.usage-line__tooltip-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.usage-line__tooltip-mark {
  flex: 0 0 auto;
  width: 14px;
  height: 8px;
}

/* 标签占满中间空隙，把数值推到右端对齐，三行的数字因此上下成列 */
.usage-line__tooltip-label {
  flex: 1;
  opacity: 0.72;
}

.usage-line__tooltip-value {
  font-variant-numeric: tabular-nums;
}

/*
 * 数据端点。用 div 而非 SVG 圆：viewBox 被 preserveAspectRatio="none"
 * 非等比拉伸，画在其中的圆会变成椭圆。
 *
 * 空心（填卡片底色）使端点在折线密集处仍能与线身区分；
 * 描边色走内联样式，从系列配置取，故与线色永远一致。
 */
.usage-line__dot {
  position: absolute;
  width: 6px;
  height: 6px;
  border: 1.5px solid;
  border-radius: 50%;
  background: var(--usage-line-surface, #fff);
  transform: translate(-50%, 50%);
  pointer-events: none;
  transition: width 0.15s ease, height 0.15s ease, background 0.15s ease;
}

/*
 * 悬停中的端点转为实心并略微放大。
 *
 * 填充色取线色本身（由内联样式注入），与空心态形成明确对比 ——
 * 只靠尺寸变化在 6px 量级上几乎看不出来。
 */
.usage-line__dot--active {
  width: 8px;
  height: 8px;
  background: var(--usage-line-dot-fill, var(--usage-line-accent, #c27a3e));
}
</style>
