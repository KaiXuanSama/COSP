# 协议翻译契约（响应侧）

> **A2O 响应翻译已落地并经实流量验证**（流式与非流式、多轮工具调用、参数分片）。
> 实现与验证状态见第 15 节；O2A 响应翻译尚未开始。
>
> 本文档既是设计契约也是实现说明：正文的「必须 / 不要」是约束，
> 标注了实测日期的段落是已验证的事实。
>
> 请求侧见 [PROTOCOL_TRANSLATION_CONTRACT.md](./PROTOCOL_TRANSLATION_CONTRACT.md)（本文沿用其编号与术语）。
> 相关：[AGENTS.md](../AGENTS.md)、[思考链回放调查](COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md)、
> [mock-anthropic](../tools/mock-anthropic/README.md)（参数分片等只能用 mock 触发的场景）

A2O = Anthropic Messages 响应 → OpenAI Chat Completions 响应。
本文档覆盖**非流式 JSON** 与**流式 SSE** 两侧。

---

## 0. 为何先做 A2O 响应而非 A2O 请求

四个功能的原定顺序是 O2A 请求 → A2O 请求 → A2O 响应 → O2A 响应，但**实际执行顺序调整为
O2A 请求 → A2O 响应**。

理由：O2A 请求已落地并实测通过（下游打 `/v1/chat/completions`、上游 DeepSeek Anthropic
端点返回完整事件序列），但下游收到的是 Anthropic 原生帧，Copilot 解析不了。
补上 A2O 响应才让这条链**端到端可用**；而 A2O 请求服务的是另一条链（下游打 `/v1/messages`、
上游只有 OpenAI），那条链在此之前一直是不可用状态，不因本次工作而改变。

即：按「让已有的一半变成可用的整体」排序，而不是按功能编号排序。

---

## 1. 实测的上游事件序列

以下是 O2A 落地后对 DeepSeek Anthropic 端点的真实抓包（`deepseek-v4-flash`，
兜底注入 `thinking: {"type":"adaptive"}`），按到达顺序：

```text
message_start          → message.id / model / usage.input_tokens
content_block_start    → index 0, content_block.type = "thinking"
ping
content_block_delta    → index 0, delta.type = "thinking_delta"   (× N)
content_block_delta    → index 0, delta.type = "signature_delta"
content_block_stop     → index 0
content_block_start    → index 1, content_block.type = "text"
content_block_delta    → index 1, delta.type = "text_delta"
content_block_stop     → index 1
message_delta          → delta.stop_reason = "end_turn", usage.output_tokens
message_stop
```

两个直接影响设计的观察：

- **thinking 块占用了 index 0**。任何把 Anthropic `index` 直接当 OpenAI
  `tool_calls[].index` 用的实现，在有 thinking 的响应里都会产出稀疏数组。见第 4 节。
- **`signature_delta` 真实存在**，不是文档里的边缘情况。见第 5 节。

---

## 2. 事件映射总表

三个参考项目交叉验证后的结论。「帧数」指该事件产出多少个下游
`chat.completion.chunk`。

| Anthropic 事件 | 下游帧数 | 产出内容 |
|---|---|---|
| `message_start` | **1** | 唯一带 `delta.role: "assistant"` 的帧，同时确定 `id` / `model` |
| `content_block_start` (text) | **0** | Chat 协议没有块生命周期概念 |
| `content_block_start` (thinking) | **0** | 同上 |
| `content_block_start` (tool_use) | **1** | `delta.tool_calls[{index, id, type:"function", function.name}]` |
| `content_block_start` (redacted_thinking) | **0** | 见第 5.2 节 |
| `content_block_start` (server_tool_use 等 hosted) | **0** | 白名单跳过，见第 6.3 节 |
| `content_block_delta` / `text_delta` | 1 | `delta.content` |
| `content_block_delta` / `thinking_delta` | 1 | `delta.reasoning_content` |
| `content_block_delta` / `signature_delta` | **0** | 吸收进状态，见第 5.1 节 |
| `content_block_delta` / `input_json_delta` | 1 | `delta.tool_calls[{index, function.arguments}]`，无 `id` / `name`。**片数不定**，见第 4.1 节 |
| `content_block_stop` | **0** | |
| `message_delta` | 1 | 唯一带 `finish_reason` 的帧。**只发 finish chunk，不带 usage** —— usage 由收尾统一发，见第 9.3 节 |
| `message_stop` | **0** | `[DONE]` 由收尾逻辑发，不由本事件触发 |
| `ping` | **0** | 见第 6.4 节 |
| `error` | 见第 6.1 节 | |

**这张表印证了 `ProtocolTranslator` 里那句「不是一帧进一帧出」**：零帧与多帧都存在，
流式翻译方法的返回类型必须是 `List` / `Flux`，不能是单个 `String`。

---

## 3. 非流式映射

| OpenAI 字段 | 来源 | 说明 |
|---|---|---|
| `id` | Anthropic `id` | 原样透传（`msg_xxx`），不加 `chatcmpl-` 前缀 |
| `object` | 硬编码 | `"chat.completion"` |
| `created` | 本地时间戳 | Anthropic 无此字段。**不是上游耗时基准**，日志对账时注意 |
| `model` | **下游原始模型名** | 见第 7 节，不用上游返回的裸名 |
| `choices[0].index` | 硬编码 `0` | Anthropic 无 n>1 概念 |
| `choices[0].message.role` | 硬编码 | `"assistant"` |
| `choices[0].message.content` | 所有 `type == "text"` 块按序拼接 | 无分隔符 |
| `choices[0].message.reasoning_content` | 所有 `type == "thinking"` 块按序**累积** | 见第 5.3 节 |
| `choices[0].message.tool_calls` | 每个 `type == "tool_use"` 块一项 | `arguments` = `Marshal(input)`，空 input → `"{}"` |
| `choices[0].finish_reason` | `stop_reason` 映射 | 见第 8 节 |
| `usage` | Anthropic `usage` 换算 | 见第 9 节 |

多个 content block 的**相对顺序**要保留：text 与 tool_use 交错出现时，
拼接后的 `content` 与 `tool_calls` 数组各自内部有序即可（Chat 协议本身表达不了交错）。

---

## 4. tool index 是两个独立索引域（必须做对）

三个项目一致确认，且是 A2O 唯一真正需要跨帧状态的原因。

- **Anthropic `index`**：覆盖**所有**块类型。实测序列里 thinking 占 0、text 占 1
- **OpenAI `tool_calls[].index`**：必须是**从 0 起的稠密序列**

直接把 Anthropic index 当 tool index 用的后果：一个「先思考、再调两个工具」的响应会产出
`tool_calls[1]` 与 `tool_calls[2]`，下游看到的是 `index: 0` 缺失的稀疏数组，
Copilot 大概率拼不出参数。

**做法**（借鉴 new-api `ClaudeToChatStreamState`）：

```text
content_block_start (tool_use) 时按需分配 nextToolIndex++，记入 map
input_json_delta 时按 Anthropic index 查表复用
查不到且不是已知 hosted 块 → 报错，不猜
```

**不要沿用 sub2api 的做法**：它在 `content_block_delta` 处理里**完全忽略 `index` 字段**，
只靠「当前打开的块」推断归属。这依赖「Anthropic 顺序发块、不交错」这个未文档化的假设。
按 `index` 建映射表的成本几乎为零，没有理由赌这个假设。

### 4.1 参数分片：每一片单独都不是合法 JSON

一个 `tool_use` 块的参数 JSON 通过 `input_json_delta` 逐片发出，下游靠拼接
`delta.tool_calls[].function.arguments` 还原。因此**每一片必须当作不透明字节
原样转发** —— 中途试图解析单片必然失败，因为切点可能落在 `\"` 或 `\uXXXX` 中间。

翻译层要保证的两件事：同一块的所有片落在**同一个** `tool_calls[].index` 上，
且**顺序不变**。任一条破了，下游拼出来的就是废串。

**上游对「切不切、怎么切」没有共识**，这一点决定了怎么测：

| 上游 | 行为 |
|---|---|
| Anthropic 官方 | 长参数切成多片 |
| MiMo（实测） | **不切**，6269 字符的参数装在单个 `input_json_delta` 里一次发完 |

因此**光靠真实流量压不到多片路径** —— 近期全部带工具调用的 A→O 日志里
`maxDeltasPerBlock` 恒为 1，提示词写得再长也没用（参数体越大只是那一片越长）。
本地唯一手段是 `tools/mock-anthropic` 的四个场景：

| 模型名 | 形状 | 压什么 |
|---|---|---|
| `at-tool-split-args` | 单工具，参数按 12 字符切成 23 片 | 拼接正确性；转义序列跨片边界 |
| `at-tool-multi-split` | 三工具，block index **1/2/3**（0 给 thinking） | 双索引域重映射为稠密 0/1/2 |
| `at-tool-interleaved` | 两工具的分片交错发送 | index 映射是否依赖隐式「当前活跃块」 |
| `at-tool-no-args` | 零个 `input_json_delta` | 不凭空补 `{}`，也不丢掉整个工具调用 |

切片刻意**不避开**转义序列：那是最容易暴露「谁在中途解析单片」的形状。
交错场景是**实现健壮性探针而非协议合规性测试** —— 官方是块顺序完成的，交错在
实践中未观测到，但按 `index` 查表本应天然支持它；若实现用了「当前活跃块」
这类隐式状态，交错会立刻把两个工具的参数搅在一起。

### 4.2 实测结论（2026-09-05，mock 四场景）

| 场景 | 上游片数 | 下游 tool index | 拼接结果 |
|---|---|---|---|
| `at-tool-split-args` | 23（block 0） | `[0]` | 合法 JSON，与上游**逐字节相等** |
| `at-tool-multi-split` | 4 / 30 / 8（block 1/2/3） | `[0,1,2]` | 三段各自合法，均逐字节相等 |
| `at-tool-interleaved` | 5 / 7 交错（block 0/1） | `[0,1]` | 两段互不污染，逐字节相等 |
| `at-tool-no-args` | 0 | `[0]` | `arguments` 为空串（**正确**，非缺陷） |

四条 `frameCounts.length === upstream.length`，日志页两栏对齐可用。
每个工具的帧位置 `monotonic` 为真。

两个容易误读的输出，写在这里免得下次重新怀疑：

- **`at-tool-no-args` 的 `arguments` 是空串，`JSON.parse` 会失败** —— 这是期望行为。
  OpenAI 协议里无参工具就该发空串，下游按 `{}` 处理。校验脚本若无条件对
  `arguments` 做 `JSON.parse`，这一条会假报失败。
- **顺序性不要用「上游 delta 的 index 序列 == 下游帧的 index 序列」来判**。
  多工具顺序发送时，块交界处 index 自然变化，会被朴素的「相邻不同即交错」
  判据误报。有效判据是每个工具**各自**的 `monotonic` 加上拼接结果与上游
  逐字节相等 —— 顺序错了字节序列就不可能相等。

---

## 5. 思考内容

### 5.1 signature_delta：吸收，不产帧，不注入替代内容

`signature` 由 Anthropic 自己签发，Chat 协议没有承载位置。

**处置**：吸收进状态（供日志/诊断），**不产出下游帧**。

**明确不抄 new-api**：它把 `signature_delta` 映射成 `delta.reasoning_content = "\n"`
（`to_oai_chat_resp.go:87-89`），即拿签名当分隔符。这有两个问题：

1. 那个换行会进入下游的 reasoning 文本，是凭空多出来的内容
2. 如果下游把 reasoning 回放上来，签名依然缺失，只是多了脏字符

签名不可跨协议携带这件事应当**显式化**（记一条 debug/diagnostic），
不该被一个 `"\n"` 掩盖成「好像处理了」。

### 5.2 redacted_thinking：显式识别并跳过，记一条 warning

三个项目的处置都不理想：

- new-api 非流式 switch 无该 case，流式落到 fall-through 产出一个**空 delta 噪声帧**
- sub2api 完全无 case，且其 `content_block_stop` 会被状态机误判成上一个 item 的收尾
  （CC 侧恰好都映射为 nil 所以无害，但那是**碰巧无害**而非设计）

**处置**：显式识别 `redacted_thinking`，产出 0 帧，并记一条 warning。
用户看到内容凭空变少时，日志里要有痕迹。

### 5.3 thinking → reasoning_content 无闸门

流式与非流式都**无条件**把 thinking 转成 `reasoning_content`，不看有没有 tool_calls。

这一点需要与请求侧区分。sub2api 的**请求侧**有个 `hasToolCalls` 闸门
（纯文本轮次的 thinking 直接丢），成因是 DeepSeek 只要求「产生 tool call 的
`reasoning_content` 必须回传」，不带闸门会让上游 400。

**那是上游请求校验的约束，响应侧不存在**。响应是发给下游客户端的，全量给出才能让客户端
渲染思考过程。sub2api 自己的响应侧也没有闸门，这个不对称是刻意的。

### 5.4 非流式的 thinking 必须累积而非赋值

new-api 的非流式实现有个真实 bug 值得记：两条 thinking 路径写同一个字段互相覆盖，
且用的是 `thinkingContent = *message.Thinking`（赋值而非追加），
**多个 thinking 块只剩最后一个**，前面的静默丢失。

只留一条路径，用 `StringBuilder` 累积。

---

## 6. 错误与控制事件

### 6.1 mid-stream error 必须让下游可感知

这是三个项目里处理得最差的一块，也是 COSP **不能照抄**的地方。

- sub2api：`error` 事件落到 default → nil，**静默吞掉**；随后 finalize 补一个
  `finish_reason: "stop"`。下游看到「成功但内容截断」
- new-api：编排层拦截并中断流，但**状态码硬编码 500**；且如果 SSE 头已发、已写过 chunk，
  最终响应会用 `c.JSON` 往流里塞一段裸 JSON —— 产出**畸形 SSE**（既无 `data:` 前缀也无 `[DONE]`）

**COSP 的处置**（与现有 `onErrorResume` + `findWebResponseException` 透传原则一致）：

- **SSE 头尚未发出**：走既有路径，透传上游状态码与错误体
- **已发过 chunk**：不能再改状态码。发一个带 `finish_reason` 的终止帧，
  随后 `[DONE]`，并在日志里记明成因。**绝不破坏帧结构**

`error` 事件本身在流内是否要映射成一个 `data: {"error":...}` 帧留待实现时定，
但「静默吞掉」与「写裸 JSON」两种都不接受。

### 6.2 空响应判定复用既有口径

`AnthropicContentDetector.eventHasPayload` 已经在做这件事，且注释里明确了
「流式的判定单位是一整轮，不是单个事件」。响应翻译**不要另起一套判空**。

cc-switch 的三档收尾可以借鉴（流结束但没 `message_stop` 时）：

| 情况 | 处置 |
|---|---|
| `stop_reason` 已到 | 语义上已完成，正常收尾 |
| 无 `stop_reason` 但有实质输出 | 按截断处理（`finish_reason: "length"`），并标记 |
| 什么都没有 | 报失败 |

第 2 档的判据（完成的块、累积文本、tool call 名/id 任一非空，**usage 不算**）
与 `AnthropicContentDetector` 的口径一致，能直接对上。

**注意 new-api 在这里比 COSP 宽松**：它的空响应不被视为错误，正常发 usage 尾帧 + `[DONE]`，
下游收到一个合法空流。COSP 有 `EmptyUpstreamResponseException` + 重试预算，
**保持现有的更严口径**，不要为了对齐参考实现而放宽。

### 6.3 hosted tool 块白名单跳过

`server_tool_use` / `web_search_tool_result` 这类块在 Chat Completions 里没有对等物。
**显式列举并跳过**，比让它们掉进 default 分支产生噪声帧干净。

### 6.4 ping 的处置

三个项目都丢弃。但 Anthropic 用 `ping` 保活，长思考期间下游若有中间层超时，
透传成 SSE 注释行更安全。

COSP 的流式链路本身已有 keep-alive 机制（实测输出里可见 `:keep-alive`），
因此**丢弃上游 ping**，不重复造保活帧。

---

## 7. 模型名必须回显下游的带前缀名

sub2api 特意把状态里的 `Model` 预置成下游原始模型名，而不是上游返回的。

COSP 有 `[provider-key] model` 前缀路由。如果把上游返回的裸 `deepseek-v4-flash`
透给下游，**Copilot 下一轮拿这个名字路由会失败**（无前缀模型只在唯一匹配时才允许路由）。

因此 `chat.completion` 与每个 chunk 的 `model` 字段都用下游原始请求里的模型名。

---

## 8. finish_reason 映射

| Anthropic `stop_reason` | OpenAI `finish_reason` |
|---|---|
| `end_turn` | `stop` |
| `stop_sequence` | `stop`（具体序列值丢失，OpenAI 无处放） |
| `max_tokens` | `length` |
| `tool_use` | `tool_calls` |
| `refusal` | `content_filter` |
| `pause_turn` | `length`（视为未完成） |
| 未知值 | **原样透传** |

最后一条与 AGENTS.md 的「不做自动降级、尊重上游」一致 ——
强行归一到 `stop` 会把「上游给了个我们不认识的终止原因」伪装成正常结束。

`finish_reason` **只在 `message_delta` 发出**。`stop_reason` 为 null 时发不带
`finish_reason` 的帧（Anthropic 允许 `message_delta` 只带 usage）。

---

## 9. usage 换算（影响计费）

**Anthropic 的 `input_tokens` 不含缓存，OpenAI 的 `prompt_tokens` 含缓存。**
三个项目都做了这个换算，且都写了注释说明。

```text
prompt_tokens     = input_tokens + cache_read_input_tokens + cache_creation_input_tokens
completion_tokens = output_tokens
total_tokens      = prompt_tokens + completion_tokens

prompt_tokens_details.cached_tokens = cache_read_input_tokens
```

不加回去，`api_call_usage` 表直接少记缓存部分。

### 9.1 usage 分两个事件到达，合并时不能用 0 覆盖

- `message_start` → `input_tokens` / `cache_read` / `cache_creation`
- `message_delta` → `output_tokens`

合并必须 `if > 0 才覆盖`，否则 `message_delta` 里的 `input_tokens: 0`
会把 `message_start` 记下的值抹掉。

### 9.2 reasoning_tokens 拿不到

Anthropic 不单独上报思考 token，所以 A2O 产出的响应**永远没有**
`completion_tokens_details.reasoning_tokens`。不要凭空估算。

### 9.3 usage 尾帧依赖 include_usage

流式的 usage 作为**独立 chunk** 发在 finish chunk 之后，形态是
`{"choices":[], "usage":{...}}`（`choices` 是空数组，不是省略）。

是否发出取决于下游请求的 `stream_options.include_usage`。
这正是请求侧契约第 7 节要求 `TranslationContext` 承载的信息之一 ——
当前 `TranslationContext` 只有 `wasStream`，实现响应侧时需要补 `includeUsage`。

**唯一发出点必须是收尾逻辑**（`finalizeStream`），不能跟着 finish chunk 一起产出。
后者会在正常路径上发出**两个** usage chunk：`message_delta` 带 `stop_reason` 时一个、
流结束时收尾又补一个。宽容的客户端拿后者覆盖前者所以看不出问题，但那是两条相同的
计费记录 —— 这是实现期真实出现过的缺陷。

收敛到收尾一处还顺带修正了**完整性**：Anthropic 允许在带 `stop_reason` 的
`message_delta` 之后再发只携带 usage 的 `message_delta`。跟着 finish chunk 发意味着用
「那一刻」的累积值抢跑，之后到达的 usage 只能进第二帧 —— 两帧数字还不一样。

注意测试要**数个数**而不是「找第一个」。原先的断言用「取第一个含 usage 的下标、
验证它在 finish 之后」，这个缺陷因此长期没被发现。

### 9.4 记账口径：三个 token 列只有一套定义

**契约**：`api_call_usage` 的三个 token 列是跨协议共用的度量列，口径**固定**、
不随行的协议变化：

```text
prompt_tokens     = 本次调用的总输入 token，包含缓存命中与缓存写入
completion_tokens = 输出 token
cached_tokens     = 输入中来自缓存**命中**的部分
```

缓存相关的 token 都是真实输入 —— 模型每一个都要处理，缓存只改变单价、
不改变是否存在。因此多轮对话里同一段前缀在每一轮都被完整计入，这是**意图**而非缺陷。

**分子与分母刻意不对称**：`cache_creation` 计入 `prompt_tokens` 但不计入
`cached_tokens`。计入分母是因为那些 token 确实被处理了；不计入分子是因为它属于
成本项而非命中项 —— 否则一次纯写入的调用会显示 100% 命中，而那一轮实际上
一个 token 都没从缓存读到。

这也解释了常见的「高占比 / 0% 跳变」：前缀缓存 TTL 内的轮次全命中，
首轮与 TTL 过期后的重建轮次纯写入、命中为 0。那是真实行为，不是统计缺陷。

`usage_raw` 与这三列是**两种数据**，同一行里同时留着是有意的：

| 列 | 内容 |
|---|---|
| `usage_raw` | 上游原始报文的存档，字段名与值都是上游原样；改写它等于销毁证据 |
| 三个 token 列 | 跨协议共用的归一化度量，口径如上 |

#### 换算发生在解析层

只有 Anthropic 需要换算：它把缓存读取排在 `input_tokens` **之外**单独计量，
而 OpenAI 的 `prompt_tokens` 本来就含缓存。

```text
AnthropicUsageParser.toTokens:
    promptTokens  = input_tokens + cache_read_input_tokens + cache_creation_input_tokens
    cachedTokens  = cache_read_input_tokens
```

**放在解析层是因为这个换算只依赖上游协议**，与下游是谁无关 —— 无论该请求是
Anthropic 直连还是 A2O 翻译，上游都是 Anthropic、都差那两份缓存。

四条线路因此不需要任何按线路的接线：

| 线路 | 上游 → 下游 | 换算 |
|---|---|---|
| OpenAI 直连 | O → O | 无需，`OpenAiUsageParser` 本就产出归一口径 |
| Anthropic 直连 | A → A | `AnthropicUsageParser` 加回 read + creation |
| A2O | A → O | 同上，同一份代码 |
| O2A（未实现） | O → A | 无需，上游是 OpenAI |

#### 与 new-api 同口径

这与 new-api 的 `buildOpenAIStyleUsageFromClaudeUsage` 一致：

```go
totalInputTokens := usage.PromptTokens + usage.PromptTokensDetails.CachedTokens + cacheCreationTokens
```

意义在于：很多中转站本身就是 new-api，因此经本服务 A2O 落库的数字，
与直接打同一中转站 OpenAI 兼容端点拿到的数字**同源** —— 换个端点打进来，
落库口径不会变。new-api 额外单独暴露写入量（`prompt_tokens_details.cached_creation_tokens`
与 `cache_write_tokens` 两个字段），本服务当前不转发那两个，因为 `UsageTokens`
只有三个成员、而三列的定义已能完整表达计量需求。

#### 曾经的做法及其失败原因

早期换算挂在 `DownstreamLogView.usageRewriter` 上，只在 A2O 路线注入
（`AnthropicToOpenAiResponseTranslator.translateUsageForLog`），前提是「Anthropic
直连的下游要的就是不含缓存的 `input_tokens`」。

那个前提被推翻了。一列承载两种定义意味着每个消费方都得先知道该行的协议，
而**汇总查询做不到这一点** —— `ApiCallUsageRepository` 的四处
`SUM(COALESCE(prompt_tokens, 0))`（第 158、198、235、330 行）一求和就把两种定义
混在一起，得到的数不对应任何真实量。前端按 `downstream_protocol` 分支只能救日志列表
一处，反而让概览页与日志列表对同一批数据给出不一致的解读。

`usageRewriter` 与 `translateUsageForLog` 都已删除。**不要重建它们**：解析层已经
加过缓存，再加一遍等于把缓存计两次。

出站报文的换算在 `AnthropicUsageAccumulator`，它从一开始就是三项相加；
解析层早期只加两项，因此同一次调用的出站数字与落库数字不一致（实测样本：
出站 53849、落库 8077，差 5.7 倍）。两侧现已同口径，但仍是两个入口（一个读
`JsonNode` 并输出 OpenAI 形态、一个输出 `UsageTokens`），不是重复实现 ——
**但修改其中一侧的口径时必须同步另一侧**。

#### 前端

`frontend/src/features/call-log/cacheHitRate.ts` 只有一套逻辑：`cached / prompt`。
协议分支已删除，不要加回来 —— 在展示层纠偏会把「口径由行决定」这个已被推翻的假设
重新固化，且每条新增线路都要再来一次。

#### 存量数据不修

口径统一之前落的 Anthropic 直连行不含缓存，`api_usage_daily` 的累加值同样。
决定是**只保证新数据正确**：存量本就是混的，重算需要从 `usage_raw` 反推并改写历史，
收益不抵风险。查证途径始终存在（同一行的 `usage_raw` 是上游原文）。

#### 为何不加第四个列 / 成员

曾考虑给 `api_call_usage` 加一列存缓存写入量，并给 `UsageTokens` 加第四个成员。
没做的理由：三列的口径本就定义为「总输入 / 总输出 / 其中命中」，
把写入量**并入总输入**完全符合这个定义，不需要新维度。

而 `UsageTokens` 保持三成员是因为它是多协议共用的输出契约 —— 为 Anthropic 的
私有拆分加成员会把协议细节漏给 OpenAI 侧与 Ollama 侧（那两边永远是 null）。

TTL 细分（`cache_creation.ephemeral_5m_input_tokens` /
`ephemeral_1h_input_tokens`）同理不单独提取：它只影响单价、不影响 token 数量，
而 `cache_creation_input_tokens` 已是两者之和。需要时 `usage_raw` 保留了上游原文。

#### 一个仍需遵守的不变式

**合并时只有正数才覆盖，0 不得抹掉已知值**。存在这样的上游：**每一个**流式事件
都带完整 usage，但只有少数几个带真实数字，其余（含最后的 `message_stop`）全是 0。
`AnthropicUsageParser.merge` 与 `AnthropicUsageAccumulator.mergeField` 必须共用同一条
规则 —— 两者不一致曾导致同一次调用出站 usage 正确而落库全零。
同理 `usage_raw` 不能取「最后一份」，而要取「信息量最大的一份」
（`GenericAnthropicChatService.pickRicherUsageRaw`）。

#### `usage_raw` 永远挑一份，不跨事件拼字段

三个 token 列跨事件合并（度量列要完整数字），存档列永远挑一份原文
（查证要的是上游原话）。两套规则刻意不同。

挑选规则：四个输入输出字段的正值个数，严格更多才替换；个数打平时取
`message_delta`（结算态），否则保留先到的那一份。`message_start` 是预算 /
预估态（请求刚被接收，输出还没产生），`message_delta` 是结算态
（这次调用最终算了多少）。尾事件全零不会替换已有存档（0 个正值压不过 2 个）。

**不把两个事件的字段合并成一份「完整」原文**。同名字段在两个事件里可以给出
不同的值：cc-switch 记录的 Qwen / MiniMax 形态里，`message_start` 报
`input_tokens=200000 / cache_read=180000`，`message_delta` 改报
`80000 / 120000` —— 两份各自自洽（配套），逐字段挑较大者会拼出一份
上游从未发出过的报文。存档一旦拼接就不再是证据。完整事件序列本就在
`chunks` 列里，真要看原文那里有全部。

官方 Claude 的 `message_start` vs `message_delta` 形态尚未一手验证
（目前掌握的样本全部来自第三方 Anthropic 兼容端点与中转项目的测试夹具，
各家并不一致）。拿到官方 key 的抓包后可以重新评估本条规则。

- **`api_usage_daily` 的写入侧**（`ApiUsageRepository.insert`）与
  `api_call_usage` 共用同一口径，因为两处都取自同一个 `UsageTokens`。
  但那张表在写入时就累加了，存量值无法靠改查询修正 —— 若将来要重算，
  得从 `api_call_usage` 整表汇总后覆盖。

---

## 10. 帧形态的细节

参考项目实测出来的几个「客户端会挑」的形态：

- **首帧**：`{"delta":{"role":"assistant"},"finish_reason":null}`。
  `content` 用 `null`（omit）还是 `""` 两家做法不同，实现时择一并钉测试
- **finish chunk**：带 `"delta":{"content":""}` 而非空对象 `{}`
- **usage chunk**：`"choices":[]` 空数组
- **`[DONE]`**：字面 `data: [DONE]`，在 usage chunk 之后无条件发出。
  **不由 `message_stop` 触发**，而由流结束触发

`id` / `model` / `created` 需要回显在**每个** chunk 上。new-api 因为只在
`message_start` 填这三项，导致后续帧的 `id` / `model` 为空，靠编排层回填补救 ——
直接每帧都填更简单。

---

## 11. 跨帧状态

A2O 需要的状态（已剔除 sub2api 因走 Responses IR 而引入的账本字段）：

| 字段 | 用途 |
|---|---|
| `id` / `model` / `created` | 每帧回显。`model` 是下游原始名（第 7 节） |
| `sentRole` | 保证 role 帧只发一次 |
| `nextToolIndex` + `anthropicIndex → toolIndex` map | 两个索引域的桥（第 4 节） |
| `blockTypeByIndex` | 识别 hosted 块、redacted_thinking |
| `sawToolCall` | 收尾时兜底 `finish_reason` 用 |
| `stopReason` | `message_delta` 写、收尾读 |
| `inputTokens` / `outputTokens` / `cacheRead` / `cacheCreation` | usage 累加器（第 9.1 节） |
| `includeUsage` | 来自 `TranslationContext` |
| `finalized` | 收尾幂等 |

**生命周期是一次请求**，且必须在**重试边界之内重建** ——
`retryWhen` 重订阅时若不重置，第二轮会带着第一轮的 `sentRole` 与 tool index 分配。
这与 `GenericAnthropicChatService` 里 `Flux.defer` 内重置 `sawPayload` / `logChunks`
是同一个道理。

**不需要**的字段（sub2api 有但那是 Responses 中间层的账本）：
`sequenceNumber`、`outputs`、`currentContent`、`textAccum`、`contentIndex`、`currentItemId`。

---

## 12. 翻译位置与重试边界

沿用请求侧契约第 1.2 节的结论：翻译器套在上游服务**外侧**，不进
`GenericAnthropicChatService` 内部。

响应侧多一条约束：**翻译必须在 `retryWhen` 之外**。

理由是空响应判定与重试预算用的是**上游原生形态**。若翻译发生在 `retryWhen` 内侧，
`AnthropicContentDetector` 看到的就是合成出来的 OpenAI chunk，
而它的取值路径是照 Anthropic 结构写的（`content[]` 数组 + `type` 字段），
会把每一轮都判成空并耗尽重试预算。

### 12.1 落库：两份 chunk 加逐事件产帧数

翻译在服务外侧、落库在服务内部，默认会记出两个错误的事实：协议列写成
`ANTHROPIC → ANTHROPIC`，chunk 记的是 Anthropic 事件而下游收到的是 OpenAI chunk。
`DownstreamLogView` 把「下游协议 + chunk 改写器」作为一个整体注入，上游服务不必知道
翻译存在。

`api_call_log.chunks` 一列两形（`ChunkLogPayload`）：

```jsonc
直连：  ["{...}", "[DONE]"]
翻译：  {"translated": [...], "upstream": [...], "frameCounts": [1, 0, 0, 2]}
```

**两份都留**：只留上游看不到客户端收到了什么（排查解析失败时最需要的那一份），
只留下游看不出上游到底发了什么。

`frameCounts[i]` 是第 i 个上游事件译出的下游帧数，**这是两栏对齐的唯一依据**。
帧数不对等是常态（实测 26 → 20），事后从两个数组反推不出映射关系 ——
只有 `translateChunksForLog` 的循环当时知道每个事件产出了几帧。零帧事件必须记 `0`
而不是被跳过，否则后面所有下标全部错位。

收尾帧（finish + usage + `[DONE]`）不计入 `frameCounts`：它们是翻译层在流结束后
自己补的，不对应任何上游事件。数量等于 `translated.length - sum(frameCounts)`，
前端据此单独成段渲染，左侧留空。

**空响应判定与重试预算仍只看上游原生形态**：翻译在 `retryWhen` 外侧，
`AnthropicContentDetector` 永远看不到合成出来的 OpenAI chunk。

---

## 13. 落地顺序

1. **非流式 A2O**——形态简单、无状态机，先把字段映射与 usage 换算钉死
2. **流式状态机骨架**——`message_start` / `text_delta` / `message_delta` / `[DONE]`，
   跑通纯文本响应
3. **thinking 流**——`thinking_delta` → `reasoning_content`，`signature_delta` 吸收
4. **tool 流**——两个索引域的映射，这是最容易错的一段
5. **收尾三档 + mid-stream error**
6. **接入 `ChatCompletionService`** 的两处翻译分支（已完成）

### 13.1 测试必须覆盖参考项目没覆盖的场景

**new-api 的 A2O 流式只有一份 golden 快照，输入是 6 个事件的纯 text 序列——
不含 thinking、不含 tool_use、不含多块。** 也就是说 tool index 重映射与 signature 处理
这两条最容易出错的路径，在 new-api 里**没有测试覆盖**。

COSP 必须自己补齐：

- thinking 占 index 0 + tool_use 占 index 1/2 的混合序列（钉住第 4 节）
- 多个 thinking 块（钉住第 5.4 节的累积而非覆盖）
- `signature_delta` 不产帧（钉住第 5.1 节）
- usage 两事件合并且 0 不覆盖（钉住第 9.1 节）
- usage chunk 只发一个 —— 要**数个数**而非「找第一个」（钉住第 9.3 节）
- 流结束无 `message_stop` 的三档收尾（钉住第 6.2 节）
- 重试重订阅后状态被重置（钉住第 11 节）

参考 new-api 的 golden 文件回归形式：整响应快照，字段级回归一目了然。

### 13.2 有些形状只能用 mock 验证，单测不够

单测能构造任意事件序列，但**构造不出真实上游的取舍**。两条只有实流量才能暴露的事：

- **参数分片**：MiMo 不切分，所以真实流量永远走不到多片路径。单测覆盖了两片，
  但「23 片 + 转义跨界」这种形状要靠 `tools/mock-anthropic` 的
  `at-tool-split-args` 等四个场景（第 4.1 / 4.2 节）。
- **单个 SSE 事件的载荷上限**：实测 6269 字符的 `data` 正常通过，
  这是单测无从验证的传输层事实。

反过来也成立：mock 场景**不替代单测**。mock 跑一次几秒，但它不进 CI，
且需要手工启服务、配供应商、打 curl。规则是「语义边界进单测，
传输与真实上游取舍进 mock」。

---

## 14. 参考实现索引（响应侧）

调研于 2026-09-04。

| 项目 | A2O 响应实现 | 位置 |
|---|---|---|
| **new-api** | **有，单段 Anthropic → Chat** | 转换器 `relaykit/relayconvert/internal/claude_messages/to_oai_chat_resp.go`；编排/收尾 `relay/channel/claude/relay-claude.go` |
| sub2api | 有，但经 Responses IR 两段串联 | `apicompat/anthropic_to_responses_response.go` + `apicompat/responses_to_chatcompletions.go` |
| cc-switch | **没有**（只有 Anthropic → **Responses**） | `src-tauri/src/proxy/providers/streaming_codex_anthropic.rs` |

三点需要记录的事实修正：

- **new-api 的 `convmeta.ClaudeConvertInfo` 不是 A2O 的状态**，它的注释明写
  "state for OpenAI chat → Claude Messages"。A2O 用的是
  `ClaudeToChatStreamState`（协议翻译）+ `ClaudeResponseInfo`（计费）两个结构
- **sub2api 的 `chatcompletions_anthropic_bridge.go` 方向与 A2O 相反**，
  它的响应方向是 CC → Anthropic（下游说 Anthropic、上游只会 CC）。
  A2O 只有两段串联那一条路
- **cc-switch 完全没有 A2O**，原因是结构性的：它的下游只有 Claude Code（说 Anthropic）
  与 Codex（说 Responses），不存在说 Chat Completions 的下游

### 14.1 值得借鉴（已纳入本契约）

- new-api 的 tool index 独立域重映射（按需分配稠密序号 + map 查表 + 查不到报错不猜）
- new-api 的 `finish_reason` 映射表，特别是 default 分支原样透传未知值
- new-api 的 hosted tool 块显式白名单跳过
- new-api 的「未知事件返回 false → 上层丢帧」契约：用布尔把「能否识别」与「产出什么」解耦
- 三家一致的 usage cache 语义换算，以及 `if > 0 才覆盖`
- cc-switch 的三档异常收尾，其判据与 `AnthropicContentDetector` 口径一致
- cc-switch 的「一份状态机吃两种输入」：live SSE 与「上游忽略 `stream:true` 返回 JSON」
  共用同一个状态机，后者只是合成事件序列喂进去。COSP 上游是任意中转站，这个兜底很可能用得上
- cc-switch 的工具参数双来源优先级：累积 delta 优先，回退到 `content_block_start` 携带的
  全量 `input`（有网关只在 start 给全量、完全不发 delta）
- cc-switch 的「非对象内容块降级成 text 而非丢弃」：丢弃会让客户端看到「成功但空」，
  无法察觉数据丢失
- sub2api 的 SSE 解析容忍 `event:xxx`（冒号后无空格）——Kimi 等 Anthropic 兼容上游发紧凑格式，
  严格匹配 `"event: "` 会丢弃全部事件
- cc-switch 的 UTF-8 跨 chunk 边界处理：多字节字符被切断时把残字节 stash 到下一 chunk

### 14.2 明确不借鉴

- **new-api 的 `signature_delta → reasoning_content = "\n"`**（第 5.1 节）
- **new-api 非流式的双路径 thinking 互相覆盖 + 赋值而非累积**（第 5.4 节）
- **new-api 的错误一律 500**，以及已发 chunk 后往 SSE 里写裸 `c.JSON`（第 6.1 节）
- **sub2api 的 `error` 事件静默吞掉**（第 6.1 节）
- **sub2api 忽略 `content_block_delta.index`**，靠「顺序发块」侥幸成立（第 4 节）
- **new-api / sub2api 的 `redacted_thinking` 无显式处理**（第 5.2 节）
- **Responses 中间层**：COSP 没有 Responses 出口，A2O 方向那一跳纯粹转发
- **cc-switch 的 `ccswitch-anthropic-thinking-v1:` base64 私有信封**：
  它依赖「下游是 Codex 且会原样回传该字段」这个封闭前提，Copilot 不认识
- **new-api 空响应不视为错误**：COSP 的 `EmptyUpstreamResponseException` + 重试预算
  是更严的口径，保持不变（第 6.2 节）
- cc-switch 的 `Read` 工具硬编码特判、`INFINITE_WHITESPACE_THRESHOLD` 定点补丁、
  按 base_url 关键字识别供应商

### 14.3 三家都没解决的：思考链回放

跨协议造不出 `signature`，三家都是丢弃。cc-switch 用私有 base64 信封绕过，
但那依赖下游是 Codex。

**丢弃是可接受的起点**，但要清楚这意味着 Anthropic 上游的多轮思考缓存拿不回来。
Copilot BYOK 会回传上一轮思考内容，因此翻译路线上开启 extended thinking 且带工具时
可能硬失败——这与 [思考链回放调查](COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md)
的关注点重合，外部项目提供不了答案。

**实测代价**（2026-09-05，MiMo 十轮工具调用链）：去程请求体里
`thinking` 块与 `signature` 一个都没有 —— 出站时 `signature_delta` 被吸收成 0 帧，
下游 Copilot 回传时也不带 `reasoning_content`，两头叠加使 `MessageTranslator`
连重建的素材都没有。后果是**每一轮上游都从零开始思考**，那十轮里思考部分占了
3 到 24 个 delta，属于重复付费。修它得先解决「Copilot 不回传 reasoning_content」
这个上游约束，不在翻译层能力范围内。

---

## 15. 实现与验证状态

### 15.1 已落地

| 项 | 位置 |
|---|---|
| 非流式 A2O | `AnthropicToOpenAiNonStreamTranslator` |
| 流式状态机 | `AnthropicToOpenAiStreamTranslator` + `A2OStreamState` |
| usage 换算（出站） | `AnthropicUsageAccumulator` |
| usage 口径归一（落库） | `AnthropicUsageParser.toTokens`（第 9.4 节） |
| finish_reason 映射 | `StopReasonMapper` |
| 落库双份 chunk + `frameCounts` | `ChunkLogPayload` / `DownstreamLogView` |
| 接入 | `ChatCompletionService` 两处分支 |

### 15.2 实流量已验证

| 场景 | 上游 | 结论 |
|---|---|---|
| 流式纯文本 + thinking | DeepSeek / MiMo | 帧形态正确，裸模型名，`reasoning_content` 正常，无 signature 泄漏 |
| 非流式 | DeepSeek | usage 与 `reasoning_content` 均正确 |
| 多轮工具调用链（10 轮） | MiMo | 9 轮 `tool_calls` + 1 轮 `stop`，tool_use/tool_result 9 对全配平，无相邻同角色 |
| 参数分片四场景 | mock-anthropic | 见第 4.2 节，全部逐字节相等 |
| 跨供应商一致性 | DeepSeek + MiMo | 无按供应商分支 |

### 15.3 未验证 / 待决

- **O2A 响应翻译**（phase 4）尚未实现。usage 不需要额外接线 —— 那条线路上游是
  OpenAI，`OpenAiUsageParser` 本就产出归一口径（第 9.4 节）。
- **A2O 请求翻译**（phase 3，下游 `/v1/messages` + 上游 OpenAI）尚未实现。
- **存量行口径不一致**：口径统一之前落的 Anthropic 行分两批 —— 早期完全不含缓存，
  中期只含 `cache_read`（缺 `cache_creation`）。`api_usage_daily` 的累加值同样。
  已决定**不修**，只保证新数据正确（第 9.4 节）。
- **思考链回放**见第 14.3 节，受上游约束。
- **mid-stream error**（第 6.1 节）与 **hosted tool 块**（第 6.3 节）只有单测覆盖，
  没有实流量样本。
