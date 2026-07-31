# rangeslider — 离散范围选择滑块

在一排等距的离散位置上选择一个**闭区间**，支持跨度上下限与整块平移。

```
┌─ 背景槽（胶囊）────────────────────────┐
│  ○ ○ ○ • • •  ●━━━━━━━━━━━━━━━━●  • •  │
│  ↑ 不可达(空心) ↑ 手柄  ↑ 范围块         │
└────────────────────────────────────────┘
                7/26            8/1
                ↑ 端点文案随手柄移动（默认）
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
| `maxSpan` | `number` | `undefined` → 可达宽度 | | 跨度上限（含） |
| `minIndex` | `number` | `0` | | 可达区间左界（含）。这个下标**之前**的位置不可达 |
| `maxIndex` | `number` | `undefined` → 末位 | | 可达区间右界（含）。这个下标**之后**的位置不可达 |
| `disabled` | `boolean` | `false` | | 禁用全部交互（含键盘） |
| `labelMode` | `RangeSliderLabelMode` | `'edges'` | | 刻度文案的显示方式，见下表 |
| `trackHeight` | `number` | `34` | | 槽高度（px）。范围块与手柄尺寸都由它派生 |
| `ariaLabel` | `string` | `'范围选择'` | | 落在整块手柄上的无障碍标签 |
| `formatValueText` | `(s: RangeSelection) => string` | `undefined` | | 把选择读成人话，用于 `aria-valuetext`。缺省是「3 至 9，共 7 项」这类下标描述 |
| `formatEdgeLabel` | `(tick, index, edge) => string` | `undefined` | | 端点文案格式化器，仅 `edges` 模式生效。缺省取 `tick.label ?? tick.key` |

`ticks` 只有一项时组件等同禁用（无从选择）。可达宽度恰等于 `minSpan` 时同样按禁用处理 —— 唯一合法的选择只有一个，拖动毫无意义。

### 可达区间

`minIndex` / `maxIndex` 划定选择块与手柄能到达的范围。语义是**连续的**：只能设定「某个位置之前不可达」或「某个位置之后不可达」，不能在中间挖洞。

**它不改变刻度数量。** 不可达的点照样画出来，只是渲染成**空心环**（可达为实心点），颜色统一中性灰：

```
可达      •  实心，略小   能选
不可达    ○  空心环       存在但不能选
```

若把不可达的位置从 `ticks` 里删掉，用户会以为轴就这么长，看不出「更早的数据不可查」这个事实。留着点并换成空心，禁区才是可见的。

**「可达 / 不可达」与「有没有数据」是两件不同的事。** 可达位置即使没有数据也照常可选、显示为空；不可达位置即使有数据也不能选。滑块只表达前者，数据的有无由图表那边表达。

三种手势撞到可达边界时都是**顶住**：

| 手势 | 撞界行为 |
|---|---|
| 拖左手柄 | 停在 `minIndex`，右端不动（不会把整个区间拽过去） |
| 拖右手柄 | 停在 `maxIndex`，左端不动 |
| 拖整块 | 停住且**跨度不变**（不压窄） |

越界的 `minIndex` / `maxIndex` 会被钳到刻度范围内；倒置时交换；非有限值按全域处理。可达宽度还会反过来收紧跨度约束 —— `minSpan` 与 `maxSpan` 都不会超过可达宽度，否则会得到「至少要选 7 格，但只有 5 格可选」这种无解配置。

### labelMode

| 取值 | 行为 | 适用 |
|---|---|---|
| `'edges'`（默认） | 只在两个手柄下方显示当前端点的文案，随手柄一起移动 | 横轴上永远只有两个数字，是「当前选了哪一段」最直接的读法，且刻度再多也不会拥挤 |
| `'all'` | 显示每个刻度自带的 `label`（未给 label 的位置留空） | 需要看清整条轴的刻度体系时。调用方要自己隔位标注，否则会挤成一团 |
| `'none'` | 不渲染文案行 | 控件只剩槽本身 |

三种模式共用同一个文案行盒子与同一套内缩，**切换模式时控件高度不变**（`none` 除外）。

`edges` 模式下跨度小于 2 格时终点文案会淡出 —— 两段文字会重叠成一团，而跨度本身已由手柄间距直观表达。判定按刻度间距而非像素：文案宽度渲染前不可知，测量要等一帧、期间会以重叠状态闪现一下。

**`formatEdgeLabel` 与 `tick.label` 的分工**：`label` 是为 `all` 模式设计的，通常只给隔位标注的少数刻度，端点落在未标注的位置上就没有文案可显示。所以 `edges` 模式下应当单独给 `formatEdgeLabel`。它收到 `(tick, index, edge)` 三个参数，`edge` 是 `'start' | 'end'` —— 两端需要不同措辞时用得上（如「自 / 至」）。

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
  label?: string    // 刻度文案。仅 labelMode='all' 时使用；只给需要标注的位置
}

interface RangeSelection {
  start: number     // 起点下标
  end: number       // 终点下标（含）
}

interface RangeBounds {
  count: number      // 刻度总数
  minSpan: number
  maxSpan: number
  minIndex: number   // 可达区间左界（含）
  maxIndex: number   // 可达区间右界（含）
}

type RangeSliderLabelMode = 'edges' | 'all' | 'none'
```

## 交互

| 手势 | 行为 |
|---|---|
| 拖左手柄 | 右端固定，改变跨度。触及 `minSpan` / `maxSpan` / `minIndex` 时**顶住** |
| 拖右手柄 | 镜像 |
| 拖范围块 | 整块平移，**跨度不变**。撞边界只停住、不压缩 |
| Tab | 依次聚焦范围块、左手柄、右手柄（三者都是 `role="slider"`） |
| ← → | 语义随焦点而变：在手柄上移动该端点，在块上整块平移 |
| Shift + ← → | 步长 5 |
| Home / End | 移到**可达区间**的最左 / 最右。在块上时保持跨度 |

三个 `role="slider"` 的 `aria-valuemin` / `aria-valuemax` 报的是**可达区间**而非刻度全域 —— 屏幕阅读器据此告知「还能往哪走」，报全域会让不可达区听起来是能到的。

支持鼠标、触屏、手写笔。用 Pointer Events + `setPointerCapture`，拖出轨道外甚至移出窗口后再松手不会「粘住」。

## 纯函数

从 barrel 一并导出，接线时会用到。

| 函数 | 用途 |
|---|---|
| `spanOf(selection)` | 闭区间跨度 |
| `normalizeSelection(selection, bounds)` | 把任意区间收敛成合法选择 |
| `resolveBounds(count, options?)` | 净化边界配置。`options` 为 `{ minSpan, maxSpan, minIndex, maxIndex }` |
| `slideTo(selection, targetStart, bounds)` | 整块平移，跨度不变 |
| `shiftBy(selection, delta, bounds)` | 按步数平移 |
| `moveStart` / `moveEnd(selection, target, bounds)` | 移动单个端点 |
| `tickRatio(index, count)` / `indexFromRatio(ratio, count)` | 下标 ↔ 位置比例，互逆 |
| `clampIndex(index, count)` | 收敛到刻度全域 `[0, count-1]` |
| `clampReachable(index, bounds)` | 收敛到**可达区间**。所有位移操作走这一条 |
| `isReachable(index, bounds)` | 某下标是否可达。渲染层据此区分点的明暗 |
| `reachableSpan(bounds)` | 可达区间宽度（含两端） |
| `selectionEquals(a, b)` | 逐字段比较，避免重复 emit |

`normalizeSelection` 在刻度数量或可达区间变化后特别有用 —— 窗口从 15 格切到 7 格、或后端返回的可查范围收窄时，旧选择会越界或落进禁区，直接参与百分比计算会把范围块画到轨道外或停在灰色区里。组件内部对 `modelValue` 始终先过一遍它。

## 用法

```vue
<script setup lang="ts">
import { computed, ref } from 'vue'
import { DiscreteRangeSlider, type RangeSelection, type RangeSliderTick } from '@/components/rangeslider'

const POOL = 15
const MIN_SPAN = 7

// edges 模式不需要 label，文案由 formatEdgeLabel 给出
const ticks = computed<RangeSliderTick[]>(() =>
  dates.value.map((date) => ({ key: date })),
)

const selection = ref<RangeSelection>({ start: POOL - MIN_SPAN, end: POOL - 1 })

/** 端点文案：M/D */
function formatEdge(tick: RangeSliderTick) {
  return `${Number(tick.key.slice(5, 7))}/${Number(tick.key.slice(8, 10))}`
}

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
    :min-index="earliestQueryableIndex"
    aria-label="日期范围"
    :format-value-text="describe"
    :format-edge-label="formatEdge"
    @change="onChange"
  />
</template>
```

`min-index` 让「最早可查日期之前」的位置变灰且不可到达，而那些点仍然画出来 —— 用户看得见轴有多长，也看得出哪一段查不了。

若改用 `labelMode="all"`，则要给 `ticks` 补上隔位标注的 `label`：

```ts
const ticks = computed<RangeSliderTick[]>(() =>
  dates.value.map((date, i) => ({
    key: date,
    // 15 个日期全标会挤成一团：首尾必标，中间每三格一个
    label: i === 0 || i === dates.value.length - 1 || i % 3 === 0 ? shortDate(date) : undefined,
  })),
)
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

**约束的耦合顺序不可颠倒。** `resolveBounds` 分四步：刻度总数 → 可达区间（钳到刻度域并保证不倒置）→ 跨度下限（钳到**可达宽度**）→ 跨度上限（先与下限比、再与可达宽度比）。后两步依赖第二步算出的可达宽度。若跨度下限的上界写成刻度总数，就会得到「至少要选 7 格，但只有 5 格可选」这种无解配置 —— 所有约束判断都不报错，只会让选择在两个矛盾条件间反复被纠正。

**不可达的点不从 `ticks` 里删掉。** 删掉会让用户以为轴就这么长；留着并换成空心环，「这里存在但不能选」才是可见的。基态 CSS 写的是空心、由 `--reachable` 修饰类填实 —— 反过来写更容易读错，因为默认情形下修饰类会挂满所有点，而空心基态让缺少修饰类的那一段一眼可辨。

**用形状而非颜色区分可达性。** 点只有 6px，两种颜色合成到槽底之后亮度相近，隔几个像素就分不出色相，必须左右对比相邻两点才读得出边界。空心与实心是形状差异，在这个尺寸下比任何配色都可辨，而且不引入第二种色相 —— 暖色全部留给范围块与手柄，那才是「当前选择」。这一版之前试过「可达用主色淡版」，观感是散落的暖色斑点，既不属于槽也不属于选择块。

**实心点缩到 0.8 倍。** 同外径下实心的视觉重量明显大于空心环（环只有一圈描边着色、内部透光），并排时实心点会显得胀出来。缩小后两种点的墨量大致相当，一整排粗细均匀。用 `scale` 而非改 `width` / `height`：盒子尺寸不变，`translate(-50%, -50%)` 的参照就不变，圆心严格钉在刻度上。覆写 `transform` 时**必须把居中位移一并写出**，只写 `scale` 会丢掉 `translate`，整排点集体右偏半个直径。

**环用 `box-shadow: inset` 而非 `border`。** border 会撑大盒子（或在 `border-box` 下挤占内容区），空心与实心两态的外径就不一致，切换时点会胀缩一下。内嵌 box-shadow 画在盒子内部、不参与布局，两态外径严格相同。

**`indexAt` 不钳到可达区间。** 它给出的是「指针指着哪个刻度」这个客观事实，收敛由三个位移函数各自按手势语义完成。若提前钳制，整块平移的抓取偏移会算错 —— 那个偏移是按下时指针与区间起点的真实差值，被钳过就不再真实了。

**范围块两端各外扩一个端头半径。** 直接用 `left: startRatio%` 会让块的左**边缘**压在起点上，而视觉预期是起点那个点落在块端头的**圆心**。跨度 1 时宽度为 `0% + 2r`，得到一个正圆 —— 不是特例，是同一式子的自然结果。

**内层轨必须是独立元素。** 绝对定位的百分比偏移相对 padding 盒计算，给轨道加 `padding` 并不会让点位内缩。内层轨同时是 `useRangeDrag` 折算指针坐标的参照 —— 两者必须是同一个盒子，否则指针与手柄会有恒定偏移。

**每次移动都重新读 `getBoundingClientRect()`。** 面板可能在拖动期间因滚动或布局变化而移动，缓存矩形会让指针逐渐错位，而这种错位只在特定交互序列下出现，极难复现。

**位置过渡在所有情形下都保留，包括拖整块。** 选择是离散的（一格一格跳），没有补间就是硬切；而同一个块在不同抓法下若快慢不一，运动方式就成了两套，比略微滞后更违和。

**端点手柄的 `pointerdown` 必须 `.stop`。** 不阻止冒泡的话会同时触发整块的 `pointerdown`，后者把 target 改成 `'range'`，拖端点就变成了拖整块。

**`--dragging` 状态里不要动 `transition`。** 若确需改动，必须把三条属性全列出来重申：只写 `transition-duration` 会把 `box-shadow` 一并提速，写 `transition: left 0s, width 0s` 这种简写会替换整条声明、`box-shadow` 的过渡彻底消失。后者正是柱状图 hover 那个 bug 的成因。端点文案的 `--sliding` 状态同理，那里把 `opacity` 与 `color` 一并重申了。

**端点文案在拖动时撤掉位置过渡。** 不这么做的话，范围块在 `$slide-duration` 里滑动而文案另起一条同长的过渡，两者起点不同步，看起来像文案在追手柄。块、手柄、文案是同一个动作的三个部分，必须共用同一条时间线。

**`tickRatio` 的分母是 `count - 1`。** 比例描述的是**点**的位置，n 个点之间有 n-1 段间隔。用 `count` 会让最后一个点落在 `(n-1)/n` 处，右端凭空空出一格 —— 且偏差随刻度增多而变小，很容易被当成渲染误差。
