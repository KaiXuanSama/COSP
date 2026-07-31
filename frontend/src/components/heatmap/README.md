# heatmap — 活动热力图

按日期铺成周列的贡献格网格，支持多指标模式切换与列级翻转动画。

```
      ┌ 7月 ─────┐ ┌ 8月 ────────┐
  日  ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪
  一  ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪
  ...
  六  ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪ ▪
```

## 适用场景

任何**按天**的活动强度可视化：调用次数、token 消耗、提交数、登录频次。

组件对业务字段**零假设** —— 日期与数值都通过 `dateKey` / `getDate` / `valueKey` / `getValue` 动态提取，因此同一份数据可以配多个「模式」（指标），切换时按列排队翻转。

**不适合**：非按天的时间粒度（组件按周切列、按星期定行）、需要点击交互的场景（组件不发任何事件）、需要富文本浮框的场景（`tooltipFormatter` 返回字符串而非 VNode）。

## 职责边界

组件**不负责**请求数据，也**不负责**渲染模式切换按钮 —— 这两件事在页面层。页面层切模式时只更新 `activeMode`，开场动画与列翻转队列由组件内部维护。

维护约定：不要把页面业务请求逻辑放回组件内部；要调整动画行为优先改 `useHeatmapTimeline`，而不是在模板层叠加状态。

## 目录

| 文件 | 职责 |
|---|---|
| `ActivityHeatmap.vue` | 组件壳：模板、tooltip、容器宽度与标签列宽测量 |
| `heatmap.ts` | 纯类型定义（8 个导出类型，无运行时代码） |
| `useHeatmapData.ts` | 数据归一化：日期轴对齐、按周切列、可见列裁剪、月份标签、数值映射与分级 |
| `useHeatmapTimeline.ts` | 动画时间线：开场 reveal、列级 flip 排队、WAAPI fallback |
| `index.ts` | barrel（**不含组件**） |

目录下没有 `.spec.ts`。

```ts
import ActivityHeatmap from '@/components/heatmap/ActivityHeatmap.vue'   // 组件走路径
import type { HeatmapModeConfig } from '@/components/heatmap'            // 类型走 barrel
```

`ActivityHeatmap.vue` **不在 barrel 里**，必须按路径直连。barrel 只导出 `heatmap.ts` 的 8 个类型与两个 composable。

## Props

### 数据

| 名称 | 类型 | 默认值 | 必填 | 说明 |
|---|---|---|---|---|
| `data` | `HeatmapRecord[]` | — | ✓ | 所有模式共用的原始数据 |
| `datasets` | `Record<string, HeatmapRecord[]>` | — | | 按 `mode.key` 覆盖单个模式的数据源；某模式缺 key 时回退到 `data` |
| `modes` | `HeatmapModeConfig<any>[]` | — | ✓ | 模式列表。为空时数据链路整体空转 |
| `activeMode` | `string` | `''` | | 当前模式 key；不在 `modes` 中时自动回退 `modes[0].key` |
| `dateKey` | `string` | `'usageDate'` | | 从记录里取日期字符串的字段名 |
| `getDate` | `(item: HeatmapRecord) => string` | — | | 完全接管日期提取。存在时 `dateKey` 被忽略 |

日期字符串**必须是 `YYYY-MM-DD` 格式** —— 这是组件唯一的硬格式约束（内部靠 `split('-')` 解析）。非字符串值对应的记录被静默丢弃。

### 状态与文案

| 名称 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `loading` | `boolean` | `false` | 仅在归一化后无数据时才显示 loading 文案 |
| `failed` | `boolean` | `false` | 同上 |
| `emptyText` | `string` | `'暂无数据'` | 可见列为 0 时的占位文案 |
| `loadingText` | `string` | `'加载中'` | |
| `errorText` | `string` | `'加载失败'` | |
| `dayLabels` | `string[]` | `['日','一','二','三','四','五','六']` | 左侧星期标签 |
| `tooltipFormatter` | `(p: HeatmapTooltipPayload) => string` | — | 自定义浮框文本。缺省为 `` `${date} · ${value.toLocaleString('zh-CN')} ${mode.unit}` `` |

有旧数据时 loading / failed 不遮挡网格，避免闪空。

### 尺寸

| 名称 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `cellSize` | `number` | `16` | 单元格边长（px）。gap 与圆角按它成比例派生 |
| `gap` | `number` | `4` | **当前未生效** —— 见下方设计说明 |

### 动画时长

| 名称 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `initialRevealDelay` | `number` | `900` | 开场 reveal 起始延迟（ms） |
| `rippleDelay` | `number` | `25` | 每格波纹递增延迟（ms） |
| `rippleDuration` | `number` | `400` | 单格波纹动画时长（ms） |
| `flipDelay` | `number` | `50` | 相邻列 flip 起始间隔（ms） |
| `flipDuration` | `number` | `300` | flip 半程时长；实际总时长 = `flipDuration × 2` |

## Events

**无。** 组件是纯展示型单向数据流，hover 与 tooltip 全部内部消化。

## Slots

**无。** 占位文案通过三个 `*Text` prop 定制，浮框内容通过 `tooltipFormatter` 返回**字符串**定制。

## Expose

无。

## 类型

```ts
type HeatmapRecord = object   // 最宽约束：任意对象

interface HeatmapModeConfig<T extends HeatmapRecord = HeatmapRecord> {
  key: string        // 模式唯一标识，与 activeMode / datasets 的键一致
  display: string    // 显示名。组件内部不使用，供页面层的切换按钮展示
  unit: string       // 单位，仅用于默认 tooltip 文案
  colors: [string, string, string, string, string]   // 恰好 5 个，对应 level 0~4
  valueKey?: keyof T & string
  getValue?: (item: T) => number    // 优先级高于 valueKey
}

interface HeatmapTooltipPayload<T = HeatmapRecord> {
  item: T | null                  // 该日期在当前模式下的原始记录，无数据为 null
  date: string
  value: number                   // 已按模式规则解析出的数值
  mode: HeatmapModeConfig<T>      // 该列正在显示的模式，不一定等于最新的 activeMode
}

interface NormalizedHeatmapDay {
  date: string
  recordsByMode: Record<string, HeatmapRecord | null>   // 注意是复数 records
}

interface MonthSegment {
  key: string      // `${月份数字}-${起始列索引}`
  label: string    // `${月份数字}月`，硬编码中文
  width: number    // 像素宽 = 列数 × (cellSize + gap) - gap
}

type VisibleWeek = Array<NormalizedHeatmapDay | null>   // 定长 7，首末周补 null
type RevealState = 'pending' | 'animating' | 'done'
```

`valueKey` 与 `getValue` 都不给时，该模式所有值恒为 0。

**分级规则**（`getLevel`）：值为 0 或该模式最大值为 0 → level 0；否则按 `value / max` 落档 —— `≤0.25` → 1，`≤0.5` → 2，`≤0.75` → 3，其余 → 4。分母是该模式在**全量数据**（不只是可见列）上的最大值。

## 用法

```vue
<script setup lang="ts">
import ActivityHeatmap from '@/components/heatmap/ActivityHeatmap.vue'
import type { HeatmapModeConfig } from '@/components/heatmap'

interface UsageDay {
  usageDate: string     // 正好等于 dateKey 默认值，故不必传 dateKey
  callCount: number
  inputTokens: number
  outputTokens: number
}

const mode = ref<'calls' | 'total'>('calls')
const data = ref<UsageDay[]>([])

const modes: HeatmapModeConfig<UsageDay>[] = [
  {
    key: 'calls',
    display: 'API 调用',
    unit: '次',
    valueKey: 'callCount',
    colors: [
      'var(--heatmap-border-light)',
      'rgba(194, 122, 62, 0.15)',
      'rgba(194, 122, 62, 0.35)',
      'rgba(194, 122, 62, 0.6)',
      'var(--heatmap-accent)',
    ],
  },
  {
    key: 'total',
    display: '总 Token',
    unit: 'token',
    // 派生值，无对应字段 —— 这是 getValue 的典型用途
    getValue: (item) => item.inputTokens + item.outputTokens,
    colors: [/* 5 个 */],
  },
]
</script>

<template>
  <n-card class="heatmap-card">
    <template #header>
      <!-- 模式切换按钮在页面层，不在组件里 -->
      <button @click="switchMode">切换</button>
    </template>

    <ActivityHeatmap
      :data="data"
      :modes="modes"
      :active-mode="mode"
      :loading="loading"
      :failed="failed"
      :cell-size="25"
      empty-text="热力图暂无数据"
      loading-text="热力图加载中"
      error-text="热力图加载失败"
    />
  </n-card>
</template>
```

`colors` 里可以直接写 `var(...)`，由外层卡片提供 —— 组件不解析这些字符串，原样写进 `background`。

## 样式

样式是 plain CSS（无 `lang="scss"`）。组件在根元素写入 4 个自定义属性：

| 变量 | 来源 | 默认值 |
|---|---|---|
| `--heatmap-cell-size` | `cellSize` | `16px` |
| `--heatmap-gap` | 派生 | `max(2, round(cellSize × 0.25))` → `4px` |
| `--heatmap-cell-radius` | 派生 | `max(2, round(cellSize × 0.25))` → `4px` |
| `--heatmap-ripple-duration` | `rippleDuration` | `400ms` |

gap 与圆角跟随 `cellSize` 成比例缩放，不写死 4px。

需要**外层祖先**提供的主题变量（组件内消费并带 fallback）：

| 变量 | fallback |
|---|---|
| `--heatmap-font-mono` | `'DM Mono', monospace` |
| `--heatmap-text-muted` | `#8f8a83` |
| `--heatmap-tooltip-bg` | `#1a1917` |
| `--heatmap-tooltip-text` | `#f5f3ee` |

`colors` 数组里引用的变量（如 `--heatmap-accent`、`--heatmap-border-light`）也由外层提供，但组件 CSS 本身不引用它们。

## 组合式函数

两者都由组件内部调用，单独使用的场合不多，但接口是公开的。

### `useHeatmapData(props, containerWidth, dayLabelsWidth, effectiveGap)`

返回 12 项，其中值得注意的：

| 返回项 | 用途 |
|---|---|
| `normalizedDays` | 全部模式日期取并集后升序排序 |
| `structuralSignature` | 判断「数据本体是否换了一套」，长度相同也能识别 |
| `visibleStateSignature` | 只关注当前屏幕内真正参与动画的列集合 |
| `visibleWeeks` | 从全部周列**尾部**切片（保留最近的列） |
| `modeMax` | 每个模式在全量数据上的最大值，作为分级分母 |
| `getModeValue` / `getLevel` / `getLevelColor` | 取值与分级 |

两个 signature 各管一件事，不能合并 —— 一个用于「数据换了」（重置整个状态），一个用于「可见列集合变了」（重建列动画）。

**可见列数的计算**（易错点集中处）：

```
availableWidth   = max(0, containerWidth - labelsWidth)
fittedColumns    = floor((availableWidth + gap) / (cellSize + gap))
visibleColumnCount = max(1, min(totalWeeks, fittedColumns))
```

必须扣除左侧星期标签宽度，否则可见列数会被高估。`containerWidth` 为 0 时返回全量，避免首帧算成 1 列。

### `useHeatmapTimeline(options)`

返回 3 项：`revealState`（驱动 cell class）、`setColumnRef`（模板 `:ref` 回调）、`getColumnMode(columnIndex)`（该列**当前正在显示**的模式 key）。

时间计算：

```
reveal 总时长   = initialRevealDelay + max(0, 列数 - 1 + 6) × rippleDelay + rippleDuration + 60
单列就绪时刻   = revealStartedAt + (columnIndex + 6) × rippleDelay + rippleDuration
flip 总时长     = flipDuration × 2
第 i 列起点     = max(now + i × flipDelay, columnReadyAt[i], 该列 reveal 就绪时刻)
数据写入时刻   = startDelay + flipDuration        // 翻转半程才换数据
```

不支持 `element.animate`（或调用抛异常）时退回 CSS keyframes `activity-heatmap-col-flip`，靠 `style.animation = 'none'` + 读 `offsetWidth` 强制 reflow 重放。`onUnmounted` 清理定时器、WAAPI 句柄、fallback 定时器三类资源。

## 设计说明

**`gap` prop 名存实亡。** 渲染与布局都走 `effectiveGap`（由 `cellSize` 派生），传入的 `gap` 不参与任何计算。保留 prop 是为了不破坏调用方，但改它没有效果 —— 要调间隙请改 `cellSize`，gap 会按 25% 比例跟随。

**动画是可叠加的队列，不是「立即切到目标模式」。** reveal 先走完每列自己的入场节奏；后续模式切换按列排队，不抢断前一轮已排好的翻转。`columnReadyAt` 让每列记录自己的下一次可执行时间，形成真正的列级排队而非全局抢占。`activeMode` 变化只是在已有时间线上追加一轮 flip。

**取值优先级 `getValue` > `valueKey`。** 前者让自定义映射不受原始字段名限制（例如「总 token」是两个字段之和，没有对应字段）。日期提取同理：`getDate` 完全接管，`dateKey` 是回退。

**数据在翻转半程才写入。** 这样新值是在卡片背面换掉的，正面看不到数字跳变。

**模块三分。** SFC 只管模板、tooltip、尺寸测量；`useHeatmapData` 只解决日期归一化、周列切片、可见列裁剪、数值映射，不碰任何动画状态；`useHeatmapTimeline` 只管 reveal、翻转排队、fallback。三者边界清晰是为了让「改动画」和「改数据口径」互不影响。

**`tooltipFormatter` 收到的 `mode` 是该列正在显示的模式，不一定是最新的 `activeMode`。** 因为列翻转是排队的，某一列可能还停在旧模式上 —— 浮框必须与它眼下显示的颜色一致。
