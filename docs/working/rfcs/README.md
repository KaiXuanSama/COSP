# 功能提案（RFC）

> 需要讨论与实施的大改动先放这里。**结论定稿后晋升到 `adr/`，但文件本身不删除**——
> RFC 是该功能的完整档案（需求 → 调研 → 计划 → 实施记录），ADR 只是其中的决策原子。

## 命名

`YYYY-MM-DD-<slug>.md`

日期取**功能立项日**（不是最后修改日）——它标记的是「这件事从什么时候开始的」。

## 文件头部必须标注

- 日期
- 状态（草稿 / 讨论中 / 已定稿 / 已否决）
- 相关 ADR（若结论已晋升）
- 分支（若挂独立分支）

## 状态的含义

| 状态 | 含义 | 是否可改内容 |
|---|---|---|
| 草稿 | 还在梳理需求 | 自由修改 |
| 讨论中 | 方案已成形，等待确认 | 自由修改 |
| 已定稿，实施待开始 | 结论已晋升 ADR，等待动手 | 仅追加实施记录 |
| 已定稿且实施完毕 | 功能已上线 | 仅追加实施记录 |
| 已否决 | 结论是不做 | 冻结 |

## 当前 RFC

| 文件 | 状态 | 一句话 |
|---|---|---|
| [`2026-09-03-protocol-translation.md`](./2026-09-03-protocol-translation.md) | 已定稿且实施完毕（结论已晋升 ADR-0002 ~ 0005） | 协议翻译四方向（C2M/M2C/C2R/R2C）：Chat ⇄ Messages ⇄ Responses 跨协议链路 |
| [`2026-09-17-responses-direct.md`](./2026-09-17-responses-direct.md) | 已定稿且实施完毕（结论已晋升 ADR-0006） | Responses 协议直连：`/v1/responses` 端点 + V13 迁移 + 独立执行器 |
| [`2026-09-30-protocol-rename.md`](./2026-09-30-protocol-rename.md) | 已定稿且实施完毕（结论已晋升 ADR-0002） | 协议命名重构：`OPENAI` → `CHAT`，枚举与持久化字面量逐字一致 |
| [`2026-10-17-claude-desktop-prompt-cache.md`](./2026-10-17-claude-desktop-prompt-cache.md) | 已定稿且实施完毕 | Claude Desktop 缓存命中率 17% → 99%：断点定位与正则规则修复 |
| [`2026-10-19-ollama-downstream.md`](./2026-10-19-ollama-downstream.md) | 已定稿，实施待开始（结论已晋升 ADR-0011） | Ollama 下游协议（O 轴）：O2C/C2O 等 6 方向 + `/api/chat` 端点 |
| [`2026-10-20-docs-migration.md`](./2026-10-20-docs-migration.md) | 已定稿，实施中（结论已晋升 ADR-0001） | docs/ 迁移至 docs-governance 规范：影子目录 + 最后切换 |
| [`2026-10-20-m2c-c2m-translation.md`](./2026-10-20-m2c-c2m-translation.md) | 实施中（去程[阶段 2](./2026-10-20-m2c-c2m-translation.md#m2c-stage-2)/[三家实机](./2026-10-20-m2c-c2m-translation.md#m2c-live-2026-10-24)、[角色透传实施](./2026-10-20-m2c-c2m-translation.md#m2c-role-passthrough-implementation-2026-10-25)、[defer_loading 实施与语义订正](./2026-10-20-m2c-c2m-translation.md#m2c-defer-loading-implementation-2026-10-26)与[回程阶段 A](./2026-10-25-c2m-response-translation.md#c2m-stage-a)已记录；决策见 ADR-0012/0013/0014/0015/0016/0017） | [M2C 请求契约](../../reference/contracts/M2C-REQUEST-CONTRACT.md) 与 [去程计划 §8](./2026-10-20-m2c-c2m-translation.md#m2c-plan)；真实 HTTP 收包与完整客户端实机待做 |
| [`2026-10-31-c2m-tool-result-media-lift.md`](./2026-10-31-c2m-tool-result-media-lift.md) | 已定稿且实施完毕（自动化与两家供应商实机均通过；决策见 ADR-0019） | C2M 工具结果中的图片移出 tool_result，放到同一 user 轮次后部并带来源标记；Messages 直连不变 |
| [`2026-11-05-call-log-search.md`](./2026-11-05-call-log-search.md) | 已定稿且实施完毕（分支 `feat/log-search`；结论已晋升 ADR-0020；[阶段 3 页面接入与浏览器实测](./2026-11-05-call-log-search.md#call-log-search-stage-3)） | 消费记录检索：时间范围、状态多选、供应商/模型全字或部分匹配；检索模式下忽略 SSE 增量 |
| [`2026-11-06-request-body-stacked-diff.md`](./2026-11-06-request-body-stacked-diff.md) | 已定稿，实施待开始（结论已晋升 ADR-0021） | 规则组约 1:2 左右布局，双容器叠放预览、后景模糊、焦点保护与逻辑行对齐 |
| [`2026-10-25-c2m-response-translation.md`](./2026-10-25-c2m-response-translation.md) | 实施中（[阶段 A](./2026-10-25-c2m-response-translation.md#c2m-stage-a) 已实现并注册、自动化通过；[三家实机](./2026-10-25-c2m-response-translation.md#c2m-live-2026-10-25)已存档；决策见 ADR-0015） | C2M 回程（上游 Chat → 下游 Messages）专项：单块/交错/日志与原生 usage；阶段 B mock/客户端实机未做 |

> **日期口径**：文件名日期取功能的**立项日**——即该功能最早一份文档在 git 中的首次出现日期
> （若经过目录规整搬迁，取搬迁前的原始路径日期）。它标记「这件事从什么时候开始的」，
> 不是最后修改日。
>
> 溯源记录：协议翻译 `2026-09-03`（原 `PROTOCOL_TRANSLATION_CONTRACT.md`）；
> Responses 直连 `2026-09-17`（原 `RESPONSES_PROTOCOL_IMPLEMENTATION_PLAN.md`）；
> 协议改名与 C2R 调研 `2026-09-30`（`254d609` 中新建）；R2C 调研 `2026-10-04`（`f4b1abf`）。

---

## 已知的候选提案

以下内容记录在 ADR 的「后果」与「备选方案」中，若决定推进需先在本目录写 RFC：

- **M2R / R2M 翻译**：Messages ↔ Responses 双向尚未实现（见 ADR-0005 后果节）。
  这是独立方向，**不是文档输入的唯一方案**：Chat 官方也有 file part，支持范围见
  [M2C 最终调研](./2026-10-20-m2c-c2m-translation.md#m2c-final-review)。
- **O2M/O2R 等其余 O 轴方向**：Ollama 下游目前只规划 O2C/C2O（见 ADR-0011）
- **`/v1/messages/count_tokens` 端点**：当前没有实现；是否被具体 Claude Desktop 操作调用需抓包确认。
  属独立 API 用例，估算/委托上游的方案未定，见 [M2C/C2M RFC](./2026-10-20-m2c-c2m-translation.md) §12.6。
- **真流式取消**：agent host BYOK 桥的缓冲式流式（[microsoft/vscode#340983](https://github.com/microsoft/vscode/issues/340983)，属上游问题）
- **契约文档缺口已补齐（2026-10-25）**：`reference/contracts/` 现有
  `C2M-REQUEST-CONTRACT.md`（C2M 链路请求）、`C2M-RESPONSE-CONTRACT.md`（C2M 链路响应，即 M2C 响应）、
  `M2C-REQUEST-CONTRACT.md`（M2C 链路请求，拆分自原 §10）、`M2C-RESPONSE-CONTRACT.md`（M2C 链路响应，即 C2M 响应）、
  `C2R-REQUEST-CONTRACT.md`（C2R 链路请求，即 C2R 去程）、`C2R-RESPONSE-CONTRACT.md`（C2R 链路响应，即 R2C 回程）。
  六份契约两两成链，请求/响应各自独立；C2R/R2C 的详细调研与计划仍只在
  [协议翻译四方向 RFC](./2026-09-03-protocol-translation.md)，契约不含历史过程。
