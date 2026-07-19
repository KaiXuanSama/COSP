# 供应商命名与模型展示名转换规则

本文档记录 COSP V8 中供应商名称在 UI、数据库、模型展示名之间的转换规则。

## 核心概念

| 层级 | 示例值 | 说明 |
|------|--------|------|
| UI 显示名 / DB `display_name` | `StepFun` | 用户输入并完整保存的名称，用于管理后台展示，保留大小写 |
| DB `provider_key` | `stepfun` | 数据库中的稳定 slug，全局唯一，用于路由 |
| 模型展示前缀 | `stepfun` | `/api/tags`、`/api/show` 直接使用的 key |
| 完整模型名 | `[stepfun] model-name` | Copilot 看到的模型标识 |

所有供应商均为数据库配置，并统一由 `GenericOpenAiChatService` 与 `GenericDiscoveryService` 处理。`mimo`、`deepseek` 等仅是普通 key 或前端预设，不再具有专有运行时实现。

## 转换链路总览

```
UI: StepFun
  ↓ 完整保存                     ↓ slugify
DB: provider_config.display_name  DB: provider_config.provider_key
    StepFun                        stepfun
  ↓
展示: [stepfun] model-name
  ↓ 解析前缀并精确查询 provider_key
路由: stepfun → GenericOpenAiChatService
```

## UI 名称到数据库字段

创建或编辑供应商时，名称原样保存到 `provider_config.display_name`。因此 `StepFun`、`MiMo` 等内部大小写在刷新后仍会保留。历史空值才会从 key 推导可读回退名称，无法恢复旧数据中已丢失的内部大小写。

`provider_key` 由名称生成：

```java
name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")
```

步骤：

1. 全部转小写。
2. 非 `[a-z0-9]` 字符替换为 `-`。
3. 去掉首尾多余的 `-`。

| UI 名称 | DB `provider_key` |
|---------|-------------------|
| `Mimo TokenPlan` | `mimo-tokenplan` |
| `My API` | `my-api` |
| `LongCat` | `longcat` |
| `Kimi CodePlan` | `kimi-codeplan` |

代码位置：

- 后端：`api/AdminPageController.java`
- 前端本地 key 预估：`frontend/src/views/Settings.vue`

## 数据库 key 到模型展示名

`DatabaseModelCatalogService` 直接将 `provider_key` 作为展示前缀：

```java
String prefixedName = ModelNameUtil.buildPrefixedName(providerKey, modelName);
```

| DB `provider_key` | 完整模型名 |
|-------------------|-----------|
| `mimo-tokenplan` | `[mimo-tokenplan] mimo-v2.5-pro` |
| `longcat` | `[longcat] gpt-4o` |
| `deepseek` | `[deepseek] deepseek-v4-flash` |

代码位置：

- 展示聚合：`application/catalog/DatabaseModelCatalogService.java`
- 拼接工具：`application/util/ModelNameUtil.java`

## 模型展示名到下游路由

请求模型名 `[stepfun] model-name` 会由 `ModelNameUtil.parse()` 解析为 provider key 和实际模型名。两个 Resolver 都会按 key 查询启用的数据库供应商配置，并统一返回 Generic 服务实现。无前缀请求则在启用的供应商模型中查找；未找到时返回明确的未匹配结果，不会回退到任意服务商。

## V8 迁移规则

从 V7.1 升级时，`custom-*` 数据会作为旧兼容数据迁移：

- `custom-stepfun` 变为 `stepfun`。
- 若 `stepfun` 已存在，保留原 `custom-stepfun` 配置及其模型、API key、请求转换规则，并删除旧 `stepfun` 配置及关联数据。
- 历史 `api_call_log.provider_key` 不修改，以保留审计记录。

## 设计约束

1. `provider_key` 是路由标识，必须唯一且稳定。
2. `display_name` 仅用于 UI 展示，应原样保存。
3. 模型能力必须从 `provider_model` 读取，不可按 key 或模型名称推断。
4. UI 名称可重复，只要 slug 化后的 key 不冲突即可。

## 相关文档

- [模型兼容性](MODEL_COMPATIBILITY.md)
- [供应商适配](PROVIDER_ADAPTATIONS.md)
