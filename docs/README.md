# docs/ 导航与分类规则

本文是 `docs/` 的入口。**新增文档前先读第 1 节** —— 它决定新文档放哪。

---

## 1. 分类规则：功能优先，生命周期下沉

判据问两句，按顺序问：

### 第一问：属于哪个功能？

| 答案 | 去处 |
|---|---|
| 属于某个具体功能 | `features/<功能名>/` |
| 被所有功能共享的地基 | `architecture/` |
| 跨功能的参考数据或报告 | `reference/` |

**为什么功能优先**：一个功能的文档天然是散在多个阶段的（调研 → 契约 → 计划 → 测试），
按功能聚拢让「我在做 X 功能」时所有相关文档在一个文件夹里。代码按块组织，文档跟着块走。

### 第二问：在这个功能里处于什么阶段？（决定文件名）

功能文件夹内部用**固定文件名**表达生命周期，不用随意命名：

| 文件名 | 含义 | 何时清理 |
|---|---|---|
| `README.md` | 该功能的状态与导航 | 随功能长期存在 |
| `research.md` | 调研：为决策服务，决策定了就冻结 | **留档**（回答「当初为什么这么选」） |
| `*-contract.md` | 契约/规范：跟着代码长期维护 | **不删** |
| `plan.md` | 实施计划（顶部复述需求，含决策理由） | 留档；功能稳定后可精简 |
| `summary.md` | 实施总结（`plan.md` 的精简版） | **长期留档**（最常被回查的入口） |
| `samples/` | 抓包、样本等原始证据 | **留档**（无法重新获得） |

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
├── architecture/                跨功能的地基（被所有功能依赖）
├── features/                    按功能聚拢
│   ├── protocol-translation/        协议翻译（一对协议一个子目录）
│   │   ├── chat-messages/               C2M / M2C
│   │   └── chat-responses/              C2R / R2C
│   ├── responses-direct/            Responses 直连（已完成）
│   └── provider-integration/        供应商接入与适配
├── reference/                   跨功能的参考数据
│   └── reports/                       实机测试报告
├── diagrams/                    图示（.drawio）
└── images/                      图片
```

**`chat-messages/` 这类子目录的存在理由**：`PROTOCOL_TRANSLATION_CONTRACT.md` 这个旧名
听起来像「所有协议翻译的总契约」，实际只覆盖 Chat↔Messages。**路径消解了歧义** ——
放进 `chat-messages/` 后，文件名不必再强调是哪对协议。

---

## 3. 各目录索引

### architecture/ —— 跨功能地基

被所有功能依赖的机制说明。判据：**它描述的东西不属于任何单一功能**。

| 文件 | 内容 |
|---|---|
| [REQUEST_PIPELINE.md](./architecture/REQUEST_PIPELINE.md) | 下游请求到上游请求的处理层与顺序 |
| [AUTH_HEADER_ASSEMBLY.md](./architecture/AUTH_HEADER_ASSEMBLY.md) | 出站与入站鉴权头的设计（含实测与边界） |
| [PROVIDER_NAMING_CONVENTION.md](./architecture/PROVIDER_NAMING_CONVENTION.md) | 供应商命名与模型名转换 |
| [KNOWN_DEBT.md](./architecture/KNOWN_DEBT.md) | 已知技术债与刻意不做的取舍 |

> 主干-支干的**结构**说明在代码里 ——
> [`src/main/java/.../pipeline/README.md`](../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/README.md)
> 是唯一能跟 `git pull` 走的架构说明，因此不放 `docs/`。

### features/protocol-translation/

一对协议一个子目录。当前四个方向的状态：

| 方向 | 含义 | 状态 |
|---|---|---|
| **C2M** | 下游 Chat → 上游 Messages | ✅ 已实现并实测 |
| **M2C** | 下游 Messages → 上游 Chat | 请求侧待做；响应侧已实现并实测 |
| **C2R** | 下游 Chat → 上游 Responses | ❌ 未实现（[调研](./features/protocol-translation/chat-responses/research.md)已完成） |
| **R2C** | 下游 Responses → 上游 Chat | ❌ 未实现 |

| 文件 | 内容 |
|---|---|
| [chat-messages/request-contract.md](./features/protocol-translation/chat-messages/request-contract.md) | C2M/M2C 请求侧契约 |
| [chat-messages/response-contract.md](./features/protocol-translation/chat-messages/response-contract.md) | M2C/C2M 响应侧契约（第 16 节含 C2R 实测序列） |
| [chat-responses/research.md](./features/protocol-translation/chat-responses/research.md) | C2R 请求翻译调研（四项目对比 + 真实抓包） |
| [chat-responses/samples/](./features/protocol-translation/chat-responses/samples/) | 7 份真实 Codex 抓包（请求 + 响应事件流） |

### features/responses-direct/ —— Responses 直连

| 文件 | 性质 | 内容 |
|---|---|---|
| [plan.md](./features/responses-direct/plan.md) | 计划 | 实施计划（含各阶段实施记录） |
| [implementation.md](./features/responses-direct/implementation.md) | 计划 | 协议语义改名 + Responses 适配的实施说明（写作于 V11，给「另一台机器上的自己」） |
| [protocol-rename-plan.md](./features/responses-direct/protocol-rename-plan.md) | 计划 | 协议命名重构规划（三协议改名第一步） |

> **本功能暂无 `summary.md`。** 三份都是计划类文档；也就是说这个功能缺一份
> 精简的实施总结（见 [功能文档 skill](../.github/skills/cosp-feature-docs-skill/SKILL.md) §3）。
> 补写的素材已齐备：`plan.md` 的各阶段实施记录 + 下文 `provider-integration/` 中的
> 思考链回放调查（那是实施过程中暴露的独立问题）。

### features/provider-integration/ —— 供应商接入

| 文件 | 内容 |
|---|---|
| [ADAPTATIONS.md](./features/provider-integration/ADAPTATIONS.md) | 供应商适配史与请求转换取舍 |
| [ANYROUTER_INVESTIGATION.md](./features/provider-integration/ANYROUTER_INVESTIGATION.md) | anyrouter 供应商行为记录 |
| [COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md](./features/provider-integration/COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md) | Copilot BYOK 思考链回放调查 |

### reference/ —— 参考数据

| 文件 | 内容 |
|---|---|
| [LIVE_TEST_MATRIX.md](./reference/LIVE_TEST_MATRIX.md) | 实机验证矩阵（测什么、用哪个 mock、看什么） |
| [MODEL_COMPATIBILITY.md](./reference/MODEL_COMPATIBILITY.md) | 模型适配问题报告 |
| [MODEL_SUPPORT.md](./reference/MODEL_SUPPORT.md) | 各模型对 Copilot 的适配度 |
| [reports/LIVE_TEST_REPORT_2026-09-27.md](./reference/reports/LIVE_TEST_REPORT_2026-09-27.md) | COSP 实机功能测试报告 |

---

## 4. 新增文档的操作

1. **先问第 1 节的两问**，确定目录与文件名
2. **写内容前，按功能所处的阶段对照** —— 三阶段的骨架
   （`plan.md` 顶部复述需求、`summary.md` 是 `plan.md` 的精简版）见
   [功能文档 skill](../.github/skills/cosp-feature-docs-skill/SKILL.md)
3. **在本文第 3 节对应位置加一行索引** —— 否则新文档无人能找到
4. 若新增的是顶层目录，同步更新 [AGENTS.md](../AGENTS.md) 的参考表

**不要**在 `docs/` 顶层放 `.md` 文件（`docs/README.md` 除外）。顶层只有目录 ——
顶层直接放文件会让「该放哪个子目录」这个问题在下次又被重新讨论一遍。
