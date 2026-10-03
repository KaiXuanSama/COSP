# Responses 协议直连实施总结

> **需求背景**：为 COSP 增加 OpenAI Responses API 直连能力 —— 下游打 `/v1/responses`，
> 上游走供应商的 Responses 端点，报文原样透传。让只支持 Responses 的模型（如 GPT-6-Astra）
> 可被 Copilot 使用。不含 C2R / R2C 翻译。
>
> 完整决策与理由见 [PLAN.md](./PLAN.md)。

## 做了什么

- **三阶段落地**（详见 PLAN.md §9–§15 各阶段实施记录）：
  - V13 迁移：`provider_config` 加 `responses_base_url` 列（空回退 `base_url`）+
    `supported_protocols` 回填 `RESPONSES` 到默认集合（PLAN.md §3）
  - `WireProtocol.RESPONSES` 枚举值 + 前后端读写链路：供应商编辑页第三个协议勾选、
    三处 base URL、规则引擎白名单（PLAN.md §4、§11）
  - `/v1/responses` 端点 + `GenericResponsesChatService` 直连执行器：与 Anthropic 侧
    平级、不继承 `AbstractUpstreamChatService`、响应全程 String 透传（PLAN.md §5、§14）
- **修了实盘暴露的空响应判定缺口**：Responses 判定器漏了 `response.output_text.delta`
  与落库链一度零覆盖，非流式是薄弱面（PLAN.md §15）
- **前置的协议改名**（独立第一步，V12）：`OPENAI→CHAT`、`ANTHROPIC→MESSAGES`，
  行为零变化，见 [PROTOCOL-RENAME-PLAN.md](../protocol-translation/PROTOCOL-RENAME-PLAN.md)

## 关键决策

| 决策 | 一句话理由 | 详见 |
|---|---|---|
| 直连执行器平级新建，不复用 Chat 执行器 | 透传路线要求对上游格式差异免疫，继承会拉进 `choices[]` 结构假设 | PLAN.md §5.1 |
| 响应全程 String 透传，不加响应侧 DTO | 加 DTO 等于加一道上游字段必须符合预期结构的约束 | PLAN.md §5.5、§7 |
| `responses_base_url` 空时回退 `base_url` | 与 `anthropic_base_url` 同语义，避免逼用户填三遍同一地址 | PLAN.md §3.2 |
| 翻译回退序独立常量 `TRANSLATION_FALLBACK_ORDER` | 枚举声明序不承载语义，展示序按「已实现优先」另排 | PLAN.md §6 |
| 不按模型名猜能力（如按 gpt-5 前缀剥 temperature） | 与「不硬编码供应商」原则冲突，中转站改名即失效 | PLAN.md §7 |
| 请求体规则先于端点支持 RESPONSES | 规则引擎的协议白名单先行，端点上线前规则已可配置 | PLAN.md §13.1 |
| 空响应判定器安全超集（7 终态事件） | 多认不错（永不匹配），删掉万一兼容端点真发反而危险 | PLAN.md §15.6 |

## 未做 / 遗留

- **C2R / R2C 翻译未实现**：调研已完成（[RESEARCH.md](../protocol-translation/chat-responses/RESEARCH.md)），
  是独立下一步（PLAN.md §7）
- **`previous_response_id` / `store` / `conversation` 不解释不缓存**：直连下 `@JsonAnySetter`
  原样透传；跨协议翻译时才需决策（PLAN.md §7）
- **`response.failed` 的日志措辞与分类**：行为正确（该重试），但归类为空响应导致措辞不准；
  属设计改动非修补（PLAN.md §15.7）
- **Chat 侧 `refusal` 字段疑似漏判**：疑似而非已证（手头无 Chat 官方文档），
  属既有线路，独立一轮（PLAN.md §15.7）
- **「每加版本手工冻结上一个」的结构性问题**：已踩两次，值得独立重构（PLAN.md §8）

## 相关

- [BYOK 思考链回放调查](../../reference/COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md) —
  实施过程中暴露的独立问题：Responses 加密思考 vs Chat 明文回放的适用范围差异
