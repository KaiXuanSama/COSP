# COSP 实机测试脚手架

用**真实 HTTP 调用**验证 COSP 的端到端行为 —— 单测绿不等于链路上正确。

```bash
cd tools/live-test
node lib.js login        # 登录取 JWT → token.txt（默认 root/root）
node a-guard.js          # A 组：空响应兜底（约 6 分钟）
node b-retry.js          # B 组：重试与放行（约 6 分钟）
node c-direct.js         # C 组：三协议 × 两态的直连主路径（约 1 分钟）
node d-translate.js      # D 组：C2M 翻译（约 1 分钟）
node f-lifecycle.js      # F 组（相位）：完成判定与计数推进（约 1 分钟）
node f-cancel.js         # F 组（中断）：停滞期取消与静默重试（约 1 分钟）
node f-retry.js          # 静默重试定向验证（约 1 分钟）
```

## 前置

1. **COSP 运行中**（默认 `:11434`）
2. **mock 运行中，且输出落盘到 `mock.log`** ← 重试计数全靠它
   ```powershell
   cd tools\mock-upstream
   node mock-upstream.js 2>&1 | Tee-Object -FilePath ..\live-test\mock.log
   ```
3. **mock 供应商已配置**（33 个模型，三协议全开）
4. **D 组额外需要**一个只勾 MESSAGES 的供应商（见下）

## 配置：全部走环境变量

**没有一项写死在代码里。** 用 `node lib.js config` 可查看当前生效值及来源：

```powershell
node lib.js config
# 当前配置：
#   来源             : .env（载入 2 项；未列出的用默认值。命令行环境变量优先于 .env）
#   COSP 地址        : http://192.168.1.50:11434  (来自 COSP_BASE_URL)
#   管理登录         : kaixuan  (来自 COSP_ADMIN_USER)
#   mock 供应商 key  : mock  (默认)
#   翻译供应商 key   : translatemock  (默认)
```

### 怎么设置：推荐 `.env` 文件

```powershell
Copy-Item .env.example .env     # 只需一次
notepad .env                    # 按需修改（每行 KEY=VALUE，可留空用默认值）
```

之后直接 `node a-guard.js` 即可 —— **`lib.js` 会自动读取 `.env`，不必加 `--env-file`**。
（虽然 Node 20.6+ 支持 `node --env-file=.env xxx.js`，但那要**每个脚本都加参数**，
七组脚本七个命令行，最容易忘。）

### 优先级（从高到低）

| # | 来源 | 用途 |
|---|---|---|
| 1 | **命令行环境变量** | 临时试一次，只影响当前 PowerShell 会话 |
| 2 | **本目录 `.env`** | 换机器/换账号的常驻配置（**自动读取**） |
| 3 | **代码里的默认值** | 内网单机开发环境即默认值，什么都不必设 |

```powershell
# 例：临时用另一个密码跑一次，不改 .env
$env:COSP_ADMIN_PASS='********'; node lib.js login; node b-retry.js
Remove-Item Env:COSP_ADMIN_PASS
```

### 可用变量

| 环境变量 | 作用 | 默认 |
|---|---|---|
| `COSP_BASE_URL` | COSP 地址（`http://host:port` 或裸 `host:port`） | `http://localhost:11434` |
| `COSP_ADMIN_USER` | 管理端点登录用户名 | `root` |
| `COSP_ADMIN_PASS` | 管理端点登录密码 | `root` |
| `COSP_MOCK_PROVIDER` | 上游 mock 的供应商 key（`[key] model` 里的 key） | `mock` |
| `COSP_TRANSLATE_PROVIDER` | 只勾 MESSAGES 的翻译供应商 key | `translatemock` |

> **`.env` 已经被 `.gitignore` 排除**（各人本地配置，可能含真实密码）。
> 要入库的模板是 **`.env.example`** —— 改配置项时请同步它。

### 两个必须知道的绑定关系

> **`token.txt` 与地址/账号绑定**：换了 `COSP_BASE_URL` 或账号密码后要重新
> `node lib.js login`，否则旧 token 对不上（表现为 401）。

> **为何地址必须收敛到一处**：四个 `f-*.js` 需要自己控制连接生命周期
> （长挂起、手动 destroy），曾各自 `http.request` 并重复写死
> `host: 'localhost', port: 11434` —— 改地址要动 5 个文件，**漏一个不会报错**，
> 它会连到默认端口去，测试看着正常但打的是别的服务。
> 现在它们统一走 `lib.httpRequest()`。

## 为什么不用 curl

| 问题 | 说明 |
|---|---|
| **PowerShell 5.1 内联 JSON 不可靠** | 引号会被吃掉 → 模型名丢失 → 落到默认场景 → **看起来像 COSP 有 bug** |
| **重试从 COSP 侧看不出来** | `api_call_log` 每轮只落一条、`duration_ms` 只记一轮；真实次数只在**上游 mock 的日志**里 |
| **SSE 需要逐帧解析** | curl 的输出经 PowerShell 管道会被拆散 |

## 判据表（踩坑后确立，**别用右边的**）

| 要测什么 | ✅ 正确判据 | ❌ 错判据（会导致假失败） |
|---|---|---|
| **重试次数** | mock 侧 `■ 连接关闭` 行的条数（每请求恰好一条） | COSP 日志的 `duration_ms`（只记一轮）；下游总耗时（抖动使阈值不可靠） |
| **流式错误** | `event: error` 帧里的 `code` | **HTTP 状态码** —— 流式恒 200（SSE body 已开始，无法再改） |
| **非流式错误** | HTTP 状态码（原样透传） | — |
| **退避间隔** | 单调上升至上限、首退 1–8s | 精确匹配 2/4/8…（`Retry.backoff` 带 ±50% 抖动，mock 时间戳仅秒级） |
| **是否走翻译** | COSP 日志的 `downstream_protocol` / `upstream_protocol` 两列 | 只看下游响应形状（两条路都可能形似） |

> 错误判据曾一次性造成 **9 项「假失败」**（实际行为全对）。

## 模型名一律带显式前缀

所有请求用 `[provider-key] model` 形式。**key 由 `COSP_MOCK_PROVIDER` 配置**
（`probe()` 的 `providerKey` 参数默认取它），不写死在测试用例里。

**为什么不靠无前缀名**：无前缀名要求 `ProviderRouteResolver` 找到**唯一匹配**。
真实环境里同一个上游常被配成多个供应商（不同协议集合、不同 Key、不同代理开关）——
**重名是常态而非例外**。显式前缀是唯一稳定的写法。

> 这条是被撞出来的：D 组最初用无前缀名，`baseline-normal` 在 `mock` 与
> `translatemock` 下重名 → 路由失败 → 报「**没有可用的上游服务来处理模型**」。
> 那个报错**不提示「你有个重名」**，排查会指向「供应商没启用」而非「名字歧义」。

**配对事实（不是笔误）**：COSP 发往上游时会**剥掉前缀**，所以
**发请求用带前缀名、数 mock 日志用无前缀名**。

## D 组的前置：造一个只支持 MESSAGES 的供应商

`ProtocolDispatchManager` **规则 1 是「同名协议优先直连」** —— 供应商三协议全开时
永远走直连，翻译支线**不会被触发**。因此需要一个指向同一 mock 但只勾 MESSAGES 的供应商。

```powershell
# 假定已设 COSP_BASE_URL；未设则用默认 http://localhost:11434
$base = if ($env:COSP_BASE_URL) { $env:COSP_BASE_URL } else { 'http://localhost:11434' }
$key  = if ($env:COSP_TRANSLATE_PROVIDER) { $env:COSP_TRANSLATE_PROVIDER } else { 'translatemock' }
$tok  = (Get-Content token.txt -Raw)

# 1) 新建供应商（表单是 form-urlencoded，不是 JSON）
curl.exe -X POST "$base/config/api/providers" `
  -H "Authorization: Bearer $tok" `
  -H "Content-Type: application/x-www-form-urlencoded" `
  --data "displayName=TranslateMock&baseUrl=http%3A%2F%2Flocalhost%3A8081&supportedProtocolsJson=%5B%22MESSAGES%22%5D"
# → 返回 providerKey（displayName 推导而来，此处为 translatemock）
#   若与 COSP_TRANSLATE_PROVIDER 不一致，请设该环境变量
```

```powershell
# 2) 加模型（多值表单；字段名是 models[N].name / .enabled / .contextSize / .capsTools）
#    这是**全量保存** —— 一次提交该供应商的全部模型
$m = @('translate-tool-split-args','translate-tool-multi-split','translate-tool-interleaved',
       'translate-tool-no-args','translate-finish-reason','translate-finish-reason-nonstandard',
       'baseline-normal')
$parts = @('baseUrl=http%3A%2F%2Flocalhost%3A8081','apiKeys=%5B%5D',
           'supportedProtocolsJson=%5B%22MESSAGES%22%5D','headerRulesJson=%5B%5D')
$i = 0
foreach ($n in $m) {
  $enc = [uri]::EscapeDataString($n)
  $parts += "models%5B$i%5D.name=$enc", "models%5B$i%5D.enabled=on",
            "models%5B$i%5D.contextSize=128000", "models%5B$i%5D.capsTools=on"
  $i++
}
($parts -join '&') | Out-File -Encoding ascii -NoNewline save.txt
curl.exe -X POST "$base/config/api/providers/$key/config" `
  -H "Authorization: Bearer $tok" `
  -H "Content-Type: application/x-www-form-urlencoded" --data-binary "@save.txt"
```

> **`anthropicBaseUrl` 留空是正确的** —— `MessagesOutboundStage` 会回退 `base_url`。

## 各文件

| 文件 | 作用 |
|---|---|
| `lib.js` | 打请求 / 读日志 / 数重试 / 登录取 token。**判据都实现在这里** |
| `a-guard.js` | A 组：空响应兜底（判定 + 扣放 + 耗尽放行） |
| `b-retry.js` | B 组：重试与放行（含流式/非流式的错误传递差异） |
| `c-direct.js` | C 组：三协议 × 两态的直连主路径（**正常路径**，其余各组是病态路径） |
| `d-translate.js` | D 组：C2M 翻译（含 tool index 重映射、finish_reason 覆盖） |
| `f-lifecycle.js` | F 组相位类：Layer 1 / Layer 2 完成判定、计数推进、慢响应 |
| `f-cancel.js` | F 组中断类：停滞期取消、非流式无静默重试 |
| `f-retry.js` | 静默重试定向验证（判据是 **mock 侧出现第二次请求**） |
| `token.txt` | 管理端点 JWT（`lib.js login` 生成，不要提交） |
| `mock.log` | mock 的运行日志（重试计数源，不要提交） |

## F 组的两个坑

**1. `phase-done-early` 的下游连接会一直挂着 —— 那是预期。**
它的场景是「发完 `[DONE]` 但**永不关 TCP**」，用来验证 Layer 1（语义终止标记）能在
**下游连接仍开着时**就收尾。判据是「相位到达 COMPLETED 的时机」，**不是**「下游流是否结束」。
`LIVE_TEST_MATRIX` 里那句「COMPLETED 不等连接关闭」说的也是**相位**。

**2. 静默重试的判据是「mock 侧出现第二次请求」。**
下游帧数变多、`retried=true` 都只是间接证据 —— 只有 mock 侧的第二次 `▶` 行
才直接证明「上游真的被重新请求了」。**不能靠 COSP 日志**：重试在同一条下游连接内发生，
`api_call_log` 只落一条。

**3. `lib.request()` 只收集 `data:` 行，不收集 `event:` 行。**
判据需要事件名时（如 Anthropic 的事件序列），要读 data 帧里的 `"type":"..."` ——
两者在 Anthropic 协议里必然一致。`event:` 行既不进 `raw` 也不进 `frames`。

## 浏览器侧核对（脚本测不了的）

F 组有两件事只能人看：**Toast 的相位文案**与**右键菜单项的可见性**。

同一时刻发一条流式 + 一条非流式挂起调用，然后在 `http://localhost:5173` 的
右上角徽标里右键两条 Toast：

| Toast | 相位文案 | 菜单项 |
|---|---|---|
| 流式（`cancel-hang`） | 已连接，正在等待首字响应 | `静默重试` + `断开连接` |
| 非流式（`cancel-hang`） | 下游发出请求 | **仅** `断开连接` |

> 两条相位文案不同，正说明 `cancel-hang` 的两态机制不同：流式先写响应头（进 CONNECTED），
> 非流式不写头（纯等待）。菜单项差异则与 `f-cancel.js` 的 F6 后端断言一致。

## 已跑出的结论

见 [`docs/LIVE_TEST_REPORT_2026-09-27.md`](../../docs/LIVE_TEST_REPORT_2026-09-27.md)：

- **A 组**：空白组被调 5–8 次（重试生效）、对照组恒 1 次（零重试）、耗尽放行 107s 后正常收尾
- **B 组**：401 零重试、`retry-recover` 恰 3 次、`retry-5xx` 共 8 次、429 / 截断均可重试
- **D 组**：C2M 翻译后下游只见 OpenAI chunk、tool index 稠密重映射、**`finish_reason` 被正确覆盖为 `tool_calls`**
