# 模型适配问题报告

> 本文档记录各模型在 Copilot 代理服务中的适配情况、已知问题及排查结论。

---

## Xiaomi MiMo V2.5 Pro — ✅ 已解决

> **状态：** MiMo 官方已修复 XML 工具调用格式问题，模型现已能正常输出标准 JSON `tool_calls`。
> 
> 代理层此前针对此问题实现的 XML 拦截/转换代码已移除。当前仅保留 system prompt 中的 JSON 工具调用格式提示注入，作为额外保障。

### 历史问题摘要

MiMo 曾在此场景下存在以下问题（均已由官方修复）：

- XML 格式工具调用输出（如 `<parameter=oldString>`）而非标准 JSON `tool_calls`
- 输出字段不稳定（XML 可能出现在 `reasoning_content` 或 `content` 中）
- 系统提示词干预无效

---

## DeepSeek — 兼容适配

### 已知问题

#### 1. 多轮工具调用需 `reasoning_content`

DeepSeek 在收到包含 `tool_calls` 的 assistant 消息时，要求同一消息中必须包含 `reasoning_content` 字段，否则返回 400 错误。

**解决方案：** 已在代理层实现 `reasoning_content` 缓存机制：
- 首次工具调用时捕获 `reasoning_content` 存入 SQLite 数据库
- 后续请求中根据 `tool_call_id` 回填缓存的思考内容
- 缓存未命中时使用空字符串 `""` 作为 fallback

#### 2. 思考链较长

DeepSeek 的 `reasoning_content` 可能很长（数千 token），会增加请求体大小和延迟。

---

## LongCat — 兼容适配

### 已知问题

#### 1. LongCat-Flash-Thinking-2601 工具调用不稳定

`LongCat-Flash-Thinking-2601` 在工具调用场景下偶尔出现输出格式异常，表现为 longcat_tool_calls 参数结构不完整或缺失必要字段，导致 Copilot 无法正确解析。

<img src="images/model-issues/longcat-field-1.png" alt="LongCat 工具调用参数结构异常" width="60%">

**建议：** 优先使用 `LongCat-2.0-Preview`，该模型在工具调用方面表现稳定。

