# ADR-0011: Ollama 只作下游协议，不做 X2O 去程

- **状态**：Accepted
- **日期**：2026-10-19
- **取代**：（无）
- **相关**：[RFC：Ollama 下游协议](../working/rfcs/2026-10-19-ollama-downstream.md)、
  [OLLAMA-EXTENSION-REVIEW.md](../reference/OLLAMA-EXTENSION-REVIEW.md)、
  [ADR-0002 命名体系](adr-0002-protocol-naming.md)

## 背景

官方 Ollama VS Code 扩展（`ollama/ollama-vscode`）通过
`vscode.lm.registerLanguageModelChatProvider` 注册 provider，聊天走**原生 `/api/chat`**
（不是 OpenAI 兼容 `/v1`）。

COSP 目前只实现了 Ollama 的**模型发现**端点（`/api/version`、`/api/tags`、`/api/show`），
**没有 `/api/chat`**。因此把官方扩展指向 COSP 时模型能列出来，但一发消息就 404。

若要做完整支持，需要回答：O 轴应该双向展开，还是只做一个方向？

## 决策

### 1. O 只作下游，不存在 X2O 去程

**没有任何正经供应商提供 Ollama 形态的上游接口。** Ollama 服务端自己就提供 OpenAI 兼容的
`/v1/chat/completions`——想用本地 Ollama 作上游，按普通 OpenAI 兼容供应商配置即可，零新代码。

因此只做 6 个方向（全部 O 作下游）：

| 方向 | 含义 | 难度 |
|---|---|---|
| **O2C** / **C2O** | Ollama ↔ Chat | 近恒等（消息形态同族） |
| **O2M** / **M2O** | Ollama ↔ Messages | 可参考现有 C2M/M2C |
| **O2R** / **R2O** | Ollama ↔ Responses | 可参考现有 C2R/R2C |

### 2. 分阶段实施，第一刀只做 O2C/C2O + `/api/chat`

覆盖绝大多数场景（OpenAI 兼容上游是主力）。

**「去程/回程独立缺省」的设计意味着可以先只做一对**：未实现的
`(OLLAMA, MESSAGES)` / `(OLLAMA, RESPONSES)` 会自动落到「原样透传 + 告警」中间态，
不阻塞阶段一上线。

### 3. NDJSON 是第一个非 SSE 下游

Ollama 流式是**换行分隔 JSON**（每行一个对象，末行 `{"done":true}`），不是 SSE。
出口按下游协议分岔是 `api/` 轴的职责，但需确认现有响应渲染路径能否承载。

### 4. 工具调用是回程的真正难点（不是格式）

**Ollama 的 `tool_calls` 是一次性完整给出，不增量。** 而上游 Chat/Responses 多是增量给。

因此回程需要**缓冲到完整再发**——这与 R2C 的 `pending` / `argsSentAt`「补齐差量」机制
**方向完全相反**。这是 M2O/R2O 最需要警惕的地方，比 NDJSON 格式本身更复杂。

### 5. 不做 `/api/generate` 与运维端点

- `/api/generate`（补全式单轮）：扩展只用 `/api/chat`
- `/api/embed` / `/api/pull` / `/api/create`：模型管理能力，与代理职责无关

## 后果

**正面**

- 工作量从 12 个翻译器砍到 **6 个**（O 轴天然不对称）
- 可分阶段落地：先 O2C/C2O 覆盖主流场景
- COSP 可被官方 Ollama 扩展直接使用，**住进 VSCode 侧正在维护的 `vscode.lm` 管线**
  （与 BYOK 桥同族，不受 agent host 缓冲式流式影响）

**负面**（知情成本）

- **NDJSON 是一个新的出口形态**。现有三个下游都是 SSE，`api/` 层需要新增一种渲染方式，
  且要处理「末行 `done:true` 后连接关闭」这类边界
- **回程工具调用需要攒完整**，与 R2C 的增量处理思路相反。两套逻辑并存会增加理解成本
- **`/api/chat` 必须加进 `GatewayAuthFilter.PROTECTED_PATHS`**——漏加在未开鉴权时
  完全无症状（AGENTS.md 已点名的坑）
- **Ollama 的 `think`、`options.*`、`keep_alive` 等专有字段**需要明确忽略或映射，
  透传给上游会被拒

## 备选方案

### 方案 A：不做 O 轴，只保留发现端点

- 描述：现状。用户把 COSP 当自定义 OpenAI 兼容端点配置
- 拒绝理由：**损失「占 11434 端口零配置自动发现」的便利**。且官方扩展是活着的替代品
  （内置 Ollama 集成已 Deprecated），不接等于放弃这条下游渠道

### 方案 B：O 轴双向展开（12 个翻译器）

- 描述：也支持把 Ollama 当上游
- 拒绝理由：**没有正经供应商提供 Ollama 形态的上游接口**。本地 Ollama 自带 OpenAI 兼容端点，
  已可用。做 X2O 是纯浪费

### 方案 C：只做非流式 `/api/chat`

- 描述：降低实现难度，先不管流式
- 拒绝理由：官方扩展**只用流式**（`invokeStream`）。只做非流式等于没做

### 方案 D：把 NDJSON 转换放在 `pipeline/` 里

- 描述：当作回程翻译的一部分
- 拒绝理由：**违反四轴框架**。出口按下游协议分岔是 `api/` 的职责，`pipeline/` 只回答
  「数据怎么流」。NDJSON 拼装属于「怎么送出去」，应留在 `api/`

## 参考

- 完整规划（6 方向、三阶段、形态差异表、待确认问题）：
  [RFC：Ollama 下游协议](../working/rfcs/2026-10-19-ollama-downstream.md)
- 官方扩展调研（活跃度、工具调用支持、与 COSP 的直连缺口）：
  [OLLAMA-EXTENSION-REVIEW.md](../reference/OLLAMA-EXTENSION-REVIEW.md)
- 现存端点：[`OllamaApiController.java`](../../src/main/java/com/kaixuan/copilot_ollama_proxy/api/ollama/OllamaApiController.java)
- 鉴权白名单：[`GatewayAuthFilter.java`](../../src/main/java/com/kaixuan/copilot_ollama_proxy/infrastructure/web/GatewayAuthFilter.java)
