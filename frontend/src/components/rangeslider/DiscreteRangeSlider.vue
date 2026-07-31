<script setup lang="ts">
/**
 * DiscreteRangeSlider — 离散位置上的范围选择滑块。
 *
 * <h2>形态</h2>
 * 一条胶囊形背景槽（`(===)`），内部均匀分布着可选位置的小点；
 * 一块略小的胶囊形范围块盖在其上，两端各以一个离散点为圆心。
 * 三种手势：拖左端、拖右端、拖整块。
 *
 * <h2>为什么不是标准日期选择器</h2>
 * 这个控件要解决的是「在一条固定长度的时间轴上挪动一个宽度受限的窗口」。
 * 标准选择器让人逐个填两个日期，而这两个日期<strong>相互约束</strong>（跨度有上下限），
 * 填完还得被纠正。滑块把约束变成物理限制 —— 拖到头就顶住，
 * 不存在「填了非法值再被弹回」的过程。
 *
 * <h2>通用性边界</h2>
 * 本组件只认<strong>下标</strong>，不认日期。刻度由调用方给出、语义由调用方解释，
 * 因此同样能用于版本、档位、章节。选择逻辑在 `./rangeslider`，
 * 拖动手势在 `./useRangeDrag`，两者都不涉及业务语义。
 *
 * <h2>受控组件</h2>
 * 拖动只 emit 期望值，自身不改 `props.modelValue`。父组件可以拒绝或修正某次变化，
 * 而不会与内部状态打架。代价是父组件必须接 `v-model`，否则拖不动 ——
 * 换来的是状态只有一份。
 */
import { computed, onScopeDispose, ref, watch } from 'vue'
import {
  normalizeSelection,
  resolveBounds,
  selectionEquals,
  spanOf,
  tickRatio,
  type RangeSelection,
  type RangeSliderLabelMode,
  type RangeSliderTick,
} from './rangeslider'
import { useRangeDrag } from './useRangeDrag'

/**
 * 范围块两端超出端点离散点的距离（px），即胶囊端头的半径。
 *
 * 与 SCSS 里的 `$cap-radius` 必须一致 —— 这里用它拼 `calc()` 表达式，
 * 那里用它给内层轨留出等宽的两侧余量。两处不一致会让范围块在轨道两端被裁掉一角。
 * 不做成 CSS 变量的原因：它同时参与 JS 的 calc 拼接与 SCSS 的静态布局，
 * CSS 变量只能解决后者。
 */
const CAP_RADIUS = 14

const props = withDefaults(
  defineProps<{
    /** 可选位置，顺序即轨道上的从左到右。 */
    ticks: RangeSliderTick[]
    /** 当前选择，闭区间下标。 */
    modelValue: RangeSelection
    /** 跨度下限（含），默认 1。 */
    minSpan?: number
    /** 跨度上限（含），默认不限（等于刻度数）。 */
    maxSpan?: number
    /** 禁用全部交互。 */
    disabled?: boolean
    /**
     * 刻度文案的显示方式，默认 `edges`（只在两个手柄下方显示端点文案）。
     *
     * 三种取值的取舍见 {@link RangeSliderLabelMode}。
     */
    labelMode?: RangeSliderLabelMode
    /** 槽高度（px）。范围块与手柄都按它派生尺寸。 */
    trackHeight?: number
    /** 无障碍标签，落在整块手柄上。 */
    ariaLabel?: string
    /**
     * 把一次选择读成人话，用于 `aria-valuetext`。
     *
     * 屏幕阅读器念下标毫无意义（「2 到 8」）；调用方知道下标代表什么，
     * 由它给出「7 月 24 日至 7 月 30 日，共 7 天」这类文案。
     */
    formatValueText?: (selection: RangeSelection) => string
    /**
     * 端点文案的格式化器，仅 `labelMode === 'edges'` 时生效。
     *
     * <p>缺省取 `tick.label ?? tick.key` —— 但那个 `label` 是为 `all` 模式设计的，
     * 通常只给隔位标注的少数刻度，端点落在未标注的位置上就没有文案可显示。
     * 故此处允许单独给出：刻度是日期时可以返回 `7/26`，是版本号时返回 `v2.1`，
     * 组件本身对刻度语义没有任何假设。
     *
     * @param tick 端点对应的刻度
     * @param index 该刻度的下标
     * @param edge 这是起点还是终点 —— 两端需要不同措辞时用得上（如「自 / 至」）
     */
    formatEdgeLabel?: (tick: RangeSliderTick, index: number, edge: 'start' | 'end') => string
  }>(),
  {
    minSpan: 1,
    maxSpan: undefined,
    disabled: false,
    labelMode: 'edges',
    trackHeight: 34,
    ariaLabel: '范围选择',
    formatValueText: undefined,
    formatEdgeLabel: undefined,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: RangeSelection): void
  /**
   * 一次拖动或键盘操作<strong>结束</strong>时触发，携带最终选择。
   *
   * 与 `update:modelValue` 分开：后者在拖动途中会连续触发数十次，
   * 拿它去发请求会让一次拖动打出几十个请求。本事件只在松手时发一次。
   */
  (e: 'change', value: RangeSelection): void
}>()

/**
 * 定位参照系 —— 离散点实际分布的那个盒子。
 *
 * 不用最外层轨道：轨道两侧要留出 {@link CAP_RADIUS} 的余量给胶囊端头，
 * 而绝对定位的百分比偏移是相对 <strong>padding 盒</strong>算的，
 * 给轨道加 padding 并不会让点位内缩。故另起一个内层盒子，
 * 它同时是 useRangeDrag 折算指针坐标的参照 —— 两者必须是同一个盒子。
 */
const railRef = ref<HTMLElement | null>(null)

const bounds = computed(() => resolveBounds(props.ticks.length, props.minSpan, props.maxSpan))

/**
 * 净化后的当前选择。
 *
 * <p>始终经 {@link normalizeSelection} 过一遍而非直接用 `props.modelValue`：
 * 刻度数量变化后旧选择可能越界（窗口从 15 格缩到 7 格），
 * 直接参与百分比计算会把范围块画到轨道外面。
 */
const selection = computed(() => normalizeSelection(props.modelValue, bounds.value))

const span = computed(() => spanOf(selection.value))

/** 交互是否可用。只有一个刻度时无从选择，等同禁用。 */
const interactive = computed(() => !props.disabled && bounds.value.count > 1)

const { dragging, start, move, end, handleKey } = useRangeDrag({
  trackRef: railRef,
  bounds: () => bounds.value,
  selection: () => selection.value,
  onChange: (next) => {
    if (selectionEquals(next, selection.value)) return
    emit('update:modelValue', next)
  },
})

const rootStyle = computed(() => ({
  '--rangeslider-track-height': `${props.trackHeight}px`,
}))

/**
 * 范围块的位置与宽度。
 *
 * <h2>为什么要向外撑出一个端头半径</h2>
 * 直接用 `left: startRatio%` 会让块的<strong>左边缘</strong>压在起点上，
 * 而视觉预期是起点那个离散点落在块端头的<strong>圆心</strong>。
 * 故两端各外扩 {@link CAP_RADIUS}：左边缘退到起点左侧一个半径处、
 * 右边缘伸到终点右侧一个半径处，两端的半圆便正好以对应的点为圆心。
 *
 * <p>起点与终点重合时（跨度 1）宽度为 `0% + 2r`，得到一个正圆 ——
 * 这不是特例，是同一式子的自然结果。
 *
 * <p>用百分比而非像素：容器宽度随窗口变化，百分比让布局无需监听 resize。
 */
const rangeStyle = computed(() => {
  const { count } = bounds.value
  const startPercent = tickRatio(selection.value.start, count) * 100
  const endPercent = tickRatio(selection.value.end, count) * 100
  return {
    left: `calc(${startPercent}% - ${CAP_RADIUS}px)`,
    width: `calc(${endPercent - startPercent}% + ${CAP_RADIUS * 2}px)`,
  }
})

/** 单个离散点的定位。 */
function tickStyle(index: number) {
  return { left: `${tickRatio(index, bounds.value.count) * 100}%` }
}

/** 某个刻度是否落在选中区间内 —— 决定点的高亮态。 */
function isSelected(index: number): boolean {
  return index >= selection.value.start && index <= selection.value.end
}

/** 某个刻度是否是区间端点 —— 端点的点被手柄盖住，需要更强的对比。 */
function isEdge(index: number): boolean {
  return index === selection.value.start || index === selection.value.end
}

const valueText = computed(() =>
  props.formatValueText
    ? props.formatValueText(selection.value)
    : `${selection.value.start + 1} 至 ${selection.value.end + 1}，共 ${span.value} 项`,
)

/**
 * 端点文案。
 *
 * <p>缺省链是 `formatEdgeLabel` → `tick.label` → `tick.key`：
 * `label` 是为 `all` 模式设计的，通常只给隔位标注的少数刻度，端点落在未标注的
 * 位置上就没有文案；再退到 `key` 至少不会显示空白（key 常常本身就是日期串）。
 */
function edgeLabelOf(edge: 'start' | 'end'): string {
  const index = edge === 'start' ? selection.value.start : selection.value.end
  const tick = props.ticks[index]
  if (!tick) return ''
  if (props.formatEdgeLabel) return props.formatEdgeLabel(tick, index, edge)
  return tick.label ?? tick.key
}

const startEdgeLabel = computed(() => edgeLabelOf('start'))
const endEdgeLabel = computed(() => edgeLabelOf('end'))

/**
 * 被顶掉的旧端点文案，用于交叉淡化；无变化时为 null。
 *
 * <p>文案是就地改写的文字，中间没有可过渡的量 —— 直接换掉会硬切。
 * 故把旧文案一并留在 DOM 里与新文案重叠，各播一次淡出 / 淡入。
 * 这与柱状图底部标签的做法一致（`UsageBarChart` 的 `outgoingLabel`）。
 */
const outgoingStartLabel = ref<string | null>(null)
const outgoingEndLabel = ref<string | null>(null)

/**
 * 旧文案的存活时长（ms），须与 CSS 里淡出动画的时长一致。
 *
 * <p>比位移时长（{@link https 见 $slide-duration}）略长：位移与淡化同时开始，
 * 若淡化更快，文字会在还没到位时就已换完，看起来像「先变字再移动」。
 */
const LABEL_FADE_DURATION = 220

let startFadeTimer: ReturnType<typeof setTimeout> | null = null
let endFadeTimer: ReturnType<typeof setTimeout> | null = null

/**
 * 登记一次文案更替。
 *
 * <h2>拖动期间不做淡化</h2>
 * 交叉淡化要解决的是「一次<strong>离散</strong>的变化太生硬」。但快速拖动时
 * 文案变化不是离散事件，而是一条连续的流：跨格间隔可能只有几十毫秒，
 * 而淡化要 {@link LABEL_FADE_DURATION} 毫秒，于是同时有五六段文案各自在淡化途中，
 * 文字全程没有一刻是完全不透明的 —— 观感是持续发白。
 *
 * <p>缩短时长治不了根：只要淡化时长大于跨格间隔就会重叠，而间隔取决于用户手速。
 * 按「距上次变化超过 N 毫秒才淡化」判定也不好 —— 同一个手势里时而柔化时而硬切，
 * 比统一硬切更没规律。
 *
 * <p>拖动时用户看的是手柄位置而非在读数字，硬切反而把「现在是哪个值」给得更清楚。
 * 松手后与键盘一步一格的场合，变化确实是离散的，那里的柔化才有价值。
 *
 * <p>用 `dragging` 而非时间阈值判定：松手那一刻不会再有文案变化，
 * 故拖动全程硬切、结束时也不会补一次多余的淡化。
 *
 * <p>定时器要先清掉再重设：连续按方向键时，若沿用上一次的定时器，
 * 最后一段旧文案会被提前摘掉、淡出动画中途断掉。
 */
function scheduleLabelFade(edge: 'start' | 'end', previous: string) {
  if (dragging.value !== null) return
  if (edge === 'start') {
    outgoingStartLabel.value = previous
    if (startFadeTimer !== null) clearTimeout(startFadeTimer)
    startFadeTimer = setTimeout(() => {
      outgoingStartLabel.value = null
      startFadeTimer = null
    }, LABEL_FADE_DURATION)
    return
  }
  outgoingEndLabel.value = previous
  if (endFadeTimer !== null) clearTimeout(endFadeTimer)
  endFadeTimer = setTimeout(() => {
    outgoingEndLabel.value = null
    endFadeTimer = null
  }, LABEL_FADE_DURATION)
}

// 只在文案「确实变了」时才登记 —— 端点在相邻刻度间移动时文案常常相同
// （如 all 模式下未标注的位置），无谓地播一次淡化会让文字闪一下。
watch(startEdgeLabel, (next, previous) => {
  if (previous === undefined || previous === next) return
  scheduleLabelFade('start', previous)
})

watch(endEdgeLabel, (next, previous) => {
  if (previous === undefined || previous === next) return
  scheduleLabelFade('end', previous)
})

onScopeDispose(() => {
  if (startFadeTimer !== null) clearTimeout(startFadeTimer)
  if (endFadeTimer !== null) clearTimeout(endFadeTimer)
})

/**
 * 两个端点文案是否会互相压到。
 *
 * <p>跨度小到一定程度时两段文字会重叠成一团。此时只显示起点那一个 ——
 * 跨度已由手柄间距直观表达，两个几乎相同的日期挤在一起反而更难读。
 *
 * <p>阈值按<strong>刻度间距</strong>而非像素判断：文案宽度渲染前不可知，
 * 测量要等一帧、期间会以重叠状态闪现一下。用「相隔几格」近似，代价是
 * 阈值需按文案大致宽度设定（当前按 `M/D` 这类短文案取 2 格）。
 */
const edgeLabelsCollide = computed(() => selection.value.end - selection.value.start < 2)

function onPointerDown(target: 'start' | 'end' | 'range', event: PointerEvent) {
  if (!interactive.value) return
  // 阻止默认行为：拖动期间浏览器可能发起文本选中或原生拖拽，两者都会打断手势。
  event.preventDefault()
  start(target, event)
}

function onPointerMove(event: PointerEvent) {
  if (!interactive.value) return
  move(event)
}

/** 松手时补发一次 change —— 拖动途中只更新 v-model，副作用留到这一刻。 */
function onPointerUp(event: PointerEvent) {
  if (!dragging.value) return
  end(event)
  emit('change', selection.value)
}

function onKeydown(target: 'start' | 'end' | 'range', event: KeyboardEvent) {
  if (!interactive.value) return
  if (!handleKey(target, event)) return
  // 消费掉方向键，避免同时滚动页面。
  event.preventDefault()
  // 键盘每次按下都是一次完整操作，故直接补 change。连续按住由浏览器重复触发，
  // 每次各算一次 —— 与拖动不同，这里没有「途中」概念。
  emit('change', selection.value)
}
</script>

<template>
  <div
    class="rangeslider"
    :class="{ 'rangeslider--disabled': !interactive }"
    :style="rootStyle"
  >
    <!--
      轨道即背景槽：`(===)` 的那个胶囊，同时承接拖动过程中的指针事件。

      pointermove / pointerup 挂在这里而非各手柄上：指针捕获会把事件送回
      按下时的那个元素，而端点手柄很窄，拖动时指针早已离开它 ——
      挂在共同祖先上，无论捕获是否生效事件都能到达。
    -->
    <div
      class="rangeslider__track"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
    >
      <!--
        内层轨：离散点与范围块的定位参照，两侧比背景槽各内缩一个端头半径。

        必须是独立元素而不能靠给轨道加 padding —— 绝对定位的百分比偏移
        相对 padding 盒计算，加 padding 并不会让点位内缩。
      -->
      <div ref="railRef" class="rangeslider__rail">
        <!-- 离散点：均匀分布、垂直居中 -->
        <span
          v-for="(tick, index) in props.ticks"
          :key="tick.key"
          class="rangeslider__dot"
          :class="{
            'rangeslider__dot--selected': isSelected(index),
            'rangeslider__dot--edge': isEdge(index),
          }"
          :style="tickStyle(index)"
          aria-hidden="true"
        />

        <!--
          范围块。整块可拖，两端各有一个手柄。

          role="slider" 落在整块上：它的语义是「当前选中的范围」，
          aria-valuetext 描述的正是它。两个端点各自也是 slider，
          三者都能被 Tab 到并用方向键操作 —— 键盘语义与鼠标手势一一对应。
        -->
        <div
          class="rangeslider__range"
          :class="{ 'rangeslider__range--dragging': dragging === 'range' }"
          :style="rangeStyle"
          role="slider"
          :tabindex="interactive ? 0 : -1"
          :aria-label="props.ariaLabel"
          :aria-valuemin="0"
          :aria-valuemax="Math.max(0, bounds.count - 1)"
          :aria-valuenow="selection.start"
          :aria-valuetext="valueText"
          :aria-disabled="!interactive || undefined"
          @pointerdown="onPointerDown('range', $event)"
          @keydown="onKeydown('range', $event)"
        >
          <!--
            端点手柄。stop 修饰符是必需的：不阻止冒泡的话，按在端点上会同时
            触发整块的 pointerdown，后者随即把 target 改成 'range'，
            于是拖端点变成了拖整块。
          -->
          <button
            type="button"
            class="rangeslider__handle rangeslider__handle--start"
            :class="{ 'rangeslider__handle--dragging': dragging === 'start' }"
            :tabindex="interactive ? 0 : -1"
            :disabled="!interactive"
            role="slider"
            aria-label="起始位置"
            :aria-valuemin="0"
            :aria-valuemax="Math.max(0, bounds.count - 1)"
            :aria-valuenow="selection.start"
            :aria-valuetext="valueText"
            @pointerdown.stop="onPointerDown('start', $event)"
            @keydown.stop="onKeydown('start', $event)"
          />
          <button
            type="button"
            class="rangeslider__handle rangeslider__handle--end"
            :class="{ 'rangeslider__handle--dragging': dragging === 'end' }"
            :tabindex="interactive ? 0 : -1"
            :disabled="!interactive"
            role="slider"
            aria-label="结束位置"
            :aria-valuemin="0"
            :aria-valuemax="Math.max(0, bounds.count - 1)"
            :aria-valuenow="selection.end"
            :aria-valuetext="valueText"
            @pointerdown.stop="onPointerDown('end', $event)"
            @keydown.stop="onKeydown('end', $event)"
          />
        </div>
      </div>
    </div>

    <!--
      刻度文案行。三种模式共用同一个盒子与同一套内缩，故切换模式时控件高度不变。

      `edges` 模式（默认）：只有两段文案，位置跟着端点走 —— 横轴上永远只有两个数字，
      是「当前选了哪一段」最直接的读法，且刻度再多也不会拥挤。

      `all` 模式：显示每个刻度自带的 label（未给的留空）。用 v-show 而非 v-if
      是为了让位置在 DOM 里保持连续 —— 与热力图那边的理由相同，抽掉元素会让
      后续标签的 :key 复用错位。
    -->
    <div
      v-if="props.labelMode !== 'none'"
      class="rangeslider__labels"
      aria-hidden="true"
    >
      <template v-if="props.labelMode === 'edges'">
        <!--
          端点文案。外层 span 只负责定位（left 可过渡，跟着手柄走），
          内层 span 负责文字与交叉淡化 —— 两件事分层是必需的：
          淡化用 animation 播在文字上，而定位要靠 transition 持续插值，
          放在同一元素上时 animation 会接管 opacity 并与 transition 抢属性。

          新文案的 key 取文案本身：不换 key 则 Vue 就地改写文字，
          CSS animation 无从重放（与柱状图底部标签同一个道理）。
        -->
        <span
          class="rangeslider__label rangeslider__label--edge"
          :style="tickStyle(selection.start)"
        >
          <span
            v-if="outgoingStartLabel !== null"
            class="rangeslider__label-text rangeslider__label-text--out"
          >{{ outgoingStartLabel }}</span>
          <span
            :key="startEdgeLabel"
            class="rangeslider__label-text"
            :class="{ 'rangeslider__label-text--in': outgoingStartLabel !== null }"
          >{{ startEdgeLabel }}</span>
        </span>

        <!--
          终点文案在跨度过小时隐去：两段文字会重叠成一团，而跨度本身已由
          手柄间距直观表达。用 opacity 而非 v-if —— 拖动中反复增删元素会闪。
        -->
        <span
          class="rangeslider__label rangeslider__label--edge"
          :class="{ 'rangeslider__label--hidden': edgeLabelsCollide }"
          :style="tickStyle(selection.end)"
        >
          <span
            v-if="outgoingEndLabel !== null"
            class="rangeslider__label-text rangeslider__label-text--out"
          >{{ outgoingEndLabel }}</span>
          <span
            :key="endEdgeLabel"
            class="rangeslider__label-text"
            :class="{ 'rangeslider__label-text--in': outgoingEndLabel !== null }"
          >{{ endEdgeLabel }}</span>
        </span>
      </template>

      <template v-else>
        <span
          v-for="(tick, index) in props.ticks"
          v-show="tick.label"
          :key="tick.key"
          class="rangeslider__label"
          :class="{ 'rangeslider__label--selected': isSelected(index) }"
          :style="tickStyle(index)"
        >{{ tick.label }}</span>
      </template>
    </div>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

/*
 * 胶囊端头的半径 —— 范围块向端点离散点<strong>外侧</strong>伸出的距离。
 *
 * 与 script 里的 CAP_RADIUS 必须一致：那里用它把范围块向两端外扩，
 * 这里用它定位手柄圆心（相对块边缘往内挪回同一段）。不一致会让手柄偏离离散点。
 *
 * 这是<strong>几何量</strong>而非留白：取值应约等于范围块高度的一半
 * （块高 = 轨高 − 6），端头才是正半圆。想调整两端留白请改 $edge-gap，不要改这里。
 */
$cap-radius: 14px;

/*
 * 两端留白 —— 范围块拖到最边上时，它的端头与背景槽内壁之间的空隙。
 *
 * 这是本组件唯一为「呼吸感」而存在的可调量。之所以必须与 $cap-radius 分开：
 * 若首尾离散点只内缩 $cap-radius，范围块滑到底时端头会与背景槽的圆角边缘
 * <strong>严丝合缝地贴上</strong>，而手柄比块更高，看起来就成了压在槽边上。
 *
 * 调大 → 两端更松、可用轨道变短；调小 → 更紧凑。0 会退化成上述贴边的样子。
 */
$edge-gap: 3px;

/*
 * 内层轨相对背景槽的两侧内缩量。
 *
 * 由上面两者相加得出，不单独取值 —— 它必须同时容纳「块的外扩」与「留白」，
 * 任一项变化时这里都得跟着变，写成派生值可避免漏改。
 */
$rail-inset: $cap-radius + $edge-gap;

/*
 * 范围块位移的过渡时长 —— 三种手势与键盘操作共用同一个值。
 *
 * 选择是离散的：块一格一格地跳，没有补间就是硬切。这个时长负责把每一跳
 * 补成连续位移，同时它也是「跟手程度」的旋钮 —— 调长更顺滑但滞后感更明显，
 * 调短更跟手但接近硬切。
 *
 * 之所以让整块与端点共用：同一个块在不同抓法下若快慢不一，
 * 运动方式就成了两套，那比略微滞后更违和。
 */
$slide-duration: 0.16s;

.rangeslider {
  width: 100%;
  user-select: none;
}

.rangeslider--disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

/*
 * 轨道 —— 即背景槽：`(===)` 的那个胶囊。
 *
 * border-radius 用远大于半高的值而非 50%：后者在宽高不等时得到椭圆，
 * 而这里要的是「两端半圆 + 中间矩形」。超过半高的值会被浏览器收敛到半高，
 * 故 999px 是表达「胶囊」最直接的写法。
 *
 * 色调比主色暗、饱和度低 —— 它是底衬，不该与范围块争夺注意力。
 * 内阴影让它读起来是「凹下去的槽」而非一块色片。
 */
.rangeslider__track {
  position: relative;
  height: var(--rangeslider-track-height);
  border-radius: 999px;
  background: $accent-light;
  border: 1px solid rgba(194, 122, 62, 0.18);
  box-shadow: inset 0 1px 3px rgba(26, 25, 23, 0.05);
  touch-action: none; /* 触屏上拖动不触发页面滚动 */
}

/*
 * 内层轨：定位参照系。
 *
 * 左右各内缩 $rail-inset（端头半径 + 两端留白），使范围块外扩之后仍落在
 * 背景槽内，且滑到最边上时端头与槽内壁保持 $edge-gap 的空隙 ——
 * 手柄比块更高，紧贴槽边会显得压在边框上。
 *
 * useRangeDrag 折算指针坐标读的是这个元素的矩形 —— 必须与百分比定位
 * 所依据的盒子是同一个，否则指针与手柄会有恒定偏移。
 */
.rangeslider__rail {
  position: absolute;
  top: 0;
  right: $rail-inset;
  bottom: 0;
  left: $rail-inset;
}

/*
 * 离散点。
 *
 * translate(-50%, -50%) 让点以自身中心对齐百分比位置 —— 少了这个位移，
 * 点的左上角会落在位置上，整排点集体右偏半个直径。
 */
.rangeslider__dot {
  position: absolute;
  top: 50%;
  z-index: 1;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: $text-muted;
  transform: translate(-50%, -50%);
  transition:
    background-color 0.2s ease,
    opacity 0.2s ease;
}

/* 落在选中区间内的点：被范围块盖住，压暗以免透出杂色 */
.rangeslider__dot--selected {
  opacity: 0.3;
}

/*
 * 端点处的点。手柄圆心正好在这里，故改成亮色，
 * 形成「手柄咬住了这个点」的印象。
 */
.rangeslider__dot--edge {
  background: $text-light;
  opacity: 0.85;
}

/*
 * 范围块 —— 略小于背景槽的胶囊。
 *
 * left / width 由 script 给出（端点百分比再各外扩一个端头半径），
 * 故两端的半圆正好以端点那个离散点为圆心。
 * 纵向内缩 3px，使背景槽在上下各露出一条边，「块在槽里」的层次才成立。
 *
 * 位置过渡在<strong>所有</strong>情形下都保留 —— 包括拖动整块的时候。
 * 选择是离散的（一格一格跳），没有补间就是硬切；而三种手势若快慢不一，
 * 同一个块在不同抓法下的运动方式就不同了，那比「略微滞后于指针」更违和。
 * 时长见 $slide-duration。
 */
.rangeslider__range {
  position: absolute;
  top: 3px;
  bottom: 3px;
  z-index: 2;
  border-radius: 999px;
  background: linear-gradient(
    180deg,
    rgba(194, 122, 62, 0.92) 0%,
    rgba(194, 122, 62, 0.76) 100%
  );
  box-shadow: 0 1px 4px rgba(26, 25, 23, 0.14);
  cursor: grab;
  transition:
    left $slide-duration cubic-bezier(0.4, 0, 0.2, 1),
    width $slide-duration cubic-bezier(0.4, 0, 0.2, 1),
    box-shadow 0.18s ease;

  &:focus-visible {
    outline: 2px solid $accent;
    outline-offset: 2px;
  }
}

/*
 * 拖动整块时：只换光标与阴影，<strong>不碰 transition</strong>。
 *
 * 早先这里把 left / width 的时长置 0，理由是「过渡会让块滞后于指针」。
 * 但那样一来拖整块是硬切、拖端点却有动画 —— 同一个块两种运动方式。
 * 离散步进本就需要补间才连贯，滞后感由 $slide-duration 控制即可。
 *
 * 若将来确实要在此改动过渡，记住必须把三条属性全列出来重申：
 * 只写 transition-duration 会把 box-shadow 一并提速，
 * 而写 `transition: left 0s, width 0s` 这种简写会替换整条声明、
 * box-shadow 的过渡彻底消失 —— 后者刚在柱状图的 hover 上踩过。
 */
.rangeslider__range--dragging {
  cursor: grabbing;
  box-shadow: 0 2px 8px rgba(26, 25, 23, 0.2);
}

/*
 * 端点手柄。
 *
 * 圆心落在端点那个离散点上（横向锚点见下方两条规则），
 * 也就是范围块端头半圆的圆心 —— 视觉上是「手柄咬住了那个点」。
 *
 * 尺寸取轨高减去上下余量，做成正圆。它比离散点大很多 ——
 * 这是要用手指或鼠标精确抓住的目标，视觉上的点只是位置指示。
 */
.rangeslider__handle {
  position: absolute;
  top: 50%;
  width: calc(var(--rangeslider-track-height) - 12px);
  height: calc(var(--rangeslider-track-height) - 12px);
  padding: 0;
  border: 2px solid rgba(255, 255, 255, 0.92);
  border-radius: 50%;
  background: $accent;
  box-shadow: 0 1px 4px rgba(26, 25, 23, 0.22);
  cursor: ew-resize;
  transition:
    transform 0.16s ease,
    box-shadow 0.16s ease;

  &:focus-visible {
    outline: 2px solid $accent;
    outline-offset: 2px;
  }

  &:disabled {
    cursor: not-allowed;
  }
}

/*
 * 左右手柄各自的横向锚点。
 *
 * 偏移量是一个端头半径而非 0：范围块的左边缘位于 `startPercent% - $cap-radius`
 * （script 里为了让端头包住离散点而外扩了这一段），故 `left: 0` 会把圆心放在
 * 点的左侧一个半径处。往内挪 $cap-radius 后，圆心正好落在端点那个离散点上 ——
 * 也就是块端头的半圆圆心。右侧镜像同理。
 *
 * 两者的 transform 不同（一个 -50%、一个 +50%），故后续所有涉及 transform
 * 的状态都必须分别写 —— 只写一次会把另一侧的位移抹掉，手柄跳到块外面去。
 */
.rangeslider__handle--start {
  left: $cap-radius;
  transform: translate(-50%, -50%);
}

.rangeslider__handle--end {
  right: $cap-radius;
  transform: translate(50%, -50%);
}

/* 悬停略放大 */
.rangeslider__handle--start:not(:disabled):hover {
  transform: translate(-50%, -50%) scale(1.08);
}

.rangeslider__handle--end:not(:disabled):hover {
  transform: translate(50%, -50%) scale(1.08);
}

/* 拖动中放大更多，给出「抓住了」的反馈；写在 hover 之后以胜出 */
.rangeslider__handle--start.rangeslider__handle--dragging {
  transform: translate(-50%, -50%) scale(1.14);
  box-shadow: 0 2px 10px rgba(26, 25, 23, 0.28);
}

.rangeslider__handle--end.rangeslider__handle--dragging {
  transform: translate(50%, -50%) scale(1.14);
  box-shadow: 0 2px 10px rgba(26, 25, 23, 0.28);
}

/*
 * 刻度文案行。
 *
 * 左右内缩与内层轨一致，故标签的百分比定位与点共用同一坐标系；
 * 高度固定，避免有无标签时整个控件高度跳动。
 */
.rangeslider__labels {
  position: relative;
  height: 18px;
  margin-top: 6px;
  margin-right: $rail-inset;
  margin-left: $rail-inset;
}

.rangeslider__label {
  position: absolute;
  top: 0;
  font-family: $font-mono;
  font-size: 11px;
  line-height: 18px;
  color: $text-muted;
  white-space: nowrap;
  transform: translateX(-50%);
  transition: color 0.2s ease;
}

/* 选中范围内的刻度文案加深，让当前窗口的跨度在文字层面也读得出 */
.rangeslider__label--selected {
  color: $text-body;
  font-weight: 500;
}

/*
 * 端点文案（edges 模式）的定位外壳。
 *
 * 位置跟着手柄走，故 left 需要过渡 —— 与范围块共用 $slide-duration，
 * 三者（块、手柄、文案）才是同一个动作的三个部分。
 *
 * <strong>拖动时不撤这条过渡</strong>：手柄的位置本身也是由范围块的
 * left / width 过渡驱动的，撤掉文案的过渡会让它瞬移到目标刻度、
 * 而手柄还在滑行 —— 那正是曾经的表现。键盘操作看起来正常，
 * 只是因为一次按键只走一格、瞬移的距离小到看不出。
 *
 * 比刻度文案略重一档：它标的是当前选择而非轴上的参考点，是要被读的主信息。
 * 高度撑满整行，好让内层文字的绝对定位有参照。
 */
.rangeslider__label--edge {
  height: 100%;
  color: $text-body;
  font-weight: 500;
  transition:
    left $slide-duration cubic-bezier(0.4, 0, 0.2, 1),
    opacity 0.16s ease;
}

/*
 * 一段端点文案的文字。
 *
 * 绝对定位使新旧两段落在同一处 —— 交叉淡化要求它们重叠，
 * 按文档流排布会并列成两列，外壳宽度也会随之跳变。
 * 居中对齐配合外壳的 translateX(-50%)，文字中心才落在刻度上。
 */
.rangeslider__label-text {
  position: absolute;
  top: 0;
  left: 50%;
  line-height: 18px;
  white-space: nowrap;
  transform: translateX(-50%);
}

/*
 * 被顶掉的旧文案淡出。
 *
 * 淡化用 animation 而非 transition，且必须播在<strong>内层</strong>元素上 ——
 * 外层的 opacity 归「碰撞隐藏」那条 transition 管，两者放在同一元素上
 * 会互相抢属性：animation 优先级高于 transition，隐藏就失效了。
 *
 * forwards 保持终态：元素要等定时器到点才被移除，不保持会在动画末尾
 * 突然弹回完全不透明。
 */
.rangeslider__label-text--out {
  animation: rangeslider-label-out 0.22s ease forwards;
  pointer-events: none;
}

/* 新文案淡入。与旧文案同时长同曲线，两者的交叉才是对称的 */
.rangeslider__label-text--in {
  animation: rangeslider-label-in 0.22s ease;
}

@keyframes rangeslider-label-out {
  from {
    opacity: 1;
  }
  to {
    opacity: 0;
  }
}

@keyframes rangeslider-label-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

/*
 * 跨度过小时隐去终点文案。
 *
 * 用 opacity 而非 display/v-if：拖动中反复增删元素会闪，
 * 而透明度变化能与位移一起平滑完成。
 */
.rangeslider__label--hidden {
  opacity: 0;
}

/* 尊重系统「减少动态效果」 */
@media (prefers-reduced-motion: reduce) {
  .rangeslider__range,
  .rangeslider__handle,
  .rangeslider__dot,
  .rangeslider__label {
    transition-duration: 0.01s;
  }

  /* 文案直接替换，不播放交叉淡化 */
  .rangeslider__label-text--out,
  .rangeslider__label-text--in {
    animation-duration: 0.01s;
  }
}
</style>
