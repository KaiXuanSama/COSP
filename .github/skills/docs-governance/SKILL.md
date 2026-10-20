---
name: docs-governance
description: '项目文档治理与生命周期管理（docs/ 目录宪法 + ADR 决策链 + 合并前文档门禁）。规范 docs/ 目录每个文件夹该放什么、不放什么，各类文档该写什么、不该写什么；要求功能开发或技术讨论中产生新决策时立即落成 ADR，新决策取代旧决策时维护 supersedes 双向链接；要求功能实现完毕、合并分支之前完成文档影响评估与更新。Use when: 初始化或整理 docs 目录、撰写或更新项目文档、开发中出现新决策或决策变更（supersede）、功能完成准备合并或提交 PR 前（pre-merge 文档检查）、归档过时文档、文档结构审查。documentation management, docs structure, ADR, architecture decision record, docs governance, pre-merge documentation gate, docs archive.'
argument-hint: '[init|adr|supersede|archive|merge-gate|audit] [可选描述]'
---

# 项目文档治理（docs-governance）

目标：让 `docs/` 成为**活的、可追溯的知识库**——任何时刻都能回答"做了什么决定、为什么做、这个决定如何演变到今天"。

## 三条铁律

1. **单一事实来源（Single Source of Truth）**
   同一条信息只在一个位置维护；其他位置只能链接，禁止复制粘贴。复制即漂移的开始。
2. **决策只增不改（ADR Immutability）**
   ADR 被接受后，内容永不修改。唯一允许的修改是旧 ADR 的**状态行**（标记被谁取代）。决策变了 → 新建 ADR 取代旧的，形成链条。
3. **合并前必须过文档门禁（Pre-Merge Gate）**
   功能实现完毕、合并到其他分支之前，必须完成文档影响评估与更新；无法确定时向用户报告阻塞项，不得静默放行。

## 使用场景

- **初始化**：项目还没有 `docs/` 结构 → 按「初始化流程」建立
- **日常写作**：在 `docs/` 下新建、修改任何文档 → 先查「目录宪法」确定归属
- **决策发生时**：开发讨论中形成新决策 / 推翻旧决策 → 走「决策演变流程」
- **功能完成时**：实现完毕、准备合并或提交 PR → 走「合并前文档门禁」
- **体检**：怀疑文档漂移、结构混乱 → 走「审计模式」

## 目录宪法（速查）

| 目录 | 该放什么 | 不该放什么 |
|---|---|---|
| `README.md` | 文档体系索引：目录地图、如何查找 | 教程、营销内容、与文档体系无关的说明 |
| `adr/` | 每个重要决策一篇，含背景/决策/后果/备选 | 完整设计细节、操作步骤、会议纪要 |
| `architecture/` | **当前**有效的架构、模块边界、数据流 | 历史决策（归 adr/）、未来计划（归 working/） |
| `guides/` | 面向任务的操作步骤（how-to） | 概念解释（归 explanations/）、字段表格（归 reference/） |
| `reference/` | 精确事实：API、配置、schema、错误码 | 观点、教程、背景叙事 |
| `explanations/` | 背景、原理、取舍讨论（why 的综述） | 操作步骤（归 guides/） |
| `working/` | 进行中材料：`rfcs/`、`investigations/`、`meeting-notes/` | 永久性结论（必须晋升到正式目录） |
| `archive/` | 已过时 / 被取代的文档（附归档说明） | 仍在使用的文档、ADR（永不归档） |

完整职责边界、命名规则、归属决策树见 [references/directory-constitution.md](./references/directory-constitution.md)。

**文件名规范**：全小写 kebab-case、纯 ASCII、无空格、无版本后缀；日期用 `YYYY-MM-DD`。正文可用中文。

## 决策演变流程

**何时需要 ADR**——用这个测试：*"三个月后，有人会问『为什么是这个方案而不是别的』吗？"* 会 → 写 ADR。
典型触发：选型（库/框架/协议/外部服务）、架构或模块边界变化、引入或移除依赖、数据模型破坏性变化、对外行为契约变化、否决某个看起来更显然的方案。

**新建决策**：
1. 在 `adr/` 下新建 `adr-NNNN-<slug>.md`（编号顺延），使用 [ADR 模板](./assets/adr-template.md)
2. 状态从 `Proposed` 开始，用户确认后改 `Accepted`
3. 结论同步进 `architecture/` 等活文档（链接 ADR，不复制论证过程）

**新决策取代旧决策（supersede）**：
1. 新建 `adr-MMMM-<slug>.md`，「取代」字段填入旧 ADR 链接
2. **只改**旧 ADR 的状态行 → `Superseded by [ADR-MMMM]`（这是铁律 2 的唯一例外）
3. 依据新决策更新 `architecture/`、`reference/`、`guides/` 等活文档
4. 旧 ADR 原样留在 `adr/`，永不删除、永不归档——决策链是历史本身

完整规则（含部分取代、working → ADR 晋升、常见错误）见 [references/adr-guide.md](./references/adr-guide.md)。

## 合并前文档门禁

功能实现完毕、准备合并 / 提交 PR 之前，**必须**依次执行：

1. **变更盘点**：列出本次改动引发的可观察变化（功能行为、API、配置、依赖、数据模型、新决策）
2. **影响映射**：按 [references/pre-merge-gate.md](./references/pre-merge-gate.md) 的映射表确定要动哪些文档
3. **执行更新**：新建/修改文档；`working/` 中已定稿的结论晋升或归档；更新 `docs/README.md` 索引
4. **输出报告**：按下方「报告格式」输出文档变更清单；无文档改动的项必须显式给出理由
5. **不执行 git 操作**：文档就绪后交用户自行提交与合并；报告中注明「门禁通过」或「存在阻塞」

## 初始化流程（`docs/` 不存在时）

1. 与用户确认是否采纳本目录宪法（如已有 `docs/`，先盘点现状，不擅自迁移）
2. 创建目录结构：`adr/`、`architecture/`、`guides/`、`reference/`、`explanations/`、`working/{rfcs,investigations,meeting-notes}/`、`archive/`
3. 按 [docs README 模板](./assets/docs-readme-template.md) 创建 `docs/README.md`
4. 建议创建首篇 ADR：`adr-0001-adopt-docs-governance.md`，记录"采纳本套文档治理规范"这一决策

## 审计模式（怀疑文档漂移时）

只读检查，逐项报告，证据必须引用具体文件路径（必要时 `路径:行号`）：

- `adr/`：是否有被改动了内容的 ADR（状态行除外）；supersede 链是否双向一致
- `working/`：是否有长期未晋升 / 未归档的材料
- `archive/`：是否缺少归档说明；是否有归档文档仍被当作当前事实引用
- 活文档（`architecture/` 等）：是否描述了已不存在的模块或行为（对照源码验证）
- `docs/README.md`：索引是否遗漏新文档
- 违反「单一事实来源」的重复内容

## 报告格式

```markdown
## 文档门禁报告：<功能名>

**变更依据**：<代码/配置变更清单，含文件路径>

### 已更新的文档
- `docs/adr/adr-0007-xxx.md`（新建）：记录 <决策>
- `docs/reference/api.md`（修改）：新增 <端点> 说明
- `docs/working/investigations/x.md`（→ archive/）：结论已晋升至 <目标>

### 无需更新（附理由）
- `docs/guides/`：本次为内部重构，无用户可观察变化

### 阻塞项
- <未决决策 / 缺失信息；无则写"无">

**结论**：门禁通过 / 存在阻塞
```

## 资源

- [目录宪法（完整）](./references/directory-constitution.md)
- [ADR 指南（完整）](./references/adr-guide.md)
- [合并前门禁（完整）](./references/pre-merge-gate.md)
- 模板：[ADR](./assets/adr-template.md) · [docs README](./assets/docs-readme-template.md) · [归档说明](./assets/archive-note-template.md)
