# usageline — token 用量折线图

时间轴上的趋势折线，两种范围（近 7 日 / 今日时段）可切换。位置由 JS 逐帧插值，切换范围时端点与横轴标签平滑滑向新位置。

```
 500k ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄
                    ●━━●
 250k ┄┄┄┄┄┄┄┄●━━●━━┛  ┗━●┄┄┄┄
          ●━━━┛              ╌╌╌╌  ← 未来段不连线
    0 ─────────────────────────
      05:00   11:00   17:00   23:00   05:00
      ├──────── 7/31 ────────┤├─ 8/1 ─┤
```

## 适用场景

**看趋势**：涨还是跌、峰值在哪个时段。回答「谁占多少」应该用堆叠柱状图（`usagechart`）。

折线的横轴是**时间**（连续量）。把类别连成线会暗示一种不存在的顺序关系，属于图表语法错误 —— 这也是本组件族与 `usagechart` 分开的根本原因。

**不适合**：类别轴、多条量级悬殊的系列（见下方「为什么只画一条线」）、需要缩放平移的大数据量时序。

## 目录

| 文件 | 职责 |
|---|---|
| `UsageLinePanel.vue` | **对外主入口**：范围切换、状态态优先级、标题与副标题文案 |
| `UsageLineChart.vue` | 底层图表：SVG 折线、端点、网格、悬停浮框、两行横轴 |
| `usageline.ts` | 类型契约 + `SERIES` 常量，无 Vue 依赖 |
| `useTimelineSeries.ts` | 数值序列 → 0~1 比例坐标 + 两行横轴标签 |
| `useSlotMorph.ts` | 泛化的「按位置序号」形变模型，JS 逐帧插值 |
| `*.spec.ts` | 2 个测试文件 |
| `index.ts` | barrel（**含两个组件**） |

```ts
import UsageLinePanel from '@/components/usageline/UsageLinePanel.vue'
import type { TimelineRange, UsageTimelinePoint } from '@/components/usageline'
```

两个组件都在 barrel 里，也可按路径直连。

---

## UsageLinePanel（主入口）

### Props

| 名称 | 类型 | 默认值 | 必填 | 说明 |
|---|---|---|---|---|
| `points` | `UsageTimelinePoint[]` | — | ✓ | 页面层拉取后传入；组件不请求数据 |
| `range` | `TimelineRange` | — | ✓ | 当前范围，`v-model:range` 的读端 |
| `label` | `string` | — | | 覆写标题里的范围称呼，缺省由 `range` 派生 |
| `loading` | `boolean` | `false` | | |
| `failed` | `boolean` | `false` | | |
| `emptyText` | `string` | `'暂无用量数据'` | | |
| `loadingText` | `string` | `'加载中'` | | |
| `errorText` | `string` | `'加载失败'` | | |

三种状态态都要求「无数据」才显示，有旧数据时不遮挡图表。

### Events

| 名称 | 载荷 | 触发时机 |
|---|---|---|
| `update:range` | `TimelineRange` | 点击「切换」按钮，两值轮换 |

配合 `v-model:range` 使用。切换按钮放在编排层而非页面层，使卡片自成一个完整的交互单元。

### Slots / Expose

均无。标题与副标题由 `range` 自动生成：

| range | 标题 | 副标题 |
|---|---|---|
| `7d` | 近 7 日 token 用量 | 按天汇总 · 纵轴为 token 数 |
| `1d` | 今日时段 token 用量 | 05:00 起算 24 小时 · 每点含前后半小时 · 纵轴为 token 数 |

标题里的称呼可用 `label` 覆写（副标题不可）。时段视图接上日期选择器后看的可能是历史某一天，此时「今日时段」是错的，而「那一天是哪天」只有页面层知道。只放开称呼而非整个标题 —— `... token 用量` 是卡片的固定语义，放开它只会让调用方反复拼同一串后缀。

副标题特意标出「05:00 起算」与「每点含前后半小时」—— 非自然日的窗口若不说明，使用者会按 0 点去对数字，也会以为 `07:00` 只含 07:00 之后那一小时。

**Panel 不转发 Chart 的尺寸与时长 props。** 需要定制 `height` / `axisWidth` / `scaleDuration` / `morphDuration` 时得直接用 `UsageLineChart`。

---

## UsageLineChart（底层图表）

| 名称 | 类型 | 默认值 | 必填 | 说明 |
|---|---|---|---|---|
| `points` | `UsageTimelinePoint[]` | — | ✓ | |
| `range` | `TimelineRange` | — | ✓ | **仅用于切换时撤掉悬停态**，不影响图表形态 |
| `height` | `number` | `180` | | 绘图区高度（px），不含横轴标签 |
| `axisWidth` | `number` | `36` | | 纵轴刻度栏宽度。与柱状图同值，两卡片的绘图区左边界才能对齐 |
| `scaleDuration` | `number` | `420` | | 纵轴标尺换算时长（ms）。与柱状图同值，使两处的「量纲变化」是同一种运动 |
| `morphDuration` | `number` | `420` | | 形变时长（ms）：范围切换时端点与横轴标签移动到新位置所用的时间 |

无 Events / Slots / Expose。

`range` 唯一的用途是「该撤掉悬停态了」—— 序号在新旧两批间指向完全不同的时刻，沿用旧序号会读出与光标无关的值，点数变少时还可能越界。**图表形态只看数据不看 `range`**（见设计说明）。

---

## 类型

```ts
interface UsageTimelinePoint {
  bucket: string        // 近 N 日为 yyyy-MM-dd；今日时段为完整时间戳 yyyy-MM-ddTHH:mm:ss
  label?: string        // 展示用短标签，缺省由 bucket 推导
  inputTokens: number
  outputTokens: number
  future?: boolean      // 该时段是否尚未到来
}

type TimelineRange = '7d' | '1d'
```

**`bucket` 必须带日期。** 今日窗口跨午夜，只给 `HH:mm` 则 `01:00` 分不清属于当天还是次日。短标签由 `label` 给出（时刻桶的桶键是完整时间戳，直接显示会撑爆横轴）。

**总量不是传输字段。** 恒等于输入加输出，由 `SERIES[0].valueOf` 现算 —— 既省字段也杜绝与后端不一致。

**`future` 由前端判定。** 今日范围恒为完整 24 小时，横轴不随时间伸缩（这样才能看出「今天还剩多少时间」）。但未来段的 0 是「还没发生」而非「没有用量」，照常连线会让折线贴底延伸到轴末，看起来像用量已归零。

```ts
interface SeriesConfig {
  key: 'total' | 'input' | 'output'
  label: string                                  // 图例与浮框中的名称
  valueOf: (point: UsageTimelinePoint) => number
  color: string                                  // CSS 变量表达式
  dash?: string                                  // SVG stroke-dasharray
  drawn: boolean                                 // 是否绘制成折线
}
```

`SERIES` 常量（顺序即浮框列出顺序）：

| key | label | drawn | 说明 |
|---|---|---|---|
| `total` | 总量 | ✓ | 输入 + 输出，实线 |
| `input` | 输入 | ✗ | 保留颜色与线型，浮框行首标记用 |
| `output` | 输出 | ✗ | `dash: '4 3'` |

三个系列共用一个纵轴（同量纲）。

其他类型：`SeriesPoint`（`x` / `y` 为 0~1 比例、`value` 原始值）、`RenderedSeries`、`AxisLabel`（`x` / `y` / `opacity` / `text`）、`AxisDateSegment`（`label` / `start` / `width`）。

`DAY_START_HOUR = 5` 从 `@/features/usage-series` 重导出，与后端 `UsageQueryService.DAY_START_HOUR` 一致。归桶与展示必须同值 —— 各定义一份会表现为「数据按 5 点切、分界线画在别处」且无编译错误。

---

## 两种范围的口径差异

| | `7d` | `1d` |
|---|---|---|
| 窗口 | 最近 7 个自然日 | **05:00 起算的 24 小时**（至次日 05:00） |
| 点数 | 7 | **25**（首尾都是 05:00） |
| 桶键 | `yyyy-MM-dd` | `yyyy-MM-ddTHH:mm:ss` |
| 每点覆盖 | 一整天 | 前后各半小时（**以整点为中心**） |
| 横轴 | 一行 `M/D` | 两行：时刻 + 日期分段 |

25 而非 24 不是差一错误：点以整点为中心聚合，首点覆盖 `[05:00, 05:30)`、末点覆盖 `[04:30, 05:00)`，两者相加恰好一小时。首尾都是 05:00 使「一天是完整一圈」的语义直接可见。

跨午夜带来横轴第二行的日期分段 —— 单看时刻行无法判断 `02:00` 属于哪天。

---

## 用法

```vue
<script setup lang="ts">
import UsageLinePanel from '@/components/usageline/UsageLinePanel.vue'
import type { TimelineRange, UsageTimelinePoint } from '@/components/usageline'
import { buildDailyPoints, buildHourlyPoints } from '@/features/usage-series'

const range = ref<TimelineRange>('1d')

// 纯 computed：增量帧只需改动源数据，点位、补零、future 标记全部自动重算，
// 切换范围也不必重新请求
const points = computed<UsageTimelinePoint[]>(() => {
  if (range.value === '1d') {
    return buildHourlyPoints(hourlyTotals.value, windowStart.value, now.value)
  }
  return buildDailyPoints(breakdownRows.value, windowDates.value)
})
</script>

<template>
  <n-card class="usage-line-card">
    <UsageLinePanel
      v-model:range="range"
      :points="points"
      :loading="loading"
      :failed="failed"
    />
  </n-card>
</template>
```

归桶策略在 `@/features/usage-series`，不在本组件族里 —— 组件只消费归约后的点位。

---

## 主题变量

由使用方注入，组件内均带 fallback。前缀 `--usage-line-`。

| 变量 | fallback | 用处 |
|---|---|---|
| `--usage-line-font-display` | `inherit` | 标题 |
| `--usage-line-font-body` | `inherit` | 副标题、状态态 |
| `--usage-line-font-mono` | `'DM Mono', monospace` | 按钮、刻度、标签、浮框 |
| `--usage-line-text-primary` | `#1a1917` | 标题 |
| `--usage-line-text-body` | `#4a4740` | 按钮文字 |
| `--usage-line-text-muted` | `#9a9590` | 副标题、刻度、标签、分隔线 |
| `--usage-line-border` | `#e8e5de` | 按钮描边 |
| `--usage-line-accent` | `#c27a3e` | 总量线色、按钮 hover、端点 |
| `--usage-line-accent-light` | `rgba(194,122,62,.08)` | 按钮 hover 底色 |
| `--usage-line-accent-mid` | `rgba(194,122,62,.35)` | 悬停参考线 |
| `--usage-line-surface` | `#fff` | 按钮底色、端点空心填充 |
| `--usage-line-gridline` | `rgba(154,149,144,.22)` | 虚线网格 |
| `--usage-line-gridline-base` | `rgba(154,149,144,.4)` | 0 刻度实线基线 |
| `--usage-line-tooltip-bg` | `#1a1917` | 浮框底 |
| `--usage-line-tooltip-text` | `#f5f3ee` | 浮框文字 |

组件内部自算的变量不建议外部覆盖：`--usage-line-plot-height`、`--usage-line-axis-width`、`--usage-line-pad-y`、`--usage-line-value-space`、`--usage-line-dot-fill`、`--usage-line-tip-x` / `-tip-y`。

与 `usagechart` 共享的布局常量：`AXIS_WIDTH = 36`、`COLUMN_PAD_Y = 4`、`VALUE_LABEL_SPACE = 18`（直接从 `../usagechart/axisTicks` import）。这使两张卡片的绘图区边界与读数风格对齐。

---

## 组合式函数

### `useTimelineSeries(points)`

```ts
{
  ceiling: ComputedRef<number>            // 轴上限，只看已发生的点
  axisTicks: ComputedRef<AxisTick[]>      // 与柱状图共用取整规则
  series: ComputedRef<RenderedSeries[]>   // 三条线的坐标序列
  axisLabels: ComputedRef<AxisLabel[]>    // 每个点一个（含被隐去的）
  dateSegments: ComputedRef<AxisDateSegment[]>   // 仅时刻桶有
  drawableCount: ComputedRef<number>      // 已发生点数，折线画到第几个点
}
```

横轴用 0~1 比例而非像素，宽度变化交给浏览器。

导出的纯函数：

| 函数 | 用途 |
|---|---|
| `xRatioOf(index, total)` | 单点时返回 0.5（居中，避免像被截断），否则 `index/(total-1)` |
| `yRatioOf(value, ceiling)` | 钳到 `[0,1]`，防 NaN 流入 SVG |
| `shortDate(date)` | `yyyy-MM-dd` → `M/D` |
| `isClockBucket(bucket?)` | 桶键含 `T` 即时刻桶 |
| `bucketLabel(bucket)` | 时刻桶取 `HH:mm`，日期桶走 `shortDate` |
| `isLabelVisible(index, total)` | 首尾恒显示，其余按 `stride = ceil(total/12)` 稀疏 |
| `labelOpacityOf(visible, future)` | 隐去 → 0；未来 → `0.42`；否则 1 |
| `buildDateSegments(points)` | 按桶键里的日期切段（不用 `new Date()`） |
| `toPolylinePoints(points, w, h)` | 生成 SVG points 字符串（纵向翻转） |

### `useSlotMorph<T>(seeds, options?)`

泛化的形变模型，端点与横轴标签各建一个实例。

```ts
{ morphItems: ShallowRef<MorphItem<T>[]> }

interface SlotMorphOptions<T> {
  duration?: number                  // 默认 420
  identity?: (data: T) => string     // 给出时，位置相同但身份不同视为换了东西
}
```

`identity` 的差异是两个实例唯一的不同：标签给了它（文字换了就是换了个东西，需交叉淡出），端点不给（一个点长什么样与它代表哪个时刻无关）。

纯函数 `buildMorphFrame(from, to, eased, identity?)` 是全部规则所在：新增元素出发处 = 上一批末位（线从原有尾端抽出）；溢出元素归处 = 新一批末位（线被拽短而非截断）；首帧 / 空批则就地淡入淡出。输出顺序为「留存与进场按位置升序，随后退场元素按位置升序」—— **折线路径依赖此顺序**。

`morphSignature(seeds, identity?)` 用于识别「数据源刷新但无动画量变化」—— 范围切换后 HTTP 首屏与 SSE 首帧携带同一份点位、相隔数十毫秒到达，照常重启形变会让正在淡出的一批当场消失。

`SLOT_MORPH_EASING = cubicBezier(0.22, 0.61, 0.36, 1)`。

---

## 设计说明

**为什么只画一条线。** 输入 token 占总量绝大部分（实测常在 99% 以上），输出贴着横轴 —— 三条线画出来是「总量与输入几乎重合、输出压成一条直线」，两条附加线都读不出独立走势，只让图变脏。故图上只留总量表达趋势，输入与输出的绝对值由悬停浮框给出。这也让浮框不是可选补充，而是构成信息的**唯一出口**。

若要真画第二条线，除了把 `drawn` 置 `true`，还须把形变改成按系列各建一个 `useSlotMorph` 实例 —— 当前渲染层只对唯一被绘制的系列建形变实例。

**为什么位移由 JS 逐帧插值而非 CSS。** `polyline` 的 `points` 是坐标字符串，7 点与 25 点长度不同，CSS 无从插值，线只能按端点当前位置每帧重算 —— 而那个位置必须是 JS 读得到的数。交给 CSS 则中间值只存在于合成器内部，观感是「线闪现、点随后追上」。

由此派生出三条约定：
- 不透明度也折进形变模型 —— 稀疏隐去与未来淡化都走同一条淡入淡出，用类名会在滑动途中突然显影或消失。
- 被隐去的标签仍留在序列里占位 —— 位置是形变配对依据，抽掉会让后面的标签错位一格。
- 退场点要串进折线路径 —— 那段线因此被拽短到消失，而非当帧截断。

**为什么按位置序号配对而非身份。** 切换范围时身份被整批替换（日期 → 时刻），按身份配对会退化成整批淡出淡入。这与 `usagechart` 的形变模型是同一个道理。

**形态由数据自己决定，`range` 只用于撤悬停。** 切换范围时 `range` 立即变而 `points` 要等请求 / 归约回来，中间几帧若按 `range` 选格式器会闪出 `2026-07-24` 这类原始值。桶键自带形态（有无 `T`），据它判断则永远同步。

**标签与端点共用一套形变模型。** `AxisLabel` 的字段形状刻意与 `SeriesPoint` 对齐并保留恒为 0 的 `y`，两者共用同一条曲线、同一个时长。标签标的就是端点所在时刻，各写一份必然在时长 / 曲线 / 出发处上慢慢漂移。

**浮框跟随光标而非锚在数据点上。** 折线上下起伏，锚在点上会让浮框随横移忽上忽下。hover 按横坐标就近吸附 —— 折线端点很细，精确命中极难触发，就近吸附也是「三值同显」的前提。

**端点用 `div` 而非 SVG 圆。** SVG 用了 `preserveAspectRatio="none"`，圆会被拉成椭圆。

**日期分段行即使没有分段也保留行高。** 否则切换范围时卡片高度会跳动。

**折线与标签都不加 CSS 过渡。** 位置由 JS 逐帧写入，再叠一层过渡会让渲染滞后于插值。端点只保留 `width/height/background 0.15s` 的 hover 反馈。

**标题强制 `lining-nums`。** Cormorant Garamond 默认旧式数字会让「近 7 日」的 7 垂到基线下，中文混排下像基线错位。
