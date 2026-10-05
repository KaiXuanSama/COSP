# COSP 实机功能测试报告

**日期**：2026-09-27
**目的**：以实际调用验证「大刀阔斧的重构」在真实链路上的行为（全量单测绿不等于端到端正确）。
**手段**：mock 供应商（`http://localhost:8081`，33 场景）+ COSP 管理端点 + 直接打 `/v1/chat/completions`。
**配置**：`retry_max_attempts = 7`，未开鉴权。

---

## 一个贯穿全局的测试约定：模型名一律带显式前缀

**所有测试请求都用 `[provider-key] model` 形式**（如 `[mock] retry-5xx`、
`[translatemock] translate-tool-split-args`）。

**为什么不靠无前缀名**：无前缀名要求 `ProviderRouteResolver` 找到**唯一匹配**。
真实环境里同一个上游常被配成多个供应商（不同协议集合、不同 Key、不同代理开关）——
重名是常态而非例外。显式前缀是唯一稳定的写法，也让「这次打的是哪个供应商」
在脚本里一目了然。

> 这条约定是被撞出来的：最初 D 组用无前缀名，结果 `baseline-normal` 在
> `mock` 与 `translatemock` 下重名 → 路由失败 → 报「**没有可用的上游服务来处理模型**」。
> 那个报错**不会提示「你有个重名」**，排查指向了「供应商没启用」而非「名字歧义」。
> （按用户决定，此报错暂不改，见下。）

**配对事实**：COSP 发往上游时会**剥掉前缀**，因此 mock 侧日志里是干净的场景名 ——
测试脚本里「发请求用带前缀名、数 mock 日志用无前缀名」是**正确**的搭配，不是笔误。

---

| 要测什么 | ✅ 正确判据 | ❌ 用过的错判据 |
|---|---|---|
| **重试次数** | mock 侧 `■ 连接关闭` 行的条数（每请求恰好一条） | COSP 日志的 `duration_ms`（只记一轮）；下游总耗时（抖动使阈值不可靠） |
| **流式错误** | `event: error` 帧里的 `code` | HTTP 状态码（流式恒 200 —— SSE body 已开始，无法再改） |
| **非流式错误** | HTTP 状态码（原样透传） | — |
| **退避间隔** | 单调上升至上限、首退 1–8s | 精确匹配 2/4/8…（`Retry.backoff` 带 ±50% 抖动，且 mock 时间戳仅秒级） |

> 错误判据曾导致 9 项「假失败」。**修正判据后，所有实际行为都是正确的。**

---

## A 组：空响应兜底 ✅ 全部通过

`EmptyResponseGate` 的判定 + 扣放 + 耗尽放行。

| 用例 | mock 请求次数 | 结论 |
|---|---|---|
| `blank-empty-content`（CHAT 流式） | **6** | 判空重试 ✓ |
| `blank-empty-body`（CHAT 流式） | **5** | 0 帧 body 也判空 ✓ |
| `blank-zero-usage`（CHAT 流式） | **5** | 全 0 usage 仍判空（usage 不是判据）✓ |
| `blank-empty-content`（Messages 流式） | **5** | 跨协议一致 ✓ |
| `blank-empty-content`（Messages 非流式） | **8** | 跨模式一致 ✓ |
| `pass-tool-call` / `pass-malformed` / `pass-reasoning-only` / `pass-sse-body` | **1 / 1 / 1 / 1** | **零重试**（对照组）✓ |

**耗尽放行实测**（`blank-empty-content` 跑满预算）：

```
下游 HTTP=200   总耗时 107.0s   mock 请求 8 次
下游收到 3 个数据帧（最后一次的空帧原样放行）
以 data:[DONE] 正常收尾
```

- 放行的帧是**上游真实返回的空帧** —— 符合「透传上游真实返回」的语义
- 107s ≈ 退避 2+4+8+16+30+30+30 加抖动

---

## B 组：重试与放行 ✅ 行为全部正确

| 用例 | 实测 | 结论 |
|---|---|---|
| `fail-401`（流式） | 请求 **1** 次；HTTP 200 + `event:error(code=401)` | 401 零重试 ✓；错误经 SSE 帧传递 ✓ |
| `fail-401`（非流式） | HTTP **401** + JSON body | 状态码原样透传 ✓ |
| `retry-recover` | 请求 **3** 次；下游 200、33 帧 | 第 3 次成功后停止 ✓ |
| `retry-5xx` | 请求 **8** 次；退避 5,5,9,24,17,21 秒 | 跑满预算 ✓ |
| `retry-429` | 请求 **6** 次 | 429 可重试 ✓ |
| `retry-truncated` | 请求 **6** 次 | 截断按网络失败重试 ✓ |

### 一个需要说清的语义：`retry_max_attempts=7` → 实测 **8** 次请求

链路是：`RetryPolicyService.toReactorMaxAttempts(7)` 返回 `7` →
`Retry.backoff(7, ...)` → **Reactor 的语义是「最多 7 次重试」** →
总请求数 = 1 首次 + 7 重试 = **8**。

日志文案与之一致（`UpstreamAutoRetry.logRetryAttempt` 打的是
`重试第 {attempt}/{budget} 次`，budget 就是配置值 7）。

**结论：配置值 = 重试次数，不是总尝试次数。** 语义自洽，但配 7 的人可能预期
「最多 7 次请求」而实际看到 8 次 —— 值得在管理后台的字段说明里点明。

---

## C 组：三协议 × 两态的直连主路径 ✅ 全部通过

> **这组是正常路径，其余各组是病态路径。** 若 C 组坏了，
> 后面所有「异常处理正确」的结论都没有意义。

`ProtocolDispatchManager` **规则 1 是「同名协议优先直连」** —— `mock` 供应商三协议全开，
因此这些用例走的是**同协议直连**（与 D 组的翻译路径正好对照）。

| # | 用例 | 实测 | 结论 |
|---|---|---|---|
| C1 | Chat 流式 | 30 个 content 帧、`finish_reason=stop`、含 `[DONE]`、连接正常关闭 | 全链路 ✓ |
| C2 | Chat 非流式 | `object=chat.completion`、`choices[0].message`、带真实 usage | 可入库 ✓ |
| C3 | Messages 流式 | 事件序列 **六种齐全**：`message_start → content_block_start → content_block_delta → content_block_stop → message_delta → message_stop`；**未混入 OpenAI chunk** | 直连未翻译 ✓ |
| C4 | Messages 非流式 | `type=message`、1 个 content 块、带 Anthropic usage | ✓ |
| C5 | `baseline-thinking-text` | 流式：`thinking_delta` + `text_delta` 都有；非流式：块类型 `[thinking, text]` | 两段都到达下游 ✓ |
| C6 | `baseline-tool-call` | CHAT：`tool_calls` 带 `name`、`arguments` 合法 JSON、`finish_reason=tool_calls`；MESSAGES：`tool_use` + `input_json_delta`（原生形态） | 原样透传 ✓ |

> **C3 的判据有个坑**：`lib.request()` 只收集 SSE 的 `data:` 行，**`event:` 行既不进 `raw` 也不进 `frames`**。
> 因此判据要读 data 帧里的 `"type":"..."`（两者在 Anthropic 协议里必然一致），
> 不能去 `raw` 里找 `event: xxx`。我最初写错了，得到假失败。

---

## D 组：C2M 跳协议翻译 ✅ 全部通过

### 如何触发（关键）

`ProtocolDispatchManager` **规则 1 是「同名协议优先直连」** —— 供应商三协议全开时
**永远走直连，翻译支线不会被触发**。因此专建了 `translatemock` 供应商（只勾 MESSAGES，
指向同一个 mock）。

> ⚠️ **建完立刻撞上一个真实约束**：`baseline-normal` 等模型名在 `mock` 与 `translatemock`
> 下**重名**，而 `ProviderRouteResolver` 对**无前缀**模型名要求唯一匹配 →
> 路由失败，报「没有可用的上游服务来处理模型」。
> 必须用**带前缀**的名字：`[translatemock] baseline-normal`。
>
> **这是用户会真实遇到的坑**：为了测翻译而给同一上游建两个供应商（不同协议配置）时，
> 模型名必然冲突 —— 而报错信息（「没有可用的上游服务」）不会提示「你有个重名」。
> 考虑在 `ProviderRouteResolver` 的歧义报错里列出冲突的供应商名。

### 结果

| 用例 | 实测 | 结论 |
|---|---|---|
| D1 `baseline-normal` 流式 | `down=CHAT → up=MESSAGES`；下游 9 帧全是 `chat.completion.chunk` | 翻译生效，下游**看不到** Anthropic 事件 ✓ |
| D2 非流式 | 同上，HTTP 200 | ✓ |
| D3 `translate-tool-split-args` | 23 片 → 拼成 265 字符**合法 JSON**；`tool_calls index = {0}` | 转义序列跨片边界未损坏 ✓ |
| D4 `translate-tool-multi-split` | `index = {0,1,2}`（上游 block index 是 **1/2/3**） | 稠密重映射正确 ✓ |
| D7 `translate-finish-reason` | `finish_reason = "tool_calls"`（上游 `stop_reason=max_tokens`） | **覆盖规则生效** ✓ |

> **D7 是最关键的一条**：若下游收到 `length`，Copilot 会判定回答被截断、
> **放弃执行已经拿到的完整工具调用**并结束对话，且全链路无任何报错。

---

## F 组：连接生命周期与中断 ✅ 全部通过

### 相位类（脚本断言）

| 用例 | 实测 | 结论 |
|---|---|---|
| **F1** `phase-done-early` | 33 帧后收到 `finish_reason:stop` + `[DONE]`；**下游连接超时未关**（mock 从不 `end`）；相位 `…CHUNK → COMPLETED` | **Layer 1 在下游连接仍挂着时就已收尾** ✓ |
| **F2** `phase-eof-fallback` | 无 `[DONE]`、TCP 关闭 → 下游流正常结束、相位 `COMPLETED` | Layer 2 兜底 ✓ |
| **F7** `phase-slow-steady` | 10 个 CHUNK 事件，`chunkCount` **1→10 单调递增** | Toast 计数实时更新 ✓ |
| **F8** `phase-slow` | 延迟 7.9s 后正常返回，相位无 `FAILED` | 长耗时不被误判为停滞 ✓ |

> **F1 的判据要小心**：`phase-done-early` 的场景是「发完 `[DONE]` 但**永不关 TCP**」，
> 所以**下游 HTTP 连接会一直挂着** —— 那是预期，不是缺陷。
> 文档里「COMPLETED 不等连接关闭」说的也是**生命周期相位**，不是下游连接。
> 我最初把判据写成「下游流是否结束」，得到假失败。

### 中断类（脚本打端点 + 浏览器核对）

| 用例 | 实测 | 结论 |
|---|---|---|
| **F3** `cancel-hang` | 取消前 `RECEIVED→CONNECTED`；取消后 `→ABORTED`；下游 **0 帧、无 `event:error`** | **静默断连**（符合设计：不发错误帧）✓ |
| **F4** `cancel-stall` | 已收 5 个 content 帧 → 取消 → `ABORTED`，下游关闭 | 产出后停滞期可中断 ✓ |
| **F5** `cancel-stall-resume` | 停滞期（35s 窗口内）打取消 → **立即生效**，不必等恢复 | ✓ |
| **F6** 非流式 | retry 端点返回 **`retried=false`**；UI 菜单**只有「断开连接」** | 非流式无静默重试入口 ✓ |
| **B4/B5** 静默重试 | `retried=true`；**mock 侧出现第二次请求**；下游 SSE **全程未断**（帧数 6→12） | ✓ |

**浏览器侧核对**（同一时刻发一条流式 + 一条非流式挂起调用）：

| Toast | 相位文案 | 右键菜单项 |
|---|---|---|
| 流式 `[mock] cancel-hang` | `已连接，正在等待首字响应` | `静默重试` + `断开连接` |
| 非流式 `mock cancel-hang` | `下游发出请求` | **仅 `断开连接`** |

> 两条的相位文案不同，正好印证 `cancel-hang` 的两态机制不同：
> 流式先写响应头（进 CONNECTED），非流式不写头（纯等待）。
> 在 UI 上点「断开连接」→ Toast 变为「**已主动取消本次调用**」，活跃计数 2 → 1 ✓。

---

---

## 副作用与待清理

| 项 | 说明 |
|---|---|
| **`translatemock` 供应商** | 为测 C2M 翻译而建（只勾 MESSAGES，7 个模型）。**保留** —— 它使 7 个模型名与 `mock` 供应商重名，因此那些模型必须带前缀（`[mock] xxx` / `[translatemock] xxx`）才能路由。这**不是缺陷**：显式前缀本就是本服务推荐的路由写法 |
| mock 侧日志标签修正 | `baseline-tool-call` 与 `pass-tool-call` 复用同一实现，日志曾写死前者的名字，导致后者在日志里查不到。已改为传 `label` 参数 |

---

## 记录：两处「报错信息不够可操作」（按决定**暂不改**）

| 现象 | 现状 | 为什么不改 / 改的话怎么改 |
|---|---|---|
| **模型名歧义** | 两个供应商有同名模型时，报错是「没有可用的上游服务来处理模型: X」—— 排查会指向「供应商没启用」而非「名字歧义」 | `ProviderRouteResolver.resolve` 已知 `matches.size() != 1`，能在那里带上冲突的 provider key 列表。按「不自作主张」原则暂留 |
| **`retry_max_attempts` 语义** | 配置值 = **重试次数**（配 7 → 共 8 次请求）。字段说明没点明 | 在管理后台该字段的说明里加一句「重试 N 次（总请求 N+1 次）」即可 |

---

## 测试脚手架（已入库，可复用）

```powershell
# mock 启动（输出必须落盘 —— 重试计数全靠它）
cd tools\mock-upstream
node mock-upstream.js 2>&1 | Tee-Object -FilePath ..\live-test\mock.log
```

```powershell
cd tools\live-test
node lib.js config        # 先看当前配置（地址 / 账号 / 供应商 key 及来源）
node lib.js login         # 登录取 JWT（用 COSP_ADMIN_USER/PASS，默认 root/root）
node a-guard.js           # A 组：空响应兜底（约 6 分钟）
node b-retry.js           # B 组：重试与放行（约 6 分钟）
node c-direct.js          # C 组：三协议 × 两态的直连主路径（约 1 分钟）
node d-translate.js       # D 组：C2M 翻译（约 1 分钟）
node f-lifecycle.js       # F 组（相位）（约 1 分钟）
node f-cancel.js          # F 组（中断）（约 1 分钟）
node f-retry.js           # 静默重试定向验证（约 1 分钟）
```

**全部配置走环境变量，脚本里没有写死项**：

| 环境变量 | 作用 | 默认 |
|---|---|---|
| `COSP_BASE_URL` | COSP 地址 | `http://localhost:11434` |
| `COSP_ADMIN_USER` / `COSP_ADMIN_PASS` | 管理端点登录 | `root` / `root` |
| `COSP_MOCK_PROVIDER` | 上游 mock 供应商 key | `mock` |
| `COSP_TRANSLATE_PROVIDER` | 翻译供应商 key | `translatemock` |

细节与判据表见 [`tools/live-test/README.md`](../../../tools/live-test/README.md)。
`token.txt` 与 `mock.log` 已由 `.gitignore` 排除（前者是凭据，后者是运行产物）。
