---
name: cosp-live-mock-testing-skill
description: "运行 COSP 与上游 mock 的实机联测（真实 HTTP 调用验证端到端行为）。Use when: 需要实机验证 COSP、跑 A/B/C/D/F 组测试、用 mock 复现上游异常（空响应/重试/停滞/翻译）、验证重构未破坏运行期行为、接管或重启 mock 服务、或排查「单测绿但实机不对」。"
---

# COSP 实机联测工作流

单测绿 **不等于** 链路上正确。本 skill 指导 agent 自主完成一次真实的端到端联测。

**可自主动作**：启停 mock、登录取 token、调 API 增删供应商与模型、打聊天请求、读调用日志、
取消/重试在途调用。**不需要用户手工准备任何东西** —— 照下面的步骤自己搭环境。

---

## 0. 环境概览（先记住这些事实）

| 项 | 值 |
|---|---|
| COSP 地址 | `http://localhost:11434`（**单端口**：Ollama 发现 + OpenAI/Anthropic 聊天 + 管理后台 API） |
| 管理后台 UI | `http://localhost:5173`（Vite dev server；由用户跑 `npm run dev`） |
| 管理登录 | `root` / `root`（默认；可用 `COSP_ADMIN_USER`/`COSP_ADMIN_PASS` 覆盖） |
| 上游 mock | `http://localhost:8081`，**33 个场景**，三协议全开 |
| 下游嗅探 mock | `http://localhost:11333`（E 组用，通常不必） |
| 网关鉴权 | 默认**关闭**；开了会拦三个聊天端点（Ollama 发现接口必须匿名） |
| `retry_max_attempts` | 用户环境设为 `7` → 语义是「**重试 7 次**」→ 总请求 **8** 次 |

**测试脚手架**：`tools/live-test/`（已入库）。判据与坑见其 `README.md` —— **开工前读一遍**。

---

## 1. 准备：检查服务状态

### 1.1 COSP 是否在运行

```powershell
try { (Invoke-WebRequest 'http://localhost:11434/api/version' -TimeoutSec 3 -UseBasicParsing).Content }
catch { 'COSP 未运行' }
```

- **已启动 → 留着，不要动它**。用户可能正依赖它（本对话自身就走它）。
- 未启动 → **不要自己启动**，让用户启动（它需要 `COSP_MASTER_KEY` 等环境，且是长驻服务）。

### 1.2 mock 是否在运行

```powershell
try {
  $r = Invoke-WebRequest 'http://localhost:8081/v1/models' -TimeoutSec 3 -UseBasicParsing
  "mock 在运行，场景数 $(($r.Content | ConvertFrom-Json).data.Count)"
} catch { 'mock 未运行' }
```

### 1.3 mock 已运行时：**必须先停掉它，自己重启**

**为什么必须接管**：重试次数的唯一可靠判据是 **mock 侧收到的请求次数**，
而那靠 `mock.log` 落盘。别人起的 mock 通常**没把 stdout 落盘**，
或者日志写在别处 —— 你会数到 0 次，得出错误结论（我踩过）。

```powershell
# 按端口精确定位 PID —— 不要按进程名杀！
$pids = Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique
foreach ($p in $pids) {
  $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $p"
  Write-Output "即将停止 PID $p : $($proc.Name) — $($proc.CommandLine)"
  # 确认命令行里是 mock-upstream.js 才停（防止端口被别人占用）
  if ($proc.CommandLine -like '*mock-upstream*') { Stop-Process -Id $p -Force }
  else { Write-Warning "端口 8081 被非 mock 进程占用，请人工确认" }
}
```

> ⚠️ **绝对不要 `Stop-Process -Name node`。**
> 实测本机同时有 **3 个 `node.exe`**：
>
> | 进程 | 是什么 | 杀掉会怎样 |
> |---|---|---|
> | `mock-upstream.js` | 上游 mock | 应该停（这就是目的） |
> | `npm-cli.js run dev` | **前端 dev server** | 用户的管理后台 UI 白屏 |
> | `vite.js` | **同上（子进程）** | 同上 |
>
> 只能按**端口**定位 PID，并**核对命令行含 `mock-upstream`** 才停。

### 1.4 自己启动 mock（输出落盘）

```powershell
Set-Location tools\mock-upstream
node mock-upstream.js 2>&1 | Tee-Object -FilePath ..\live-test\mock.log
```

用 **`mode=async`** 启动（长驻进程），并把启动横幅当作就绪信号 —— 它会打印
`可用场景 33 个` 与 `Base URL 填 http://localhost:8081`。

**`Tee-Object` 是必需的**，不是可选：它同时写终端与 `mock.log`，而后者是重试计数的唯一来源。

---

## 2. 准备：登录与验证供应商

```powershell
Set-Location tools\live-test
node lib.js config     # 先看配置及来源：地址 / 账号 / 供应商 key
node lib.js login      # 取 JWT → token.txt
```

**配置优先级**：命令行环境变量 > `.env` > 代码默认值。需要换环境时：

```powershell
Copy-Item .env.example .env    # 模板（入库），按需修改
```

### 2.1 确认 mock 供应商存在且模型已启用

```powershell
$tok = (Get-Content token.txt -Raw)
$prov = (Invoke-WebRequest "http://localhost:11434/config/api/providers" `
         -Headers @{Authorization="Bearer $tok"} -UseBasicParsing).Content | ConvertFrom-Json
$p = $prov.mock
"providerKey=mock  enabled=$($p.enabled)  protocols=$($p.supportedProtocols -join ',')  models=$($p.models.Count)"
```

**期望**：`enabled=True`、`protocols=CHAT,MESSAGES,RESPONSES`、`models=33`。

### 2.2 缺失时自己补上（agent 可自主完成）

**新建供应商**（表单是 `form-urlencoded`，**不是 JSON**）：

```powershell
$tok = (Get-Content token.txt -Raw)
$body = 'displayName=Mock&baseUrl=http%3A%2F%2Flocalhost%3A8081' +
        '&supportedProtocolsJson=%5B%22CHAT%22%2C%22MESSAGES%22%2C%22RESPONSES%22%5D'
Invoke-WebRequest -Method Post "http://localhost:11434/config/api/providers" `
  -Headers @{Authorization="Bearer $tok"} -ContentType 'application/x-www-form-urlencoded' `
  -Body $body -UseBasicParsing | Select-Object -ExpandProperty Content
# → {displayName, providerKey, ok:true}；providerKey 由 displayName 推导
```

**批量启用模型** —— 注意是**全量保存**（一次提交该供应商的**全部**模型，
未提交的会被删掉）。字段名是 `models[N].name` / `.enabled` / `.contextSize` / `.capsTools`：

```powershell
$tok  = (Get-Content token.txt -Raw)
$key  = 'mock'
$names = @(  # 33 个场景名，见 tools/mock-upstream/README.md §5 或 GET /v1/models
  'baseline-normal','baseline-tool-call','baseline-thinking-text',
  'baseline-content-first','baseline-tool-first',
  'blank-empty-content','blank-empty-body','blank-empty-choices','blank-zero-usage',
  'pass-tool-call','pass-reasoning-only','pass-thinking-only','pass-malformed','pass-sse-body',
  'retry-5xx','retry-429','retry-truncated','retry-recover','fail-401',
  'phase-done-early','phase-eof-fallback','phase-slow','phase-slow-steady',
  'cancel-hang','cancel-stall','cancel-stall-delayed','cancel-stall-resume',
  'translate-tool-split-args','translate-tool-multi-split','translate-tool-interleaved',
  'translate-tool-no-args','translate-finish-reason','translate-finish-reason-nonstandard'
)
$parts = @('baseUrl=http%3A%2F%2Flocalhost%3A8081','apiKeys=%5B%5D',
           'supportedProtocolsJson=%5B%22CHAT%22%2C%22MESSAGES%22%2C%22RESPONSES%22%5D',
           'headerRulesJson=%5B%5D')
$i = 0
foreach ($n in $names) {
  $enc = [uri]::EscapeDataString($n)
  $parts += "models%5B$i%5D.name=$enc", "models%5B$i%5D.enabled=on",
            "models%5B$i%5D.contextSize=128000", "models%5B$i%5D.capsTools=on"
  $i++
}
($parts -join '&') | Out-File -Encoding ascii -NoNewline save.txt
Invoke-WebRequest -Method Post "http://localhost:11434/config/api/providers/$key/config" `
  -Headers @{Authorization="Bearer $tok"} -ContentType 'application/x-www-form-urlencoded' `
  -Body (Get-Content save.txt -Raw) -UseBasicParsing | Select-Object -ExpandProperty Content
Remove-Item save.txt
```

> 也可先试 `POST /config/api/providers/{key}/pull-models` 自动拉取，但它可能因缺 API Key 回 400。

---

## 3. 跑测试

```powershell
Set-Location tools\live-test
node a-guard.js      # A 空响应兜底（约 6 分钟）
node b-retry.js      # B 重试与放行（约 6 分钟）
node c-direct.js     # C 三协议直连主路径（约 1 分钟）← 唯一的正常路径组，别跳过
node d-translate.js  # D C2M 翻译（需只勾 MESSAGES 的供应商，见下）
node f-lifecycle.js  # F 相位
node f-cancel.js     # F 中断
node f-retry.js      # 静默重试
```

**每组用 `mode=async`**（A/B 各 6 分钟，超时会中断），完成时会自动通知。

### 3.1 D 组的前置：造一个只支持 MESSAGES 的供应商

`ProtocolDispatchManager` **规则 1 是「同名协议优先直连」** —— 供应商三协议全开时
永远走直连，**翻译支线不会被触发**。因此 D 组必须有第二个供应商：

```powershell
# 建一个指向同一 mock、但只勾 MESSAGES 的供应商，再加 7 个模型
# 完整脚本见 tools/live-test/README.md「D 组的前置」（含 providerKey 推导与模型表单）
```

建完后**模型名会与 `mock` 供应商重名** —— 这是必然的（同一上游两个供应商）。
因此 D 组脚本用 `[translatemock] xxx` 显式前缀路由。**不要试图避免重名**：
无前缀名要求全库唯一匹配，重名时路由失败，而报错是
「没有可用的上游服务来处理模型」—— **不会提示「你有个重名」**。

### 3.2 模型名一律带显式前缀

所有请求用 `[provider-key] model`。key 由 `COSP_MOCK_PROVIDER` /
`COSP_TRANSLATE_PROVIDER` 配置，不写死在脚本里。

**配对事实（不是笔误）**：COSP 发往上游时会**剥掉前缀**，所以
**发请求用带前缀名、数 mock 日志用无前缀名**。

---

## 4. 判据（**用错会得到假失败** —— 我踩过 9 项）

| 要测什么 | ✅ 正确判据 | ❌ 错判据 |
|---|---|---|
| **重试次数** | mock 侧 `▶` 行的条数（形如 `▶ chat  model=x  stream=true`；每请求一条，且带协议与 `stream=` 维度） | COSP 日志的 `duration_ms`（**每轮各记一条**，看不出总数）；下游总耗时 |
| **流式错误** | `event: error` 帧里的 `code` | **HTTP 状态码 —— 流式恒 200**（SSE 已开始，无法再改） |
| **非流式错误** | HTTP 状态码（原样透传） | — |
| **退避间隔** | 单调上升至上限、首退 1–8s | 精确匹配 2/4/8…（`Retry.backoff` 带 ±50% 抖动，mock 时间戳仅秒级） |
| **静默重试** | **mock 侧出现第二次 `▶` 请求** | 下游帧数变多、`retried=true`（都是间接证据） |
| **是否走翻译** | COSP 日志 `downstream_protocol` / `upstream_protocol` 两列 | 只看下游响应形状 |
| **Anthropic 事件名** | data 帧里的 `"type"` | 在 `raw` 里找 `event:`（`lib.request` **只收 `data:` 行**） |

### 4.1 一个反直觉但正确的事实

`phase-done-early` 场景**永不关 TCP**（发完 `[DONE]` 就不动了）—— 因此
**下游 HTTP 连接会一直挂着**，直到重试预算耗尽才放行。**那是预期，不是缺陷**。
判据是「生命周期相位到达 COMPLETED 的时机」（可通过 `GET /config/api/calls/stream` 观察），
不是「下游流是否结束」。

---

## 5. 观察手段

### 5.1 调用日志（最常用）

```powershell
$tok = (Get-Content token.txt -Raw)
$logs = (Invoke-WebRequest "http://localhost:11434/config/api/logs?pageSize=10" `
         -Headers @{Authorization="Bearer $tok"} -UseBasicParsing).Content | ConvertFrom-Json
$logs.items | Format-Table id, model_name, downstream_protocol, upstream_protocol, status_code, duration_ms, ttfb_ms
```

**关键字段**：`status_code`（`-1` = 非 HTTP 异常占位，可能是连接/DNS/TLS/截断/空响应耗尽）、
`downstream_protocol` → `upstream_protocol`（判断是否走翻译）、`ttfb_ms`（首字延迟）。

明细：`GET /config/api/logs/{id}`（含 `usage` 与 chunks）。

### 5.2 生命周期事件流

```powershell
# 先连 SSE 再打请求 —— 这样能完整捕获该请求的全部相位
# GET /config/api/calls/stream（连接时先补发在途调用快照，随后推实时事件）
# 相位：RECEIVED → CONNECTED → CHUNK… → COMPLETED / FAILED / ABORTED / CANCELED
# 工具：tools/live-test/lib.js 的 httpRequest() 可复用地址与认证
```

### 5.3 中途干预（人/agent 都可）

```powershell
$tok = (Get-Content token.txt -Raw); $id = '<requestId>'
# 取消（终止整条调用链、关下游连接、发 ABORTED）
Invoke-WebRequest -Method Post "http://localhost:11434/config/api/calls/$id/cancel" `
  -Headers @{Authorization="Bearer $tok"} -UseBasicParsing | Select -ExpandProperty Content
# 静默重试（只中断**上游**请求并重发；下游 SSE 保持打开、不消耗自动重试预算）
Invoke-WebRequest -Method Post "http://localhost:11434/config/api/calls/$id/retry" `
  -Headers @{Authorization="Bearer $tok"} -UseBasicParsing | Select-Object -ExpandProperty Content
```

两者返回 `{canceled:true/false}` / `{retried:true/false}`。
**`false` = 该调用不存在或已结束**（非流式没有重试注册项，因此 `retried` 恒为 `false`）。

> UI 侧也可右键 Toast 里的调用项，菜单项与上面两个端点一致。
> 但**优先用端点**：更快、返回确定性结果；UI 的 DOM 较杂。

### 5.4 mock 侧日志

```powershell
Select-String -Path ..\live-test\mock.log -Pattern '▶|■|✗' | Select-Object -Last 20
```

| 行 | 真实文本 | 用途 |
|---|---|---|
| `▶` | `▶ chat  model=x  stream=true` | 请求进入（路由层发，每请求一条）—— **计数用这个**，它带协议与 `stream=` |
| `■` | `■ 连接关闭  model=x` | 连接关闭。只带 model，**无协议/流式维度**，仅用于观察断连 |
| `✓` | `✓ 返回错误 401  model=x  bytes=87` | 响应已发出（**含错误响应** —— 别按符号猜成败） |
| `✗` | `✗ retry-recover 第 1 次请求，返回 500` | 仅用于**可重试轮次的中间态**与截断 |
| `↻` | `↻ retry-recover 第 3 次请求，正常回复` | 场景内部的轮次提示 |
| `…` | `… cancel-stall 永久停滞（不关连接）` | 场景进入等待/停滞 |

---

## 6. 常见坑

| 坑 | 症状 | 对策 |
|---|---|---|
| **按 `node` 名杀进程** | 用户的前端页面白屏 | 只按端口定位 PID，并核对命令行含 `mock-upstream` |
| **mock 日志没落盘** | 重试计数恒为 0 | `Tee-Object -FilePath ..\live-test\mock.log`，且自己启动 |
| **PowerShell 内联 JSON 不可靠** | 模型名丢失 → 落到默认场景 → 像 COSP 有 bug | 用 `tools/live-test` 的 Node 脚本打请求；curl 要写 body 文件 |
| **未带 `[provider]` 前缀** | 「没有可用的上游服务来处理模型」 | 一律带前缀；重名时无前缀必然失败 |
| **模型名重名** | 同上，且报错不提示重名 | 用 `[mock] xxx` / `[translatemock] xxx` 显式区分 |
| **忘记 D 组前置** | `translate-*` 打 CHAT 端点回 **422** | 建只勾 MESSAGES 的供应商（§3.1） |
| **用 COSP 日志判断重试** | 分不清哪些行属于同一次下游请求 | 看 mock 侧 `▶` 计数（`api_call_log` 是**每轮上游往返一条**，8 轮就落 8 行，但没有 request_id 列可归组） |
| **换了地址/密码没重新 login** | 401 | `node lib.js login`（token 与地址、账号绑定） |

---

## 7. 结束与清理

```powershell
# 停掉自己启动的 mock（按端口，别按进程名）
Get-NetTCPConnection -LocalPort 8081 -State Listen -ErrorAction SilentlyContinue |
  Select-Object -ExpandProperty OwningProcess -Unique |
  ForEach-Object { Stop-Process -Id $_ -Force }
```

- **D 组建的 `translatemock` 供应商会留着** —— 它使 7 个模型名与 `mock` 重名。
  若测试后不再需要，问用户是否删除（`DELETE /config/api/providers/{key}`）。
  **不要擅自删** —— 那可能是用户自己建的。
- `token.txt` / `mock.log` / `.env` 已被 `.gitignore` 排除，不必清理。
- **不要删 `admin.db`，不要提交任何改动** —— 测试不应产生代码变更。

---

## 8. 报告结论

按**判据**给结论，不要只说「测试通过」。参考格式：

```markdown
## 实测结果
| 组 | 关键证据 | 结论 |
|---|---|---|
| A | 空白组上游被调 5–8 次；对照组恒 1 次；耗尽后 98s 放行 | 空响应兜底 + 耗尽放行 ✓ |
| B | 401 零重试；retry-recover 3 次；retry-5xx 8 次 | 重试白名单与预算 ✓ |
| C | Messages 六种事件齐全且未混入 OpenAI chunk | 直连未翻译 ✓ |
| D | down=CHAT → up=MESSAGES；finish_reason=tool_calls | C2M 翻译 ✓ |
| F | 相位 COMPLETED 在下游连接仍开着时到达；静默重试后 mock 收到第二次请求 | Layer 1/2 + 控制面 ✓ |
```

**如实报告发现的异常**，包括「判据本身写错了」这类自我纠正 —— 那比一个漂亮的
「全部通过」更有价值（曾据此发现 3 处真 bug 与 9 处假失败）。

---

## 参考

| 主题 | 文件 |
|---|---|
| mock 场景清单、新旧名对照、422 语义 | [`tools/mock-upstream/README.md`](../../../tools/mock-upstream/README.md) |
| 测试脚手架、判据表、实操细节 | [`tools/live-test/README.md`](../../../tools/live-test/README.md) |
| 全部可验证项（测什么、看什么） | [`docs/LIVE_TEST_MATRIX.md`](../../../docs/LIVE_TEST_MATRIX.md) |
| 项目架构与不变量 | [`AGENTS.md`](../../../AGENTS.md) |
