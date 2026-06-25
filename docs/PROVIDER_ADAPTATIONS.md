# Provider 特殊适配记录

> 记录各供应商相对于"标准 OpenAI 兼容"实现的针对性调整。
> 标准实现（无特殊逻辑）：**Agnes、SenseNova、Uumit、Xunfei、Zhipu、LongCat**。

---

## DeepSeek

- **思考链缓存**：多轮工具调用时，流式阶段缓存 `reasoning_content` 到 `reasoning_cache` 表，下一轮请求时回填到 `assistant+tool_calls` 消息中
- **Ollama 字段注入**：`thinking`（→ `{"type":"..."}`）和 `reasoning_effort` 字段

## MiMo

- **认证方式**：⚠️ `api-key` + `x-api-key` 双 header（非 Bearer Token）
- **图片格式转换**：`media_type` → `type: "image_url"`，`role: "tool"` 图片转 `role: "user"`
- **JSON 工具调用提示**：向 system prompt 注入约束，防止模型输出 XML 格式工具调用
- **思考链缓存**：同 DeepSeek 的读写逻辑

## Kimi

- **Coding 端点限制**：当 Base URL 含 `api.kimi.com/coding` 时，移除请求体中的 `temperature` 和 `top_p`（OpenAI 链 + Ollama 链双层处理）
- **Ollama 字段注入**：`thinking`（→ `{"type":"..."}`）字段

---

## 汇总矩阵

| Provider | 认证 | customizeRequestBody | onRawStreamChunk | Ollama 字段注入 | ReasoningCache |
|----------|------|---------------------|-----------------|----------------|----------------|
| **DeepSeek** | Bearer | ✅ reasoning 注入 | ✅ 缓存写入 | ✅ thinking + effort | ✅ |
| **MiMo** | ⚠️ api-key | ✅ 图片 + JSON 提示 + reasoning | ✅ 缓存写入 | ❌ | ✅ |
| **Kimi** | Bearer | ✅ Coding 端点 | ❌ | ✅ thinking | ❌ |
| 其余 6 个 | Bearer | ❌ | ❌ | ❌ | ❌ |
