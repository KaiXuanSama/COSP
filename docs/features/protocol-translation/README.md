# 协议翻译

三个线路协议（CHAT / MESSAGES / RESPONSES）两两之间的六个翻译方向。一对协议一个子目录。

## 方向状态

| 方向 | 含义 | 状态 |
|---|---|---|
| **C2M** | 下游 Chat → 上游 Messages | ✅ 已实现并实测 |
| **M2C** | 下游 Messages → 上游 Chat | 请求侧待做；响应侧已实现并实测 |
| **C2R** | 下游 Chat → 上游 Responses | 🔶 去程已实测可用（[PLAN.md](./chat-responses/PLAN.md) §7）；回程未实现（响应原样透传） |
| **R2C** | 下游 Responses → 上游 Chat | 📋 调研与决策完成（[R2C-RESEARCH.md](./chat-responses/R2C-RESEARCH.md)），未实现 |
| **M2R** / **R2M** | Messages ↔ Responses | ❌ 未实现 |

## 文档

| 文档 | 内容 |
|---|---|
| [chat-messages/REQUEST-CONTRACT.md](./chat-messages/REQUEST-CONTRACT.md) | C2M/M2C 请求侧契约 |
| [chat-messages/RESPONSE-CONTRACT.md](./chat-messages/RESPONSE-CONTRACT.md) | M2C/C2M 响应侧契约（第 16 节含 C2R 实测序列） |
| [chat-responses/C2R-RESEARCH.md](./chat-responses/C2R-RESEARCH.md) | C2R 请求翻译调研（四项目对比 + 真实抓包） |
| [chat-responses/R2C-RESEARCH.md](./chat-responses/R2C-RESEARCH.md) | R2C 回程翻译调研与决策（四项目对比 + 三家联测验证） |
| [chat-responses/samples/](./chat-responses/samples/) | 7 份真实 Codex 抓包（请求 + 响应事件流） |
| [PROTOCOL-RENAME-PLAN.md](./PROTOCOL-RENAME-PLAN.md) | 协议命名重构规划（`OPENAI→CHAT` 改名第一步，已落地） |

## 命名约定

- 方向缩写：`C`=Chat Completions、`M`=Messages、`R`=Responses，`C2M` 读作
  「下游 C 翻译到上游 M」。
- 枚举名与持久化字面量逐字一致（无映射层），见
  [PROTOCOL-RENAME-PLAN.md](./PROTOCOL-RENAME-PLAN.md) §1。
