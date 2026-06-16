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

## Agnes agnes-2.0-flash — ⚠️ 工具调用参数流式生成异常

> **状态：** 模型在工具调用场景下存在流式参数生成缺陷，可能导致 Copilot 对话中断。

### 问题描述

`agnes-2.0-flash` 在生成工具调用（tool_calls）时，流式响应（SSE）的 chunk 顺序和内容存在异常，表现为：

1. **`finish_reason` 提前出现**：在工具调用参数尚未生成完毕时，模型就输出了 `finish_reason: "stop"`，随后又继续输出参数 chunk，导致参数解析混乱
2. **参数名不一致**：模型使用 `path` 而非标准的 `filePath` 作为文件路径参数名，导致工具调用匹配失败
3. **参数不完整**：`create_file` 工具调用缺少必需的 `content` 参数
4. **对话中断**：上述问题导致 Copilot 无法正确执行工具调用，对话被停止

### 日志特征

在 `run.log` 中可以看到以下异常模式：

```
// 正常开始工具调用
{"name":"create_file"}

// 参数开始生成
{"arguments":"{"}

// ⚠️ finish_reason 在参数中间出现
{"finish_reason":"stop"}

// ⚠️ 参数在 stop 后继续到达
{"arguments":", \"path\": \"...\"}

// ⚠️ 参数名错误：应为 filePath，实为 path
// ⚠️ 缺少 content 参数
```

### 影响范围

- 所有涉及工具调用的对话场景（文件创建、文件读取、编辑等）
- 非流式文本生成正常，仅在工具调用时触发
- 多轮对话中一旦触发工具调用，对话大概率中断

### 建议

- 当前版本不建议在生产环境中使用该模型进行需要工具调用的编码任务
- 建议等待 Agnes 官方修复工具调用的流式输出逻辑
- 如需使用 Agnes 平台，建议测试其他模型是否受影响

### agnes-1.5-flash — ⚠️ 工具调用能力缺失

> **状态：** 模型在应该调用工具的场景下选择不调用工具，直接以文本回复，导致任务无法完成。

#### 问题描述

`agnes-1.5-flash` 在明确需要调用工具的场景下（如用户要求获取网页内容并保存到文件），模型在文本回复中说明了"让我获取速率限制和错误码信息"，但**最终没有触发任何工具调用**，而是直接以 `finish_reason: "stop"` 结束。

#### 与 agnes-2.0-flash 的对比

| 对比项 | agnes-2.0-flash | agnes-1.5-flash |
|--------|-----------------|-----------------|
| 工具调用意愿 | ✅ 尝试调用 | ❌ 放弃调用 |
| 工具调用结果 | ❌ 参数生成异常，对话中断 | 未触发工具调用 |
| 最终表现 | 对话中断，任务未完成 | 文本回复了事，任务未完成 |

#### 结论

`agnes-1.5-flash` 的问题比 `agnes-2.0-flash` 更严重：2.0 至少尝试了调用工具（虽然失败了），而 1.5 直接**放弃了工具调用**，说明其指令遵循能力（instruction following）存在明显不足。

#### 建议

- 不建议在需要工具调用的编码场景中使用 `agnes-1.5-flash`
- 如需使用 Agnes 平台，建议优先测试 `agnes-2.0-flash`（等待官方修复后）或其他模型

