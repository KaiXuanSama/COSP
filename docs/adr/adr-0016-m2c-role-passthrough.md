# ADR-0016: M2C 请求角色未知透传，对齐 new-api，不再拒绝

- **状态**：Accepted
- **日期**：2026-10-25
- **取代**：部分取代 [ADR-0012](./adr-0012-m2c-request-translation.md)（仅「只接受 user/assistant 角色、未知角色报错」这一条；其余仍 Accepted）
- **相关**：[ADR-0012 忠实映射](./adr-0012-m2c-request-translation.md)、
  [M2C 请求契约](../reference/contracts/M2C-REQUEST-CONTRACT.md) §5、
  [M2C/C2M RFC](../working/rfcs/2026-10-20-m2c-c2m-translation.md)

## 背景

实现 M2C 去程时（ADR-0012）对消息角色采用严格白名单：只允许 `user/assistant`，未知角色直接
抛 `RequestTranslationException` → 400。该决策写入了契约 §5，并随阶段 2 实现。

2026-10-25 实测发现 **Claude CLI 发来的请求含非 `user/assistant` 角色**，被 COSP 当场 400
拒绝。排查四个参考项目对未知角色的处置：

- new-api：**原样透传**任意 role（`Role: claudeMessage.Role`），不校验、不拒绝，由上游决定。
- sub2api：反向方向未知类型尽量按 user 保留文本（宽容，不报错）。
- cc-switch：role 读取失败默认回退 `"user"`，透传未知角色。
- CLIProxyAPI：switch 无 default，未知角色被静默忽略（丢消息，不报错）。

四家均不在本地拒绝未知角色。而 COSP 当前是唯一本地 400 的实现。用户决策：按 new-api 方向调整
（原样透传未知角色），让上游回答兼容性，本地不拦截。

## 决策

**M2C 去程对消息角色不再做白名单拒绝：未识别角色原样透传到 Chat 消息的 `role` 字段，
本地不报错、不丢弃、不擅自改写。**

要点：

1. 角色校验只保留「存在且为字符串」的结构要求，不校验枚举值。
2. 未知角色消息按与 `user/assistant` 相同的处理路径继续转换（工具块、文本块、配对等逻辑不变）。
3. 透传后的兼容性由上游裁决：上游拒绝（如 OpenAI Chat 收到未知 role）时透传对应的上游错误，
   本地不再提前 400。
4. 这个改变**不覆盖**错误路径校验：错误的 `messages`/`content` 结构、工具块错置（如
   `tool_result` 出现在 assistant）等仍按 ADR-0012 报错。

## 后果

**正面**

- 兼容 Claude CLI 等真实客户端发出的非标准角色，不再阻断会话。
- 与四个参考项目的主流行为一致（均不本地拒绝未知角色）。
- 本地逻辑更简单，消除「白名单角色」与真实上游形态不符导致的误拒绝。

**负面**

- 未知角色可能透传给上游后返回 400/错误，但那是上游语义，错误可见可定位（符合「保留可理解失败」）。
- 深度未知角色可能被上游忽略或产生意外行为；需实机验证记录。

## 备选方案

### 保持白名单报错（现状）
拒绝：已实测 Claude CLI 真实请求被拒，阻断真实客户端。

### 未知角色去掉退为 user（cc-switch / sub2api 风格）
拒绝：改写角色会悄悄改变意图；用户明确选择 new-api 的透传方向。

### 静默丢弃未知角色消息（CLIProxyAPI 风格）
拒绝：丢消息会静默丢失上下文，与 ADR-0012「不伪造、不静默丢失」冲突。

## 参考

- new-api `to_oai_chat_req.go`（`Role: claudeMessage.Role` 原样透传）
- [M2C 请求契约](../reference/contracts/M2C-REQUEST-CONTRACT.md) §5
- ADR-0012 决策链
