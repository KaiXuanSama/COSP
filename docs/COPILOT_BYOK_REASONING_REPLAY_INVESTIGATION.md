# VS Code Copilot BYOK 工具思考链回放调查

> 调查日期：2026-07-13  
> 状态：已通过 MiMo 与 DeepSeek 实际调用验证。COSP 已在 V8.2 删除 SQLite 思考链缓存、请求体回填和定时清理；本文保留为调查记录。

## 1. 背景

DeepSeek 等推理模型在思考模式下执行工具调用时，会在 assistant 消息中同时返回：

- `reasoning_content`
- `content`
- `tool_calls`

DeepSeek 官方协议要求：发生工具调用的 assistant 消息，其 `reasoning_content` 必须在后续请求中完整回传。字段缺失时，API 会返回 HTTP 400。

早期版本的 GitHub Copilot Chat 在构造下一轮 OpenAI Chat Completions 请求时，会丢失供应商扩展字段 `reasoning_content`。为兼容这一行为，COSP 曾在代理层实现工具思考链缓存：

1. 从流式响应中累积 `reasoning_content`；
2. 收集本轮响应中的 `tool_call_id`；
3. 在 `finish_reason=tool_calls` 时，以 `tool_call_id` 为键写入 SQLite；
4. 下一轮请求中找到带 `tool_calls` 的历史 assistant 消息；
5. 根据 `tool_call_id` 查询缓存并注入 `reasoning_content`；
6. 缓存未命中时注入空字符串作为兼容兜底。

该历史实现曾位于：

- `AbstractUpstreamChatService.trackToolCallIds()`
- `AbstractUpstreamChatService.persistReasoningIfToolCalls()`
- `AbstractUpstreamChatService.injectCachedReasoning()`
- `ReasoningCache`
- `ReasoningCacheRepository`
- SQLite 表 `reasoning_cache`

## 2. 调查动机

2026 年 7 月的实际使用中发现，即使临时关闭 MiMo 的代理层思考链缓存与回填，Copilot 仍可正常完成：

- 单轮对话内的连续工具调用；
- 跨两轮用户对话的多次工具调用。

由于早期 MiMo 对该字段的校验存在变化，仅凭 MiMo 不能确定是客户端行为发生变化，还是上游放宽了校验。因此进一步使用长期严格要求回传 `reasoning_content` 的 DeepSeek 进行对照实验。

## 3. COSP 实验方法

### 3.1 MiMo 实验

在当时的 `MimoOpenAiChatService` 中覆写 `setReasoningCache()` 并有意忽略 Spring 注入的 `ReasoningCache`。

实验效果：

- 不缓存 MiMo 工具调用响应中的思考链；
- 不从 SQLite 查询历史思考链；
- 不向下一轮 assistant 工具调用消息注入 `reasoning_content`；
- 不执行缓存未命中时的空字符串兜底。

结果：单轮连续工具调用和跨轮工具调用均正常完成。

### 3.2 DeepSeek 严格验证

随后以相同方式在当时的 `DeepSeekOpenAiChatService` 中忽略 `ReasoningCache` 注入。

DeepSeek 官方文档明确说明：

> 进行了工具调用的轮次，在后续所有请求中，必须完整回传 `reasoning_content` 给 API；未正确回传时 API 会返回 400。

结果：在关闭 COSP 缓存和回填后，DeepSeek 连续工具调用仍正常完成。

该结果说明后续请求所需的 `reasoning_content` 并非由 COSP 补充，而是已经由上游客户端保留并重新发送。

### 3.3 定向测试

实验期间增加或调整了定向测试，确认：

- 即使提供能够命中的 `ReasoningCache`，MiMo 请求也不会由 COSP 注入 `reasoning_content`；
- 即使提供能够命中的 `ReasoningCache`，DeepSeek 请求也不会由 COSP 注入 `reasoning_content`；
- 服务内部的 `reasoningCache` 保持为空；
- 两组定向测试均通过。

## 4. VS Code 上游实现证据

### 4.1 原始问题

Microsoft VS Code Issue：

- [#312746 — Copilot Chat drops reasoning_content field when using DeepSeek models (OpenRouter BYOK), causing 400 Error](https://github.com/microsoft/vscode/issues/312746)

该问题描述与 COSP 早期兼容问题完全一致：

1. 推理模型返回思考内容和工具调用；
2. Copilot 执行工具；
3. 下一轮历史消息丢失 `reasoning_content`；
4. DeepSeek、Kimi、Minimax 或 OpenRouter 返回 HTTP 400。

### 4.2 修复 PR

Microsoft VS Code PR：

- [#318809 — Fix HTTP 400 for BYOK Open Router reasoning models](https://github.com/microsoft/vscode/pull/318809)

核心提交：

- [`f6bf90f` — byok: echo reasoning back to thinking models on Chat Completions](https://github.com/microsoft/vscode/commit/f6bf90fde7596e65c15cdd09c7ea4fab370b171c)

提交时间：2026-05-29。

修复明确针对 BYOK Chat Completions 历史消息回放：当模型元数据声明支持 thinking 时，VS Code 会把内部保存的思考文本重新序列化为供应商可识别的字段。

### 4.3 当前处理字段

在 BYOK OpenAI Chat Completions 路径中，支持 thinking 的模型会在历史 assistant 消息中回放：

| 字段 | 主要兼容对象 |
|------|--------------|
| `reasoning_content` | DeepSeek、Moonshot/Kimi、Minimax，以及采用相同约定的 OpenAI 兼容端点 |
| `reasoning` | OpenRouter BYOK 代理 |
| `cot_id` / `cot_summary` | VS Code/CAPI 已有兼容表示 |

该实现不是按供应商名称硬编码，而是由模型能力驱动：

```text
BYOK Chat Completions
  └─ capabilities.supports.thinking == true
      ├─ 保存上游 reasoning delta 为内部 ThinkingPart
      └─ 回放历史 assistant 消息时输出 reasoning_content / reasoning
```

因此 DeepSeek、Kimi、Minimax 只是促成该修复的典型供应商，并非功能适用范围的硬编码边界。

### 4.4 VS Code 代码位置

截至调查时，相关实现和测试位于 `microsoft/vscode`：

- `extensions/copilot/src/platform/thinking/common/thinking.ts`
  - 定义 `reasoning_content`、`reasoning` 等思考字段；
- `extensions/copilot/src/platform/thinking/common/thinkingUtils.ts`
  - 从不同供应商字段中提取思考文本；
- `extensions/copilot/src/extension/byok/node/openAIEndpoint.ts`
  - 构造 BYOK Chat Completions 请求并回放思考字段；
- `extensions/copilot/src/platform/endpoint/test/node/stream.sseProcessor.spec.ts`
  - 验证 `reasoning_content` / `reasoning` 流式增量解析；
- `extensions/copilot/src/extension/byok/node/test/openAIEndpoint.spec.ts`
  - 验证工具调用历史消息回放 `reasoning_content`；
- `extensions/copilot/src/extension/byok/vscode-node/test/customEndpointProvider.spec.ts`
  - 验证自定义 OpenAI Chat Completions 端点同样生效。

COSP 通过 VS Code 自定义 OpenAI 兼容模型配置接入 `/v1/chat/completions`，属于上述 BYOK Chat Completions 路径。

## 5. 版本时间线

| 日期 | 事件 |
|------|------|
| 2026-04-27 | Issue #312746 报告 Copilot 丢失 `reasoning_content` |
| 2026-05-29 | PR #318809 合并，提交 `f6bf90f` 实现思考链回放 |
| 2026-06-02 前后 | 修复进入 VS Code Insiders 并被标记验证通过 |
| 2026-06-03 | VS Code 1.123 Stable 发布说明日期 |
| 2026-06-05 | GitHub Release 页面发布 VS Code 1.123.0 |
| 2026-07-13 | COSP 分别关闭 MiMo、DeepSeek 缓存回填并完成实际验证 |

Issue 与 PR 均被分配到 VS Code `1.123.0` 里程碑。因此可将 **VS Code 1.123.0** 视为该功能进入 Stable 的首个版本。

## 6. 调查结论

### 6.1 对当前 VS Code Copilot 的结论

从 VS Code 1.123.0 开始，在满足以下条件时：

- 使用 BYOK OpenAI Chat Completions 路径；
- 模型元数据声明支持 thinking；
- 上游响应返回了可识别的 reasoning 增量；
- assistant 消息发生工具调用；

VS Code Copilot 会自行保存思考内容，并在后续请求中以 `reasoning_content` 和 `reasoning` 等字段回放。

因此，对当前 VS Code Copilot 主链路而言，COSP 的 SQLite 思考链缓存与注入已经属于冗余兼容层。

### 6.2 更准确的功能定位

该功能不应再描述为“当前 Copilot 必需能力”，而应描述为：

> 面向 VS Code 1.123.0 之前版本、旧版独立 Copilot Chat 扩展，以及不保留供应商思考字段的第三方客户端的兼容兜底。

### 6.3 不应过度推导的边界

本次结论不代表所有客户端和所有中间网关都已兼容：

- 非 BYOK/CAPI 路径使用不同的思考字段序列化；
- 未声明 thinking 能力的模型不会触发该回放逻辑；
- 上游未返回可解析的 reasoning 时，VS Code 无法凭空生成有效思考链；
- 部分第三方网关可能过滤、改名或破坏 `reasoning_content`；
- VS Code Issue 中仍有人报告特定网关组合存在问题，需要按最终上游请求体判断；
- 其他 OpenAI 客户端不一定实现相同的历史消息保存逻辑。

因此，“COSP 缓存已冗余”的结论目前严格限定于已验证的新版 VS Code Copilot BYOK Chat Completions 使用场景。

## 7. 对 COSP 的影响

继续保留代理层缓存会产生以下成本：

- 重复保存已经由 Copilot 管理的会话数据；
- 将模型思考内容持久化到 SQLite，增加隐私和数据生命周期负担；
- 增加 `tool_call_id` 关联与定时清理逻辑；
- 增加流式处理链路复杂度；
- 缓存未命中时注入空字符串并不能还原 DeepSeek 所要求的完整思考链；
- 可能掩盖客户端本身是否正确遵循供应商协议。

新版 Copilot 的自然链路应为：

```text
Provider 返回 reasoning_content
  → COSP 透传流式响应
  → VS Code 保存 ThinkingPart
  → VS Code 在工具调用后的下一请求回放 reasoning_content
  → COSP 原样转发
  → Provider 验证通过
```

## 8. V8.2 移除结果

在完成当前 GitHub Copilot 客户端兼容性确认后，V8.2 已执行以下变更：

1. 删除 `ReasoningCache`、`ReasoningCacheRepository` 与跨请求的工具调用 ID 追踪、持久化和请求体注入逻辑；
2. 删除 `reasoning_cache` 表及其索引的 V8.2 增量迁移；
3. 删除 15 天缓存清理任务；
4. 保留上游 reasoning 字段规范化和同一请求内的 reasoning fallback；
5. 明确当前兼容范围为 GitHub Copilot 的 BYOK Chat Completions 路径。

历史上，正式剔除前建议完成以下检查：

1. 明确 README 中支持的最低 VS Code 版本为 `1.123.0` 或更高；
2. 检查实际发送给 DeepSeek 的后续请求，确认 `reasoning_content` 为非空且来源于 Copilot；
3. 确认 MiMo、DeepSeek 在单轮连续工具调用和跨轮工具调用中均稳定；
4. 确认 Generic Provider 使用 thinking 模型时行为正常；
5. 评估是否仍需要支持旧版 VS Code 或第三方客户端；
6. 若需要兼容旧客户端，应在独立版本中重新评估可选兼容层，而不是恢复默认缓存；
7. 在升级说明中注明最低 VS Code 版本和行为变化。

## 9. 最终判断

本次实验和 VS Code 上游源码形成了完整证据链：

1. DeepSeek 官方协议确认工具调用后必须回传 `reasoning_content`，缺失时返回 400；
2. COSP 已在实验中关闭 DeepSeek 的缓存和回填；
3. DeepSeek 连续工具调用仍正常完成；
4. VS Code Issue #312746 精确描述了旧版丢字段问题；
5. VS Code PR #318809 明确实现 BYOK Chat Completions 的思考内容保存与回放；
6. 修复进入 VS Code 1.123.0；
7. 因而当前成功行为来自新版 VS Code Copilot，而非 COSP 的代理层补丁。

结论：**对 VS Code 1.123.0 及以上的 Copilot BYOK Chat Completions 主链路，COSP 工具思考链缓存不是必要功能，已在 V8.2 移除。**

## 10. 参考资料

- [DeepSeek 思考模式文档](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode)
- [VS Code Issue #312746](https://github.com/microsoft/vscode/issues/312746)
- [VS Code PR #318809](https://github.com/microsoft/vscode/pull/318809)
- [VS Code Commit f6bf90f](https://github.com/microsoft/vscode/commit/f6bf90fde7596e65c15cdd09c7ea4fab370b171c)
- [VS Code 1.123 Release Notes](https://code.visualstudio.com/updates/v1_123)
- [VS Code 1.123.0 GitHub Release](https://github.com/microsoft/vscode/releases/tag/1.123.0)
