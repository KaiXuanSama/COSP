# ADR-0014: M2C 直接省略三个顶层服务端配置字段

- **状态**：Accepted
- **日期**：2026-10-24
- **取代**：部分取代 [ADR-0012](./adr-0012-m2c-request-translation.md) 的执行要求显式拒绝原则，
  仅限顶层 `container` / `mcp_servers` / `context_management`；其余决策保持有效。
  同时取代 [RFC §12.7](../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-open-items)
  后续确认的三字段「非空报错」边界。
- **相关**：[ADR-0013](./adr-0013-m2c-output-format-alias.md)、
  [三字段调研与变更记录](../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-server-fields-2026-10-24)

## 背景

这三个字段配置的是 Anthropic 服务端沙箱、远程 MCP 连接和上下文管理，不是普通聊天正文。
COSP 的 M2C 请求译器只做无状态结构转换，没有实现这些服务端能力的替代执行环境。

ADR-0012 确立了对已确认不可等价表达的内容或执行要求显式失败的原则，
但没有冻结容器的值级边界。随后 RFC §12.7 确认空值省略、非空报错，阶段 2 按此实现。
本次逐条核对 new-api、sub2api、cc-switch 的真实 M2C 路径后，确认三家都省略这三个字段；
用户明确选择相同兼容行为，不再仅因这些配置阻断请求。

## 决策

**M2C（下游 MESSAGES → 上游 CHAT）直接省略顶层 `container`、`mcp_servers`、
`context_management`，不因它们的存在、值或类型触发本地翻译错误。**

省略发生在译器对白名单目标请求的重建中，不修改源请求，不递归删除
tool input、schema 或其它业务内容里的同名键。流式与非流式共用该规则。
不为这些字段新增警告、下载、远程连接、状态存储或能力模拟；后续请求体规则仍拥有最终决定权。

这是上述三个顶层字段的限定例外，不扩展为「所有服务端工具或无法表达的内容都丢弃」。
文档源、引用定位、服务端工具、工具执行方式以及重复输出格式的既有拒绝边界不变；
Messages 直连和其它翻译方向不受影响。

## 后果

**正面**

- 客户端附带这些配置时不再被本地拒绝，与三家参考项目的 M2C 兼容行为一致。
- 不增加服务端执行、凭据连接或跨请求状态，保持译器的纯结构转换边界。
- 丢弃点仅在顶层白名单重建，业务内容和既有规则装配不受牵连。

**负面**

- 被省略的沙箱复用、服务端 MCP 和上下文策略不会因此生效；请求成功不代表这些语义被保留。
- 不再用本地错误提醒客户端这一能力损失，属于已接受的兼容性取舍。
- 该省略策略不补齐 C2M 回程，也不意味着完整 Anthropic 客户端兼容。

## 备选方案

### 保持非空字段显式报错

未选择：虽能提醒能力损失，但用户已明确接受省略，继续拒绝会阻断可完成的普通聊天请求。

### 原样透传到 Chat 上游

未选择：这不是通用 Chat 的等价能力声明，既不能保证生效，也可能让严格上游拒绝请求。

### 在代理内模拟服务端能力

未选择：需要执行环境、远程 MCP 客户端或上下文策略执行器，不再是本次请求体翻译调整。

## 参考

- [M2C 顶层字段契约 §10.1](../reference/contracts/C2M-REQUEST-CONTRACT.md#m2c-request-spec)
- [M2C 调研与变更记录](../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-server-fields-2026-10-24)
- `src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/protocol/translate/MessagesToChatRequestTranslator.java`