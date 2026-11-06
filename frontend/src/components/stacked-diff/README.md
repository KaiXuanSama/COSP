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

不适用于必须同时完整看到两侧内容的并排校对。当前实现为阶段一：承载与层级交互已提供，
行映射、Diff 渲染和滚动协调由后续阶段补充；组件当前不会自动计算差异或同步滚动。

## 目录与导入

| 文件 | 职责 |
|---|---|
| `LayeredPanel.vue` | 单个内容容器、标题与操作插槽、视觉状态、焦点事件 |
| `StackedDiff.vue` | 两个容器、稳定的边缘入口、层级及焦点协调 |
| `layers.ts` | 无 Vue / DOM 的状态模型与迁移 |
| `useStackedLayers.ts` | 状态的响应式包装与变更通知 |
| `layers.spec.ts` | 层级、失焦、输入法状态与实例隔离测试 |
| `index.ts` | 组件、类型、状态函数与 composable 导出 |
| `examples/` | 不依赖 API、Pinia、项目主题的双文本示例 |

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

宽度填满父容器，高度跟随父容器并受最小高度约束。宿主应提供确定高度，或用外部布局
分配高度；两侧内容不会反向撑高组件。

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
import { ref } from 'vue'
import { StackedDiff } from '@/components/stacked-diff'

const text = ref('原始文本')
const output = '只读结果'
</script>

<template>
  <div style="height: 420px">
    <StackedDiff source-title="编辑区" result-title="结果区">
      <template #source><textarea v-model="text" class="text" /></template>
      <template #result><textarea :value="output" class="text" readonly /></template>
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
仍是 `index.html`。示例中结果是固定样本，只验证容器交互，不模拟请求体转换。

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

单容器同时使用内部 `--stacked-diff-origin`，组合组件固定为 source 的 `left top` 与 result
的 `right top`。`prefers-reduced-motion: reduce` 下取消缩放与过渡，保留模糊、层级和焦点保护。

## 状态逻辑

`createStackedLayers(initialSide?)` 创建独立状态；`reduceStackedLayers(state, event)` 返回
新状态，不修改输入；`lockedLayer(state)` 返回当前锁所属侧。状态只含 `activeSide`、
`focusedSide`、`composingSide`，不包含文本值或 DOM。

`useStackedLayers({ initialSide?, onChange? })` 提供响应式 `activeSide`、`lockedSide`，以及
`hover`、`activate`、`focus`、`blur`、`compositionStart`、`compositionEnd` 方法，均接收一侧。
可用于其他渲染结构，DOM 焦点协调由调用方承担。

## 交互与设计说明

> **锁定契约（目标行为）**：焦点锁只对 `editableSide` 声明的可编辑侧生效；只读展示侧
> 聚焦不锁，悬停切换照常。阶段一的实现目前仍按任意聚焦均锁处理，本契约随实现调整对齐。

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
