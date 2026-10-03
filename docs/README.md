# docs/ 导航与分类规则

本文是 `docs/` 的入口。**新增文档前先读第 1 节** —— 它决定新文档放哪。

---

## 1. 分类规则：功能优先，生命周期下沉

判据问两句，按顺序问：

### 第一问：属于哪个功能？

| 答案 | 去处 |
|---|---|
| 属于某个具体功能 | `features/<功能名>/` |
| 从已完成的实现里沉淀下来的经验（机制说明、约定、刻意不做的取舍） | `architecture/` |
| 实测出来的数据与结论（模型表现、验证矩阵、调查记录、报告） | `reference/` |

**为什么功能优先**：一个功能的文档天然是散在多个阶段的（调研 → 契约 → 计划 → 测试），
按功能聚拢让「我在做 X 功能」时所有相关文档在一个文件夹里。代码按块组织，文档跟着块走。

### 第二问：在这个功能里处于什么阶段？（决定文件名）

功能文件夹内部用**固定文件名**表达生命周期，不用随意命名：

文件名统一全大写（与 `architecture/`、`reference/` 既有文档一致）：

| 文件名 | 含义 | 何时清理 |
|---|---|---|
| `README.md` | 该功能的状态与导航 | 随功能长期存在 |
| `RESEARCH.md` | 调研：为决策服务，决策定了就冻结 | **留档**（回答「当初为什么这么选」） |
| `*-CONTRACT.md` | 契约/规范：跟着代码长期维护 | **不删** |
| `PLAN.md` | 实施计划（顶部复述需求，含决策理由） | 留档；功能稳定后可精简 |
| `SUMMARY.md` | 实施总结（`PLAN.md` 的精简版） | **长期留档**（最常被回查的入口） |
| `samples/` | 抓包、样本等原始证据 | **留档**（无法重新获得） |

描述性专题文档（如 `PROTOCOL-RENAME-PLAN.md`）同样全大写，不在固定名之列时可自由命名。

**判据是「这份文档什么时候会被删」**：

- 永不删 → 契约
- 决策定了冻结、但留档 → 调研
- 功能上线即失效 → 计划
- 大块功能完成后长期回查 → 总结

> **三阶段模型与各文档的骨架**（`plan.md` 顶部复述需求、`summary.md` 是精简版）
> 见 [功能文档 skill](../.github/skills/cosp-feature-docs-skill/SKILL.md)。
> 本文只定「放哪个目录」，那个定「叫什么、写什么」。
>
> **阶段①「需求描述」不产出文档** —— 需求在对话里谈出来，复述进 `plan.md` 顶部
> 与 `summary.md` 开头。

---

## 2. 目录结构

```text
docs/
├── README.md                    本文
├── architecture/                经验层：沉淀下来的机制说明与约定
├── features/                    按功能聚拢（一次有始有终的开发工作）
│   ├── protocol-translation/        协议翻译（一对协议一个子目录）
│   │   ├── chat-messages/               C2M / M2C
│   │   └── chat-responses/              C2R / R2C
│   └── responses-direct/            Responses 直连（已完成）
├── reference/                   实测层：实测出来的数据与结论
│   └── reports/                       实机测试报告
├── diagrams/                    图示（.drawio）
└── images/                      图片
```

**`chat-messages/` 这类子目录的存在理由**：`PROTOCOL_TRANSLATION_CONTRACT.md` 这个旧名
听起来像「所有协议翻译的总契约」，实际只覆盖 Chat↔Messages。**路径消解了歧义** ——
放进 `chat-messages/` 后，文件名不必再强调是哪对协议。

---

## 3. 各目录索引

### architecture/ —— 经验层

从已完成的实现里沉淀下来的经验：机制说明、约定、刻意不做的取舍。判据：**它描述的东西不属于任何单一功能，且在动手改代码之前值得读一遍**。

| 文件 | 内容 |
|---|---|
| [REQUEST_PIPELINE.md](./architecture/REQUEST_PIPELINE.md) | 下游请求到上游请求的处理层与顺序 |
| [AUTH_HEADER_ASSEMBLY.md](./architecture/AUTH_HEADER_ASSEMBLY.md) | 出站与入站鉴权头的设计（含实测与边界） |
| [PROVIDER_NAMING_CONVENTION.md](./architecture/PROVIDER_NAMING_CONVENTION.md) | 供应商命名与模型名转换 |
| [KNOWN_DEBT.md](./architecture/KNOWN_DEBT.md) | 已知技术债与刻意不做的取舍 |
| [ADAPTATIONS.md](./architecture/ADAPTATIONS.md) | 供应商适配史与请求转换系统的能力边界 |

> 主干-支干的**结构**说明在代码里 ——
> [`src/main/java/.../pipeline/README.md`](../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/README.md)
> 是唯一能跟 `git pull` 走的架构说明，因此不放 `docs/`。

### features/protocol-translation/

一对协议一个子目录。方向状态表与文档索引见该功能自己的
[README.md](./features/protocol-translation/README.md)。

### features/responses-direct/ —— Responses 直连（已完成）

| 文件 | 性质 | 内容 |
|---|---|---|
| [PLAN.md](./features/responses-direct/PLAN.md) | 计划 | 实施计划（含各阶段实施记录） |
| [SUMMARY.md](./features/responses-direct/SUMMARY.md) | 总结 | 精简实施总结（最常被回查的入口） |

### reference/ —— 实测层

实测出来的数据与结论：想知道某个东西**实际表现如何**时打开。单点调查（`*_INVESTIGATION.md`）
的结论全部来自对照实验或行为嗅探，归这里。

| 文件 | 内容 |
|---|---|
| [LIVE_TEST_MATRIX.md](./reference/LIVE_TEST_MATRIX.md) | 实机验证矩阵（测什么、用哪个 mock、看什么） |
| [MODEL_COMPATIBILITY.md](./reference/MODEL_COMPATIBILITY.md) | 模型适配问题报告 |
| [MODEL_SUPPORT.md](./reference/MODEL_SUPPORT.md) | 各模型对 Copilot 的适配度 |
| [ANYROUTER_INVESTIGATION.md](./reference/ANYROUTER_INVESTIGATION.md) | 供应商行为记录 |
| [COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md](./reference/COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md) | Copilot BYOK 思考链回放调查 |
| [reports/LIVE_TEST_REPORT_2026-09-27.md](./reference/reports/LIVE_TEST_REPORT_2026-09-27.md) | COSP 实机功能测试报告 |

---

## 4. 新增文档的操作

1. **先问第 1 节的两问**，确定目录与文件名
2. **写内容前，按功能所处的阶段对照** —— 三阶段的骨架
   （`PLAN.md` 顶部复述需求、`SUMMARY.md` 是 `PLAN.md` 的精简版）见
   [功能文档 skill](../.github/skills/cosp-feature-docs-skill/SKILL.md)
3. **在本文第 3 节对应位置加一行索引** —— 否则新文档无人能找到
4. 若新增的是顶层目录，同步更新 [AGENTS.md](../AGENTS.md) 的参考表

**不要**在 `docs/` 顶层放 `.md` 文件（`docs/README.md` 除外）。顶层只有目录 ——
顶层直接放文件会让「该放哪个子目录」这个问题在下次又被重新讨论一遍。
