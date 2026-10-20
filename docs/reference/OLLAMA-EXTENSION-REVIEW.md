# Ollama 官方 VS Code 扩展调研

> **调查时间**：2026-10-08（源码逐文件阅读 + git 历史考古）
> **调查对象**：[`ollama/ollama-vscode`](https://github.com/ollama/ollama-vscode) @ 本地克隆
> **调查动机**：VSCode 的 Ollama 官方支持长期被标记为 "Deprecated"，需要确认
> 「Ollama 渠道的官方插件现阶段支持程度如何」

---

## 1. 结论速览

| 问题 | 结论 |
|---|---|
| Deprecated 标记还有效吗？ | **那是旧插件的墓碑，不是新插件的现状**。本仓库是 Ollama 官方 2026-06-02 新建的全新插件，是替代关系 |
| 维护活跃吗？ | **很活跃**。0.0.12（2026-10-02），两周内连发 0.0.11/0.0.12，社区 PR 正常合入 |
| 支持工具调用吗？ | **支持**。`toOllamaTools` 自首个 POC 提交（2026-06-02）就存在，三段链路完整 |
| 与 COSP 能直连吗？ | **不能**。插件用原生 `/api/chat`，COSP 只实现了发现端点。见 [ollama-downstream/PLAN.md](../working/rfcs/2026-10-19-ollama-downstream.md) |

---

## 2. 版本与活跃度

| 项 | 数据 |
|---|---|
| 项目起点 | 2026-06-02（`4601f09` Initial Ollama VS Code extension POC） |
| 上架 Marketplace | 2026-06-09 |
| 最新版本 | **0.0.12**（2026-10-02） |
| 发布节奏 | 0.0.11（09-28）→ 0.0.12（10-02），两周连发 |
| 维护者 | Eva Ho（105 提交）；社区 PR 合入（#41/#79/#84） |
| 依赖要求 | VS Code ≥ 1.127（更早版本无法可靠取消 LM provider 请求）；Ollama ≥ 0.17.6（云模型登录与更丰富的元数据） |

**近期关键变更**：
- 0.0.12：`ollama.thinkingLevels` 按模型设 think 档位（走 Settings JSON，
  因为下拉菜单依赖 VS Code proposed API，会踩 Marketplace 限制）；
  修复工具/媒体请求导致 token 估算偏高、过早触发会话压缩（#79）

---

## 3. 技术形态

### 3.1 注册路径

```ts
vscode.lm.registerLanguageModelChatProvider('ollama-models', provider)
```

**不走 GitHub 认证、不走 BYOK 桥、不走 agent host 子进程**——是 renderer 侧
`vscode.lm` LM API 的直连 provider。这与我们追查 BYOK 图片 bug 时看到的那条
管线同族（`extHostTypeConverters` → LM API）。

### 3.2 协议：原生 `/api/*`，不是 OpenAI 兼容 `/v1`

用官方 `ollama` npm 客户端，发现与聊天都打 Ollama 原生端点：
`/api/tags`、`/api/show`、`/api/chat`。

这对 COSP 是核心事实——**COSP 的美化端点面缺 `/api/chat`**。

### 3.3 能力声明

从 `/api/show` 的 `capabilities` 读取，与 COSP 的
`provider_model.caps_*` 声明哲学同源：

```ts
capabilities: {
  toolCalling: hasCapability(capabilities, 'tools', 'tool'),
  imageInput: hasCapability(capabilities, 'vision', 'image')
}
```

配套：上下文长度检查、过期模型警告（提示 agent 工具可能不可靠 + 推荐替换模型）、
诊断命令（`Ollama: Diagnose Models`）、推理超时配置（默认 10 分钟）、
自签 CA 支持（undici agent + 系统 CA 合并）。

---

## 4. 工具调用支持细节

三段链路均完整：

### ① 请求侧——工具声明

`convert.ts` 的 `toOllamaTools`：

```ts
VSCode LanguageModelChatTool[] → { type:'function', function:{ name, description, parameters } }
```

**已修的实机坑**：无参工具的空 schema `{}` 会被 Ollama 重新序列化成
`{ type:'', properties:null }` 导致模板解析拒绝——已改为显式
`{ type:'object', properties:{} }`（有专项测试）。

### ② 请求侧——历史回传

`toOllamaMessages`：
- assistant 的 `ToolCallPart` → `tool_calls[]`（id / name / arguments **对象**形态）
- tool result → 独立 `role:'tool'` 消息 + `tool_call_id` 配对

### ③ 响应侧——流式回传

`provider.ts` 每个 chunk 的 `message.tool_calls` 逐个
`progress.report(new LanguageModelToolCallPart(...))`，id 缺失时
`randomUUID()` 兜底。

### 测试覆盖（`test/convert.test.js`）

6 个工具侧专项用例，覆盖：schema 保真（约束/不支持特性透传）、
**tool result 图片挂到对应 tool 消息**、空结果、JSON 数据、`cache_control` 剥离。

> **与我们报的 Copilot bug 的对照**：插件的 `toolResultContent()` 会完整保留
> tool result 里的图片（`LanguageModelDataPart` → base64 `images[]`）。
> 对比 [microsoft/vscode#339595](https://github.com/microsoft/vscode/issues/339595)
> ——Copilot BYOK 桥的 `function_call_output` 曾把图片静默丢弃（已于 PR
> [#339830](https://github.com/microsoft/vscode/pull/339830) 修复）。
> **同一 LM API 入口，插件自己做了转换就绕开了那个丢弃点**——反证坑在桥上、
> 不在 LM API。

---

## 5. 设置项与命令

| 设置 | 作用 |
|---|---|
| 供应商配置 `url` / `models` / `headers` | 供应商级别（VS Code 侧配置，优先于扩展设置） |
| `ollama.endpoint` | 兼容用的全局端点 |
| `ollama.headers` | 额外 HTTP 头（可用于携带 COSP 鉴权头） |
| `ollama.thinkingLevels` | 按模型名（含 tag）设 think 档位 |
| `ollama.inferenceTimeoutMinutes` | 推理首字超时（1–60 分钟，默认 10） |

命令：`Ollama: Refresh Models`、`Ollama: Diagnose Models`。

---

## 6. 对 COSP 的影响

### 直接结论

官方扩展**不能**直接指向 COSP 使用——模型能列出（`/api/tags` 已实现），
一发消息就 404（`/api/chat` 未实现）。

### 两条替代路径

1. **现状可用的替代**：把 COSP 当作**自定义 OpenAI 兼容端点**配置
   （`/v1/models` 已实现），模型发现与聊天全链路可用。唯一损失是
   「占 11434 端口零配置自动发现」的便利。
2. **补齐能力（规划中）**：实现 `/api/chat` + O2C/C2O 一对翻译器。
   详见 [ollama-downstream/PLAN.md](../working/rfcs/2026-10-19-ollama-downstream.md)。

### 战略含义

- 官方扩展走的是 `vscode.lm` provider 直连路径，**不经过 BYOK 桥**，
  因此不受 agent host 缓冲式流式的影响（真流式、取消即断）
- 若 COSP 补齐 `/api/chat`，就等于住进了 VSCode 侧正在积极维护的那条管线，
  Ollama 发现端点被 deprecated 与否不再是风险敞口

---

## 7. 未穷举的部分

本次调研聚焦在 `chatFetch.ts` / `provider.ts` / `convert.ts` / `extension.ts`
与 `package.json` / `README.md` / `CHANGELOG.md` / git 历史。

**未做**：
- 未穷举扩展发出的全部 HTTP 请求（可能还有 `/api/ps` 等）
- 未实机运行扩展对着 COSP 测试
- 未读 `thinking.ts` / `contextLength.ts` / `recommendations.ts` / `diagnostics.ts` 的完整逻辑
- 未核实 `node_modules/ollama` 客户端的具体端点拼接（本地未装依赖，靠源码推断）

开工实现 `/api/chat` 前，建议先抓一次真实扩展的完整请求日志。
