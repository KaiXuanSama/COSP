# stacked-diff — 双容器叠放预览

两张只在水平方向错位的内容卡片，通过悬停或主动操作交换前后景。前景背景设 30% 透明，
文字保持正常强度；后景整体轻微模糊。文本和其他内容由插槽提供。

```text
原始：┌────────────────────────────┐
结果：     ┌────────────────────────────┐
  左边缘 │←──── 内容重叠 ────→│ 右边缘
```

上图按两行标注水平覆盖范围；实际两张卡片顶部对齐，没有上下错位。

## 适用场景

- 编辑内容与只读结果共享一处阅读空间；通过露出侧边快速切换。
- 两个内容区需要持续挂载，保留输入光标、选区与滚动位置。
- 可迁移到其他 Vue 3.5 项目的通用叠放容器。

不适用于必须同时完整看到两侧内容的并排校对。当前已实现容器、按编辑能力过滤的焦点锁、
逻辑行映射端口与双向滚动协调，并提供 `DiffTextView` 只读行展示。组件不自行解析 JSON 或
计算业务差异；行对应和展示行由宿主提供，缺少可靠映射时不使用滚动比例猜测。

## 目录与导入

| 文件 | 职责 |
|---|---|
| `LayeredPanel.vue` | 单个内容容器、标题与操作插槽、视觉状态、焦点事件 |
| `StackedDiff.vue` | 两个容器、稳定边缘入口、焦点锁及两侧滚动协调 |
| `DiffTextView.vue` | 只读行号 / Diff 状态展示，提供结果侧滚动端口 |
| `layers.ts` | 无 Vue / DOM 的状态模型与迁移 |
| `useStackedLayers.ts` | 状态的响应式包装与变更通知 |
| `layers.spec.ts` | 层级、失焦、输入法状态与实例隔离测试 |
| `lines.ts` / `lines.spec.ts` | 通用行范围、对应模型、双向区间索引与滚动锚点 |
| `scrollPorts.ts` | 原生 textarea 镜像测量及只读行展示的 DOM 滚动端口 |
| `useLinkedScroll.ts` / `useLinkedScroll.spec.ts` | 当前前景发起联动、微任务合并、失效守卫与监听清理 |
| `index.ts` | 组件、类型、状态函数与 composable 导出 |
| `examples/` | 不依赖 API、Pinia、项目主题或 JSON 业务适配的双文本与手写映射示例 |

```ts
import { StackedDiff, LayeredPanel, type LayerChange } from '@/components/stacked-diff'
```

通用组件仅依赖 Vue 3.5，内部导入使用相对路径。迁移时复制本目录，替换调用方的导入位置，
由宿主构建处理 SFC 与 scoped SCSS。没有 Naive UI、COSP API 或请求体规则依赖。
标题与内容关联使用 Vue `useId()`，同一应用内多组自动唯一；同一页面挂载多个独立 Vue
应用时，宿主需分别设置 `app.config.idPrefix`，避免不同应用的可访问 ID 重复。

## `StackedDiff` Props

| 名称 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `sourceTitle` | `string` | `原始文本` | 左侧容器标题与边缘入口标签 |
| `resultTitle` | `string` | `转换后文本` | 右侧容器标题与边缘入口标签 |
| `initialSide` | `'source' \| 'result'` | `'source'` | 仅初始化时读取；后续主动切换使用实例方法 |
| `hoverSwitch` | `boolean` | `true` | 响应鼠标 / 笔悬停；触屏仍使用主动点击 |
| `ariaLabel` | `string` | `双文本对比` | 整组可访问名称 |
| `editableSide` | `'source' \| 'result' \| null` | `'source'` | 声明处于编辑状态的侧；仅该侧聚焦锁定，null 表示两侧都不锁 |
| `sourceScrollPort` | `LineScrollPort \| null` | 未提供 | 原始文本侧滚动端口 |
| `resultScrollPort` | `LineScrollPort \| null` | 未提供 | 结果侧滚动端口 |
| `lineMap` | `TextLineMap \| null` | 未提供 | 与当前两侧文本版本对应的行映射；无效或过期时传 null |
| `syncScroll` | `boolean` | `true` | 两个端口和可靠映射齐备时开启滚动联动 |

宽度填满父容器，高度跟随父容器并受最小高度约束。宿主应提供确定高度，或用外部布局
分配高度；两侧内容不会反向撑高组件。
`editableSide` 只决定焦点是否阻止悬停，不代替宿主输入控件的 `readonly` 设置；改变该 Prop
立即重新判定锁。只读侧聚焦仍会进入前景，但悬停到另一侧时允许切换，不主动调用 blur。

## Events

`change(change: LayerChange)`：只有前景实际改变时才发出，不因重复激活或失焦重复通知。

```ts
type StackedSide = 'source' | 'result'
type LayerActivationReason = 'hover' | 'focus' | 'explicit'

interface LayerChange {
  side: StackedSide
  previousSide: StackedSide
  reason: LayerActivationReason
}
```

`focus` 包括键盘或其他方式聚焦后景，以及输入法组合输入开始；`explicit` 是标题、边缘点击
或实例方法的主动切换。点击前浏览器若已经把焦点移到该侧，通知原因会是先发生的 `focus`。

## Slots

所有插槽收到 `{ foreground: boolean }`，用于视觉呈现；不要据此销毁内容。

| 名称 | 内容 |
|---|---|
| `source` / `result` | 两侧主体，均应提供；可以是输入框、只读文本或其他可聚焦内容 |
| `source-title` / `result-title` | 可选标题内容；默认使用相应标题 Prop，内部已有按钮，不放嵌套按钮 |
| `source-actions` / `result-actions` | 可选标题右侧操作 |
| `source-notice` / `result-notice` | 可选底部提示 |

**内容表面需要透明。** 容器的背景透明度不会自动修改插槽内输入控件的背景；如果控件自带
不透明底色，需在接入层将其背景调透明。文字无需降低 `opacity`。

## Expose

| 名称 | 说明 |
|---|---|
| `activeSide` | 只读当前前景；通过 Vue 组件 ref 读取时已自动解包 |
| `lockedSide` | 当前可编辑侧焦点 / 输入法锁所属侧，未锁定为 `null` |
| `activate(side)` | 主动切换并将焦点移到目标标题，不重建内容 |

不提供受控前景 Prop，以免父级状态覆盖正在进行的焦点保护。业务需要主动进入另一侧时
调用 `activate`。

## `LayeredPanel` 独立使用

Props 为 `title: string` 和 `foreground: boolean`，均必填。默认插槽和 `title`、`actions`、
`notice` 插槽同样收到 `{ foreground }`。单容器不决定层级，也不处理 hover。

事件：`activate`、`focusin(FocusEvent)`、`focusout(FocusEvent)`、`compositionstart`、
`compositionend`。实例方法 `focusTitle()` 只聚焦标题，使用 `preventScroll`。

单容器主体自带 `tabindex="0"`，纯只读文本也能获得焦点并用键盘滚动；主体内部的输入
控件照常参与 Tab 顺序。仅切换前后景不会给内容添加 `inert` 或 `aria-hidden`。

## 用法示例

```vue
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import {
  StackedDiff, DiffTextView, createTextareaScrollPort,
  type DiffDisplayLine, type LineScrollPort, type TextLineMap,
} from '@/components/stacked-diff'

const text = ref('原始文本')
const input = ref<HTMLTextAreaElement | null>(null)
const sourcePort = shallowRef<LineScrollPort | null>(null)
const resultPort = shallowRef<LineScrollPort | null>(null)
const lineMap = shallowRef<TextLineMap | null>({
  sourceLineCount: 1, resultLineCount: 1,
  pairs: [{ id: 'text', source: { start: 1, end: 1 }, result: { start: 1, end: 1 }, status: 'changed' }],
})
const lines: DiffDisplayLine[] = [
  { id: 'text', text: '只读结果', lineNumber: 1, sourceLineNumber: 1, status: 'changed' },
]
onMounted(() => {
  if (input.value) sourcePort.value = createTextareaScrollPort(input.value)
})
onBeforeUnmount(() => sourcePort.value?.dispose())

function edit(value: string) {
  text.value = value
  // 旧结果不再对应新草稿；新预览成功后由宿主替换映射。
  lineMap.value = null
}
</script>

<template>
  <div style="height: 420px">
    <StackedDiff
      source-title="编辑区" result-title="结果区"
      :source-scroll-port="sourcePort" :result-scroll-port="resultPort" :line-map="lineMap"
    >
      <template #source>
        <textarea ref="input" :value="text" class="text" @input="edit(($event.target as HTMLTextAreaElement).value)" />
      </template>
      <template #result><DiffTextView :lines="lines" @port="resultPort = $event" /></template>
    </StackedDiff>
  </div>
</template>

<style scoped>
.text {
  box-sizing: border-box;
  flex: 1;
  width: 100%;
  min-height: 0;
  padding: 12px;
  border: 0;
  resize: none;
  background: transparent;
  color: inherit;
}
</style>
```

本仓库完整独立示例由 `frontend/stacked-diff.html` 加载：在已运行的 Vite dev server 访问
`/stacked-diff.html`。该入口不进入管理后台路由、不登录、不发 API 请求，默认生产构建入口
仍是 `index.html`。示例用 `sample.ts` 手写双文本与增删行对应，不模拟请求体转换。
修改草稿后暂停同步，点击「还原」恢复样本映射；支持普通及窄容器下的双向联动。

## 逻辑行与滚动端口

```ts
interface TextLineRange { start: number; end: number } // 1 起始，包含两端
interface TextLinePair {
  id: string
  source: TextLineRange | null
  result: TextLineRange | null
  status: 'same' | 'changed' | 'added' | 'deleted'
}
interface TextLineMap {
  sourceLineCount: number
  resultLineCount: number
  pairs: readonly TextLinePair[]
}
interface TextLineAnchor { line: number; fraction: number }
interface LineScrollPort {
  readAnchor(): TextLineAnchor
  writeAnchor(anchor: TextLineAnchor): number // 返回写入后实际 scrollTop
  scrollTop(): number
  onScroll(listener: () => void): () => void
  refresh(): void
  dispose(): void
}
```

- `fraction` 是真实逻辑行的行内进度，覆盖其全部软换行高度；视觉变换不改变行号。
- `compileLineMap(map)` 预编译双向索引。重叠范围（例如紧凑 JSON 的多个字段共用一行）
  归并为完整对应区间；未匹配行固定到最近的有效边界，等距时取前一个，不使用全篇滚动比例。
- `lineMap` 是一份版本快照；内容变化后替换映射对象，不在原对象上原地修改。
  未匹配的一侧用 null，不能伪造行号或把对齐占位插入 textarea.value。
- `createTextareaScrollPort(textarea)` 保持真实输入控件，离屏镜像仅测量当前字体、缩进、
  空行与软换行后的布局高度。支持 LF / CRLF、Tab 和紧凑文本，不改输入值或光标。
- `createDisplayScrollPort(element)` 查找带 `data-line-number` 的展示行，用布局坐标定位；
  删除痕迹没有结果行号，不冒充真实结果行。浮沉缩放从测量中抵消。
- 端口缓存布局；input、容器 resize 和字体加载使测量失效，尺寸 / 字体变化也会触发重新联动。
  `refresh()` 只使测量失效，程序化赋值或自定义展示变化后由宿主调用它。
- 两个端口由宿主创建并负责 `dispose()`；组合组件只管理订阅，不替宿主销毁端口。
  原生 textarea 示例在卸载时销毁端口，`DiffTextView` 自动管理自己的端口。

`useLinkedScroll(options)` 同样导出，可用于其他组合结构。`options` 的 `source`、`result`、
`lineMap`、`activeSide`、`enabled` 均接受值 / Ref / getter。它必须在 Vue effect scope 中调用。

只有当前前景的滚动驱动另一侧，程序设置目标 scrollTop 的事件不会反向反馈。滚动事件用
微任务合并，而非依赖前台标签的动画帧；微任务执行时再次核对映射、端口、开启状态与前景。
这只避免已送达事件再等待动画帧；浏览器仍可能延迟后台标签的原生 scroll 事件，不保证
隐藏页面的程序化滚动立即可见。实际联动验收在可见页面完成。
缺少端口、映射为 null、关闭同步或离开作用域时解除监听。绑定新映射时根据当前前景校准，
单纯浮沉换层不会重置两侧 scrollTop。目标行受该容器最大滚动范围约束，视觉位置不要求
逐像素相等；滚到顶部不会凭空跳过文本 padding。

## `DiffTextView` 只读展示

Props：`lines: readonly DiffDisplayLine[]` 必填；`ariaLabel: string` 默认「差异文本」。
每行含 `id`、`text`、`lineNumber: number | null`、可选 `sourceLineNumber` 和 `status`。
新增 / 改写为强调色，删除为红色加删除线；没有真实结果行号时展示「−」。文本用插值渲染，
不通过 `v-html` 展示不可信内容。

`port(LineScrollPort | null)` 在挂载时上报结果端口，卸载时上报 null 并清理；实例暴露
`scrollPort`。显示行变化后重新测量，不因改变前后景销毁内容。
原生文本选取是展示视图内容；“复制结果”操作应由宿主使用真实结果文本，不能直接复制
带删除痕迹和行号的 DOM。

## COSP JSON 行适配（宿主能力）

[`features/request-body-rules/jsonLines.ts`](../../features/request-body-rules/jsonLines.ts) 使用
`jsonc-parser` 定位源码范围，并复用现有语义 Diff 的数组锚点与配对；这部分不属于通用
组件依赖，也不执行请求体规则。严格 JSON 校验先于位置解析；重复字段或过深嵌套暂停映射。

`createJsonLineComparison(sourceText, output)` 返回原样源文本、真实结果文本、Diff 展示行和
行映射。删除痕迹只取被删节点自身的词法范围，不连带同一紧凑行的相邻字段。
`resolvePreviewLines(draft, snapshot, stale?)` 仅在草稿与成功输入快照可靠对应时提供联动映射；
格式变化可重新定位，语义变化、无效输入或旧结果则保留成功结果展示并返回 null 映射与提示。
成功快照本身无法定位时，规范副本只用来保留结果展示，不能用它恢复真实草稿行映射。
正式规则组接入属于阶段三，当前独立示例不加载这份业务适配。

## 样式与主题变量

局部变量可设置在 `StackedDiff` 或其祖先上，不依赖全局 SCSS。默认值如下，仍需业务接入后
依真实内容校准；标题与内容文字不参与整组透明度变化。

| 变量 | 默认值 | 说明 |
|---|---|---|
| `--stacked-diff-offset` | `12%` | 水平错位；有效值限制在 28px 至容器宽的 25% |
| `--stacked-diff-min-height` | `22rem` | 内容最小高度 |
| `--stacked-diff-surface-rgb` | `255 255 255` | 无逗号 RGB 三通道，用于背景 alpha |
| `--stacked-diff-front-alpha` | `0.7` | 前景背景不透明度，即 30% 透明 |
| `--stacked-diff-back-alpha` | `0.88` | 后景背景不透明度 |
| `--stacked-diff-back-blur` | `2px` | 后景容器的 `filter: blur(...)` |
| `--stacked-diff-back-scale` | `0.985` | 后景轻微收缩；以两侧外上角为原点，无上下平移 |
| `--stacked-diff-radius` | `14px` | 圆角 |
| `--stacked-diff-color` | `#3f3f46` | 正文字色 |
| `--stacked-diff-muted` | `#71717a` | 提示与侧边入口字色 |
| `--stacked-diff-accent` | `#596b8a` | 焦点轮廓与标题图标颜色 |
| `--stacked-diff-border` | `rgb(99 103 114 / 20%)` | 边框色 |
| `--stacked-diff-front-shadow` | `0 8px 24px rgb(40 45 55 / 12%)` | 前景阴影 |
| `--stacked-diff-back-shadow` | `0 2px 8px rgb(40 45 55 / 5%)` | 后景阴影 |
| `--stacked-diff-duration` | `260ms` | 视觉属性过渡时长 |
| `--stacked-diff-easing` | `ease` | 过渡曲线 |
| `--stacked-diff-deleted` | `#b84a4a` | Diff 删除痕迹颜色 |
| `--stacked-diff-text-padding` | `16px` | `DiffTextView` 内容内边距 |
| `--stacked-diff-text-font` | `'Cascadia Code', Consolas, monospace` | Diff 字体；两侧可在接入层统一 |
| `--stacked-diff-text-size` | `13px` | Diff 字号 |
| `--stacked-diff-text-line-height` | `1.75` | Diff 行高 |

单容器同时使用内部 `--stacked-diff-origin`，组合组件固定为 source 的 `left top` 与 result
的 `right top`。`prefers-reduced-motion: reduce` 下取消缩放与过渡，保留模糊、层级和焦点保护。

## 状态逻辑

`createStackedLayers(initialSide?)` 创建独立状态；`reduceStackedLayers(state, event)` 返回
新状态，不修改输入；`lockedLayer(state)` 返回当前锁所属侧。状态只含 `activeSide`、
`focusedSide`、`composingSide`，不包含文本值或 DOM。
`reduceStackedLayers` 与 `lockedLayer` 额外接受 `editableSide`，默认 source，null 表示无锁。
“当前拥有焦点”和“拥有悬停锁”分开，展示侧进入前景并不意味着锁住层级。

`useStackedLayers({ initialSide?, editableSide?, onChange? })` 提供响应式 `activeSide`、`lockedSide`，以及
`hover`、`activate`、`focus`、`blur`、`compositionStart`、`compositionEnd` 方法，均接收一侧。
`editableSide` 支持值 / Ref / getter，变化后立即重新判锁。可用于其他渲染结构，DOM 焦点
协调由调用方承担。

## 交互与设计说明

> **锁定契约**：焦点锁只对 `editableSide` 声明的可编辑侧生效；只读展示侧聚焦不锁，
> 悬停切换照常。焦点记录和内容选区保留，不因自动换层主动调用 blur。

- 前景默认 source；离开指针不复位。每组拥有独立状态。
- 悬停只在固定的露出侧边触发。边缘命中区域不缩放；卡片交叠处不监听 hover，
  避免静止指针因层级交换反复触发。
- 仅在 `editableSide` 声明的可编辑侧上，标题、操作或主体内部焦点锁定整侧，输入法组合
  状态也阻止 hover；非编辑展示侧聚焦不锁，悬停仍正常切换。被忽略的悬停不排队，失焦
  只解除锁，重新进入边缘才触发新的悬停。
- 鼠标按钮按下时不因 hover 切换，避免拖选中途换层；触屏只通过点击切换。
- 标题是原生按钮，Tab、Enter、Space 都可用。边缘作为补充点击入口不加入 Tab 顺序；
  键盘进入任一侧内容时，该侧立即变为前景。
- `focusout.relatedTarget` 为空时延迟到微任务核对真实焦点。旧侧的迟到 blur / 输入法结束
  事件只清理旧侧状态，不解锁新侧。
- 切换不重建插槽、不给输入框重新赋值、不调用 blur；主动激活只聚焦标题。
  预览内容更新应由宿主维持稳定 key，原始输入仍由宿主持有。
- 焦点弹到 Teleport 弹层时，弹层已经离开该容器，按真实焦点失焦处理；需要持续锁住的
  操作可以在容器内渲染。当前不拦截全局快捷键或模态框关闭。
- `z-index` 立即交换；背景 alpha、模糊、阴影与缩放平滑过渡。后景文字直接模糊，
  没有使用前景 `backdrop-filter`。
