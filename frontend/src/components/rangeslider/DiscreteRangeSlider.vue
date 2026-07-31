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
import { computed, ref } from 'vue'
import {
  normalizeSelection,
  resolveBounds,
  selectionEquals,
  spanOf,
  tickRatio,
  type RangeSelection,
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
    /** 是否显示刻度文案行。刻度密时可关掉，只留点。 */
    showLabels?: boolean
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
  }>(),
  {
    minSpan: 1,
    maxSpan: undefined,
    disabled: false,
    showLabels: true,
    trackHeight: 34,
    ariaLabel: '范围选择',
    formatValueText: undefined,
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
      刻度文案。左右内缩与内层轨一致，故标签中心与对应的点严格对齐。
      只渲染带 label 的刻度 —— 全标会挤成一团，调用方按需隔位给。
    -->
    <div v-if="props.showLabels" class="rangeslider__labels" aria-hidden="true">
      <span
        v-for="(tick, index) in props.ticks"
        v-show="tick.label"
        :key="tick.key"
        class="rangeslider__label"
        :class="{ 'rangeslider__label--selected': isSelected(index) }"
        :style="tickStyle(index)"
      >{{ tick.label }}</span>
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

/* 尊重系统「减少动态效果」 */
@media (prefers-reduced-motion: reduce) {
  .rangeslider__range,
  .rangeslider__handle,
  .rangeslider__dot,
  .rangeslider__label {
    transition-duration: 0.01s;
  }
}
</style>
