# COSP 模拟上游（工具调用顺序）

一个零依赖的 Node 脚本，**专攻「工具调用与正文的先后顺序」这一个变量**。
同时提供 OpenAI 与 Anthropic 两种协议形态的端点，两个场景在两侧同名同义，
便于比对同一顺序在两种协议下的下游行为差异。

端点路径与真实上游一致（`/chat/completions`、`/messages`），因此 Base URL 的填法
也与真实供应商一致 —— 接入时不用为 mock 特殊处理。

## 起因

某模型把 `tool_calls` 发在正文**之前**时，Copilot 直接终止对话且不执行工具，
提示词无法纠正，只能换模型。

COSP 侧的清洗已由单测证明**无损**（`AbstractUpstreamChatServiceTests` 里那组
`normalizeChunkKeepsToolCall*` 用例：工具调用的 `id` / `name` / `arguments` 与
`finish_reason` 全部原样透传，空串占位被删但对下游等效）。因此当时把责任指向下游客户端的流解析，
本 mock 的作用是**稳定复现那个顺序**，用来观察客户端实际行为。

当时怀疑的位置是 `vscode-copilot-chat` 的 `SSEProcessor.processSSEInner`
（`src/platform/networking/node/stream.ts`）：工具调用分支里有一句
`if (solution.text.length)` 补空格去 flush linkifier，说明作者假定的是「正文先、工具调用后」；
而收尾的 `getToolCalls()` 对 `name` 与 `id` 用非空断言，没填上就产出 `undefined`，
上层匹配不到工具却又不报错，于是判成「本轮没有工具调用」而结束整轮。

## 实测结论（2026-09-09）：顺序已被排除

> **先看这一节**。工具调用失败时不要再从「顺序」开始怀疑，那条路已经走完了。

四种组合（OpenAI 直连 / O2A 翻译 × 两种顺序）各跑两轮，**Copilot 全部正常解析并执行工具**。
mock 日志还确认了每一轮都「下游声明 73 个工具，含 read_file」，因此工具未声明这个干扰因素也不成立。

这推翻了上一节的怀疑：`SSEProcessor` 里那句补空格确实假定了正文在前，
但不足以丢掉工具调用。昨夜看到的现象另有成因，待实机重测。

一个值得记住的干扰项：那时 COSP 日志的**规整显示自己有序列 bug**
（OpenAI 侧按类型归桶后按写死顺序输出，总是显示工具在正文之前），
已修（`frontend/src/components/calllog/chunkAggregation.ts`）。当时看到的「工具在前」
有可能就是这个显示 bug 造成的假象 —— 日志本身不可信时，由它得出的现象描述也不可信。

本 mock 保留下来作为回归工具：它现在是一个已知能跑通的基准，
下次怀疑流解析时可用它先确认「正常形态仍然正常」。

## 与另外四个 mock 的分工

| 工具 | 模拟谁 | 协议 | npm script | 端口 |
| --- | --- | --- | --- | --- |
| `mock-upstream` | 上游供应商（流式） | OpenAI | `mock:stream` | 8081 |
| `mock-nonstream` | 上游供应商（非流式） | OpenAI | `mock:nonstream` | 8082 |
| `mock-anthropic` | 上游供应商（两种模式） | Anthropic | `mock:anthropic` | 8083 |
| **`mock-toolorder`** | **上游供应商（顺序专项）** | **OpenAI + Anthropic** | **`mock:toolorder`** | **8084** |
| `mock-cosp` | COSP 自己 | Ollama / OpenAI | `mock:cosp` | 11333 |

**为何不往现有 mock 里加场景**：那三个的场景清单已经很长，再塞进「顺序」这一维会让模型名
更难记；而本 mock 只有两个互为对照的场景，多一个都会削弱「唯一变量是顺序」这件事。

## 启动

```bash
cd frontend
./node/npm run mock:toolorder
```

或直接：

```bash
node tools/mock-toolorder/mock-toolorder.js
```

默认监听 `http://localhost:8084`（`MOCK_TOOLORDER_PORT` 可覆盖）。
帧间隔默认 120ms（`MOCK_TOOLORDER_INTERVAL_MS` 可覆盖）—— 调大可在 UI 上肉眼看清顺序，
调小可加快脚本验证。

## 端点

| 方法 | 主路径 | 形态 | 容忍的别名 |
| --- | --- | --- | --- |
| `POST` | `/chat/completions` | OpenAI Chat Completions | `/v1/chat/completions`、`/chat` |
| `POST` | `/messages` | Anthropic Messages | `/v1/messages`、`/mess` |
| `GET` | `/models` | 场景清单 | `/v1/models` |

主路径就是 COSP 实际拼接的那两个（`GenericOpenAiChatService` 接 `/chat/completions`，
`GenericAnthropicChatService` 接 `/messages`）。带 `/v1` 的别名容忍 Base URL 多写了一层；
`/chat` 与 `/mess` 只是手打 curl 时少敲几个字的短写，接入 COSP 时用不到。

`/messages` 与 `mock-anthropic` 同一口径地严格校验 `anthropic-version`，缺失即 400。

## 两个场景

| 模型名 | 顺序 | 实测结果 |
| --- | --- | --- |
| `to-content-first` | 正文 → 工具调用 | Copilot 正常执行工具（两种协议）|
| `to-tool-first` | 工具调用 → 正文 | Copilot **也**正常执行工具（两种协议）|

两个场景共用同一份正文、同一个工具（`read_file`）、同一份参数，**唯一差异是顺序**。
参数刻意切成两片，顺带覆盖「参数跨片」这一常见形态。

模型名解析容忍 `[provider-key] ` 前缀 —— 手打请求时容易带上。

### 工具参数必须满足下游的 schema

参数发的是 `read_file` 的完整形态，三个字段在它的 schema 里**都是 required**：

```json
{ "filePath": "<仓库根>/README.md", "startLine": 1, "endLine": 40 }
```

`filePath` 由脚本位置反推仓库根得出的**绝对路径**，换机器换盘符不用改代码；
`MOCK_TOOLORDER_FILE` 可指向其他文件。

这里曾经只发 `{"filePath":"README.md"}` —— 缺两个必填字段、路径又是相对的，
于是工具执行必然以参数校验失败告终。**那种失败会把「顺序」这个唯一变量淹没**：
看到的报错来自参数，而不是来自想观察的流解析行为。
教训：mock 的载荷也要照下游 schema 查证，不能只求「形态像个工具调用」。

启动时会打印完整参数，目标文件不存在会告警；每次请求还会记录下游声明了多少工具、
含不含 `read_file` —— 「工具未找到」与「顺序导致的静默终止」是两种现象，必须能一眼分开。

### OpenAI 侧（`/chat/completions`）帧序列

```text
to-content-first                          to-tool-first
─────────────────────────────────         ─────────────────────────────────
delta{role, content:"我来看一下"}          delta{role, tool_calls[id+name+args片1]}
delta{content:"这个文件的内容。"}          delta{tool_calls[args片2]}
delta{tool_calls[id+name+args片1]}        delta{content:"我来看一下"}
delta{tool_calls[args片2]}                delta{content:"这个文件的内容。"}
delta{} finish_reason=tool_calls          delta{} finish_reason=tool_calls
```

`role: assistant` 始终挂在**助手轮的第一帧**上，因此两个场景里它的宿主不同。这是刻意的：
若固定挂在正文帧上，`to-tool-first` 就多出「role 出现在第三帧」这个额外变量。

工具调用首帧给全 `id` / `type` / `function.name`，后续帧只带 `arguments` 增量 ——
这是 OpenAI 协议的标准分片方式，也是 Copilot 的 `StreamingToolCall` 期待的形态
（它只在 `toolCall.id` 为 truthy 时赋值）。

参数的切点取 JSON 串中点，必然落在 `filePath` 的值内部，因此两片各自都不是合法 JSON ——
任何试图按单片解析的实现都会在这里暴露。

### Anthropic 侧（`/messages`）block index

```text
to-content-first          to-tool-first
────────────────          ────────────────
index 0  text             index 0  tool_use
index 1  tool_use         index 1  text
```

Anthropic 的 `index` 覆盖**所有**块类型，所以顺序不同则 index 不同。
`to-tool-first` 因此还是「thinking 占 index 0」那类稀疏 index 问题的同构场景：
任何把 Anthropic `index` 直接当 OpenAI `tool_calls[].index` 用的实现，在这里都会产出稀疏数组。

## 非流式

两个端点都支持 `stream: false`。非流式没有「顺序」可言（正文与工具调用同在一个对象里），
提供它是为了**排除顺序之外的因素**：若非流式下工具能正常执行，就进一步指向流解析。

Anthropic 非流式的 `content` 数组顺序仍按场景颠倒，可直接观察下游是否按序处理。

## 在 COSP 中接入

管理后台新增供应商，两个 Base URL 都填 `http://localhost:8084`（**不带 `/v1`**）：

| 配置项 | 填入 | COSP 拼出的最终请求 |
| --- | --- | --- |
| OpenAI Base URL | `http://localhost:8084` | `POST /chat/completions` |
| Anthropic Base URL | `http://localhost:8084` | `POST /messages` |

带上 `/v1` 也能通（那两个别名已注册），但没必要多一层 —— 真实 Anthropic 官方的
 Base URL 确实带 `/v1`，而本 mock 为了两侧填法一致把两种都收下了。

API Key 随意，mock 不校验。拉取模型能一次拿到两个场景；记得勾上 `caps_tools`，
否则 Copilot 不下发 tools，两个场景都无从触发。

## 手动验证

### OpenAI 侧看帧顺序（PowerShell）

```powershell
$body = '{"model":"to-tool-first","stream":true,"messages":[{"role":"user","content":"hi"}]}'
$r = (Invoke-WebRequest -Uri 'http://localhost:8084/chat/completions' -Method Post `
        -ContentType 'application/json' -Body $body -UseBasicParsing).Content
$i = 1
$r -split "`n" | Where-Object { $_ -match '^data: \{' } | ForEach-Object {
  $j = ($_ -replace '^data: ','' | ConvertFrom-Json)
  $d = $j.choices[0].delta | ConvertTo-Json -Compress -Depth 6
  Write-Output ("帧{0}: delta={1} finish={2}" -f $i, $d, $j.choices[0].finish_reason)
  $i++
}
```

### Anthropic 侧看 block index

```powershell
$body = '{"model":"to-tool-first","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"hi"}]}'
$r = (Invoke-WebRequest -Uri 'http://localhost:8084/messages' -Method Post `
        -ContentType 'application/json' -Headers @{'anthropic-version'='2023-06-01'} `
        -Body $body -UseBasicParsing).Content
$r -split "`n" | Where-Object { $_ -match '^data: \{"type":"content_block_start"' } | ForEach-Object {
  $j = ($_ -replace '^data: ','' | ConvertFrom-Json)
  Write-Output ("index={0} type={1}" -f $j.index, $j.content_block.type)
}
```

### 端到端（Copilot）

在 Copilot 里选 `[<provider-key>] to-content-first` 问一句话，确认工具被执行；
再选 `to-tool-first` 问同一句话，观察是否静默终止。两者的唯一差异就是顺序，
因此任何行为差异都只能归因于顺序。

Anthropic 端点**不能靠在 Copilot 里选模型触发** —— Copilot 走 OpenAI / Ollama 协议。
要验证 `/messages`，用上面的 PowerShell 直接打 COSP 的 `/v1/messages`，或用 Claude 系客户端。

## 不参与自动化测试

与另外四个 mock 一致：本脚本是手动验证工具，不进 `./mvnw test`，也不进 Vitest。
需要在自动化测试里断言 chunk 形态时，用 `AbstractUpstreamChatServiceTests` 那种
直接调清洗函数的方式，不要起 HTTP 服务。
