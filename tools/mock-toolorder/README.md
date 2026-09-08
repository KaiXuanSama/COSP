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
`finish_reason` 全部原样透传，空串占位被删但对下游等效）。因此责任落在下游客户端的流解析，
本 mock 的作用是**稳定复现那个顺序**，用来观察客户端实际行为、并产出可复现的最小样例。

怀疑的位置在 `vscode-copilot-chat` 的 `SSEProcessor.processSSEInner`
（`src/platform/networking/node/stream.ts`）：工具调用分支里有一句
`if (solution.text.length)` 补空格去 flush linkifier，说明作者假定的是「正文先、工具调用后」；
而收尾的 `getToolCalls()` 对 `name` 与 `id` 用非空断言，没填上就产出 `undefined`，
上层匹配不到工具却又不报错，于是判成「本轮没有工具调用」而结束整轮。

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

| 模型名 | 顺序 | 预期 |
| --- | --- | --- |
| `to-content-first` | 正文 → 工具调用 | **对照组**：已知可正常执行工具 |
| `to-tool-first` | 工具调用 → 正文 | **复现组**：疑似静默终止且不执行工具 |

两个场景共用同一份正文、同一个工具（`read_file`）、同一份参数，**唯一差异是顺序**。
参数刻意切成两片，顺带覆盖「参数跨片」这一常见形态。

模型名解析容忍 `[provider-key] ` 前缀 —— 手打请求时容易带上。

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
