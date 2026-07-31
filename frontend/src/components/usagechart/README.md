# usagechart — 三级下钻堆叠柱状图

一份最细粒度明细，前端 pivot 出三级视图；下钻与悬浮**不产生任何额外请求**。层级切换是形变（morph）而非淡入淡出。

```
 一级 overview        二级 day             三级 primary
 按天堆叠              某天各主维度          某天某主维度下各次维度
 ┌─┐ ┌─┐ ┌─┐          ┌─┐  ┌─┐  ┌─┐        ┌─┐  ┌─┐  ┌─┐
 │▓│ │▓│ │▓│  点段     │▓│  │▓│  │▓│  点柱  │█│  │█│  │█│
 │█│ │█│ │█│  ────>    │█│  │█│  │█│  ────> │█│  │█│  │█│
 └─┘ └─┘ └─┘          └─┘  └─┘  └─┘        └─┘  └─┘  └─┘
 7/25 7/26 7/27        供应商A B  C          模型a b   c
```

## 适用场景

**看构成**：谁占多少、哪一天最忙、某天里哪个成员贡献最大。回答「涨还是跌」应该用折线图（`usageline`）—— 把类别连成线会暗示一种不存在的顺序关系。

三级结构适合「时间 → 一级分类 → 二级分类」这类两层分类的下钻需求。

**不适合**：一维数据（只有一层分类时下钻无处可去，虽然组件能正常渲染）、连续量趋势、成员数量极多的分类（other 阈值会把大量成员合并）。

## 与业务解耦

组件与数据层只认 **primary / secondary** 两个抽象，不知道业务含义。第一版主维度是供应商、次维度是模型；把模型作为主维度只需换一份 `BreakdownDimension`，pivot 逻辑与图表**零改动**。

因此本目录源码中不出现 provider / model 字样，业务称呼由 `dimension.primaryTerm` / `secondaryTerm` 提供，用于面包屑与副标题文案。

## 目录

| 文件 | 职责 |
|---|---|
| `UsageBreakdownPanel.vue` | **对外主入口**：三级状态机 + 面包屑 + 数据分发 |
| `UsageBarChart.vue` | 底层图表：三级共用，无状态，只渲染并报告交互 |
| `UsageTooltip.vue` | 悬浮明细浮框（单层 / 双层），纯展示 |
| `usagechart.ts` | 类型契约（primary/secondary 抽象） |
| `useUsageBreakdown.ts` | 数据层：一份明细 pivot 出三级视图与 hover 明细 |
| `useStackMorph.ts` | 形变模型：按位置序号配对新旧柱与堆叠层 |
| `useAxisScale.ts` | 纵轴标尺换算动画（刻度压缩 / 解压） |
| `axisTicks.ts` | 刻度取整与布局常量 |
| `tooltipAnchor.ts` | 光标 → 浮框落点与展开方向 |
| `*.spec.ts` | 5 个测试文件 |
| `index.ts` | barrel（**不含 Panel**） |

```ts
import UsageBreakdownPanel from '@/components/usagechart/UsageBreakdownPanel.vue'
import type { BreakdownDimension, BreakdownMetric } from '@/components/usagechart'
```

`UsageBreakdownPanel.vue` **不在 barrel 里**，必须按路径直连。barrel 导出 `UsageBarChart`、`UsageTooltip` 与全部类型、composable、纯函数。

---

## UsageBreakdownPanel（主入口）

### Props

| 名称 | 类型 | 默认值 | 必填 | 说明 |
|---|---|---|---|---|
| `rows` | `UsageBreakdownRow[]` | — | ✓ | 明细数据，由页面层拉取后传入 |
| `dimension` | `BreakdownDimension` | — | ✓ | 主次维度定义 |
| `metric` | `BreakdownMetric` | — | ✓ | 指标定义 |
| `loading` | `boolean` | `false` | | |
| `failed` | `boolean` | `false` | | |
| `emptyText` | `string` | `'暂无数据'` | | |
| `loadingText` | `string` | `'加载中'` | | |
| `errorText` | `string` | `'加载失败'` | | |
| `transitionDuration` | `number` | `260` | | 层级切换动画时长（ms），写入 `--usage-chart-transition` |

三种状态态都要求「无数据」才显示，已有数据时的 loading / failed 不遮挡图表，避免闪空。

### Events

**无。** 层级状态完全内部管理，下钻与上浮不向外通报。

### Slots

| 名称 | 作用域参数 | 用途 |
|---|---|---|
| `actions` | 无 | 塞进面包屑那一行，与副标题并列 |

放在面包屑行而不是卡片头部，因为面包屑兼作卡片标题，头部再加标题就是双标题。留成插槽而不内建维度开关，是为了让组件继续只认 primary / secondary 抽象 —— 有哪几种维度组合可切、怎么命名都是页面层的事。

### Expose

无。

### 内部行为

- 面包屑：`总览` / `M月D日` / 主维度名，每项可点击跳转。
- 副标题按层级用 `primaryTerm` / `secondaryTerm` / `metric.display` 拼装，不写死业务词。
- 归一化基准：一级用 `maxColumnTotal`（跨天可比），二 / 三级用本层最大值。
- 维度 `key` 一变即回退到 `overview` 并清空选中路径 —— `selectedPrimary` 存的是原始值，取值空间随维度而变，互换后三级图会查不到数据、面包屑会显示不属于当前维度的名字。`overview` 是唯一在两种配置下都成立的位置。
- `CRUMB_LINE_HEIGHT = 26` 固定面包屑行高，避免层级切换时卡片高度跳动。

---

## UsageBarChart（底层图表）

三级共用的唯一图表。**无状态**，只渲染并报告「某根柱被点了，它的 key 是 X」。

### Props

| 名称 | 类型 | 默认值 | 必填 | 说明 |
|---|---|---|---|---|
| `bars` | `StackBar[]` | — | ✓ | 当前层级的柱子 |
| `maxTotal` | `number` | `0` | | 柱高归一化基准。一级传全局最大总量使各天可横向比较；二 / 三级不传，由本视图最大值自行归一 |
| `drillable` | `boolean` | `true` | | 是否可继续下钻。三级为最细粒度，应传 `false` |
| `ascendable` | `boolean` | `false` | | 是否可返回上一级。为 `false` 时右键不拦截，交还浏览器默认菜单 |
| `drillHint` | `string` | `'查看'` | | 下钻提示的动词部分，用于 aria-label |
| `unit` | `string` | `'次'` | | 指标单位，用于 tooltip 汇总文案 |
| `height` | `number` | `180` | | 绘图区高度（px），不含横轴标签 |
| `segmentGap` | `number` | `3` | | 段间空隙（px） |
| `minSegmentHeight` | `number` | `4` | | 段的最小可见高度（px） |
| `barWidth` | `number` | `36` | | 单柱最大宽度（px） |
| `axisWidth` | `number` | `36` | | 纵轴刻度栏宽度（px），需容纳最长刻度读数 |
| `scaleDuration` | `number` | `420` | | 纵轴标尺换算动画时长（ms） |

### Events

| 名称 | 载荷 | 触发时机 |
|---|---|---|
| `drill` | `string`（`bar.key`） | 左键点击柱列，或柱列聚焦时按 Enter / Space。要求 `drillable` 且该柱不在退场中 |
| `ascend` | 无 | 在绘图区根元素上右键。仅 `ascendable` 为真时 `preventDefault()` 并 emit |

段上的点击冒泡到柱，段与柱共用同一入口。emit 之前会先隐藏 tooltip 并登记形变锚点。

### Slots / Expose

均无。

---

## UsageTooltip

| 名称 | 类型 | 默认值 | 必填 | 说明 |
|---|---|---|---|---|
| `visible` | `boolean` | — | ✓ | 由 `v-if` 控制整体渲染 |
| `left` / `top` | `number` | — | ✓ | 相对图表容器的坐标（px） |
| `title` | `string` | — | ✓ | |
| `summary` | `string` | — | | 标题右侧的汇总值，如「234 次 · 91.8%」 |
| `detail` | `SegmentDetail[]` | — | ✓ | 明细列表；有 `children` 时渲染第二层 |
| `alignEnd` | `boolean` | `false` | | 向左展开（右缘对齐锚点） |
| `below` | `boolean` | `false` | | 落在锚点下方 |

无 Events / Slots / Expose。占比 `0 < p < 0.1%` 时显示 `<0.1%`，避免误解为无调用。

---

## 类型

```ts
interface UsageBreakdownRow {          // 从 @/features/usage-series 重导出
  date: string                          // yyyy-MM-dd（本地时区）
  providerKey: string
  modelName: string
  callCount: number
  inputTokens: number
  outputTokens: number
}

interface BreakdownDimension<T = UsageBreakdownRow> {
  key: string                           // 维度组合标识，Panel 监听它以重置层级
  primaryOf: (row: T) => string          // 主维度取值（一级段、二级柱的身份）
  secondaryOf: (row: T) => string        // 次维度取值（hover 明细、三级柱的身份）
  primaryLabel?: (v: string) => string   // 主维度显示名，缺省用原始值
  secondaryLabel?: (v: string) => string
  primaryTerm: string                    // UI 称呼，如「供应商」
  secondaryTerm: string                  // 如「模型」
}

interface BreakdownMetric<T = UsageBreakdownRow> {
  key: string
  display: string                        // 如「调用次数」
  unit: string                           // 如「次」
  valueOf: (row: T) => number
}

interface StackBar {
  key: string          // 同层级内唯一的稳定标识（一级为日期，二/三级为分类原始值）
  label: string        // 横轴标签（一级为 M/D）
  fullLabel: string    // aria-label 与面包屑用的完整名称
  total: number        // 各段之和
  segments: StackSegment[]   // 自下而上
}

interface StackSegment {
  primary: string | null     // 主维度原始值；other 段为 null
  label: string
  value: number
  ratio: number              // 占当列总量的比例（0~1）
  isOther: boolean
  detail: SegmentDetail[]    // 普通段单层，other 段双层
}

interface SegmentDetail {
  label: string
  value: number
  ratio: number
  children?: SegmentDetail[]   // 仅 other 段有值
}

type DrilldownLevel = 'overview' | 'day' | 'primary'
```

**「三级共用一种结构」**是这套类型的核心：分类柱状图本质是「只有一段的堆叠柱状图」。一级每列由多个主维度段堆叠，三级每列只有一段（该分类自身），二级则按次维度真堆叠。只是段数不同，就没必要两套结构两个组件。

---

## 用法

```vue
<script setup lang="ts">
import UsageBreakdownPanel from '@/components/usagechart/UsageBreakdownPanel.vue'
import type { BreakdownDimension, BreakdownMetric } from '@/components/usagechart'

// 供应商视图
const providerDimension: BreakdownDimension = {
  key: 'provider-model',
  primaryOf: (row) => row.providerKey,
  secondaryOf: (row) => row.modelName,
  primaryTerm: '供应商',
  secondaryTerm: '模型',
}

// 主次互换 —— 同一份数据的另一种切法，零改动即得完整三级下钻
const modelDimension: BreakdownDimension = {
  key: 'model-provider',
  primaryOf: (row) => row.modelName,
  secondaryOf: (row) => row.providerKey,
  primaryTerm: '模型',
  secondaryTerm: '供应商',
}

const callCountMetric: BreakdownMetric = {
  key: 'calls',
  display: '调用次数',
  unit: '次',
  valueOf: (row) => row.callCount,
}
</script>

<template>
  <n-card class="breakdown-card">
    <UsageBreakdownPanel
      :rows="breakdownRows"
      :dimension="activeDimension"
      :metric="callCountMetric"
      :loading="loading"
      :failed="failed"
    >
      <template #actions>
        <span>{{ activeDimension.primaryTerm }}视图</span>
        <button @click="switchDimension">切换</button>
      </template>
    </UsageBreakdownPanel>
  </n-card>
</template>
```

---

## 布局常量

从 `axisTicks.ts` 导出，两级图表**必须共享**这些值，否则层级切换时会出现整体跳动。

| 常量 | 值 | 作用 |
|---|---|---|
| `COLUMN_PAD_Y` | `4` | 柱列纵向内边距。**三处必须同值**：柱列 `padding-top`、纵轴 `margin-top`、网格线层 `top`；否则柱底会比 0 刻度线低出这段距离（视觉上「悬空」） |
| `COLUMN_PAD_X` | `2` | 柱列横向内边距。必须由 JS 按权重缩放后内联 —— `flex-basis: 0` 只压内容盒，padding 不参与 flex 收缩 |
| `COLUMN_GAP` | `8` | 柱列间距。由每根柱左右各半的外边距表达而非容器 `gap` —— gap 由容器分配，退场柱只要还在 DOM 就占一整格 |
| `VALUE_LABEL_SPACE` | `18` | 柱顶读数预留高度。不属于坐标系，刻度与网格线仍只占 `plot-height` |
| `AXIS_WIDTH` | `36` | 纵轴刻度栏宽度，决定绘图区左起点 |
| `LABEL_ROW_HEIGHT` | `26` | 底部标签行高度 |

动画时长：`MORPH_DURATION = 420`（形变）、`AXIS_SCALE_DURATION = 420`（标尺换算）、`ENTER_HEIGHT_RATIO = 0.5`（新柱起始高度占轴上限比例）。CSS 侧的 `transition: 0.42s` 与之对应，改一处要改两处。

其他阈值：`OTHER_THRESHOLD = 0.05`（other 合并阈值）、`CURSOR_GAP = 14` / `EDGE_MARGIN = 170` / `TOP_MARGIN = 120`（浮框避让）。

## 主题变量

由页面层注入，**注意两套前缀不同**：

- `UsageBarChart` / `UsageTooltip` 读 `--usagechart-*`（**无连字符**）
- `UsageBreakdownPanel` 读 `--usage-chart-*`（**有连字符**）

⚠️ 当前 `Overview.vue` 只注入了 `--usage-chart-*` 一套，因此图表与浮框实际走的是 SCSS 里写死的回退值 —— 回退值与主题色恰好一致，视觉上看不出来。若要改主题色，必须同时注入两套前缀，否则只有面包屑与副标题会变。

`--usagechart-*` 需要的变量：`font-mono`、`text-muted`、`gridline`、`gridline-base`、`accent`、`accent-light`、`accent-muted`、`tooltip-bg`、`tooltip-text`。

---

## 组合式函数

### `useUsageBreakdown(rows, dimension, metric)`

```ts
{
  columns: ComputedRef<StackBar[]>          // 一级
  maxColumnTotal: ComputedRef<number>
  barsForDate: (date: string) => StackBar[]                    // 二级
  barsForPrimary: (date, primary) => StackBar[]                // 三级
  otherThreshold: number                    // 0.05
}
```

三级视图与 hover 明细全部在此 pivot 得出。所有分组都经 `primaryOf` / `secondaryOf` 取值，不直接读业务字段。

**other 合并**：一级中占比低于 5% 的主维度合并为「其余」段，固定排在最上方。信息损失由下钻与 hover 双层明细双向补回。**二级明确不做合并** —— 已限定单日单主维度，成员有限，合并会掩盖下钻本想看清的小成员。

**排序稳定性**：值降序，等值时按名称升序 —— 避免相同数据两次渲染顺序不同。

### `useStackMorph(bars, ceiling, options?)`

```ts
{
  morphBars: ComputedRef<MorphBar[]>
  morphFrom: (slot: number) => void       // 登记下钻锚点
}
```

三维度的形变：

- **柱体**（横向）：左端对齐。公共前缀原地复用（高度不经过 0）；新增尾部柱自右淡入、起始高度取轴中位；多余尾部柱向右淡出。
- **堆叠层**（纵向）：底部对齐。公共底层平滑过渡；新增顶层从 0 膨胀；多余顶层收缩为 0 后移除。
- **宽度**：`flex-grow` 本身可过渡，退场柱收到 0、进场柱涨到 1。位移与「向右滑出 / 从右挤入」都是宽度变化的**副作用**，不写位移动画。

**下钻锚点**：`morphFrom(slot)` 声明「这一次变化由第几根柱引发」，`assignSlots` 保证锚点必入选、其余名额最左优先补齐。锚点只对紧随其后的那一批数据有效 —— 跨批留存会让无关的数据变化（如 SSE 推送）被错误锚定。

### `useAxisScale(targetCeiling, options?)`

```ts
{
  displayScale: Ref<number>    // 当前标尺：1 个单位数值对应的高度占比
  rescaling: Ref<boolean>      // 是否正在换算
  progress: Ref<number>        // 缓动进度 0~1
}
```

上限变化时不动刻度档位，而让**标尺**动起来：上限变小 → 标尺变大 → 刻度自下往上散开（解压）；上限变大 → 刻度自上往下收拢（压缩）。

配套 `buildAnimatedAxisTicks(from, to, progress, displayScale)` 按**数值身份**配对旧新刻度，绝不插值或改写刻度读数。

### 纯函数

| 函数 | 用途 |
|---|---|
| `niceCeiling(rawMax)` | 向上取整到好看的刻度上限 |
| `buildAxisTicks(rawMax, desiredCount?)` | 生成含 0 与上限的刻度序列 |
| `formatTickValue(value)` | `≥1000` 转 `k` 后缀 |
| `anchorFromCursor(event, container, options?)` | 光标 → 浮框落点与翻转方向 |
| `scaleOf(ceiling)` | 上限 → 标尺（倒数） |
| `cubicBezier(x1,y1,x2,y2)` | 与 CSS `cubic-bezier()` 同语义 |
| `prefersReducedMotion()` | 折线形变也复用它 —— 判定必须同源，否则出现「轴落位了、点还在动」 |

---

## 设计说明

**为什么渲染 key 取位置序号而非业务身份。** 跨层级时柱子身份被整批替换（日期 → 供应商 → 模型），按身份做 key 会让 Vue 判定「旧的全删、新的全建」，只能得到 fade；按位置做 key 则第 N 根柱始终是同一个 DOM 节点，高度变化被 CSS transition 自然捕捉成连续形变。形变的本质就是「同一个 DOM 节点的样式值连续变化」，所以问题在**数据层**（决定 key）而非 DOM 层解决。

**为什么只有一个图表组件。** 三级数据同构。统一后纵轴、网格线、柱顶读数、tooltip、键盘可达性都只有一份实现，不会出现「同一视觉概念在两处各写一份、值悄悄漂移」。更关键的是动效：换组件只能整批淡出淡入，同组件换数据才能按位置复用 DOM 得到 morph。

**为什么标尺在倒数空间插值。** 位置是 `数值 / 上限`，与上限成反比。直接对上限线性插值，200 → 40 会在前半段几乎不动、末段突然窜开（先卡顿后甩尾）。对 `1 / 上限` 插值则位置随进度均匀推进。

**为什么不用 CSS transition 做刻度动画。** 刻度的 `bottom` 是百分比，等分档位下换标尺前后永远是 0/25/50/75/100%，没有属性值变化可供过渡。这与柱体形变面对的是同一类问题：变的是**换算规则本身**。

**柱高用目标上限而非动画标尺。** 刻度压缩表达「量纲变了」、柱体形变表达「数据变了」，两者共用基准会让柱子被两股力量同时拉扯。

**间隙必须算进层高之内。** flex `gap` 是额外空间不占配额，N 层柱子会凭空长高 `(N-1) × gap`，柱顶明显高出刻度线、纵轴失去可量化读出的意义。改为每层自带下外边距并从自己配额扣除后，占位之和恒等于 `(总量 / 轴上限) × 绘图区高`。

**间隙用外边距而非透明下边框。** 边框版本要靠 `background-clip: padding-box`，带来两处缺陷：`border-radius` 按边框盒算而色块被裁到内盒，底部圆角半径变成 `半径 − 边框宽`；`background` 简写会把 `background-clip` 重置回 `border-box`，任何只改底色的修饰类（如 other 段）都会静默失去间隙。外边距区域无法被背景绘制，两点都不复存在。

**hover 用 `filter` 而非 `opacity`。** 把「悬停反馈」（0.18s，要跟手）与「进出场淡入淡出」（0.42s，要与高度同步）分到两个属性上。共用 `opacity` 会迫使 hover 规则改 `transition` 声明 —— 写 `transition-duration` 会把高度一并提速，写 `transition` 简写则把高度过渡整个替换掉，悬停期间点击下钻那一层的形变就成了硬切。

**底色用 `background-color` 而非 `background`。** 只有前者可过渡。层的「其余」身份会随层级切换变动（成员被并入 other 或被拆出），底色跟着变，硬切会在形变途中闪一下。修饰类必须用同一属性名，否则浏览器视作两个不同属性、过渡不发生。

**tooltip 锚在光标而非段中心。** 段可能占满整柱，锚在中心时浮框会离光标很远，看起来像在为别的元素服务。翻转用阈值判断而非测量浮框尺寸 —— 精确避让要先知道浮框实际宽高，渲染前不可知，测量要等一帧，期间浮框会以错误位置闪现。两个方向的参照系不同：**水平看容器**（卡片宽度就是可用空间），**垂直看视口**（向上盖住卡片标题可接受，只有溢出屏幕顶部才真看不见）。

**右键上浮绑在绘图区而非柱子上。** 「返回」与具体某根柱子无关，绑在柱子上用户得先瞄准一根柱才能后退。仅在可上浮时 `preventDefault()` —— 顶层拦下右键却不做事，观感像页面卡了。

**`useStackMorph` 的 watch 必须 `immediate`。** 图表由 `v-if` 控制，数据到齐才创建组件，那一刻 `bars` 已是最终值不再变化，非 immediate 的 watch 永不触发，`previous` 永久为空 —— 第一次下钻会退化为整批重新入场，第二次起才正常。这个 bug 修过一次，不要移除 `immediate`。
