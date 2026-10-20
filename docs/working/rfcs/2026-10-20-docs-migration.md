# RFC：docs/ 文档体系迁移至 docs-governance 规范

- **日期**：2026-10-20
- **状态**：**已定稿且实施完毕**
  （讨论结论已晋升 [ADR-0001](../../adr/adr-0001-adopt-docs-governance.md)；
  七阶段全部完成，见 §6 实施记录）
- **相关 ADR**：[ADR-0001: 采纳 docs-governance 规范并迁移现有 docs/](../../adr/adr-0001-adopt-docs-governance.md)
- **分支**：`master`（工作区直改；迁移完成后一次性切换目录）
- **策略**：**影子目录 + 最后切换**。`docs/` 全程保持可用，`docs-new/` 独立建设，阶段 7 才执行
  `docs → docs-old`、`docs-new → docs`

---

## 背景

见 [ADR-0001 §背景](../../adr/adr-0001-adopt-docs-governance.md#背景)。要点：

1. 决策散在 24 篇文档的 `§决策与理由` 等段落里，被推翻时只能改原文（C2R 已发生过一次，
   手写「后记」作为替代品）
2. 没有合并前文档门禁，文档漂移靠自觉（R2C 收尾时才发现实现计数集体过时）
3. 没有归档纪律

本次迁移把文档体系换成 docs-governance，同时保留三处有意偏离（见 ADR-0001 §三处有意偏离）。

### 已确认的讨论结论

以下 8 项在讨论中逐条确认，构成实施的全部前置约束：

| # | 议题 | 结论 |
|---|---|---|
| 1 | 是否完整迁移 | **是**。用户明确接受「8.5 个工作日的大工程」，理由是长久标准化管理 |
| 2 | 组织模型 | **`working/rfcs/` 提案容器**（非 `features/` 目录），参考 `text-embedding-api-rs` 实践 |
| 3 | 契约文档去向 | **`reference/contracts/`**（符合 Diátaxis 的 reference 象限） |
| 4 | C2R / R2C 是否合并 | **合并为一个 RFC**（约 130 KB，需导航目录） |
| 5 | 调查类文档去向 | **留在 `reference/`**，不进 `working/investigations/`（偏离二） |
| 6 | 存量文件名 | **保持全大写**，只有新增 ADR/RFC 用小写（偏离三） |
| 7 | `guides/` `explanations/` | **建 README 占位**，保持宪法完整性 |
| 8 | `MIGRATION-MAP.md` | 放 `docs-new/` 根下，**阶段 7 验收通过后删除**（不归档：38 条映射届时全部失效，长期价值已被 ADR-0001 与本 RFC 实施记录覆盖） |

---

## 1. 目标结构

```text
docs-new/
├── README.md                     文档体系索引
├── adr/                          决策记录（11 篇）
├── architecture/                 当前架构（5 篇，原样搬）
├── guides/                       操作指南（README 占位）
├── reference/                    查事实
│   ├── contracts/                C2M 请求/响应契约（2 篇）
│   ├── samples/                  抓包 7 份 + 规则样例 1 份
│   ├── reports/                  LIVE_TEST_REPORT_2026-09-27.md
│   ├── MODEL_SUPPORT.md
│   ├── MODEL_COMPATIBILITY.md
│   ├── LIVE_TEST_MATRIX.md
│   ├── ANYROUTER_INVESTIGATION.md
│   ├── COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md
│   └── OLLAMA-EXTENSION-REVIEW.md
├── explanations/                 原理综述（README 占位）
├── working/
│   ├── rfcs/                     功能提案容器（7 个 RFC）
│   ├── investigations/           空
│   └── meeting-notes/            空
├── archive/                      归档（空）
├── diagrams/                     cosp-request-flow.drawio
└── images/                       cosp-banner.png + model-issues/*.png
```

### 目录职责速查

| 目录 | 职责 | 判据 |
|---|---|---|
| `adr/` | 「为什么选 X」 | 决策原子，冻结不可改（仅状态行可变） |
| `architecture/` | 「系统现在长什么样」 | 活文档，随架构变 |
| `guides/` | 「怎么做 X」 | 面向任务的步骤序列 |
| `reference/` | 「X 的字段/取值是什么」 | 精确事实，与代码强同步 |
| `explanations/` | 「为什么这样设计的综述」 | 可综合多篇 ADR 讲背景 |
| `working/rfcs/` | 「这个功能做了什么、到哪了」 | 功能容器，带状态，完成后不删 |
| `archive/` | 历史 | 只读，禁止作为当前事实引用 |

---

## 2. 内容映射表

### A. 零改动搬迁（17 个文件）

| 原路径 | 新路径 | 变更 |
|---|---|---|
| `architecture/ADAPTATIONS.md` | 同名 | 无 |
| `architecture/AUTH_HEADER_ASSEMBLY.md` | 同名 | 无 |
| `architecture/KNOWN_DEBT.md` | 同名 | 无 |
| `architecture/PROVIDER_NAMING_CONVENTION.md` | 同名 | 无 |
| `architecture/REQUEST_PIPELINE.md` | 同名 | 无 |
| `reference/ANYROUTER_INVESTIGATION.md` | 同名 | 无 |
| `reference/COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md` | 同名 | 无 |
| `reference/LIVE_TEST_MATRIX.md` | 同名 | 无 |
| `reference/MODEL_COMPATIBILITY.md` | 同名 | 无 |
| `reference/MODEL_SUPPORT.md` | 同名 | 无 |
| `reference/OLLAMA-EXTENSION-REVIEW.md` | 同名 | 无 |
| `reference/reports/LIVE_TEST_REPORT_2026-09-27.md` | 同名 | 无 |
| `diagrams/cosp-request-flow.drawio` | 同名 | 无 |
| `images/cosp-banner.png` | 同名 | 无 |
| `images/model-issues/longcat-field-1.png` | 同名 | 无 |
| `images/model-issues/mimo-field-1.png` | 同名 | 无 |
| `images/model-issues/mimo-field-2.png` | 同名 | 无 |

**动作**：仅需修内部相对链接深度（部分文件的 `../../AGENTS.md` 等需重算）。

### B. 重组（11 个源文件 → 7 个 RFC + 2 个契约 + 8 个样本）

| 原文件 | 规模 | 新去向 | 处理方式 |
|---|---|---|---|
| `features/protocol-translation/chat-messages/REQUEST-CONTRACT.md` | 38.5 KB | `reference/contracts/C2M-REQUEST-CONTRACT.md` | 改名加 `C2M-` 前缀，内容不动 |
| `features/protocol-translation/chat-messages/RESPONSE-CONTRACT.md` | 54.4 KB | `reference/contracts/C2M-RESPONSE-CONTRACT.md` | 同上 |
| `features/protocol-translation/README.md` | 2 KB | 合并进 RFC-1 | 方向状态表作为该 RFC 一节 |
| `features/protocol-translation/chat-responses/C2R-RESEARCH.md` | 40.1 KB | 合并进 RFC-1 | 「调研」节 |
| `features/protocol-translation/chat-responses/C2R-PLAN.md` | 31.5 KB | 合并进 RFC-1 | 「计划与实施记录」节 |
| `features/protocol-translation/chat-responses/R2C-RESEARCH.md` | 30.3 KB | 合并进 RFC-1 | 「调研」节 |
| `features/protocol-translation/chat-responses/R2C-PLAN.md` | 30.9 KB | 合并进 RFC-1 | 「计划与实施记录」节 |
| `features/protocol-translation/chat-responses/samples/*.json`（7） | 500 KB | `reference/samples/` | 原样搬，不改名 |
| `features/protocol-translation/PROTOCOL-RENAME-PLAN.md` | 15.4 KB | RFC-2 | 加状态头 |
| `features/responses-direct/PLAN.md` | 63.4 KB | RFC-3 主体 | 实施计划 + 记录 |
| `features/responses-direct/SUMMARY.md` | 3.6 KB | 合并进 RFC-3 | 「结果摘要」节 |
| `features/claude-desktop-prompt-cache/SUMMARY.md` | 16.9 KB | RFC-4 | 加状态头（已修复） |
| `features/claude-desktop-prompt-cache/RULE-EXAMPLE.json` | 3.4 KB | `reference/samples/claude-desktop-cache-rule.json` | 配置样例 |
| `features/ollama-downstream/PLAN.md` | 11.9 KB | RFC-5 | 加状态头（规划中） |
| `docs/README.md` | 8.3 KB | 被 `docs-new/README.md` 替代 | 重写 |

> **C2M/M2C 的翻译功能档案**：现有 `protocol-translation/README.md` 只是方向状态表，
> 没有独立的调研/计划文档（C2M 是早期实现）。它并入 RFC-1 作为「C2M/M2C 方向」一节。

### C. RFC 清单（7 个）

| # | 文件名 | 覆盖功能 | 规模估计 | 状态 |
|---|---|---|---|---|
| RFC-1 | `2026-09-03-protocol-translation.md` | C2M/M2C + C2R/R2C 四方向 | **约 130 KB** | 已定稿且实施完毕 |
| RFC-2 | `2026-09-17-responses-direct.md` | Responses 协议直连 | 67 KB | 已定稿且实施完毕 |
| RFC-3 | `2026-09-30-protocol-rename.md` | 协议重命名（OPENAI→CHAT） | 15.4 KB | 已定稿且实施完毕 |
| RFC-4 | `2026-10-17-claude-desktop-prompt-cache.md` | Claude Desktop 缓存命中率修复 | 16.9 KB | 已定稿且实施完毕 |
| RFC-5 | `2026-10-19-ollama-downstream.md` | Ollama 下游协议（O 轴） | 11.9 KB | 已定稿（**未实施**） |
| RFC-6 | `2026-10-20-docs-migration.md` | 本次文档迁移 | 本文件 | 已定稿，实施中 |

> **日期口径**：取功能的**立项日**——该功能最早一份文档在 git 中的首次出现日期。
> 若经过目录规整搬迁，取搬迁前的原始路径日期。溯源：
> 协议翻译 `2026-09-03`（原 `PROTOCOL_TRANSLATION_CONTRACT.md`）；
> Responses 直连 `2026-09-17`（原 `RESPONSES_PROTOCOL_IMPLEMENTATION_PLAN.md`）；
> 协议改名 `2026-09-30`（`254d609` 中新建）；缓存 `2026-10-17`（`dc0b36b`）。
>
> **RFC-1 的规模问题**：130 KB / 约 2100 行。**必须**在文件开头加导航目录（按 §/方向
> 分节 + 锚点链接），否则无法高效阅读。这是偏离一的已知代价。

### D. 删除（1 个）

| 原文件 | 理由 |
|---|---|
| `docs/README.md` | 被 `docs-new/README.md` 重写替代 |

---

## 3. ADR 清单（11 篇）

| ADR | 主题 | 来源文档 | 状态 |
|---|---|---|---|
| 0001 | 采纳 docs-governance 并迁移（含三处偏离） | 本 RFC | ✅ 已完成 |
| 0002 | 协议翻译命名与缩写体系 | `PROTOCOL-RENAME-PLAN.md` | 待写 |
| 0003 | C2M 翻译口径：纯字段翻译、不推导 | `REQUEST/RESPONSE-CONTRACT.md` | 待写 |
| 0004 | C2R 去程翻译（请求侧） | `C2R-PLAN.md` 决策表（6 条） | 待写 |
| 0005 | R2C 回程翻译（响应侧） | `R2C-RESEARCH.md` §9（21 条聚合） | 待写 |
| 0006 | Responses 直连实施方案 | `responses-direct/PLAN.md` §3.6/§5.5/§7 | 待写 |
| 0007 | 请求体规则引擎的能力边界 | `ADAPTATIONS.md` | 待写 |
| 0008 | 出站鉴权头装配三层分离 | `AUTH_HEADER_ASSEMBLY.md` | 待写 |
| 0009 | 观测轴归拢与「观测 vs 观察」判据 | `architecture/` + `pipeline/README.md` | 待写 |
| 0010 | 刻意不做的技术债清单 | `KNOWN_DEBT.md`（15 条） | 待写 |
| 0011 | Ollama 下游协议：只做下游、不做 X2O | `ollama-downstream/PLAN.md` | 待写 |

**粒度原则**：一个决策族一篇 ADR（非一条决策一篇）。每篇内部可用小节分列多条决策，
但共享同一份「背景」与「备选方案」——这恰是原决策表做不到的。

---

## 4. 七阶段执行计划

| 阶段 | 内容 | 工时 | 验收标准 |
|---|---|---|---|
| **0 准备** | ✅ 建目录骨架；✅ 写 ADR-0001；写本 RFC；重写 `README.md` | 0.5 天 | 目录齐全、ADR-0001 Accepted、RFC 状态头完整 |
| **1 映射表** | 产出 `MIGRATION-MAP.md`（内容见 §2，需落成独立文件并逐行核对 38 个源文件） | 0.5 天 | 38/38 覆盖，每行有明确去向 |
| **2 零改动搬迁** | 17 个文件搬入。**只搬不修链接** | 1 天 | 搬迁后字节数逐一核对一致 |
| **3 契约与样本** | 2 篇契约改名搬入 `reference/contracts/`；8 个 samples 搬入 `reference/samples/` | 0.5 天 | 文件数、字节数对得上 |
| **4 RFC 合成** | 11 个源文件按 §2B 合并成 6 个 RFC；写 `working/rfcs/README.md` 索引表 | 2 天 | RFC 齐全、状态头完整、无内容丢失 |
| **5 ADR 撰写** | 10 篇 ADR（0002–0011） | 2 天 | 每篇通过「三个月后能独立读懂」自检 |
| **6 瘦身与链接** | ①原文档决策段改为 ADR 链接；②**统一修复全部相对链接**（含 `../features/` 重定向、31 处外部引用、18 处 Java 注释） | 1.5 天 | 编译通过、全量 1449 测试绿、diff 仅注释行、`docs-new/` 内死链为 0 |
| **7 切换** | 死链扫描 → `docs`→`docs-old`，`docs-new`→`docs` → 复测 → 删备份 | 0.5 天 | 死链 0、测试绿、AGENTS.md 19 处引用可达 |
| **合计** | | **8.5 天** | |

### 阶段依赖

```text
0 准备 ──→ 1 映射 ──→ 2 搬迁 ──┬──→ 4 RFC 合成 ──→ 5 ADR 撰写 ──→ 6 瘦身与链接 ──→ 7 切换
                     └──→ 3 契约 ──┘
```

`docs/` 在所有阶段保持可用，直到阶段 7 一次性切换。

### 时序修正（阶段 2 执行时发现）

**原计划缺陷**：原打算「阶段 2 搬完即修链接」，但搬迁的文件里有 **10 处链接指向阶段 3/4 才产生的目标**——
现在改是死链，不改也是死链，无法在阶段 2 收尾。

实测清单（`docs-new/` 内扫描所得）：

| 源链接 | 出现次数 | 正确目标 | 目标产生阶段 |
|---|---|---|---|
| `../features/protocol-translation/chat-messages/REQUEST-CONTRACT.md` | 4 | `reference/contracts/C2M-REQUEST-CONTRACT.md` | 3 |
| `../features/protocol-translation/chat-messages/RESPONSE-CONTRACT.md` | 3 | `reference/contracts/C2M-RESPONSE-CONTRACT.md` | 3 |
| `../features/ollama-downstream/PLAN.md` | 2 | `working/rfcs/2026-10-19-ollama-downstream.md` | 4 |
| `../features/claude-desktop-prompt-cache/SUMMARY.md` | 1 | `working/rfcs/2026-10-17-claude-desktop-prompt-cache.md` | 4 |
| **合计** | **10** | | |

**修正**：阶段 2 改为**只搬不修链接**；全部相对链接修复统一移到阶段 6（此时目标已全部存在）。
代价是阶段 2 到阶段 6 期间 `docs-new/` 内存在已知死链——但因 `docs/` 全程可用，
不影响任何实际使用。

**另一项验证**：17 个搬迁文件里的 `../../AGENTS.md`、`../../.github/...`、`../../tools/...`
等链接**全部无需修改**——`docs/architecture/` 与 `docs-new/architecture/` 目录深度相同，
相对路径天然成立。这一项省掉了原计划中「修相对链接深度」的主要工作量。

---

## 5. 风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| 阶段 4 合并长文丢内容 | **高** | 用「标题层级降级 + 机械拼接」不重写；合并前后对比字节数与标题清单 |
| 阶段 6 删决策段丢信息 | **高** | 阶段 5 先写 ADR；删前逐篇核对；`docs-old` 保留至验收通过 |
| 127 处内部链接 + 18 处 Java 注释 | 中 | 阶段 2/3 后立即扫链；阶段 7 再扫；Java 用 `get_errors` + 全量测试兜底 |
| RFC-1 单文件 130 KB | 中 | 文件开头加导航目录；按方向分节 |
| 迁移期间文档不可用 | 低 | `docs/` 全程不动，最后切换 |

### 回滚点

- 阶段 1–6：任意时刻删 `docs-new/` 即可，`docs/` 完好无损
- 阶段 7 后：保留 `docs-old/` 直至验收通过再删除

---

## 6. 实施记录

> 本节在每阶段完成后追加，记录实际耗时、遇到的问题、偏离计划之处。

### 阶段 0（2026-10-20）

- ✅ 建立 `docs-new/` 完整目录骨架（15 个目录）
- ✅ 写 [ADR-0001](../../adr/adr-0001-adopt-docs-governance.md)，状态 Accepted
- ✅ 写本 RFC（讨论结论 + 实施计划）
- ✅ `docs-new/README.md` 重写完成（含 7 条分类判据、4 组易混边界、合并前门禁、归档规则）
- ✅ 空目录 README 占位：`guides/`、`explanations/`、`working/rfcs/`
- ✅ RFC 文件名日期溯源完成（见 §3 日期口径说明）

### 阶段 1（2026-10-20）

- ✅ 产出 `MIGRATION-MAP.md`（见 §2 内容；**该文件已于阶段 7 删除**，映射明细见本节）
- ✅ 逐行核对 `docs/` 全部 **38 个文件**，覆盖情况：

  | 类别 | 数量 | 编号 |
  |---|---|---|
  | 零改动搬迁 | 17 | #1–17 |
  | 契约改名搬迁 | 2 | #18–19 |
  | 样本搬迁 | 8 | #20–27 |
  | 合并进 RFC | 11 | #28–37 |
  | 删除 | 1 | #38 |
  | **合计** | **38** | ✅ 无遗漏、无重复 |

- ✅ 目标侧文件数推算：38 → **49**（净增 11 篇 ADR + 3 篇 README 占位，减去合并的 11 篇与删除的 1 篇）
- ⚠️ **盘点结果与阶段 0 估计的偏差**：RFC 数量从 6 个调整为 **6 个**（不变，但 RFC-1 的
  覆盖范围确认包含 C2M/M2C 的方向总览——因其无独立调研/计划文档）
- 📝 契约改名细节确认：`REQUEST-CONTRACT.md` → `reference/contracts/C2M-REQUEST-CONTRACT.md`
  （加 `C2M-` 前缀消解歧义；同目录将来会有 C2R/R2C 契约）

### 阶段 2（2026-10-20）

- ✅ 搬迁 17 个零改动文件，**逐个核对字节数全部一致**
  - `architecture/` ×5、`reference/` 顶层 ×6、`reference/reports/` ×1
  - `diagrams/cosp-request-flow.drawio`、`images/` ×4
- ✅ 链接扫描：`../..` 类链接（`AGENTS.md`、`.github/`、`tools/`）**全部无需修改**
  （目录深度相同，相对路径天然成立）
- ⚠️ **发现计划缺陷**：10 处 `../features/` 链接的目标属于阶段 3/4，此刻不存在。
  已修正时序——阶段 2 只搬不修链接，全部链接修复移至阶段 6（详见 §4 时序修正节）
- 📝 修正后阶段 4 的 RFC 数量为 **6 个**（新增 5 个 + 已存在的 RFC-6）

### 阶段 3（2026-10-20）

- ✅ 契约 2 篇改名搬入 `reference/contracts/`，字节数一致
  - `REQUEST-CONTRACT.md` → `C2M-REQUEST-CONTRACT.md`（39449 字节）
  - `RESPONSE-CONTRACT.md` → `C2M-RESPONSE-CONTRACT.md`（55737 字节）
- ✅ 样本 8 个搬入 `reference/samples/`，字节数全部一致
  - 7 份抓包原名搬入；`RULE-EXAMPLE.json` → `claude-desktop-cache-rule.json`
- ✅ **契约内部链接全部重算并验证**（19 处，原位置在 `features/.../chat-messages/`，
  新位置 `reference/contracts/` 深度差 2 层）：

  | 原链接 | 新链接 | 处数 |
  |---|---|---|
  | `../../../../AGENTS.md` | `../../../AGENTS.md` | 3 |
  | `../../../reference/COPILOT_BYOK_...md` | `../COPILOT_BYOK_...md` | 3 |
  | `../../../architecture/ADAPTATIONS.md` | `../../architecture/ADAPTATIONS.md` | 2 |
  | `../chat-responses/C2R-RESEARCH.md` | `../../working/rfcs/2026-09-03-protocol-translation.md` | 5 |
  | `../chat-responses/R2C-RESEARCH.md` / `R2C-PLAN.md` | `../../working/rfcs/2026-09-03-protocol-translation.md` | 3 |
  | `./REQUEST-CONTRACT.md` / `./RESPONSE-CONTRACT.md` | 加 `C2M-` 前缀 | 2 |
  | `../../../../tools/mock-upstream/README.md` | `../../../tools/mock-upstream/README.md` | 1 |

- ✅ 链接可达性验证：除 RFC-1（阶段 4 才创建）外全部 EXISTS
- ⚠️ **发现并修正一处深度错误**：思考链调查文档首次改为 `../../`（错，那是 `docs-new/`
  根下的路径），实际应为 `../`（同目录 `reference/` 内）——已修正并复验
- 📝 契约里的 `C2R/R2C 调研` 链接统一指向 RFC-1（合并后的完整档案），
  而非不存在的单篇 RESEARCH 文件

### 阶段 4（2026-10-20）

- ✅ 合成 **6 个 RFC**（5 个新建 + RFC-6 已存在）：

  | RFC | 文件 | 规模 | 源文件数 |
  |---|---|---|---|
  | RFC-1 | `2026-09-03-protocol-translation.md` | **137.2 KB** / 2101 行 | 5（README + C2R/R2C 各两组） |
  | RFC-2 | `2026-09-17-responses-direct.md` | 66.8 KB | 2（SUMMARY + PLAN） |
  | RFC-3 | `2026-09-30-protocol-rename.md` | 15.5 KB | 1 |
  | RFC-4 | `2026-10-17-claude-desktop-prompt-cache.md` | 17.1 KB | 1 |
  | RFC-5 | `2026-10-19-ollama-downstream.md` | 12.3 KB | 1 |
  | RFC-6 | `2026-10-20-docs-migration.md` | 18.9 KB | 本文件 |

- ✅ **合并方法：标题层级降级（H1→H2，H2→H3）+ 机械拼接**，未改写任何正文
  - 字节核对：源文件合计 134.9 KB（5 个进 RFC-1）→ 生成 137.2 KB（+2.3 KB 为新增头部与导航）
- ✅ RFC-1 已按 ADR-0001 要求加**导航目录**（5 个分节 + 锚点链接 + 原始来源对照表）
- ✅ 每个 RFC 都有统一头部：日期（含立项日依据）、状态、相关 ADR、契约/样本链接
- ✅ **链接全部重算**：

  | RFC | 修复处数 | 主要模式 |
  |---|---|---|
  | RFC-1 | 34 | `./chat-messages/*` → `../../reference/contracts/C2M-*`；`./chat-responses/*` → 内部锚点 |
  | RFC-2 | 9 | `./PLAN.md` → 内部锚点；`../protocol-translation/*` → 兄弟 RFC |
  | RFC-3 | 5 | `./chat-messages/*` → `../../reference/contracts/C2M-*` |
  | RFC-4 | 4 | `./RULE-EXAMPLE.json` → `../../reference/samples/claude-desktop-cache-rule.json` |
  | RFC-5 | 7 | `../protocol-translation/README.md` → RFC-1 锚点 |

- ✅ **链接可达性全量验证**：除 5 处指向阶段 5 待写 ADR 的链接外，全部 EXISTS
- ⚠️ **发现的模式**：合并后的跨文件引用统一降级为「指向另一份 RFC + 内部锚点」，
  而非指向不存在的单篇文件。这保证了 RFC 之间形成网状引用而非断链
- 📝 RFC-1 的 §A 子节标题层级修正（`README.md` 的 H2 需降为 H3，因外层已有 `## A.`）

### 阶段 5（2026-10-20）

- ✅ 写完 **10 篇 ADR**（0002–0011），加上 0001 共 **11 篇**：

  | ADR | 主题 | 规模 |
  |---|---|---|
  | [0001](../../adr/adr-0001-adopt-docs-governance.md) | 采纳 docs-governance 并迁移 | 10.2 KB |
  | [0002](../../adr/adr-0002-protocol-naming.md) | 协议翻译命名与缩写体系 | 4.0 KB |
  | [0003](../../adr/adr-0003-translator-faithful-mapping.md) | 翻译器只做字段搬运 | 5.3 KB |
  | [0004](../../adr/adr-0004-c2r-request-translation.md) | C2R 去程六项决策 | 6.1 KB |
  | [0005](../../adr/adr-0005-r2c-response-translation.md) | R2C 回程六项核心决策 | 6.4 KB |
  | [0006](../../adr/adr-0006-responses-direct.md) | Responses 直连端点与执行器 | 6.0 KB |
  | [0007](../../adr/adr-0007-request-rules-over-provider-classes.md) | 规则表达供应商差异 | 6.2 KB |
  | [0008](../../adr/adr-0008-auth-header-by-config.md) | 鉴权头由配置决定 | 4.7 KB |
  | [0009](../../adr/adr-0009-observability-axis.md) | 观测轴判据与单向依赖 | 4.1 KB |
  | [0010](../../adr/adr-0010-known-debt-policy.md) | 刻意不做清单与判断原则 | 4.1 KB |
  | [0011](../../adr/adr-0011-ollama-downstream.md) | Ollama 只作下游 | 5.3 KB |

- ✅ **每篇都遵循 ADR 模板**：元数据（状态/日期/取代/相关）→ 背景 → 决策 → 后果（正+负）→ 备选方案（含具体拒绝理由）→ 参考
- ✅ **粒度：决策族而非单条决策**。例如 ADR-0004 用一节内的六条编号覆盖 C2R 的全部决策，
  共享同一份背景与备选——这正是原决策表做不到的（原表只有"结论 + 理由"两列）
- ✅ **每篇都写了「负面后果」**（知情成本），而非只列优点。这是 ADR 指南的明确要求
- ✅ 备选方案全部给出**具体的拒绝理由**，不是"不够好"
- ✅ 链接验证：仅剩 1 处 `../docs/README.md`（指向旧目录，阶段 7 切换后自然消失）

### 阶段 6（2026-10-20）

**① 修复 docs-new 内部 10 处 `../features/` 链接**

| 文件 | 处数 | 重定向到 |
|---|---|---|
| `architecture/ADAPTATIONS.md` | 3 | `../working/rfcs/...`、`../reference/contracts/C2M-*` |
| `architecture/KNOWN_DEBT.md` | 2 | `../reference/contracts/C2M-*` |
| `architecture/REQUEST_PIPELINE.md` | 2 | 同上 |
| `reference/LIVE_TEST_MATRIX.md` | 2 | 同上 |
| `reference/OLLAMA-EXTENSION-REVIEW.md` | 2 | `../working/rfcs/2026-10-19-ollama-downstream.md` |

**② 决策段与 ADR 建立双向关联（5 处指引）**

采用「**保留决策表 + 加指引头**」而非删除原文：

| RFC | 位置 | 指向 |
|---|---|---|
| RFC-1 | §C0 决策与理由（六项已定） | ADR-0004 |
| RFC-1 | §E0 决策与理由 | ADR-0005 |
| RFC-1 | §D9 已决定的事项 | ADR-0005 |
| RFC-2 | §5.5 两处取舍 | ADR-0006 |
| RFC-3 | §4 明确不做的事 | ADR-0002 |

> **为什么不删除**：RFC 是功能档案，读者需要就地看到决策的字段级细节与实测依据；
> ADR 提供的是**独立可引用性**与**备选方案对比**。二者分工而非替代。
> 这与 ADR-0001 偏离一「RFC 是容器、ADR 是原子」的定位一致。

**③ 外部引用批量改写（21 个文件）**

| 类别 | 文件数 | 说明 |
|---|---|---|
| **Java 源码/测试** | 18 | 仅 Javadoc 注释行（`+21/−21`，**diff 验证全部为注释**） |
| 文档 | 3 | `AGENTS.md`、`tools/mock-upstream/README.md`、`cosp-feature-docs-skill/SKILL.md`（后者已于本次迁移后删除） |

映射规则（18 条路径）：

- 契约：`features/.../chat-messages/*-contract.md` → `reference/contracts/C2M-*.md`
- C2R/R2C 系列 → `working/rfcs/2026-09-03-protocol-translation.md`
- Responses 直连 → `working/rfcs/2026-09-17-responses-direct.md`
- 协议改名 → `working/rfcs/2026-09-30-protocol-rename.md`
- 缓存调查 → `working/rfcs/2026-10-17-claude-desktop-prompt-cache.md`
- **未改**：`architecture/`、`reference/` 顶层路径不变，故 16 处引用保持原样

**④ 验证结果**

| 项 | 结果 |
|---|---|
| `./mvnw compile` | ✅ exit 0 |
| Java diff 性质 | ✅ **全部为注释行**（+21/−21，18 文件） |
| `./mvnw surefire:test` | ✅ **1455 条全绿** |
| 遗留旧路径引用 | ✅ 仅剩 `architecture/` / `reference/` 顶层（路径未变，无需改） |

### 阶段 7（2026-10-20）

**① 切换前检查**

| 项 | 结果 |
|---|---|
| `docs/` 文件数 | 38 |
| `docs-new/` 文件数 | 49（与预测一致） |
| `docs-new/` 死链扫描 | ✅ 无死链 |

**② 执行切换**

```powershell
Rename-Item docs docs-old
Rename-Item docs-new docs
```

结果：`docs/` 49 个文件、`docs-old/` 38 个文件（保留回滚窗口）。

**③ 切换后验证**

| 项 | 结果 |
|---|---|
| `docs/` 死链扫描 | ⚠️ 1 处 → 修正后 **✅ 无死链** |
| 外部引用残留 `docs/features/` | ✅ 无 |
| `AGENTS.md` / `README.md` / skill 引用可达 | ✅ 全部可达 |
| `./mvnw surefire:test` | ✅ **1455 条全绿** |

**④ 修正的最后一处死链**

`adr-0001` 里链接了「被本决策取代的原文档体系」`../docs/README.md`。切换后该路径失效，
且旧文档即将删除——**从「相关」字段移除该链接**（原始文档体系的存在已在 §背景 中文字说明，
不需要链接）。

**⑤ 删除临时映射表**

`MIGRATION-MAP.md` 已删除。理由见 §1 讨论结论第 8 条：38 条源→目标映射在迁移完成后
全部失效，长期价值已被本 RFC 的实施记录与 ADR-0001 覆盖。

**⑥ 最终结构（48 个文件）**

```text
docs/
├── README.md                    文档体系索引
├── adr/                         11 篇（0001–0011）
├── architecture/                5 篇（零改动搬迁）
├── explanations/README.md       占位
├── guides/README.md             占位
├── reference/                   6 篇 + contracts/ 2 篇 + reports/ 1 篇 + samples/ 8 个
├── working/rfcs/                6 个 RFC + README
├── archive/                     空
├── diagrams/                    1
└── images/                      4
```

**⑦ 与预估的偏差**

| 项 | 预估 | 实际 |
|---|---|---|
| 最终文件数 | 49 | **48**（少 1：`MIGRATION-MAP.md` 删除，与预估时未计入该删除一致） |
| 总工时 | 8.5 天 | 实际约 1 天（分多轮完成） |
| 相对链接修复 | 127 处 + 31 外部 + 18 Java | 实际约 70 处（`architecture/` `reference/` 顶层路径未变，省去大量工作） |

> **工时偏差原因**：脚本化批量处理（标题降级、路径替换、死链扫描）替代了手工逐篇编辑。
> 预估按人工编辑计算，实际是工具辅助。

---

## 7. 验收清单（阶段 7 已完成 ✅）

- [x] `docs/` 文件数：**48 个**（38 源文件 − 11 合并进 RFC − 1 旧 README + 11 ADR + 3 README 占位 + 5 新建 RFC + 8 样本迁移净增）
- [x] 死链扫描为 **0**
- [x] `./mvnw.cmd surefire:test` 全量 **1455 绿**
- [x] `AGENTS.md` 19 处、根 `README.md` 7 处、skill 5 处引用全部可达
- [x] 18 处 Java Javadoc 引用路径正确（diff 验证全部为注释行）
- [x] `docs/README.md` 索引覆盖所有目录与关键文档
- [x] `MIGRATION-MAP.md` 已删除（其内容由 ADR-0001 与本 RFC 实施记录覆盖）
- [x] `docs-old/` 备份已删除（验收通过后）
