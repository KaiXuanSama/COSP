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

---

## SenseNova（商汤）DeepSeek V4 Flash — ⚠️ 过度思考

> **状态：** 模型功能正常，但思考链（reasoning_content）长度不可控，容易过度思考，影响响应速度和 token 消耗。

### 问题描述

商汤平台提供的 `deepseek-v4-flash` 模型在工具调用和代码生成场景下，容易出现**过度思考**现象：`reasoning_content` 字段长度远超预期，有时达到数千 token，严重拖慢响应速度并增加 token 消耗。

### 表现特征

- 模型功能本身正常，工具调用、代码生成均可正常工作
- 思考链（reasoning_content）长度不稳定，有时正常，有时异常偏长
- 长思考链会显著增加首 token 延迟（TTFT）和总响应时间
- 相同提示词下，商汤版的思考链长度明显长于 DeepSeek 官方版

### 建议

- 对响应速度敏感的场景，建议直接使用 DeepSeek 官方 API 的 `deepseek-v4-flash`
- 如果只能使用商汤平台，可接受较慢的响应速度，则功能上不受影响

---

## Zhipu glm-4.1v-thinking-flashx — ⚠️ 无工具调用能力

> **状态：** 模型完全不具备工具调用能力，不推荐在 Copilot 编码场景中使用。

### 问题描述

`glm-4.1v-thinking-flashx` 是智谱推出的轻量级视觉思考模型，响应速度极快，但**完全不具备工具调用（function calling / tool_calls）能力**。在 Copilot 编码场景中，几乎所有操作都依赖工具调用（文件读写、搜索、终端执行等），因此该模型无法完成实际编码任务。

### 表现特征

- 模型不会生成任何 `tool_calls` 字段
- 即使在 system prompt 中明确要求使用工具，模型仍以纯文本回复
- 文本回复内容可能包含工具调用的描述，但不会实际触发调用

### 建议

- 不推荐在 Copilot 中使用该模型
- 如需使用智谱平台，建议选择支持工具调用的模型（如 `GLM-5.0`、`glm-4.7` 等）

> **注意：** `glm-4.1v-thinking-flash` 是该模型的免费版本，并发限制更严格，但缺陷一致——同样不具备工具调用能力，不推荐在 Copilot 中使用。

