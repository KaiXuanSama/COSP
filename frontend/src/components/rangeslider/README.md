# rangeslider — 离散范围选择滑块

在一排等距的离散位置上选择一个**闭区间**，支持跨度上下限与整块平移。

```
┌─ 背景槽（胶囊）────────────────────────┐
│  · · · · · ·  ●━━━━━━━━━━━━━━━━●  · ·  │
│  ↑ 离散点      ↑ 手柄  ↑ 范围块         │
└────────────────────────────────────────┘
     7/17        7/20        7/23   7/31
```

## 适用场景

**要解决的问题是「在一条固定长度的轴上挪动一个宽度受限的窗口」。** 标准日期选择器让人逐个填两个端点，而当两个端点相互约束（跨度有上下限）时，填完还得被纠正。滑块把约束变成物理限制 —— 拖到头就顶住，不存在「填了非法值再被弹回」的过程。

- 概览页的日期窗口：15 天池子里滑动一个 7~15 天的窗口
- 版本区间、章节范围、档位区间等任何**等距离散序列**上的区间选择

**不适合**：跨度无约束的自由日期选择（此时标准选择器更直接）、刻度数量巨大（每个刻度都渲染一个 DOM 节点）、非等距刻度（位置按下标均分，不按数值比例）。

## 组件只认下标，不认日期

模型建在**下标**上，位置本身没有量纲。刻度由调用方给出、语义由调用方解释。若模型里出现日期，这个组件就只能选日期了。

区间是**闭区间**：`{ start: 2, end: 8 }` 表示含两端共 7 个位置，跨度是 `end - start + 1`。这与「窗口宽度 7 天」直接对应；用半开区间的话每次读跨度都要在脑子里加一。

## 目录

| 文件 | 职责 |
|---|---|
| `DiscreteRangeSlider.vue` | 渲染与样式 |
| `rangeslider.ts` | 纯选择模型（无 Vue、无 DOM），可单测 |
| `useRangeDrag.ts` | 拖动手势与键盘操作 |
| `rangeslider.spec.ts` | 37 项 |
| `index.ts` | barrel（组件与全部类型、函数） |

```ts
import { DiscreteRangeSlider, type RangeSelection } from '@/components/rangeslider'
```

组件本身也在 barrel 里，与 `usagechart` / `usageline` / `heatmap` 三者不同（那三个的 `.vue` 需按路径直连）。

## Props

| 名称 | 类型 | 默认值 | 必填 | 说明 |
|---|---|---|---|---|
| `ticks` | `RangeSliderTick[]` | — | ✓ | 可选位置，数组顺序即轨道上从左到右。长度决定刻度数 |
| `modelValue` | `RangeSelection` | — | ✓ | 当前选择，闭区间下标。配合 `v-model` |
| `minSpan` | `number` | `1` | | 跨度下限（含）。拖到此处顶住 |
| `maxSpan` | `number` | `undefined` → 刻度数 | | 跨度上限（含） |
| `disabled` | `boolean` | `false` | | 禁用全部交互（含键盘） |
| `showLabels` | `boolean` | `true` | | 是否渲染刻度文案行。关掉后只剩槽本身 |
| `trackHeight` | `number` | `34` | | 槽高度（px）。范围块与手柄尺寸都由它派生 |
| `ariaLabel` | `string` | `'范围选择'` | | 落在整块手柄上的无障碍标签 |
| `formatValueText` | `(s: RangeSelection) => string` | `undefined` | | 把选择读成人话，用于 `aria-valuetext`。缺省是「3 至 9，共 7 项」这类下标描述 |

`ticks` 只有一项时组件等同禁用（无从选择）。

## Events

| 名称 | 载荷 | 触发时机 |
|---|---|---|
| `update:modelValue` | `RangeSelection` | 选择变化即触发。拖动途中**连续触发数十次** |
| `change` | `RangeSelection` | 一次拖动松手时、或一次键盘操作后，各触发一次 |

**这两个事件的分工是使用时最需要注意的一点。** 拿 `update:modelValue` 去发请求，一次拖动会打出几十个请求；副作用应挂在 `change` 上，`update:modelValue` 只负责让 UI 跟手。

## Slots

**当前没有插槽。** 组件只渲染槽、离散点、范围块、手柄和刻度文案，全部由 props 驱动。

摘要文案（「2026-07-25 至 2026-07-31，共 7 天」）目前写在调用方，位于组件外部 —— 它的排版与周围元素相关，收进组件会限制布局自由度。若后续需要让它跟着控件走，可加一个 `#summary` 具名插槽并把 `selection` / `span` 作为作用域参数暴露。

## Expose

无。组件是受控的，状态只有 `modelValue` 一份。

## 类型

```ts
interface RangeSliderTick {
  key: string       // 稳定标识，用于 v-for 的 key，不参与选择逻辑
  label?: string    // 刻度文案。只给需要标注的位置，其余留空以免横向拥挤
}

interface RangeSelection {
  start: number     // 起点下标
  end: number       // 终点下标（含）
}

interface RangeBounds {
  count: number     // 刻度总数
  minSpan: number
  maxSpan: number
}
```

## 交互

| 手势 | 行为 |
|---|---|
| 拖左手柄 | 右端固定，改变跨度。触及 `minSpan` / `maxSpan` 时**顶住** |
| 拖右手柄 | 镜像 |
| 拖范围块 | 整块平移，**跨度不变**。撞边界只停住、不压缩 |
| Tab | 依次聚焦范围块、左手柄、右手柄（三者都是 `role="slider"`） |
| ← → | 语义随焦点而变：在手柄上移动该端点，在块上整块平移 |
| Shift + ← → | 步长 5 |
| Home / End | 移到最左 / 最右。在块上时保持跨度 |

支持鼠标、触屏、手写笔。用 Pointer Events + `setPointerCapture`，拖出轨道外甚至移出窗口后再松手不会「粘住」。

## 纯函数

从 barrel 一并导出，接线时会用到。

| 函数 | 用途 |
|---|---|
| `spanOf(selection)` | 闭区间跨度 |
| `normalizeSelection(selection, bounds)` | 把任意区间收敛成合法选择 |
| `resolveBounds(count, minSpan?, maxSpan?)` | 净化边界配置 |
| `slideTo(selection, targetStart, bounds)` | 整块平移，跨度不变 |
| `shiftBy(selection, delta, bounds)` | 按步数平移 |
| `moveStart` / `moveEnd(selection, target, bounds)` | 移动单个端点 |
| `tickRatio(index, count)` / `indexFromRatio(ratio, count)` | 下标 ↔ 位置比例，互逆 |
| `clampIndex(index, count)` | 收敛成合法下标（非有限值按 0 处理） |
| `selectionEquals(a, b)` | 逐字段比较，避免重复 emit |

`normalizeSelection` 在刻度数量变化后特别有用 —— 窗口从 15 格切到 7 格时旧选择会越界，直接参与百分比计算会把范围块画到轨道外。组件内部对 `modelValue` 始终先过一遍它。

## 用法

```vue
<script setup lang="ts">
import { computed, ref } from 'vue'
import { DiscreteRangeSlider, type RangeSelection } from '@/components/rangeslider'

const POOL = 15
const MIN_SPAN = 7

const ticks = computed(() =>
  dates.value.map((date, i) => ({
    key: date,
    // 隔位标注：15 个日期全标会挤成一团
    label: i === 0 || i === dates.value.length - 1 || i % 3 === 0 ? shortDate(date) : undefined,
  })),
)

const selection = ref<RangeSelection>({ start: POOL - MIN_SPAN, end: POOL - 1 })

function describe(s: RangeSelection) {
  return `${ticks.value[s.start]?.key} 至 ${ticks.value[s.end]?.key}，共 ${s.end - s.start + 1} 天`
}

// 副作用挂在 change 上，不要挂 update:modelValue
function onChange(s: RangeSelection) {
  void fetchWindow({ size: s.end - s.start + 1, offset: POOL - 1 - s.end })
}
</script>

<template>
  <DiscreteRangeSlider
    v-model="selection"
    :ticks="ticks"
    :min-span="MIN_SPAN"
    :max-span="POOL"
    aria-label="日期范围"
    :format-value-text="describe"
    @change="onChange"
  />
</template>
```

**与后端窗口参数的换算**（`ticks` 升序、末位为今天时）：

```
size   = end - start + 1
offset = (count - 1) - end
```

## 样式微调

都在 `<style>` 块顶部。

| 变量 | 当前值 | 作用 |
|---|---|---|
| `$edge-gap` | `3px` | 两端留白（范围块滑到底时端头与槽内壁的空隙）。**唯一为呼吸感存在的可调量**，改它不影响任何对齐 |
| `$slide-duration` | `0.16s` | 位移过渡时长，即跟手程度。三种手势与键盘共用 |
| `$cap-radius` | `14px` | 几何量：范围块向端点离散点外侧伸出的距离。与 script 里的 `CAP_RADIUS` **必须同步改两处** |
| `$rail-inset` | 派生 | `$cap-radius + $edge-gap`，内层轨的两侧内缩 |

改 `trackHeight` prop 时手柄尺寸自动跟随（`trackHeight - 12`），但 `$cap-radius` 不会 —— 它应约等于范围块高度的一半（`(trackHeight - 6) / 2`），端头才是正半圆。改完槽高若发现端头不圆，需一并调整。

组件不消费任何外部主题变量，配色直接 `@use '@/styles/variables'`。

## 设计说明

**受控组件。** 拖动只 emit 期望值，自身不改 `modelValue`。父组件可以拒绝或修正某次变化（比如夹到后端实际可用范围内），而不会与内部状态打架。代价是必须接 `v-model`，否则拖不动 —— 换来的是状态只有一份。

**三种手势的差异全在「谁让位」。** 拖端点时另一端固定，撞限时顶住而非跳变；若在这里改动另一端，用户拖左端会看到右端一起动，那是平移的手感，与拖端点的语义冲突。拖整块时跨度恒定，撞边界只停住 —— 若让区间收窄，用户会看到「拖到头之后块变短了」，松手再拖回来也不复原。

**约束的耦合顺序不可颠倒。** 宽度先钳到 `[minSpan, maxSpan]`，偏移的上界才有依据。`resolveBounds` 里上限也要先与下限比较再与总数比较，否则 `minSpan=10, maxSpan=3` 会得到 `maxSpan < minSpan`，此后所有判断相互矛盾且不报错。

**范围块两端各外扩一个端头半径。** 直接用 `left: startRatio%` 会让块的左**边缘**压在起点上，而视觉预期是起点那个点落在块端头的**圆心**。跨度 1 时宽度为 `0% + 2r`，得到一个正圆 —— 不是特例，是同一式子的自然结果。

**内层轨必须是独立元素。** 绝对定位的百分比偏移相对 padding 盒计算，给轨道加 `padding` 并不会让点位内缩。内层轨同时是 `useRangeDrag` 折算指针坐标的参照 —— 两者必须是同一个盒子，否则指针与手柄会有恒定偏移。

**每次移动都重新读 `getBoundingClientRect()`。** 面板可能在拖动期间因滚动或布局变化而移动，缓存矩形会让指针逐渐错位，而这种错位只在特定交互序列下出现，极难复现。

**位置过渡在所有情形下都保留，包括拖整块。** 选择是离散的（一格一格跳），没有补间就是硬切；而同一个块在不同抓法下若快慢不一，运动方式就成了两套，比略微滞后更违和。

**端点手柄的 `pointerdown` 必须 `.stop`。** 不阻止冒泡的话会同时触发整块的 `pointerdown`，后者把 target 改成 `'range'`，拖端点就变成了拖整块。

**`--dragging` 状态里不要动 `transition`。** 若确需改动，必须把三条属性全列出来重申：只写 `transition-duration` 会把 `box-shadow` 一并提速，写 `transition: left 0s, width 0s` 这种简写会替换整条声明、`box-shadow` 的过渡彻底消失。后者正是柱状图 hover 那个 bug 的成因。

**`tickRatio` 的分母是 `count - 1`。** 比例描述的是**点**的位置，n 个点之间有 n-1 段间隔。用 `count` 会让最后一个点落在 `(n-1)/n` 处，右端凭空空出一格 —— 且偏差随刻度增多而变小，很容易被当成渲染误差。
