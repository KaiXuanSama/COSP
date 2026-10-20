# ADR-0005: R2C 回程翻译的六项核心决策

- **状态**：Accepted
- **日期**：2026-10-04（决策定案）
- **取代**：（无）
- **相关**：[RFC §D R2C 调研](../working/rfcs/2026-09-03-protocol-translation.md#d-r2c-回程--调研)、
  [RFC §E R2C 计划](../working/rfcs/2026-09-03-protocol-translation.md#e-r2c-回程--计划与实施记录)、
  [ADR-0004 C2R](adr-0004-c2r-request-translation.md)

## 背景

R2C = 上游 Responses 响应（非流式 JSON / SSE 事件流）→ 下游 Chat Completions 形态。

这是本项目最复杂的一次翻译：上游是**事件流状态机**（18 类事件），下游是**扁平 chunk 序列**
（`delta` 累加 + `finish_reason` + `[DONE]`）。核心难点不在字段映射，而在**时序**：

- 上游可能**乱序**（MiniMax 实测 `done` 早于 `delta`）
- 上游可能**跳过事件**（只发终态不发 delta）
- 上游可能**不发声明帧**（直接发 delta，没有 `output_item.added`）
- 参数分片粒度**因供应商而异**（stepfun 逐字符 / deepseek 单 delta 全量）

调研对比四项目后，发现有 21 个决策点曾有过实质分歧。本文记录其中六个**核心**决策
（其余按 §6 的倾向列直接实现，无不一致意见）。

## 决策

### 1. usage 收尾 chunk：对齐 C2M 规范

独立 usage chunk（`choices: []`），收尾时统一发出，**finish 在前、usage 在后**，
仅 `IncludeUsage && hasUsage()` 时发。

依据：本服务「向上游发什么请求就保留该请求回传的 usage 形态」的既有规范。C2M 的
`usageFrame` Javadoc 记录了踩坑历史——**跟着 finish 发会产生两个 usage chunk（双倍计费记录），
且以抢跑值覆盖后续累积**。

### 2. `IncludeUsage` 的传入路径：`TranslationContext.includeUsage`

取 C2R 去程记下的下游原值；**仅流式消费**。

`TranslationContext` 这个 record 的字段设计就是为「响应侧需要知道请求侧事实」——
`includeUsage` 必须在 `stream_options` 被丢弃前记下。

### 3. `response.created` 缺失的兜底：混合方案

created 到达时发 role 帧；**同时在首个内容事件**（text/reasoning/tool delta）检查
「role 未发则带上」。

前半段语义清晰（new-api / sub2api 做法）；后半段借 CPA 的「不依赖 created」鲁棒性。
两行成本防住一类不发 created 的上游。

### 4. `output_item.added` 缺失的兜底：pending 存留

delta 早于工具注册时先存 `pendingArgsByOutputIndex` / `pendingArgsByItemID`，
注册时拼入。

与「前缀补齐」同源，两者合起来即 new-api 的完整乱序方案。极简上游（直接发 delta
不发 added）也能收齐参数。

### 5. reasoning 的 `\n\n` 分隔：「标记 + 差额」

段结束事件（`reasoning_text.done` 等）**只设 `needsReasoningBreak` 标记**；
下个 delta 到达时看其开头换行数，**只补足到 2 个**（本身以 `\n\n` 开头则不补）。

对比：done 时立即发固定 `\n\n`（CPA 做法）会在「下个 delta 自带换行」时变成四个换行；
sub2api 完全不加分隔会让多段思考糊在一起。**标记 + 差额是唯一不产生多余空行的做法。**

### 6. `output[].phase` / `encrypted_content`：丢弃 + 日志留痕

Chat 协议无承载位置（`reasoning_content` 是单字符串字段，装不下密文；`phase` 无对应概念）。

对齐 C2M 的 `redacted_thinking` 处置先例：**吸收但不产帧**，记 debug/warn 让
「内容凭空变少」在日志里有痕迹。

### 其余 15 项（无分歧，按调研 §6 倾向列实现）

含：content 空行分隔、reasoning 读取 content 优先 summary 兜底、工具三级映射键、
前缀补齐、finish_reason 推导、model 回显下游名、id/created 原样等。

## 后果

**正面**

- 三个兜底机制（created 缺失 / added 缺失 / 乱序）分别在**两行到十几行**的实现成本内，
  覆盖了实测到的全部上游变体
- 「标记 + 差额」而非「立即补分隔」避免了一类难以发现的格式退化（四个换行）
- usage 收尾对齐 C2M，落库层零改动（`usage_raw` 原样存档 + `ResponsesUsageParser`
  已随 C2R 就位）

**负面**（知情成本）

- **状态机是 R2C 最复杂的部分**：`R2CStreamState` 维护三级映射键（`item_id` /
  `call_id` / `output_index`）、前缀已发长度、pending 队列、两组标记位。任何新增事件类型
  都要考虑这三个兜底路径
- **丢弃密文意味着多轮思考依赖明文**。若将来上游只发密文不发明文
  （`reasoning_text` 缺失而只有 `encrypted_content`），R2C 会丢掉这部分思考——
  届时需要重新决策（新建 ADR 取代本条）
- **`phase` 丢弃后无法区分 reasoning 的阶段**，下游只能看到一个连续的思考串

## 备选方案

### 方案 A：reasoning 分隔在 done 时立即发 `\n\n`（CPA 路线）

- 描述：段结束时无条件补两个换行
- 拒绝理由：若下个 delta 本身以换行开头，结果变成四个换行。实测多家上游的 delta
  开头带换行，这不是理论风险

### 方案 B：完全不加分隔（sub2api 路线）

- 描述：多段 reasoning 直接拼接
- 拒绝理由：多段思考糊成一串，模型和人都无法区分段落边界

### 方案 C：严格按事件顺序处理，不做乱序兜底

- 描述：假设上游总是 `added → delta ×N → done`
- 拒绝理由：cc-switch 的实测记录（MiniMax，2026-10-03）证明 `done` 会早于 `delta` 到达。
  不做兜底会导致参数丢失且无告警

### 方案 D：usage 跟 finish 同一个 chunk 发

- 描述：省一个 chunk，下游解析更简单
- 拒绝理由：C2M 的踩坑历史明确记录——会产生两个 usage chunk（双倍计费记录），
  且以抢跑值覆盖后续累积。同一个 bug 不应在 R2C 重演

## 参考

- 完整调研（四项目对比 + 乱序实测 + 三家联测）：[RFC §D](../working/rfcs/2026-09-03-protocol-translation.md#d-r2c-回程--调研)
- 实施记录（三阶段 + 三个实机修复）：[RFC §E](../working/rfcs/2026-09-03-protocol-translation.md#e-r2c-回程--计划与实施记录)
- 代码：`ResponsesToChatResponseTranslator`（门面）、`R2CStreamState`、
  `ResponsesToChatStreamTranslator`、`ResponsesToChatUsageConverter`
