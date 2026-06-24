# 各模型对 copilot 的适配度

> 就目前的测试来看，出问题的只是部分带有思考链的模型，而不带思考链的模型一定没有问题。欢迎测试后提 iss 以完善此表。
>
> 💡 **智谱模型命名说明：** 智谱平台中，`flashx` 后缀为并发优化的付费版本，`flash` 后缀为免费版本（响应速度同样很快，但并发限制更严格）。

---

## 官方供应商

### DeepSeek（深度求索）

| 模型 ID | 适配度 |
|---------|--------|
| `deepseek-v4-pro` | 完美 |
| `deepseek-v4-flash` | 完美 |

### MiMo（小米）

| 模型 ID | 适配度 |
|---------|--------|
| `mimo-v2.5-pro` | 完美 |
| `mimo-v2.5` | 完美 |

### Zhipu（智谱）

| 模型 ID | 适配度 |
|---------|--------|
| `glm-5.2` | 完美 |
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

### LongCat（美团）

| 模型 ID | 适配度 |
|---------|--------|
| `LongCat-2.0-Preview` | 完美 |

### Agnes

| 模型 ID | 适配度 |
|---------|--------|
| `agnes-2.0-flash` | [缺陷 ⚠️](MODEL_COMPATIBILITY.md#agnes-agnes-20-flash--工具调用参数流式生成异常) |
| `agnes-1.5-flash` | [缺陷 ⚠️](MODEL_COMPATIBILITY.md#agnes-15-flash--工具调用能力缺失) |

---

## 知名模型整合商

### SenseNova（商汤）

| 模型 ID | 适配度 |
|---------|--------|
| `sensenova-6.7-flash-lite` | 完美 |
| `deepseek-v4-flash`（商汤版） | [缺陷 ⚠️](MODEL_COMPATIBILITY.md#sensenova-商汤deepseek-v4-flash--过度思考) |

### Xunfei（讯飞）

> 讯飞星辰 MaaS 平台提供的模型种类繁多，以下仅列出经过测试的模型。

| 模型 ID | 适配度 |
|---------|--------|
| `xopqwen36v35b` | 可用 |
| `xopqwen35v35b` | 可用 |

---

## 小众整合商

### Uumit

> 以下为 Uumit 平台提供的全部模型。部分模型（如 DeepSeek、GLM 系列）也支持通过官方供应商直接使用。

| 模型 ID | 适配度 |
|---------|--------|
| `deepseek-v4-pro` | 完美（同 DeepSeek 官方） |
| `deepseek-v4-flash` | 完美（同 DeepSeek 官方） |
| `glm-5.1` | 完美（同智谱官方） |
| `glm-4.7` | 完美（同智谱官方） |
| `GLM-5.0` | 完美（同智谱 glm-5 别名） |
| `Doubao-Seed-2.0-Pro` | 可用 |
| `Doubao-Seed-2.0-Lite` | 可用 |
| `Doubao-Seed-2.0-Code` | 可用 |
| `qwen3.5-flash` | 可用 |
| `qwen3.5-plus` | 可用 |
| `qwen3.6-flash` | 可用 |
| `Kimi-K2.5` | 可用 |
| `kimi-k2.6` | 可用 |
| `Minimax-M2.5` | 可用 |
| `minimax-m2.7` | 可用 |
