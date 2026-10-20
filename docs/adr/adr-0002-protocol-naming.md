# ADR-0002: 协议翻译的方向命名与缩写体系

- **状态**：Accepted
- **日期**：2026-09-30（原 `PROTOCOL-RENAME-PLAN.md`）
- **取代**：（无）
- **相关**：[RFC：协议翻译四方向](../working/rfcs/2026-09-03-protocol-translation.md)、
  [WireProtocol](../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/protocol/WireProtocol.java)

## 背景

三协议初期的枚举值是 `OPENAI` / `ANTHROPIC` / `RESPONSES`——按**供应商**命名。
但 `OPENAI` 与 `RESPONSES` 属于同一个供应商下的两个**不同接口**，枚举名却暗示它们是同一层级的
供应商。这导致两处实际困难：

1. **方向缩写无法统一**。文档里曾同时出现 `O2A`、`A2O`、`C2M`、`C2R` 等多套缩写，
   `O2A`（OpenAI → Anthropic）在加入 Responses 之后无法再准确表达"OpenAI 家族的哪个接口"。
2. **阅读时要把供应商名翻译成接口语义**。看到 `OPENAI` 要停下来想"这是 chat/completions"。

同时有一个**持久化约束**：枚举名直接作为数据库列的字面量存储（`downstream_protocol` /
`upstream_protocol`），没有映射层。改名意味着存量数据也要迁移。

## 决策

**枚举改按「接口形态」命名，与方向缩写体系一起确立。**

### 枚举值

| 旧 | 新 | 接口 |
|---|---|---|
| `OPENAI` | **`CHAT`** | Chat Completions（`/v1/chat/completions`） |
| `ANTHROPIC` | **`MESSAGES`** | Anthropic Messages（`/v1/messages`） |
| `RESPONSES` | `RESPONSES` | OpenAI Responses（`/v1/responses`） |

### 方向缩写

一个字母对应一个接口：**`C`** = Chat Completions、**`M`** = Messages、**`R`** = Responses。
`X2Y` 读作「下游 X 翻译到上游 Y」。

### 逐字一致的约束

**枚举名与持久化字面量逐字一致，不设映射层。** 即数据库里存的字符串就是枚举的 `name()`。

## 后果

**正面**

- 方向缩写有了唯一定义，不再出现 `O2A` 这类依赖供应商名的表达
- 阅读时「看到枚举名即知道接口形态」，不需要心算
- 新增 Responses 方向时缩写体系自然扩展（`C2R` / `R2C`），无需重新设计

**负面**（知情成本）

- **一次性数据迁移**：存量 `api_call_log` 等表的协议列要写 `UPDATE`（已在改名提交中完成）
- **18 处 Java 源码 Javadoc 与 12 篇文档的引用要同步改写**——这是改名的实际成本，
  但只付一次
- 「逐字一致无映射层」意味着**将来若再改名，成本同样是全量迁移**。这个约束是双刃的：
  它换来的是「看到数据库值就懂」的直观性

## 备选方案

### 方案 A：保持 `OPENAI` / `ANTHROPIC`，只在文档里区分接口

- 描述：枚举不动，文档中用 `OPENAI(chat)` / `OPENAI(responses)` 消歧
- 拒绝理由：消歧成本转嫁给每一次阅读；且方向缩写仍然无法自洽（`O2A` 指哪个 OpenAI 接口？）。
  问题不在文档，在命名本身

### 方案 B：按接口路径命名（`CHAT_COMPLETIONS` / `MESSAGES` / `RESPONSES`）

- 描述：用完整接口名，语义最准确
- 拒绝理由：`CHAT_COMPLETIONS` 太长，方向缩写得写成 `CC2M` 这种双字母前缀，
  破坏「一字母一接口」的简洁性。`CHAT` 已经足够准确——它是这套接口的通用简称

### 方案 C：引入映射层（枚举名与存储值解耦）

- 描述：数据库存 `OPENAI`，代码里用 `CHAT`，中间加转换
- 拒绝理由：**多一层就多一处漂移源**。本项目已有「能力由 `caps_tools` 声明而非按模型名猜」
  这类「让事实只有一个来源」的取向，映射层与之相悖

## 参考

- 迁移规划全文：[RFC 协议翻译 §A 命名约定](../working/rfcs/2026-09-03-protocol-translation.md#a-方向总览)
- 协议改名实施记录：[RFC 协议命名重构](../working/rfcs/2026-09-30-protocol-rename.md)
- 枚举定义：[`WireProtocol.java`](../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/protocol/WireProtocol.java)
