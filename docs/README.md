# 文档索引

> 本项目文档遵循 `docs-governance` 规范（决策记录 [ADR-0001](./adr/adr-0001-adopt-docs-governance.md)）。
> **新增文档前先读本节**，它决定新文档放哪。

## 目录地图

| 目录 | 内容 | 典型问题 |
|---|---|---|
| [`adr/`](./adr/) | 架构决策记录（**只增不改**，永不归档） | 「为什么当初选了这个方案？」 |
| [`architecture/`](./architecture/) | 当前架构（活文档） | 「系统现在长什么样？」 |
| [`guides/`](./guides/) | 操作指南 | 「怎么做 X？」 |
| [`reference/`](./reference/) | API / 配置 / schema / 契约 / 实测事实 | 「这个字段的取值是什么？」 |
| [`explanations/`](./explanations/) | 原理与背景综述 | 「为什么这样设计？」 |
| [`working/rfcs/`](./working/rfcs/) | 功能提案容器（**带状态**，完成后不删） | 「这个功能做了什么、到哪了？」 |
| [`working/investigations/`](./working/investigations/) | 进行中的调查 | 「这个技术验证到哪了？」 |
| [`working/meeting-notes/`](./working/meeting-notes/) | 会议纪要 | 「上次讨论的结论？」 |
| [`archive/`](./archive/) | 已过时的文档（**禁止作为当前事实引用**） | 「以前那个版本怎么写的？」 |
| `diagrams/` / `images/` | 图示与图片 | — |

## 分类判据（按顺序问）

1. **是「为什么做这个决定」吗？** → `adr/`
2. **是「现在是什么样」吗？** → `architecture/`（结构）或 `reference/`（事实/契约）
3. **是「怎么做」吗？** → `guides/`
4. **是「一个功能的完整档案」吗？**（需求 → 调研 → 计划 → 实施记录）→ `working/rfcs/`
5. **是「为什么这样设计的综述」吗？** → `explanations/`
6. **是「还没定稿的调查」吗？** → `working/investigations/`
7. **已经过时、不再维护？** → `archive/`（附归档说明）

### 几组容易混淆的边界

| 对比 | 判据 |
|---|---|
| `adr/` vs `working/rfcs/` | ADR 是**决策原子**（一个决策族，冻结）；RFC 是**功能容器**（完整生命周期，有状态，可追加实施记录） |
| `reference/` vs `working/investigations/` | `reference/` 是**已完成、长期可查**的事实；`investigations/` 是**进行中**的调查 |
| `explanations/` vs `adr/` | ADR 是**当时**的决策记录（冻结）；explanation 是**现在视角**的综述（可随认知更新） |
| `features/` vs `working/rfcs/` | 本项目**不设** `features/` 目录。原 `features/` 的内容已迁入 `working/rfcs/`（见 ADR-0001 偏离一） |

## 常用直达

- 决策历史：[`adr/`](./adr/)
- 请求链路全貌：[`architecture/REQUEST_PIPELINE.md`](./architecture/REQUEST_PIPELINE.md)
- 已知技术债：[`architecture/KNOWN_DEBT.md`](./architecture/KNOWN_DEBT.md)
- 协议契约：[`reference/contracts/`](./reference/contracts/)
- 功能提案索引：[`working/rfcs/README.md`](./working/rfcs/README.md)
- M2C 去程：[最终调研](./working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-final-review) ·
   [实施计划](./working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-plan) ·
   [目标规格（待实现）](./reference/contracts/C2M-REQUEST-CONTRACT.md#m2c-request-spec)

## 各目录的索引

### adr/ —— 架构决策记录

| ADR | 主题 |
|---|---|
| [ADR-0001](./adr/adr-0001-adopt-docs-governance.md) | 采纳 docs-governance 规范并迁移现有 docs/ |
| [ADR-0012](./adr/adr-0012-m2c-request-translation.md) | M2C 去程的忠实映射、文档/工具损失边界与既有元数据编码（已确认，未实现） |

### architecture/ —— 当前架构

| 文件 | 内容 |
|---|---|
| [REQUEST_PIPELINE.md](./architecture/REQUEST_PIPELINE.md) | 下游请求到上游请求的处理层与顺序（改 pipeline 前必读） |
| [AUTH_HEADER_ASSEMBLY.md](./architecture/AUTH_HEADER_ASSEMBLY.md) | 出站与入站鉴权头的设计（schema.sql / V14 迁移 / 常量三处必须逐字一致） |
| [PROVIDER_NAMING_CONVENTION.md](./architecture/PROVIDER_NAMING_CONVENTION.md) | 供应商命名与模型名路由前缀规则 |
| [KNOWN_DEBT.md](./architecture/KNOWN_DEBT.md) | 经审查确认、刻意不在当前分支处理的技术债 |
| [ADAPTATIONS.md](./architecture/ADAPTATIONS.md) | 供应商适配史与请求转换系统的能力边界 |

> 主干-支干的**结构**说明在代码里：
> [`src/main/java/.../pipeline/README.md`](../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/README.md)
> 是唯一能跟 `git pull` 走的架构说明，因此不放 `docs/`。

### reference/ —— 参考事实

| 文件 | 内容 |
|---|---|
| [`contracts/`](./reference/contracts/) | C2M 请求/响应契约与 [M2C 待实现目标规格](./reference/contracts/C2M-REQUEST-CONTRACT.md#m2c-request-spec)；调研/实施计划不在契约重复，见上方 RFC 入口 |
| [`samples/`](./reference/samples/) | 真实抓包（Codex 请求/响应事件流）与配置样例 |
| [LIVE_TEST_MATRIX.md](./reference/LIVE_TEST_MATRIX.md) | 实机验证矩阵（测什么、用哪个 mock、看什么） |
| [MODEL_SUPPORT.md](./reference/MODEL_SUPPORT.md) | 各模型对 Copilot 的适配度 |
| [MODEL_COMPATIBILITY.md](./reference/MODEL_COMPATIBILITY.md) | 模型适配问题报告 |
| [OLLAMA-EXTENSION-REVIEW.md](./reference/OLLAMA-EXTENSION-REVIEW.md) | Ollama 官方 VS Code 扩展支持程度调研 |
| [ANYROUTER_INVESTIGATION.md](./reference/ANYROUTER_INVESTIGATION.md) | 供应商行为记录 |
| [COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md](./reference/COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md) | Copilot BYOK 思考链回放调查 |
| [reports/LIVE_TEST_REPORT_2026-09-27.md](./reference/reports/LIVE_TEST_REPORT_2026-09-27.md) | COSP 实机功能测试报告 |

### working/rfcs/ —— 功能提案

见 [`working/rfcs/README.md`](./working/rfcs/README.md)（含状态表与候选提案）。

### guides/ / explanations/

本项目暂无此类内容。目录保留以维持宪法完整性；有内容时再补充索引。

## 更新规则（摘要）

1. **同一事实只维护一处**，其余用链接。复制即漂移的开始。
2. **新决策 → 新建 ADR**；决策变更 → 新 ADR 取代 + 旧 ADR **仅改状态行**标记 supersede。
3. **功能实施完毕、合并前必须过文档门禁**（详见下文 §合并前文档门禁）。
4. **过时文档归档而非删除**，归档文件头部加归档说明（日期 / 原因 / 替代文档链接）。
5. 任何目录或文档增删后，同步更新本索引。

## RFC 写作要求

RFC 是**一个功能的完整档案**（需求 → 调研 → 计划 → 实施记录），放在
[`working/rfcs/`](./working/rfcs/)。结论定稿后晋升为 ADR，但 RFC **不删除**——
它保留字段级细节与实测依据，ADR 只承载决策原子与备选方案对比。

### 结构要求

```markdown
# RFC：<功能名>

- **日期**：YYYY-MM-DD（**立项日**：该功能最早一份文档在 git 中的首次出现日期）
- **状态**：草稿 / 讨论中 / 已定稿，实施待开始 / 已定稿且实施完毕 / 已否决
- **相关 ADR**：（结论已晋升时填写）
- **分支**：（挂独立分支时填写）

## 背景
## 1. 目标结构 / 范围
## 2. 内容映射或字段规格
## 3. 分阶段计划
## 4. 风险与对策
## 5. 实施记录（每阶段完成后追加）
## 6. 验收清单
```

### 四条硬要求

1. **需求必须复述在文档内，不单独成文。** 需求从来不是一次写完的——它在讨论中被修正、
   收窄、推翻。单独一份「需求文档」会立刻过时且无人维护；复述进 RFC 顶部，它会随计划一起演进。

2. **「明确不做的事」一节不可省。** 它是日后最常被回查的部分——反过来问
   「为什么当初没顺手做 X」时，答案在这里。

3. **实施记录按阶段追加，不改写历史。** 计划与实际不符时，**在实施记录里写清偏差原因**，
   而不是回头修改计划让它看起来对。例：docs 迁移的「工时预估 8.5 天 → 实际约 1 天」
   就记录在实施记录里。

4. **大块功能才写 RFC。** 零散小改动、bug 修复、单点调整不写——它们有 commit message 与
   代码注释就够。误判的代价是文档泛滥，比不写更糟。

### 判据：这份 RFC 什么时候会失效

- 决策被推翻 → **新建 ADR 取代**，RFC 内容不动（历史记录）
- 功能下线 → 移入 `archive/` 并加归档说明
- 其他情况 → **长期留档**，它是该功能最常被回查的入口

## 合并前文档门禁

功能实现完毕、准备合并或提交 PR 之前，依次执行：

1. **变更盘点**：列出可观察变化——功能行为、API 端点/格式/错误码、配置项/默认值、
   依赖、数据模型/schema、架构边界、本次产生的决策或被推翻的旧决策。
2. **影响映射**：

   | 变化类型 | 必须动作 |
   |---|---|
   | 新决策 / 决策变更 | 新建 ADR（变更时另加旧 ADR 状态行标记） |
   | 架构 / 模块边界 / 数据流 | 更新 `architecture/` |
   | API / 配置 / schema / 契约 | 更新 `reference/` |
   | 用户操作流程变化 | 更新或新建 `guides/` |
   | 需要解释的取舍 | 更新或新建 `explanations/` |
   | 功能实施进展 | 更新对应 RFC 的状态头与实施记录 |
   | `working/` 结论已定稿 | 晋升正式目录或归档 |
   | 新增 / 删除文档 | 更新本 README 索引 |
   | 被取代的旧内容 | 归档（附归档说明） |

3. **输出报告**：变更依据（对应代码改动）、已更新文档清单 + 原因、无需更新的项 + 理由、
   阻塞项。**不得以「时间紧」「改动很小」为由跳过**——是否更新取决于**可观察性**，不取决于改动大小。

> 「无需更新」的合法理由：纯内部重构（对外零变化）、bug 修复（行为回归到文档已描述的状态）、
> 仅测试代码变更、仅日志/注释文案调整。

## 归档规则

- 归档目录：[`archive/`](./archive/)
- 归档文件**头部必须加归档说明**：日期、原因、替代文档链接
- 归档后**不再维护**；**禁止引用归档文档作为当前事实来源**
- ADR **永不归档**（决策链是历史本身）
