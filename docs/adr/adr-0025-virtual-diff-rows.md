# ADR-0025: 叠放 Diff 以共享虚拟行对齐，CodeMirror 提供文档外占位

- **状态**：Accepted
- **日期**：2026-11-07
- **取代**：[ADR-0021](./adr-0021-stacked-diff-preview.md) 中以真实行对应作为滚动坐标的部分；[ADR-0023](./adr-0023-json-text-line-locations.md) 中紧凑行仅以区间映射滚动的部分。真实草稿、词法定位、语义 Diff 和成功快照守卫继续保留。
- **相关**：[叠放 Diff RFC](../working/rfcs/2026-11-06-request-body-stacked-diff.md#stacked-diff-virtual-rows)

## 背景

删除痕迹没有真实结果行号，旧滚动端口因此跳过其高度；新增内容的源侧也缺少可定位空间。
仅把单侧行映射到邻近真实行，会在连续增删中产生跳跃。用户要求将显示号与计算号分开：
删除痕迹照常无真实结果号，但有虚拟计算号；新增结果行在源侧对应无显示号的条纹。
原生 textarea 无法在文档中间放入不属于 value 的块级占位，同时保持原生编辑行为。

## 决策

**建立两侧等长的共享虚拟对比序列，用 CodeMirror 6 块级装饰提供源侧条纹占位。**

1. `lineNumber` 只表达真实文档行号；`virtualLineNumber` 表达两侧共享的计算位置。
   滚动锚点为虚拟行及行内进度，删除痕迹、条纹和真实行全部参与。
2. 保留真实草稿、真实结果和展示序列三个独立数据源。占位不进入文档、请求、复制结果或
   撤销历史；新增 / 删除不伪造可见真实行号。
3. 每条真实源行只出现一次。紧凑文本的一对多、格式空行、多行收缩和调序使用额外展示
   占位补齐；语义来源由宿主提供，通用组件不解析 JSON。
4. 编辑区只引入 `@codemirror/state`、`@codemirror/view`、`@codemirror/commands`，沿用
   原字体、配色、尺寸和叠放外观。输入时移除旧占位，新快照可靠后恢复；编辑器持续挂载。
5. 显示行和虚拟行使用各侧真实布局高度处理软换行；保留原有焦点锁、快照守卫和防反馈。

## 后果

- 删除与新增有独立可停留的滚动位置，显示号无需为计算让步；输入与撤销保持真实字符语义。
- 新增编辑器依赖及装饰 / 布局适配成本；系统输入法和系统剪贴板兼容仍需人工环境验收。
- 紧凑行不能拆成多个可编辑物理行；只保留原行一次，其余对比位置显示条纹。
  字段调序时以不交叉锚点保留双方顺序，不承诺每个语义对应都能在同一物理位置展示。
- 软换行、浮沉缩放与滚动边界允许视觉偏差，目标仍是同一虚拟行及行内进度。

## 备选方案

- **继续使用相邻真实行**：无法在删除痕迹或连续新增内部稳定定位，不满足用户要求。
- **向 textarea 写空行**：会污染草稿、请求和撤销，也使显示号失真。
- **自建透明 textarea 叠层**：无法给原生输入文档插入中间视觉块；维护光标、选取、IME
  与软换行需要重新实现编辑器布局。

## 参考

- [组件契约](../../frontend/src/components/stacked-diff/README.md#共享虚拟行与真实显示行)
- [`comparison.ts`](../../frontend/src/components/stacked-diff/comparison.ts)
- [`DiffTextEditor.vue`](../../frontend/src/components/stacked-diff/DiffTextEditor.vue)
- [`jsonLines.ts`](../../frontend/src/features/request-body-rules/jsonLines.ts)