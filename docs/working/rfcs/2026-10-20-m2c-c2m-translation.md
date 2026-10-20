# RFC：M2C 去程与 C2M 回程翻译（下游 Anthropic → 上游 Chat 的完整链路）

- **日期**：2026-10-20（立项日）
- **状态**：**讨论中** —— 结构调研已完成，4 项待定决策见 §5
- **相关 ADR**：（尚无；决策定稿后新建 ADR 承载「M2C 去程思考/文档块处置」决策族）
- **分支**：`feat/m2c-request-c2m-response-translation`
- **前置文档**：
  [协议翻译四方向 RFC](./2026-09-03-protocol-translation.md) §A 方向总览、
  [C2M 请求契约](../../reference/contracts/C2M-REQUEST-CONTRACT.md) §4.3（M2C 思考映射）、
  [C2M 响应契约](../../reference/contracts/C2M-RESPONSE-CONTRACT.md) §0

> **需求复述**：用户近期开始把 Claude Desktop 作为常用客户端，其**文档处理能力**是主要动机，
> 因此 `/v1/messages` 这条下游路径从「能用」升级为「常用」。
> 当前 COSP 在该路径上只支持 **M→M 直连**与 **C→M**（下游 Chat、上游 Messages）；
> 一旦供应商只有 OpenAI 兼容端点（即上游 CHAT），M2C 去程翻译缺失会让整个请求
> 在协议调度阶段就被拒（`ProtocolTranslationNotSupportedException`，控制器译 400）。
> 本轮要补齐 **M2C 去程 + C2M 回程**这一对，使「下游 Anthropic、上游任何 OpenAI 兼容服务」
> 端到端可用。
>
> **本轮只做结构调研**，不写实现代码。本文记录「接在哪、缺什么、有哪些必须拍板的选择」。

---

## 1. 方向命名：先避开一个真实的歧义

现有文档里 **「M2C 响应」不等于「M2C 的回程」**，必须先钉住，否则实施时一定会接错方向。

约定（源自 [Ollama 下游 RFC](./2026-10-19-ollama-downstream.md) §2 对同一问题的记录）：

- **请求侧缩写**：`X2Y` = 「下游 X 的请求 → 上游 Y」
- **响应侧缩写**：`X2Y` = 「上游 X 的响应 → 下游 Y」（沿用「上游在前」的写法）

于是同一对协议的两半恰好共用**同一个缩写**，但含义相反：

| 本文要做的 | 缩写 | 实际含义 | 声明 `(downstream, upstream)` |
|---|---|---|---|
| 去程 | **M2C 请求** | 下游 Messages 请求 → 上游 Chat 请求 | `(MESSAGES, CHAT)` |
| 回程 | **C2M 响应** | 上游 Chat 响应 → 下游 Messages 响应 | `(MESSAGES, CHAT)` |

已落地的对照（`ProtocolTranslator` 的「实现现状」段，本次核对确认）：

| 已存在 | 类 | 声明 |
|---|---|---|
| C2M 请求 | `ChatToMessagesRequestTranslator` | `(CHAT, MESSAGES)` |
| M2C 响应 | `MessagesToChatResponseTranslator` | `(CHAT, MESSAGES)` |
| C2R 请求 | `ChatToResponsesRequestTranslator` | `(CHAT, RESPONSES)` |
| R2C 响应 | `ResponsesToChatResponseTranslator` | `(CHAT, RESPONSES)` |

> **实施时以 `downstreamProtocol()` / `upstreamProtocol()` 两个声明方法为准，不要靠缩写或类名猜。**
> `TranslatorRegistry` 的两张表也是按这两个方法建的，猜错方向的症状是查表未命中 →
> 报「翻译不支持」，而错误消息会把人引向「去检查供应商勾了什么协议」。
>
> 另一处容易搞混的地方：**`MessagesToChatResponseTranslator` 名字里的 `MessagesToChat` 是「响应流向」**，
> 它是 C2M 的回程，不是本文要补的那个方向。本文的回程类应命名为
> `ChatToMessagesResponseTranslator`（上游 Chat → 下游 Messages）。

---

## 2. 现状盘点：这条链路缺什么

### 2.1 缺的两块

| # | 缺什么 | 现状后果 |
|---|---|---|
| 1 | `(MESSAGES, CHAT)` 的**去程**翻译器 | `ProtocolDispatchManager.dispatch` 在供应商不勾 MESSAGES、只勾 CHAT 时，规则 2 找不到已实现的候选方向，抛 `ProtocolTranslationNotSupportedException` → 400。**这是本文要解决的主问题。** |
| 2 | `(MESSAGES, CHAT)` 的**回程**翻译器 | 去程补上后若回程仍缺，`AfterSend` 会走 `warnIfHalfRound` 透传上游原生 Chat chunk。下游 Claude Desktop 解析不了 Chat chunk，表现为「界面卡住 / 无输出」。 |

### 2.2 已经就绪的（**不需要改动**，已逐个核对）

这一节是本轮调研最重要的产出：M2C/C2M 是**纯增量接线**，主干、注册表、出口层一行都不用改。

| 组件 | 为什么不用改 |
|---|---|
| `MessagesService` | 早已退化成「声明协议 + 建 ctx + 交主干」，翻译分支不在它里面 |
| `BeforeSend.translateStep` | 按 `(downstream, upstream)` 查两张表；去程未命中才抛错，命中即改写 body |
| `AfterSend.process` | 回程翻译器从 `ctx.responseTranslator()` 读，本块不自己查表、不加协议分支 |
| `ProtocolDispatchManager` | 构造期从 `List<RequestProtocolTranslator>` 收集 `implementedRequestRoutes`，新 `@Component` 自动进入该集合 |
| `TranslatorRegistry` | 集合注入 + 按方向建两张表，零改动 |
| `RequestPipelineContext` | `applyTranslation` 同时替换 body 与 `bodyProtocol`，查表键自动切到 CHAT |
| `GenericOpenAiChatService` / `AbstractUpstreamChatService` | 上游是 CHAT，执行器与 usage 解析现成 |
| `ChatOutboundStage` | 读 `base_url`，无协议必需头（对比 `MessagesOutboundStage` 补 `anthropic-version`） |
| `ChatContentDetectorStage` | 空响应判定的键是 `upstreamProtocol = CHAT`，命中 |
| `ChatChunkNormalizeStage` / `ChatReasoningFallbackStage` | 键同为 `upstreamProtocol`，命中 |
| `AnthropicController` | 出口只认 `UpstreamEvent` 形态，不关心帧是怎么翻译来的 |
| `StreamLifecycle` / `NonStreamLifecycle` | 同上；Anthropic 侧靠 `extractEventType` 回填 SSE 的 `event:` 名 |
| `UpstreamEventClassifier` | 按传入协议判定终止标记，回程翻译器只要产出 `message_stop` 即可收尾 |
| `GatewayAuthFilter.PROTECTED_PATHS` | `/v1/messages` 已在列 |
| `ProviderRouteResolver` | 路由只由模型名决定，协议不参与候选集筛选 |
| `ProviderProtocolSupport` | 默认全勾，MESSAGES 本来就是合法取值 |
| 前端 `ALL_WIRE_PROTOCOLS` / `RULE_ENGINE_WIRE_PROTOCOLS` | 三协议早已齐备 |
| `tools/mock-upstream` | 已同时提供 `/chat/completions` 与 `/messages`，实机验证无需改 mock |

### 2.3 必须同步更新（否则测试会红，或注释会说谎）

| 位置 | 现状问题 |
|---|---|
| `ProtocolTranslator` 的 `TODO(待实现)` 注释块 | **已过期且会误导**：它写「`MessagesService` 目前尚未改用查表……需先把 `MessagesService` 的翻译分支改成查 `TranslatorRegistry`」。实际该分支**早已上移主干**，`MessagesService` 里根本没有翻译分支。照着做会去改一个不存在的东西。 |
| `ProtocolTranslator` 的「实现现状」表 | M2C 请求 / C2M 响应两条「未实现」要改写 |
| `ProtocolDispatchManager.TRANSLATION_FALLBACK_ORDER` 注释 | 现有依据写「MESSAGES —— 已有一个方向完整实现（C2M 去程 + M2C 回程）」，届时 MESSAGES 作为**下游**也完整，排序理由需重述 |
| `ProtocolDispatchManagerTests.skipsUnimplementedCandidateAndFallsToNextImplemented` | 该用例的前提是「`(MESSAGES, CHAT)` 未实现 → 跳过 CHAT 降到 RESPONSES」。M2C 落地后此断言必红，需换成仍未实现的方向（如 `(MESSAGES, RESPONSES)`） |
| `ChatDispatchErrorSignalTests` 中「下游 Anthropic 而供应商只有 OpenAI（M2C 请求翻译未实现）」一组 | 前提消失，需改为「已实现路径」或改方向 |
| `TranslatorRegistrySpringWiringTests` | 目前只断言 `(CHAT, MESSAGES)` 的两半；应补 `(MESSAGES, CHAT)` 的 `containsSame` 断言，理由见该类的类注释：装配漏了**不响**，只在第一个跨协议请求时暴露 |
| `pipeline/README.md` 的翻译支线一节 | 「19 个实现」的计数与「四方向双通」的描述 |
| 本目录 `README.md` 的状态表 | 新增本 RFC 一行 |
| [协议翻译 RFC](./2026-09-03-protocol-translation.md) §A 方向状态表 | M2C 从「请求侧待做；响应侧已实现」改为「请求侧 + 回程双向可用」，并说清这与「C2M 回程」是两件事 |

---

## 3. 组件清单

### 3.1 新建：去程 M2C

门面 `MessagesToChatRequestTranslator implements RequestProtocolTranslator`，
声明 `(MESSAGES, CHAT)`，带 `@Component`。

按项目既有粒度（对齐 `ChatToMessagesRequestTranslator` 下的协作类拆分），门面之下需要：

| 类 | 职责 | 对应 C2M 侧的先例 |
|---|---|---|
| `AnthropicMessageTranslator` | Anthropic `messages[]` → Chat `messages[]`：`tool_use` → 顶层 `tool_calls`、`tool_result` → 独立 `{role:"tool"}` 消息、`image` → `image_url`、`thinking` 丢弃 | `MessageTranslator` |
| `AnthropicContentBlockTranslator` | 单块转换（`text` / `image` / `document` / `tool_use` / `tool_result`） | `ContentBlockTranslator` |
| `AnthropicToolTranslator` | `{name, description, input_schema}` → `{type:"function", function:{...}}`；`tool_choice` 三态反向映射 | `ToolTranslator` |
| `ToolPairingNormalizer`（复用或新增反向版） | 见 §4.2，**方向相反** | `ToolPairingNormalizer` |

### 3.2 新建：回程 C2M

门面 `ChatToMessagesResponseTranslator implements ResponseProtocolTranslator`，
声明 `(MESSAGES, CHAT)`，带 `@Component`。

| 类 | 职责 | 对应 M2C 侧的先例 |
|---|---|---|
| `C2MStreamState` | 跨事件状态机：Chat chunk 流 → `message_start` / `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop` | `M2CStreamState` |
| `ChatToMessagesNonStreamTranslator` | `chat.completion` JSON → Anthropic `message` JSON | `MessagesToChatNonStreamTranslator` |
| `ChatToMessagesStreamTranslator` | 逐 chunk 翻译 + 收尾 | `MessagesToChatStreamTranslator` |
| `FinishReasonMapper` | `stop`→`end_turn`、`tool_calls`→`tool_use`、`length`→`max_tokens`、`content_filter`→`refusal` | `StopReasonMapper`（反向） |
| `ChatUsageConverter` | **项目内不存在，必须新建**：Chat `usage` → Anthropic 三段式 | `AnthropicUsageAccumulator`（反向） |
| `AnthropicMessageShapes` | Anthropic 响应报文的形态常量（`type` 字段、SSE event 名） | `OpenAiResponseShapes` |

> **`ChatUsageConverter` 是唯一「没有现成参照」的协作类**。已核对：
> `AnthropicUsageAccumulator` 是 Anthropic → OpenAI 方向；
> `ResponsesToChatUsageConverter` 是 Responses → Chat 方向；
> **Chat → Anthropic 方向的换算器当前不存在**。换算规则见 §4.3。

### 3.3 落库与日志：零接线，但有前提

`AfterSend.chunkRewriterFor` 只在回程命中后构造 rewriter。因此 C2M 回程一注册，
`api_call_log` 就会自动同时记录「上游 Chat chunk」与「下游 Anthropic 事件」两栏 + `frameCounts`，
日志页两栏对齐视图随之生效。

**前提**：`translateChunksForLog` 必须用**独立状态重放**，不能复用出站流的状态。
M2C 侧的实现注释已记录这个坑（落库发生在 `doFinally`，此时出站状态机的 `finalized` 已置位，
复用会产出不完整帧序列）。

---

## 4. 字段规格要点（完整契约另立）

本节只记录**结构性**映射与已知坑，逐字段清单在实施阶段写入契约文档。
现有 [C2M 请求契约](../../reference/contracts/C2M-REQUEST-CONTRACT.md) §4.3 已定的 M2C 思考映射
仍是有效前提，不在此重复。

### 4.1 顶层字段搬运

去程需要显式处置的字段（其余按白名单制丢弃，理由同 C2M：链条末尾只有 `removeIf(Objects::isNull)`，
非 null 的多余字段会原样发给上游然后 400）：

| Anthropic | Chat | 处理 |
|---|---|---|
| `model` / `stream` | 同 | 直通（`stream` 随后被 `RequestBodyAssembler` 覆写） |
| `max_tokens` | `max_tokens` | 直通，**不补默认值**（由 `MaxOutputTokensSetting` 管，但那一步对 CHAT 无支线） |
| `temperature` / `top_p` / `top_k` | `temperature` / `top_p` | `top_k` **无对应**，丢弃 |
| `stop_sequences` | `stop` | 数组 → 数组；空数组省略 |
| `system` | **落回 `messages[]`** | 见 §5.1（**待定决策 1**） |
| `tools` / `tool_choice` | 同 | 结构转换，见 §4.2 |
| `thinking` / `output_config.effort` | `reasoning_effort` | 依契约 §4.3：只在 `output_config.effort` 存在时翻译深度；`thinking` 只搬开关语义 |
| `metadata` / `service_tier` / `container` / `mcp_servers` / `context_management` | — | 丢弃（契约 §4.3 已列） |

### 4.2 工具配对：**方向相反**，不能直接复用

C2M 侧的 `ToolPairingNormalizer` 修的是「OpenAI 邻接 → Anthropic 邻接」：
把散落的 `role:"tool"` 消息**合并**成紧邻 `tool_use` 的 `tool_result` 块。

M2C 需要的是反向：Anthropic `user` 消息里的 `tool_result` 块**拆出来**，
成为独立的 `{role:"tool", tool_call_id, content}` 消息，且必须紧跟对应的 assistant 消息。

**这是本轮最容易静默出错的一处**：配错方向不会编译失败（两个方法签名可以完全相同），
症状是上游收到合法 JSON 但工具结果和调用对不上，模型行为诡异而没有报错。
建议新类命名为 `AnthropicToolResultExpander`，并在类注释里显式指向 `ToolPairingNormalizer` 说明这是反向。

同时：孤儿 `tool_result`（找不到匹配的 `tool_use`）的处置应与 C2M 同口径 —— **丢弃并留痕**，
不凭空造 `tool_calls` 去配。

### 4.3 usage 换算

```
Anthropic input_tokens                = Chat prompt_tokens − prompt_tokens_details.cached_tokens
Anthropic cache_read_input_tokens     = Chat prompt_tokens_details.cached_tokens
Anthropic cache_creation_input_tokens = 0
Anthropic output_tokens               = Chat completion_tokens

Anthropic total_tokens 不是字段 —— Anthropic 的 usage 没有 total
```

**Why 要减**：Anthropic 的 `input_tokens` **不含缓存部分**（缓存读写是并列的两列），
而 OpenAI 的 `prompt_tokens` **已含缓存**（`cached_tokens` 只是其中标注出来的子集）。
直接搬会让输入 token 被重复计一次，使用统计随之偏高。
`AnthropicUsageAccumulator` 的类注释记录了同一个事实的反方向表述，可直接引用。

### 4.4 流式：帧数不对等与终止标记

C2M 回程必须处理两类「形态契约」，缺一个是硬失败而非降级：

1. **每帧 JSON 必须有 `type` 字段** —— `AnthropicController.extractEventType` 靠它回填 SSE 的
   `event:` 名。Anthropic 客户端是状态机，只发 `data` 无法解析（会表现为「流有数据但界面不动」）。
2. **末尾必须产出 `message_stop`** —— `UpstreamEventClassifier` 按 MESSAGES 协议识别它，
   `StreamLifecycle` 的 Layer 1 靠它即时收尾。缺它则只能等 Layer 2（连接关闭）兜底，
   而部分上游发完不关连接（HTTP keep-alive），表现为 Toast 永远悬挂在 CHUNK。
   这与 M2C 侧「`[DONE]` 由流结束触发而非绑定某个事件」是同一类问题，处置方向相反。

**不需要**产出 `[DONE]`（那是 Chat 的哨兵）。

帧数对照（Chat → Anthropic）：

| 帧数 | Chat chunk |
|---|---|
| 0 | `{role:"assistant"}` 空 delta、`finish_reason` 非 null 但无 delta |
| 1 | 首个带内容的 chunk（同时产 `message_start` 需要提前发出 —— 见下） |
| 多 | 收尾（`message_delta` + `message_stop`）、带 `tool_calls` 的首个 delta（同时开 `content_block`） |

> **`message_start` 的时机**：Anthropic 要求它是**第一帧**，且携带 usage（输入 token）。
> 但 Chat 的 usage 通常在**最后一个** chunk（且只有 `stream_options.include_usage` 时才给）。
> 因此 `C2MStreamState` 必须在**首个 chunk 到达时**发 `message_start`，
> 其 usage 字段填已知值（通常是 0），真实 usage 在 `message_delta` 里补 ——
> 这与 Anthropic 官方流的行为一致（`message_start` 的 `output_tokens` 恒为 1 或 0，
> `message_delta` 才给最终值）。这一条需要在实施时用真实上游实测确认。

---

## 5. 待定决策（本轮讨论的对象）

以下 4 项都不是「实现细节」，而是会改变架构或产品行为的取舍，需要先定。

### 5.1 决策 1：`system` 如何落回 `messages[]`

**问题**：Anthropic 的 `system` 是**顶层字段**，Chat 的 system 是 **`messages[]` 里的一条**。
搬过去需要一个「落回」动作。而 `RequestBodyAssembler` 按 `ctx.bodyProtocol()` 查三张支线表：

| 步骤 | 现有 MESSAGES 支线 | 现有 CHAT 支线 |
|---|---|---|
| system 归一 | `MessagesSystemPromptStage`（抬升到顶层） | **无实现** |
| max_tokens | `MessagesMaxTokensStage` | 无（Chat 侧可选，跳过正确） |
| 思考注入 | `MessagesThinkingStage` | `ChatThinkingStage`（写 `reasoning_effort`） |

M2C 翻译后 `bodyProtocol` 切成 CHAT，于是 system 那一步**查不到实现 → 跳过**。

**两个方案**：

| 方案 | 做法 | 代价 |
|---|---|---|
| **A. 翻译器内联** | 在 `MessagesToChatRequestTranslator.translateRequest` 里把顶层 `system` 插成 `messages[0]` | 简单、不碰骨架；但违反 C2M 已确立的分工先例（「翻译器不管 system 语义归一化」，见 `ChatToMessagesRequestTranslator` 类注释的「本类不做什么」） |
| **B. 新增支线** | 新建 `ChatSystemPromptStage implements SystemPromptNormalizeStage` + `SystemPromptNormalizer.inlineSystemIntoMessages` | 与 C2M 完全对称、符合四轴框架；但该支线对**直连 CHAT** 也会执行 —— 实际是 no-op（Chat 客户端不发顶层 `system`），但这是一条「新增的每次请求都会跑的路径」 |

**倾向 B**，理由是四轴框架的判据是「协议差异应是数据/支线而非主干分叉」，
而 A 把协议差异塞进了翻译器。但 B 需要确认「直连 CHAT 时的 no-op 是否真的无害」
（若某个客户端**同时**发顶层 `system` 和 `messages` 里的 system，B 会改变其行为）。

### 5.2 决策 2：usage 是否无条件发出

**问题**：`TranslationContext` 只有两个字段 `(wasStream, includeUsage)`，
而 `includeUsage` 读的是 Chat 专属的 `stream_options.include_usage`。
Anthropic 下游**没有这个概念** —— 它的 SSE 协议规定 `message_start` / `message_delta` 必须带 usage。

**选项**：

| 方案 | 做法 |
|---|---|
| **A. 无条件发** | `C2MStreamState` 不看 `context.includeUsage()`，恒发 usage 帧 |
| **B. 扩 `TranslationContext`** | 加一个 `downstreamExpectsUsage` 字段，由翻译器按下游协议决定 |

**倾向 A**：`includeUsage` 的语义是「下游**要求**附带」，而 Anthropic 是「下游**协议规定**必须带」——
这是两种不同的事实。A 在翻译器里硬编码符合「谁的协议谁负责」，B 会让 `TranslationContext`
承载一个只有一条链用得上、且值恒为 true 的字段。

### 5.3 决策 3：`document` 内容块如何处置（**与本次需求直接相关**）

**问题**：Claude Desktop 的文档处理能力靠 Anthropic 的 **`document` content block**
（base64 PDF / 纯文本文件）。而 **Chat Completions 协议没有文档类型** —— 它只有 `image_url`。

| 上游协议 | 文档能否透传 |
|---|---|
| CHAT | ❌ |
| RESPONSES | ✅（`input_file`）—— 但那是 M2R 方向，不在本分支 |

**候选处置**：

1. **丢弃 + `log.warn`** —— 与 C2M 对 hosted 工具块的处置同调（`.orElse(null)`）
2. **丢弃 + 在下游响应里追加可见提示** —— 但这是代理在伪造模型输出，语义上是错的
3. **提取 PDF 文本后塞进 content** —— 需要 PDF 解析依赖，超出翻译层职责，且会改变 token 计数
4. **报错拒绝整条请求**（`RequestTranslationException`）—— 与 C2M 的「不认识的块类型报错」同调

**这是个产品决定而非技术决定**：用户明确说文档处理是选 Claude Desktop 的主要原因，
那么「静默丢弃文档」等于让 COSP 在这个场景下**看起来能用但结果是错的** —— 这正是项目最警惕的失效形态。

**需要用户拍板**。若选 4，则本 RFC 的收益需要重新评估（因为最常见场景会直接失败）。

> **附带事实**：`/v1/messages/count_tokens` 端点**不存在**（已核对，全项目无此路径）。
> Claude Desktop 在长上下文 / 文档场景会调它，当前得到 404。
> 这不属于翻译层，但会成为下一个缺口 —— 建议先记入 `KNOWN_DEBT.md`，避免届时误判为翻译 bug。

### 5.4 决策 4：`cache_control` 断点的处置

**问题**：Claude Desktop 依赖 `system` 块与 `messages` 块上的 `cache_control: {type:"ephemeral"}`
标记缓存断点（见 [Claude Desktop 缓存 RFC](./2026-10-17-claude-desktop-prompt-cache.md)）。
Chat 协议**没有对等物** —— OpenAI 的自动前缀缓存不读这个字段。

**可以做的**：把 `cache_control` 直接丢弃（它是 Anthropic 方言，发给 Chat 上游会被拒或被忽略）。
**后果**：走 M2C 路线时缓存命中率必然归零。

**这是可以接受的**（用户的缓存优化工作在 M→M 直连路径上，M2C 只在上游为纯 OpenAI 兼容时启用），
但**必须写进契约的「明确不做的事」并留一句已知限制**，否则以后会有人以为是 bug。

---

## 6. 明确不做的事

这一节回答「为什么当初没顺手做 X」。

| 不做 | 理由 |
|---|---|
| **M2R / R2M（Messages ↔ Responses）** | 另一对协议方向，与本文无关。文档走 Responses 上游才是「文档能透传」的正解，但那是独立功能，另行立项 |
| **`/v1/messages/count_tokens` 端点** | 不属于翻译层（不产生上游调用），且归一化口径（哪种 tokenizer）本身是个待调研问题。先记 `KNOWN_DEBT.md` |
| **PDF / 文档内容提取** | 超出翻译层职责。协议翻译只搬运，不做内容理解 —— 这是 `ProtocolTranslator` 与 ADR-0003「翻译器只做字段搬运」确立的边界 |
| **`budget_tokens` ↔ 档位换算** | 契约 §4.4 已定「不做」。三家参考项目的换算表毫无共识，反向表的阈值也各不相同 |
| **自动降级（如 `minimal` → `low`）** | 契约 §4.5 已定「不做」。让上游用错误码回答比静默降级诚实 |
| **思考链重建（`signature`）** | 契约 §4.7 已定「已知无解」。`signature` 由 Anthropic 签发，跨协议造不出来 |
| **修主干 / 注册表 / 出口层** | 已核对全部就绪（§2.2）。任何「顺手改一下主干」的冲动都应视为越界 |
| **协议流转编排** | `ProtocolDispatchManager.TRANSLATION_FALLBACK_ORDER` 的 TODO 是独立功能（用户按供应商/模型指定映射），不在本文范围 |

---

## 7. 分阶段计划

按项目既有的「让已有的一半变成可用的整体」原则排序（见 C2M 响应契约 §0 的同一条理由）。

### 阶段一：去程 M2C 单独上线

只加 `MessagesToChatRequestTranslator` 及协作类，**不加回程**。

- 此时 `AfterSend` 会走 `warnIfHalfRound`，下游收到**上游原生 Chat chunk**
- 这正是 `TranslatorRegistry` 设计里那个「正常中间态」：可以用真实上游验证
  「Anthropic 请求是否被 OpenAI 兼容上游接受」
- 落库会记录上游原生 chunk（`chunkRewriterFor` 返回 null，因为回程未命中）

**验收**：mock-upstream 的 `/chat/completions` 端点被真实调用，且 COSP 日志的
`downstream_protocol = MESSAGES` / `upstream_protocol = CHAT` 两列正确。

### 阶段二：回程 C2M 非流式

- 加 `ChatToMessagesNonStreamTranslator` + `ChatUsageConverter` + `FinishReasonMapper`
- 非流式形态简单、无状态机，可先把字段映射、usage 换算、`finish_reason` 映射钉死
- 流式那侧的每一条映射都能复用这里的结论

**验收**：Claude Desktop 非流式对话可用，usage 数值与上游 `prompt_tokens` 对得上（含缓存减法）。

### 阶段三：回程 C2M 流式

- 加 `C2MStreamState` + `ChatToMessagesStreamTranslator` + `AnthropicMessageShapes`
- **`Flux.defer` 内建状态**：`retryWhen` 会重订阅，跨轮复用状态会让 `message_start` 缺失
- 收尾用 `concatWith(Flux.defer(...))` 而非 `doFinally`（后者无法注入元素）

**验收**：见 §9。

### 阶段四：文档化收尾

- 更新 §2.3 列出的全部过期注释与测试
- 新建 C2M 请求契约 / 响应契约（或扩写现有两份）—— 需要先确定它们与
  `C2M-REQUEST-CONTRACT.md` / `C2M-RESPONSE-CONTRACT.md` 的关系：
  那两份文件名里的 `C2M` 指的是**协议对**而非单个方向，因此**扩写现有两份**比新建更符合既有习惯
- 决策定稿后新建 ADR

---

## 8. 风险与对策

| 风险 | 表现 | 对策 |
|---|---|---|
| **装配漏了不响** | `@Component` 不在扫描范围 → 注册表空表正常启动，既有测试全绿，直到第一个跨协议请求才报「翻译不支持」 | `TranslatorRegistrySpringWiringTests` 补 `(MESSAGES, CHAT)` 的 `containsSame` 断言（断言必须命中**容器里那个 Bean**，不能只断言「某个实例」） |
| **工具配对配反方向** | 签名相同，编译通过；上游收到合法 JSON 但 `tool_result` 与 `tool_use` 对不上，模型行为诡异而无报错 | 新类名 `AnthropicToolResultExpander` 并在注释里显式指向 `ToolPairingNormalizer` 说明反向；单测覆盖「多轮工具链」场景 |
| **翻译进重试内侧** | 空响应判定看到合成 chunk，每轮都判空，耗尽预算（约 62 秒 / 6 次上游调用） | 翻译器必须是 `@Component`，由 `BeforeSend` / `AfterSend` 在 `retryWhen` 外侧装配 —— **不要**把翻译写进执行器 |
| **usage 重复计算** | 输入 token 统计偏高（`prompt_tokens` 已含缓存，直接搬会重复） | `ChatUsageConverter` 显式做减法，单测钉住「有 cached_tokens 时 input + cache_read = prompt」 |
| **`message_start` 缺失 / 时机错** | 下游状态机不启动，表现为「有数据但界面不动」 | 单测断言第一帧是 `message_start`；实机验证时看 SSE 逐帧 |
| **`message_stop` 缺失** | Toast 永远悬挂在 CHUNK（部分上游不主动关连接） | `C2MStreamState` 的 `finalizeStream` 恒产出 `message_stop`；单测覆盖「上游不发终止标记就断连」 |
| **`includeUsage` 语义错用** | Anthropic 下游永远收不到 usage | 见 §5.2，倾向方案 A（无条件发） |
| **文档块静默丢弃** | 用户以为文档被处理了，实际模型没看到 | 见 §5.3，**待定决策** |
| **单测绿但实机不对** | 翻译器单测覆盖不到真实上游的字段变体 | 走 `tools/live-test/` 的 D 组（翻译组）+ mock-upstream 的参数分片场景（`translate-tool-*`） |

---

## 9. 验收清单

### 单元测试

- [ ] `MessagesToChatRequestTranslatorTests` —— 字段映射、白名单丢弃、思考映射（对齐契约 §4.3）、`stop_sequences` → `stop`
- [ ] 工具配对：多轮 `tool_use` / `tool_result` 展开正确、孤儿 `tool_result` 丢弃并留痕
- [ ] `ChatToMessagesNonStreamTranslatorTests` —— 完整报文映射、`finish_reason` → `stop_reason`
- [ ] `ChatUsageConverterTests` —— **含 cached_tokens 的减法**、缺失字段的保守默认值
- [ ] `ChatToMessagesStreamTranslatorTests` —— 帧数对照表逐项、「首帧必为 `message_start`」、「收尾必产 `message_stop`」
- [ ] `TranslatorRegistrySpringWiringTests` —— `(MESSAGES, CHAT)` 两半都 `containsSame` 容器 Bean
- [ ] `ProtocolDispatchManagerTests` —— 「下游 MESSAGES + 供应商只勾 CHAT」现在应**命中翻译**而非抛错（原有用例需改）

### 实机验证（`tools/live-test/`）

- [ ] D 组：`downstream_protocol = MESSAGES` / `upstream_protocol = CHAT` 两列正确
- [ ] mock 侧 `/chat/completions` 被真实调用（判据是 mock 的「▶ 请求进入」行数，不是 COSP 日志行数）
- [ ] 流式逐帧：`message_start` → `content_block_start` → `content_block_delta`… → `message_delta` → `message_stop`
- [ ] 多轮工具链（≥ 5 轮）配对全平，无相邻同角色
- [ ] 非流式 usage 数值与上游 `prompt_tokens` 对得上（含缓存减法）
- [ ] Claude Desktop 真实客户端非流式 + 流式对话可用

### 文档

- [ ] §2.3 的全部过期注释与测试已更新
- [ ] 契约文档（扩写现有两份）已更新 M2C / C2M 方向
- [ ] `ProtocolTranslator` 的 `TODO(待实现)` 块已删除（它的前提已不成立）
- [ ] 本 RFC 状态改为「已定稿且实施完毕」并追加实施记录
- [ ] 决策定稿后新建 ADR，并在本 RFC 头部回填 ADR 链接
- [ ] `docs/working/rfcs/README.md` 状态表已更新

---

## 10. 实施记录

> 按阶段追加，**不改写历史**。计划与实际不符时在此记录偏差原因。

（尚未开始）
