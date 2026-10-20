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

- **M2R / R2M 翻译**：Messages ↔ Responses 双向尚未实现（见 ADR-0005 后果节）
- **O2M/O2R 等其余 O 轴方向**：Ollama 下游目前只规划 O2C/C2O（见 ADR-0011）
- **真流式取消**：agent host BYOK 桥的缓冲式流式（[microsoft/vscode#340983](https://github.com/microsoft/vscode/issues/340983)，属上游问题）
