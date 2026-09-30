# anyrouter 供应商行为记录

> 本文档记录 **anyrouter 这一个供应商**的实测行为、已排除项与未解问题。
> 与 COSP 自身的实现说明分开放：COS`P 的实现取舍见
> [ADAPTATIONS.md](./ADAPTATIONS.md) 与 [KNOWN_DEBT.md](../../architecture/KNOWN_DEBT.md)，
> 本文只记「这个上游到底怎么表现」。
>
> 起始于 2026-09-14。**本文件刻意不入库**（未 `git add`，也未写进 `.gitignore`），
> 理由：它是针对单个第三方服务的观测，会随对方改动过期，不适合作为仓库的长期事实。

## 供应商画像

| 项 | 值 |
|---|---|
| 端点 | `https://anyrouter.top`（Chat / Responses / Messages 三条路径同为 `/v1` 前缀） |
| 后端实现 | **new-api** —— 证据：错误信封带 `"type":"new_api_error"` 与 `"code":"get_channel_failed"` |
| 前置分层 | 阿里云 **ESA** + **Caddy** —— 证据：响应头 `Server: ESA`、`Via: 1.1 Caddy, ...`、`Set-Cookie: acw_tc=...; cdn_sec_tc=...`（阿里云反爬 token） |
| 网络要求 | **必须走代理**。COS`P 侧已验证：`anyrouter.top:443` 在代理投影集合内且能连通 |

## 已确认可用

### Responses 路径（`gpt-6-astra`）

多次成功，流式正常。一次典型成功调用：

| 指标 | 值 |
|---|---|
| TTFB / 总耗时 | 3.8s / 9.0s |
| tokens | 输入 6741、输出 178、缓存 0% |
| 事件序列 | 全用官方标准事件名、`sequence_number` 连续无跳号 |

**它还证明了代理链路是通的** —— 同一个 `anyrouter.top:443`、同一条 Java 客户端出站路径。
因此任何「代理/网络层」的猜测都可以先用这条事实排除。

该路径的回传数据里另有三处值得注意的形态（已记入 `PROVIDER_ADAPTATIONS.md`）：
四个非官方字段（`content_filters` / `tool_usage` /
`internal_chat_message_metadata_passthrough` / `client_metadata`）靠 `@JsonAnySetter` 透传；
`reasoning` 项只有 `encrypted_content`（`content` 与 `summary` 皆空）；
`phase` 为官方字段不可丢。

### 失败时的错误信封（有信息量）

模型负载满时（HTTP 500）：

```json
{"error":{"message":"当前模型 gpt-6-astra 负载已经达到上限，请稍后重试 (request id: ...)",
          "type":"new_api_error","param":"","code":"get_channel_failed"}}
```

带 `code`、带 request id、`type` 明确 —— 这**是 new-api 在业务逻辑里生成的**，
可用于区分「请求到了应用层」与「被基础设施拦下」。这个对比后来成了关键判据。

## 未解问题：`claude-fable-5-1`（Messages 路径）恒定 503

### 症状

Claude CLI → ccs（仅作配置）→ COSP → anyrouter，恒定失败：

```
HTTP 503
{"error":{"message":"Service Unavailable","type":"error"},"type":"error"}
```

- TTFB 为 `—`（从未收到首字节），3.5s 后失败
- **anyrouter 管理页无任何该调用的日志**
- 而 ccs **直连** anyrouter 用同一模型是通的

### 错误信封的反常（重要线索）

与上面 astra 失败时的 `new_api_error` 相比，这条错误**信息量低得多**：

| | astra 失败（500） | fable 失败（503） |
|---|---|---|
| `code` | `get_channel_failed` | **无** |
| request id | 有 | **无** |
| `type` | `new_api_error`（业务层） | `error`（泛化） |

**这个差别说明 fable 的失败很可能不是 new-api 的业务判断**，而是链路中间某层发出的通用错误
—— 与「管理页无日志」互相印证。**待服务恢复后重新采样确认。**

### 已排除项（强证据，重测也不会变）

| # | 排除项 | 依据 |
|---|---|---|
| 1 | 出站代理 | `anyrouter.top:443` 在投影集合内；`gpt-6-astra` 经同一代理、同一目标成功 |
| 2 | 请求头透传 | 日志内 `User-Agent: claude-cli/2.1.270`、全套 `X-Stainless-*`、完整 `anthropic-beta`（12 项）均为 CLI 原值 |
| 3 | URL 路径 | 界面显示解析结果为 `https://anyrouter.top/v1/messages`（路径正确） |
| 4 | 模型名解析 | `[anyrouter] claude-fable-5-1[1M]` → 剥离为 `claude-fable-5-1`；`[1M]` 正确表达为 beta 头 `context-1m-2025-08-07` |
| 5 | 请求体注入 | 「思考深度」与「思考方式」双双设为**透传**后，错误完全不变 |

这五项合起来说明：**CCS→COSP 这一段没有可指摘之处**。
至少在 fable 这个问题上，COS`P 的转发链路是清白的（请求头、路径、模型名、请求体全部正确）。

### 唯一未验证项

**COS`P 库里那把 key（令牌）与 `claude-fable-5-1` 的权限关系。**

new-api 的令牌可限制可用模型列表。若 COSP 用的那把 token 建时未勾选该模型，
表现就是「模型不可用」类错误。

之前做过一次对比，但**当时服务已开始不稳定，结果被污染**。此外留意一个盲区：
界面上 key 是掩码（`****`），只对比前缀属于**弱对比**，不能作为结论。

### 为何暂停

服务方后来连**直连也不通**了，Claude CLI 报：

```
Unable to connect to API (UNKNOWN_CERTIFICATE_VERIFICATION_ERROR)
```

这是 **TLS 层失败**，比 503 更靠前。它直接推翻了整个排查的前提
——「ccs 能通 / COSP 不通」这个对比不再成立。继续研究没有意义。

（顺带：证书错误经代理出现时也可能是代理 MITM 导致，那是另一条独立的线。）

### 恢复后的测试序列

**第一步（决定性，只改一个字段）**

> 把 **ccs 直连成功时用的那把 key** 填进 COSP 的 anyrouter 供应商配置，重试。

| 结果 | 结论 |
|---|---|
| 能通 | key / 令牌权限是根因 → 进 anyrouter 后台比对两把 token 的模型白名单与分组 |
| 仍失败 | 与 key 无关 → 回到客户端身份方向（Java/Reactor Netty vs Node/undici） |

**第二步（若第一步未通）**

看 anyrouter 管理页：**直连成功的那几次有日志吗？**

| 结果 | 含义 |
|---|---|
| 成功的有、失败的无 | 失败请求根本没到应用层 → 卡在 ESA/CDN 或更前 |
| 都没有 | 该页覆盖不到这个 token 的流量，此路不通，需换判别手段 |

**第三步（若前两步都无结论）**

抓包对比。目标是拿到**直连成功那一次的完整出站请求**（headers + body），逐项对比。
抓法按成本排序：

1. ccs 自身的日志（它本身就是代理，可能已记录）
2. 把 ccs 的 provider base_url 指向 mitmproxy
3. Claude CLI 直连 anyrouter（隔离 ccs 这一层）

对比重点：`x-api-key` 实际值、完整 header 集合与顺序、`Host` 与路径、
请求体差异、TLS/HTTP2 指纹（最后才考虑）。

### 候选根因（按可能性排序）

1. **key / 令牌权限差异** —— 未验证，最可能
2. **`/v1/messages` 在该中转被单独限制** —— 与 astra 走 `/v1/responses` 不同的路径策略
3. **客户端指纹差异** —— Java/Reactor Netty vs Node；但 astra 同客户端能通，故此条偏弱
   （除非 WAF 对两条路径设了不同严格度）
4. **模型渠道在该分组下不可用**

## 附：一个配置陷阱（与 COSP 约定有关，但会在这里踩到）

COS`P 的 `anthropic_base_url` **保留**数据库里写的路径，只在其后追加 `/messages`；
而 **Claude Code 的 `ANTHROPIC_BASE_URL` 约定是客户端自己补 `/v1/messages`**。

两者对「base」的语义不同，于是从 ccs 抄地址过来时：

| 抄过来的值 | COSP 实际请求 | 结果 |
|---|---|---|
| `https://anyrouter.top` | `https://anyrouter.top/messages` | ❌ 少一层 `/v1` |
| `https://anyrouter.top/v1` | `https://anyrouter.top/v1/messages` | ✅ 正确 |

本次**不是**这个原因（界面显示的解析结果是对的），但换别的供应商时会踩到。
COS`P 侧已显示解析后的完整 URL，不算隐蔽 —— 若收口，给该输入框的占位符补一句
「含 `/v1`，如 `https://xxx/v1`」即可。
