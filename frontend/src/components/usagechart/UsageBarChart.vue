<script setup lang="ts">
/**
 * UsageBarChart — 三级视图共用的唯一柱状图。
 *
 * <h2>为什么只有一个组件</h2>
 * 三级视图的数据本就同构 —— 单一柱子只是「只有一层的堆叠柱」：
 * 一级按主维度分层、二级按次维度分层、三级已到最细粒度故只有一层。
 * 既然只是层数不同，就不必准备两套结构与两个组件 ——
 * 统一之后纵轴、网格线、柱顶读数、tooltip、键盘可达性都只有一份实现，
 * 不会再出现「同一视觉概念在两处各写一份、值悄悄漂移」的问题。
 *
 * 更关键的是动效：层级切换时若换组件，柱子只能整批淡出再淡入；
 * 同一组件内换数据，则可按<strong>位置序号</strong>复用 DOM 节点
 * （见 {@link useStackMorph}），让柱子平滑长高 / 缩短，得到 morph 而非 fade。
 *
 * <h2>职责边界</h2>
 * 只负责渲染与交互事件，不请求数据、不感知业务维度：
 * 柱与段由 {@link StackBar} 给定，标签文案由数据层经 dimension 配置产出。
 */
import { computed, ref, watch } from 'vue'
import type { StackBar, StackSegment } from './usagechart'
import { AXIS_WIDTH, buildAxisTicks, COLUMN_GAP, COLUMN_PAD_X, COLUMN_PAD_Y, formatTickValue, VALUE_LABEL_SPACE } from './axisTicks'
import { buildAnimatedAxisTicks, useAxisScale } from './useAxisScale'
import { anchorFromCursor } from './tooltipAnchor'
import { useStackMorph, type MorphBar, type MorphSegment } from './useStackMorph'
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
  /**
   * 是否可返回上一级（一级已是顶层，应传 false）。
   *
   * 为 false 时右键不再拦截，交还浏览器默认菜单 —— 顶层没有「上一级」可去，
   * 屏蔽菜单却什么也不做只会让人以为页面卡住。
   */
  ascendable?: boolean
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
  /**
   * 纵轴标尺换算的动画时长（ms）。
   *
   * 与段高过渡同量级，使「柱子形变」与「刻度压缩」看起来是同一次转场，
   * 而不是两段各自为政的动画。
   */
  scaleDuration?: number
}>(), {
  maxTotal: 0,
  drillable: true,
  ascendable: false,
  drillHint: '查看',
  unit: '次',
  height: 180,
  segmentGap: 3,
  minSegmentHeight: 4,
  barWidth: 36,
  axisWidth: AXIS_WIDTH,
  scaleDuration: 420,
})

const emit = defineEmits<{
  (e: 'drill', key: string): void
  /**
   * 请求返回上一级。
   *
   * 图表只报告「用户想上浮」，不关心上一级是谁 —— 与 {@code drill} 对称，
   * 层级语义一律留在编排层。
   */
  (e: 'ascend'): void
}>()

const rootRef = ref<HTMLElement | null>(null)
const tooltip = ref({
  visible: false,
  left: 0,
  top: 0,
  alignEnd: false,
  below: false,
  title: '',
  summary: '',
  detail: [] as StackSegment['detail'],
})

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
  // 柱列的横向内边距与列间距。两者都按权重缩放（见 columnStyle），
  // 因此在这里只声明「满权重时的量」，实际值由每根柱子各自算出。
  '--usagechart-column-pad-x': `${COLUMN_PAD_X}px`,
  '--usagechart-column-gap': `${COLUMN_GAP}px`,
}))

/**
 * 单根柱列的内联样式。
 *
 * 横向的每一项都必须<strong>随权重一起归零</strong>，否则退场柱在
 * {@code flex-grow} 已趋近 0 时仍占着固定宽度，被移除的瞬间这部分空间才
 * 一次性释放，留存柱因此在动画收尾处「弹」一下。
 *
 * 具体有两笔固定开销：
 * <ul>
 *   <li><strong>左右 padding</strong> —— {@code flex-basis: 0} 只压内容盒，
 *       padding 不参与 flex 收缩，构成宽度地板；</li>
 *   <li><strong>列间距</strong> —— 退场柱作为 flex 项仍占一个 gap 位，
 *       所以 gap 不能交给容器的 {@code gap}，必须由柱子自己以外边距表达。</li>
 * </ul>
 *
 * 把两者都乘上权重后，退场柱的横向占位真正连续地收到 0，移除时无空间可释放。
 */
function columnStyle(item: MorphBar) {
  return {
    flexGrow: item.weight,
    paddingLeft: `${COLUMN_PAD_X * item.weight}px`,
    paddingRight: `${COLUMN_PAD_X * item.weight}px`,
    // 半个间距挂在每一侧，相邻两柱各出一半即得完整间距，
    // 且首尾柱只贡献半格，与容器 gap 的视觉效果一致。
    marginLeft: `${(COLUMN_GAP / 2) * item.weight}px`,
    marginRight: `${(COLUMN_GAP / 2) * item.weight}px`,
  }
}

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

/** 目标刻度序列。以取整后的上限为基准，使刻度读数是 10、20、50 这类整数。 */
const targetTicks = computed(() => buildAxisTicks(referenceMax.value))

/** 目标轴上限 —— 取整后的值，而非数据真实最大值。 */
const targetCeiling = computed(() => {
  const ticks = targetTicks.value
  return ticks.length ? ticks[ticks.length - 1].value : referenceMax.value
})

/**
 * 动画中的标尺（1 个单位数值对应的高度占比）。
 *
 * 上限跳变时它不会立刻到位，而是在 {@link scaleDuration} 内缓动过去。
 * 刻度与柱高都按它换算，于是刻度线在纵轴上「聚拢 / 散开」——
 * 即压缩与解压的观感。
 */
const { displayScale, rescaling, progress } = useAxisScale(targetCeiling, {
  duration: props.scaleDuration,
})

/**
 * 柱高换算用<strong>目标</strong>轴上限，而非动画中的标尺。
 *
 * 刻度的压缩 / 解压与柱体的形变是两个独立的视觉层：前者表达「量纲变了」，
 * 后者表达「数据变了」。若柱高也跟着动画标尺走，一次层级切换里柱子会被
 * 两股力量同时拉扯，落点难以预期。各用各的基准，两层便都保持单一职责。
 */
const heightBasis = targetCeiling

/**
 * 当前要渲染的刻度。
 *
 * 换算进行中时，同序号的旧/新刻度会同时插值数值与位置；没有配对项的
 * 旧刻度淡出、新刻度淡入。这样大区间和小区间互转时，不会再发生硬切换。
 */
const previousTicks = ref(targetTicks.value)

watch(targetTicks, (_next, previous) => {
  previousTicks.value = previous
})

const axisTicks = computed(() => {
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

/**
 * 统一形变模型 —— 按位置序号配对新旧两批柱子与堆叠层。
 *
 * 三级视图的数据本就同构（单一柱是「只有一层的堆叠柱」），故不区分
 * 「堆叠↔单一」：左侧公共前缀原地演化，右侧多出的柱子进出场；
 * 柱内底部公共层原地演化，顶部多出的层进出场。
 */
const { morphBars, morphFrom } = useStackMorph(computed(() => props.bars), heightBasis)

/** 一个堆叠层的占位尺寸。 */
interface SegmentBox {
  /** 占位总高（px），已含下方那道分隔间隙。 */
  height: number
  /** 与下方邻居之间的间隙（px）—— 由下外边距实现；最底层为 0。 */
  gap: number
}

/**
 * 计算一根柱子各层的盒高。
 *
 * <h2>间隙为何算在层高之内</h2>
 * 层间空隙曾用 flex 的 {@code gap} 实现，那是<strong>额外</strong>空间，
 * 不占任何层的高度配额，于是 N 层柱子会凭空长高 {@code (N-1) × gap}，
 * 柱顶明显高出对应的刻度线 —— 层越多、偏差越大，纵轴也就失去了可量化读出的意义。
 *
 * 改为让每层自带间隙、并从自己的高度配额里扣除后，各层占位之和恒等于
 * {@code (总量 / 轴上限) × 绘图区高}，柱顶因此严格落在刻度上。
 *
 * <h2>间隙归属于「上面那一层」</h2>
 * 间隙是「本层与下方邻居的分隔」，故最底下的可见层不带间隙 —— 它要贴住基线；
 * 最顶层也不需要上边框 —— 它上方没有邻居，加了反而让柱顶多出一段空白。
 *
 * 判定依据是「下方是否已有可见层」而非层序号：占比为 0 的层（含退场层）
 * 不占视觉位置，若按序号判断，第 0 层不可见时第 1 层会被当成非底层而带上间隙，
 * 柱底就浮起来了。
 *
 * <h2>兜底抬升要从最高层扣回</h2>
 * 占比极小的层会被 {@link minSegmentHeight} 抬高，这部分同样是纯附加高度。
 * 把抬升总量从最高的那一层扣回，柱顶才仍然对齐刻度：最高层承担几个像素的误差
 * （百余像素上不足 3%，肉眼不可察），远好过柱顶整体飘出刻度线。
 */
function computeSegmentBoxes(item: MorphBar): SegmentBox[] {
  const gap = props.segmentGap
  const minFill = props.minSegmentHeight

  /** 下方是否已出现可见层 —— 决定本层要不要带分隔间隙。 */
  let hasVisibleBelow = false
  /** 按比例应得的总高，即柱顶该落到的位置。 */
  let exactTotal = 0
  /** 因兜底抬升而多出的高度，稍后从最高层扣回。 */
  let excess = 0

  const boxes: SegmentBox[] = item.segments.map((segment) => {
    if (segment.ratio <= 0) return { height: 0, gap: 0 }

    const ownGap = hasVisibleBelow ? gap : 0
    hasVisibleBelow = true

    const exact = segment.ratio * props.height
    // 可见填充至少 minFill，故盒高至少是它加上自带的间隙
    const height = Math.max(minFill + ownGap, exact)
    exactTotal += exact
    excess += height - exact
    return { height, gap: ownGap }
  })

  if (excess <= 0 || exactTotal <= 0) return boxes

  // 从最高层扣回，且不让它跌破自己的下限（否则又会引入新的抬升）
  let tallest = -1
  boxes.forEach((box, index) => {
    if (box.height > 0 && (tallest < 0 || box.height > boxes[tallest].height)) tallest = index
  })
  if (tallest < 0) return boxes

  const floor = minFill + boxes[tallest].gap
  boxes[tallest].height = Math.max(floor, boxes[tallest].height - excess)
  return boxes
}

/**
 * 各柱的层高缓存。
 *
 * 兜底补偿要看整柱的层，无法逐层独立算出，故按柱算一次并缓存 ——
 * 模板里每层都要读，逐层重算会把一次 O(n) 变成 O(n²)。
 */
const segmentBoxes = computed(() => {
  const map = new Map<number, SegmentBox[]>()
  for (const item of morphBars.value) {
    map.set(item.slot, computeSegmentBoxes(item))
  }
  return map
})

/** 取某柱某层的占位尺寸；缺失时按零高度处理，避免模板里出现 undefined。 */
function boxOf(item: MorphBar, index: number): SegmentBox {
  return segmentBoxes.value.get(item.slot)?.[index] ?? { height: 0, gap: 0 }
}

/**
 * 一个堆叠层的内联样式。
 *
 * <h2>间隙为何是外边距，而不是透明下边框</h2>
 * 边框版本要靠 {@code background-clip: padding-box} 才能让透明边框真正「透」出间隙，
 * 由此带来两处视觉缺陷：
 *
 * <ul>
 *   <li>圆角按<strong>边框盒</strong>计算，而色块被裁到内盒，其底部圆角半径变成
 *       {@code 半径 − 边框宽}，间隙一存在，上层的底部圆角就被压平；</li>
 *   <li>{@code background} 简写会把 {@code background-clip} 重置回 {@code border-box}，
 *       任何只改底色的修饰类（如 other 段）都会静默失去间隙。</li>
 * </ul>
 *
 * 外边距区域无法被背景绘制，故上述两点都不复存在：元素回归纯色块，圆角原生生效。
 * 代价只是高度要自己扣一次 —— 元素高 = 占位高 − 间隙，间隙以外边距补回，
 * 两者之和仍等于占位高，柱顶与刻度的对应关系不变。
 */
function segmentStyle(item: MorphBar, index: number) {
  const box = boxOf(item, index)
  return {
    height: `${Math.max(0, box.height - box.gap)}px`,
    marginBottom: `${box.gap}px`,
  }
}

/**
 * 整柱的实际像素高度 —— 各层占位之和。
 *
 * 间隙已含在占位高内，故这里不再另加 —— 若再加一份，柱顶读数就会飘在真实柱顶上方。
 */
function morphBarHeight(item: MorphBar): number {
  const boxes = segmentBoxes.value.get(item.slot)
  if (!boxes) return 0
  return boxes.reduce((sum, box) => sum + box.height, 0)
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
 * 显示段的悬浮明细，浮框跟随光标。
 *
 * 定位取<strong>光标</strong>的容器内相对坐标，而非段元素中心：段可能很高
 * （占满整柱），锚在中心时浮框会离光标很远，看起来像在为别的元素服务。
 * 用相对坐标则页面滚动、卡片位移都不会让浮框漂走。
 */
function showSegmentTooltip(event: MouseEvent, segment: StackSegment) {
  const root = rootRef.value
  if (!root) return

  const anchor = anchorFromCursor(event, root)
  const ratioText = `${(segment.ratio * 100).toFixed(1)}%`

  tooltip.value = {
    visible: true,
    left: anchor.left,
    top: anchor.top,
    alignEnd: anchor.alignEnd,
    below: anchor.below,
    title: segment.label,
    summary: `${formatValue(segment.value)} ${props.unit} · ${ratioText}`,
    detail: segment.detail,
  }
}

/**
 * 光标在段内移动时更新浮框位置。
 *
 * 只改坐标不重算内容：段没变，标题与明细都是同一份，重建会让浮框内容闪烁。
 */
function trackSegmentTooltip(event: MouseEvent) {
  if (!tooltip.value.visible) return
  const root = rootRef.value
  if (!root) return

  const anchor = anchorFromCursor(event, root)
  tooltip.value.left = anchor.left
  tooltip.value.top = anchor.top
  tooltip.value.alignEnd = anchor.alignEnd
  tooltip.value.below = anchor.below
}

function hideTooltip() {
  tooltip.value.visible = false
}

/**
 * 点击整柱进入下一级。段上的点击同样冒泡到柱，故段与柱共用一个下钻入口。
 *
 * 退场柱要排除掉：它已不属于当前层级，只是为播放淡出而暂留，
 * 点它下钻会跳到一个刚被移除的分类上。
 *
 * 下钻前把被点柱的位置登记为形变锚点：下一屏的内容正是由它展开而来，
 * 因此它必须是留存下来的那根，使「点哪根 → 哪根留下」的因果可见。
 * 登记要早于 emit —— 上层换数据是同步的，晚一步锚点就赶不上这次配对。
 */
function drillInto(item: MorphBar) {
  if (!props.drillable || item.phase === 'leave') return
  hideTooltip()
  morphFrom(item.slot)
  emit('drill', item.bar.key)
}

/**
 * 右键返回上一级。
 *
 * 与左键下钻构成一对自然的手势：左键进、右键退，省去每次都要移到面包屑。
 * 绑在整个绘图区而非柱子上 —— 「返回」与具体某根柱子无关，
 * 空白处同样应当响应，否则用户得先瞄准一根柱子才能后退。
 *
 * 仅在可上浮时阻止默认菜单：顶层拦下右键却不做事，观感像是页面卡了。
 */
function ascend(event: MouseEvent) {
  if (!props.ascendable) return
  event.preventDefault()
  hideTooltip()
  emit('ascend')
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
 * 结构变化时只需收掉 tooltip，不再重播入场动画。
 *
 * 形变模型已保证每根柱子都有连续的起点（左侧公共前缀延续旧高度，
 * 新增柱从轴中位起），因此「归零重长」这一步既没必要也有害 ——
 * 它会把刚建立的连续性打断成一次闪烁。
 *
 * tooltip 必须清掉：它指向的段可能在新结构里已不存在，
 * 留着会悬停在错误的位置上显示过期数据。
 */
watch(structureKey, () => {
  hideTooltip()
})
</script>

<template>
  <div
    ref="rootRef"
    class="usage-bar"
    :class="{ 'usage-bar--ascendable': ascendable }"
    :style="rootStyle"
    @mouseleave="hideTooltip"
    @contextmenu="ascend"
  >
    <div class="usage-bar__body">
      <!-- 纵轴：刻度读数 + 与之对齐的网格线，让柱高可被量化读出 -->
      <div class="usage-bar__axis" aria-hidden="true">
        <span
          v-for="tick in axisTicks"
          :key="'axis-' + tick.key"
          class="usage-bar__tick"
          :style="{ bottom: `${tick.ratio * 100}%`, opacity: tick.opacity }"
        >
          {{ formatTickValue(Math.round(tick.value)) }}
        </span>
      </div>

      <div class="usage-bar__plot">
        <div class="usage-bar__grid" aria-hidden="true">
          <span
            v-for="tick in axisTicks"
              :key="'grid-' + tick.key"
            class="usage-bar__gridline"
            :class="{ 'usage-bar__gridline--base': tick.value === 0 }"
              :style="{ bottom: `${tick.ratio * 100}%`, opacity: tick.opacity }"
          />
        </div>

        <!--
          柱子按「位置序号」而非业务身份渲染。

          这是整套形变动效的支点：跨层级时柱子身份被整批替换（日期 → 供应商 → 模型），
          若用身份做 key，Vue 会判定「旧的全删、新的全建」，只能得到淡出淡入；
          用位置做 key 则第 N 根柱始终是同一个 DOM 节点，高度变化自然被
          CSS transition 捕捉成连续形变。

          flex-grow 由形变模型给出：进场柱从 0 涨到 1、退场柱收到 0。
          柱子的横向位置本就是 flex 分配的结果，让权重可过渡之后，
          「留存柱挤占空间」「多余柱被挤出去」都是宽度变化的副作用，
          无需再单独写位移动画。
        -->
        <div
          v-for="morph in morphBars"
          :key="morph.slot"
          class="usage-bar__column"
          :style="columnStyle(morph)"
          :class="{
            'usage-bar__column--drillable': drillable && morph.phase !== 'leave',
            'usage-bar__column--leaving': morph.phase === 'leave',
            'usage-bar__column--hidden': morph.weight === 0 && morph.phase === 'enter',
          }"
          :role="drillable && morph.phase !== 'leave' ? 'button' : undefined"
          :tabindex="drillable && morph.phase !== 'leave' ? 0 : undefined"
          :aria-hidden="morph.phase === 'leave' ? 'true' : undefined"
          :aria-label="barAriaLabel(morph.bar)"
          @click="drillInto(morph)"
          @keydown.enter.prevent="drillInto(morph)"
          @keydown.space.prevent="drillInto(morph)"
        >
          <!-- 柱体：自下而上堆叠，故用 column-reverse -->
          <div class="usage-bar__stack">
            <!--
              柱顶总量读数。位置由柱高驱动（bottom = 柱高），故跟着柱顶一起运动；
              aria-hidden 是因为柱的 aria-label 已含同一数字。
            -->
            <span
              v-if="morph.bar.total > 0 && morph.phase !== 'leave'"
              class="usage-bar__value"
              aria-hidden="true"
              :style="{ bottom: `${morphBarHeight(morph)}px` }"
            >
              {{ formatValue(morph.bar.total) }}
            </span>

            <!--
              堆叠层同样按位置序号渲染，底部对齐：
              公共底层复用节点平滑过渡，新增顶层从 0 膨胀，多余顶层收缩到 0。
            -->
            <div
              v-for="(seg, index) in morph.segments"
              :key="seg.slot"
              class="usage-bar__segment"
              :class="{ 'usage-bar__segment--other': seg.segment.isOther }"
              :style="segmentStyle(morph, index)"
              @mouseenter="seg.leaving ? null : showSegmentTooltip($event, seg.segment)"
              @mousemove="seg.leaving ? null : trackSegmentTooltip($event)"
              @mouseleave="hideTooltip"
            />
          </div>

          <!--
            分类标签。换文案时新旧两段短暂共存，交叉淡出淡入。

            新文案的 key 取文案本身：标签所在的 DOM 节点按 slot 复用，若不换 key，
            文字是被就地改写的，CSS 动画无从重放。换了 key 则 Vue 重建节点，
            淡入才会真正播放。
          -->
          <div class="usage-bar__label" :title="morph.bar.fullLabel">
            <span
              v-if="morph.outgoingLabel !== null"
              class="usage-bar__label-text usage-bar__label-text--out"
              aria-hidden="true"
            >{{ morph.outgoingLabel }}</span>
            <span
              :key="morph.bar.label"
              class="usage-bar__label-text"
              :class="{ 'usage-bar__label-text--in': morph.outgoingLabel !== null }"
            >{{ morph.bar.label }}</span>
          </div>
        </div>
      </div>
    </div>

    <UsageTooltip
      :visible="tooltip.visible"
      :left="tooltip.left"
      :top="tooltip.top"
      :align-end="tooltip.alignEnd"
      :below="tooltip.below"
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
 * 可上浮时把光标换成右键菜单指针，作为手势的一点视觉线索。
 * 只作用于柱子之外的空白区域 —— 柱子本身要保留 pointer 表示可下钻，
 * 两种手势的提示因此不会互相盖掉。
 */
.usage-bar--ascendable {
  cursor: context-menu;
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

/*
 * 绘图区。
 *
 * 不设 gap —— 列间距由柱子自己的左右外边距表达（见 columnStyle）。
 * 容器分配的 gap 无法随权重收缩：退场柱只要还在 DOM 里就占着一整个 gap 位，
 * 移除瞬间才释放，留存柱会因此在动画收尾处跳一下。
 */
.usage-bar__plot {
  position: relative;
  display: flex;
  flex: 1;
  align-items: flex-end;
  justify-content: space-around;
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

/*
 * 柱列。
 *
 * flex-grow 走内联样式（由形变模型给出），基准尺寸取 0 —— 这样柱子的宽度
 * 完全由权重决定，权重收到 0 时宽度真的归零，而不是被 flex-basis 撑出残留。
 * overflow 隐藏让内容随宽度一起被裁掉，柱体因此是「被挤扁」而非溢出到邻居身上。
 *
 * 横向 padding 与 margin 同样走内联样式并按权重缩放：它们不参与 flex 收缩，
 * 若固定成常量，退场柱在权重趋零时仍留着这几像素的宽度地板，
 * 被移除时才一次性释放，观感是留存柱在收尾处弹一下。纵向 padding 无此问题，
 * 故仍写在这里。
 */
.usage-bar__column {
  position: relative;
  /* 压在网格线之上，避免虚线穿过柱体 */
  z-index: 1;
  display: flex;
  flex-basis: 0;
  flex-shrink: 1;
  flex-direction: column;
  align-items: center;
  min-width: 0;
  padding-top: var(--usagechart-column-pad-y);
  padding-bottom: var(--usagechart-column-pad-y);
  border-radius: 8px;
  transition:
    flex-grow 0.42s cubic-bezier(0.4, 0, 0.2, 1),
    padding-left 0.42s cubic-bezier(0.4, 0, 0.2, 1),
    padding-right 0.42s cubic-bezier(0.4, 0, 0.2, 1),
    margin-left 0.42s cubic-bezier(0.4, 0, 0.2, 1),
    margin-right 0.42s cubic-bezier(0.4, 0, 0.2, 1),
    opacity 0.42s cubic-bezier(0.4, 0, 0.2, 1),
    background 0.18s ease;
}

.usage-bar__column--drillable {
  cursor: pointer;

  &:hover,
  &:focus-visible {
    background: var(--usagechart-accent-light, rgba(194, 122, 62, 0.08));
    outline: none;
  }
}

/*
 * 退场：淡出。
 *
 * 只做淡出，不做位移 —— 「向右滑走」的观感来自 flex-grow 收到 0 时
 * 留存柱挤占它的空间，那是宽度过渡的副作用，无需在这里重复表达。
 *
 * pointer-events 关掉，避免正在消失的柱子还能被点中触发下钻。
 */
.usage-bar__column--leaving {
  pointer-events: none;
  opacity: 0;
}

/*
 * 进场起始帧：宽度为 0 时同样透明。
 *
 * 与退场恰好互为镜像 —— 下一帧权重涨到 1、本类被摘掉，宽度与不透明度
 * 便沿同一条曲线一起长出来，观感是「从右侧挤入并显影」。
 */
.usage-bar__column--hidden {
  opacity: 0;
}

/* 尊重系统「减少动态效果」：直接落位，不播放宽度与淡入淡出过渡 */
@media (prefers-reduced-motion: reduce) {
  .usage-bar__column {
    transition-duration: 0.01s;
  }
}

/*
 * 柱体。
 *
 * 这里<strong>没有</strong> flex gap：层间空隙改由每层自带的下外边距实现，
 * 且该间隙已从这一层的高度配额里扣除。flex gap 是纯额外空间，不占任何层的配额，
 * N 层柱子会因此凭空长高 (N-1) × gap、柱顶明显高出对应刻度；
 * 改由各层自负后，占位之和恒等于按比例应得的总高，柱顶严格落在刻度线上。
 */
.usage-bar__stack {
  /* 作为柱顶读数的定位上下文 */
  position: relative;
  display: flex;
  /* 自下而上堆叠：值最大的段在最底部 */
  flex-direction: column-reverse;
  justify-content: flex-start;
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

/*
 * 堆叠层。
 *
 * 元素本身就是可见色块，层间空隙是它的下外边距（走内联样式，最底层为 0）；
 * 内联 height 已把间隙扣除，故 (height + margin) 才是这一层的占位高。
 * 详见 segmentStyle 的说明。
 */
.usage-bar__segment {
  width: 100%;
  /* 圆角 + 间隙即分段依据，与热力图格子风格统一 */
  border-radius: 4px;
  background: var(--usagechart-accent, #c27a3e);
  /*
   * 形变动画的实际执行者：高度由 useStackMorph 逐帧给到内联样式，
   * 这条过渡负责把每次取值变化补成连续运动（长高 / 收缩 / 归零消失）。
   *
   * 外边距一并过渡：某层变成 / 不再是最底层时（下方的层退场或进场），
   * 它的间隙会在 0 与 gap 之间切换，硬切会让柱底跳一下。
   */
  transition:
    height 0.42s cubic-bezier(0.4, 0, 0.2, 1),
    margin-bottom 0.42s cubic-bezier(0.4, 0, 0.2, 1),
    opacity 0.18s ease;

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
 *
 * 作为两段文案的定位上下文：换文案时新旧短暂共存，需要叠在同一处交叉淡化。
 * 行高显式给出而非由内容撑开 —— 两段文字都绝对定位后容器就没有内容高度了，
 * 不固定的话标签行会塌陷，柱状图整体高度随之抽动。
 */
.usage-bar__label {
  position: relative;
  width: 100%;
  height: 1.6em;
  margin-top: 8px;
  font-family: var(--usagechart-font-mono, 'DM Mono', monospace);
  font-size: 11px;
  color: var(--usagechart-text-muted, #9a9590);
  font-variant-numeric: tabular-nums;
  text-align: center;
}

/*
 * 一段文案。绝对定位使新旧两段落在同一处 —— 交叉淡化要求它们重叠，
 * 若按文档流排布会并列成两列，容器宽度也会随之跳变。
 */
.usage-bar__label-text {
  position: absolute;
  inset: 0;
  line-height: 1.6;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 被顶掉的旧文案淡出。
 *
 * forwards 保持终态：动画结束后它仍在 DOM 里（要等下一次形变推进配对基准
 * 才被移除），不保持的话会在动画末尾突然弹回完全不透明。
 */
.usage-bar__label-text--out {
  animation: usage-bar-label-out 0.42s ease forwards;
  pointer-events: none;
}

/* 新文案淡入。与旧文案同时长同曲线，两者的交叉才是对称的 */
.usage-bar__label-text--in {
  animation: usage-bar-label-in 0.42s ease;
}

@keyframes usage-bar-label-out {
  from {
    opacity: 1;
  }
  to {
    opacity: 0;
  }
}

@keyframes usage-bar-label-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/* 尊重系统「减少动态效果」：文案直接替换，不播放交叉淡化 */
@media (prefers-reduced-motion: reduce) {
  .usage-bar__label-text--out {
    animation-duration: 0.01s;
  }

  .usage-bar__label-text--in {
    animation-duration: 0.01s;
  }
}
</style>
