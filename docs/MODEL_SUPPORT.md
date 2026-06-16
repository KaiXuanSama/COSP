# 模型适配度

> 就目前的测试来看，出问题的只是部分带有思考链的模型，而不带思考链的模型一定没有问题。欢迎测试后提 iss 以完善此表。
>
> 💡 **智谱模型命名说明：** 智谱平台中，`flashx` 后缀为并发优化的付费版本，`flash` 后缀为免费版本（响应速度同样很快，但并发限制更严格）。

| 模型 ID | 适配度 |
|---------|--------|
| `deepseek-v4-pro` | 完美 |
| `deepseek-v4-flash` | 完美 |
| `mimo-v2.5-pro` | 可用 |
| `mimo-v2.5` | 可用 |
| `mimo-v2-pro` | 完美 |
| `mimo-v2-omni` | 未验证 ❓ |
| `LongCat-2.0-Preview` | 完美 |
| `Doubao-Seed-2.0-Lite` | 概率可用 |
| `Doubao-Seed-2.0-pro` | 未验证 ❓ |
| `Doubao-Seed-2.0-Code` | 可用 |
| `qwen3.5-flash` | 未验证 ❓ |
| `qwen3.5-plus` | 未验证 ❓ |
| `qwen3.6-flash` | 未验证 ❓ |
| `Kimi-K2.5` | 未验证 ❓ |
| `kimi-k2.6` | 未验证 ❓ |
| `minimax-m2.7` | 未验证 ❓ |
| `Minimax-M2.5` | 未验证 ❓ |
| `glm-5.1` | 完美 |
| `glm-5-turbo` | 完美 |
| `glm-5` | 完美 |
| `glm-4.7` | 完美 |
| `glm-4.7-flash` | 完美 |
| `glm-4.7-flashx` | 完美 |
| `glm-4.6` | 完美 |
| `glm-4.6v` | 完美 |
| `glm-4.6v-flash` | 完美 |
| `glm-4.6v-flashx` | 完美 |
| `glm-4.5-air` | 完美 |
| `glm-4.1v-thinking-flash` | [缺陷 ⚠️](MODEL_COMPATIBILITY.md#zhipu-glm-41v-thinking-flashx--无工具调用能力) |
| `glm-4.1v-thinking-flashx` | [缺陷 ⚠️](MODEL_COMPATIBILITY.md#zhipu-glm-41v-thinking-flashx--无工具调用能力) |
| `agnes-2.0-flash` | [缺陷 ⚠️](MODEL_COMPATIBILITY.md#agnes-agnes-20-flash--工具调用参数流式生成异常) |
| `agnes-1.5-flash` | [缺陷 ⚠️](MODEL_COMPATIBILITY.md#agnes-15-flash--工具调用能力缺失) |
