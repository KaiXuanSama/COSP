# ADR-0017: M2C 工具 `defer_loading` 改为省略并告警，不再本地拒绝

- **状态**：Accepted
- **日期**：2026-10-26
- **取代**：部分取代 [ADR-0012](./adr-0012-m2c-request-translation.md)（仅「`defer_loading=true` 无法等价保留、报路径错误」这一条；其余仍 Accepted）
- **相关**：[ADR-0012 忠实映射](./adr-0012-m2c-request-translation.md)、
  [ADR-0016 角色透传](./adr-0016-m2c-role-passthrough.md)、
  [M2C 请求契约](../reference/contracts/M2C-REQUEST-CONTRACT.md) §5.2、
  [M2C/C2M RFC](../working/rfcs/2026-10-20-m2c-c2m-translation.md)

## 背景

实现 M2C 去程时（ADR-0012）对工具扩展字段 `defer_loading` 采用「无法等价保留即显式失败」：
值为 `true` 时抛 `RequestTranslationException` → 400。该决策写入了契约 §5.2，并随阶段 2 实现。

2026-10-26 实测发现 **Claude CLI 发来的请求在工具声明里带 `defer_loading: true`**
（`tools[13].defer_loading`），被 COSP 当场 400 拒绝。

该字段的实际语义：它是 Anthropic **advanced tool use**（`advanced-tool-use-2025-11-20` beta）
体系里的**客户端运行时修饰符**，控制客户端何时加载工具实现（延迟加载），
**不是服务端消费的数据**，对上游请求语义没有贡献。参考项目证据：

- sub2api `bedrock_request.go`：Claude Code v2.1.69+ 会发送 `custom: {defer_loading: true}`，
  Bedrock 会拒绝（"Extra inputs are not permitted"），实现选择移除 `custom` 字段。
- sub2api `responses_client_tools.go`：`stripResponsesDeferredToolFlags` 直接删除 `defer_loading`。
- CLIProxyAPI `claude_executor_request.go`：注释明确
  *"defer_loading tools on the wire; plain tool declarations no longer carry it"* —— 普通工具声明不再携带它。
- CLIProxyAPI `injectToolsCacheControl` / sub2api `applyToolsLastCacheBreakpoint`：
  Anthropic 不允许 `defer_loading=true` 的工具携带 `cache_control`，缓存断点只注入最后一个非 deferred 工具。
- new-api / cc-switch：白名单重建，自然丢弃。

即：参考项目一致选择**剥离**该字段，而不是本地拒绝。

## 决策

**M2C 去程对工具 `defer_loading` 不再本地拒绝：值为 `true` 时在目标请求中省略，并输出路径化告警；`false`/null/缺省仍省略；非布尔仍报类型错误。**

要点：

1. `defer_loading=true` 与 `eager_input_streaming` 同类处理：省略 + `warnOmitted` 路径告警。
2. 不向 OpenAI Chat 透传：该协议无对等字段，上游不消费，透传可能被忽略或 400。
3. 省略不改变客户端实际行为：延迟加载是客户端本地决策，`defer_loading` 只是声明；
   且 deferred 工具本就不参与 prompt 缓存，不存在「悄悄丢失缓存能力」。
4. 该改变不覆盖其它扩展字段的错误路径：`allowed_callers` 非 direct、`input_examples` 等保留现有行为。

## 后果

**正面**

- 兼容 Claude CLI 等真实客户端发出的 `defer_loading` 工具，不再阻断会话。
- 与 sub2api / CLIProxyAPI 的剥离行为一致。
- 与 ADR-0016「本地不拦截真实客户端」的方向一致。

**负面**

- 客户端侧延迟加载提示在发往 Chat 上游时不会传递，但该提示对 OpenAI Chat 无语义，无实际损失。
- 若未来某上游提供类似延迟加载字段，需单独评估有限映射；当前不猜测。

## 备选方案

### 保持报错（现状）
拒绝：已实测 Claude CLI 真实请求被拒，阻断真实客户端。

### 透传给上游（OpenAI Chat）
拒绝：OpenAI Chat 不认识该字段，可能忽略或 400；且会把客户端运行时修饰符当作服务端指令发送，语义错位。

### 静默省略且不告警
拒绝：与项目「不静默丢失」原则冲突；路径告警便于审计与排障。

## 参考

- [M2C 请求契约](../reference/contracts/M2C-REQUEST-CONTRACT.md) §5.2
- sub2api `backend/internal/service/bedrock_request.go`（`removeCustomFieldFromTools`）、
  `backend/internal/pkg/apicompat/responses_client_tools.go`（`stripResponsesDeferredToolFlags`）、
  `backend/internal/service/gateway_tool_rewrite.go`（`applyToolsLastCacheBreakpoint` / `isDeferredLoadingTool`）
- CLIProxyAPI `internal/runtime/executor/claude_executor_request.go`（`claudeBodyUsesAdvancedToolUse`）、
  `internal/runtime/executor/claude_executor_cloaking.go`（`injectToolsCacheControl`）
- ADR-0012 决策链
