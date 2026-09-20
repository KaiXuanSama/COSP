# 供应商适配历史记录

> 本文档记录 V8 之前的专有 Provider 适配历史，不是当前运行时实现说明。

当前 COSP 不再按 DeepSeek、MiMo、Kimi 等名称加载专有 Java 服务。所有供应商均使用数据库配置，
由 `GenericOpenAiChatService`（OpenAI 线路）与 `GenericAnthropicChatService`（Anthropic 线路）统一执行。
认证差异、上游不支持字段和消息结构差异应通过管理后台的请求头规则与请求体规则组表达。

## 当前通用能力

- 默认使用 Bearer 认证；请求头规则可覆盖、添加或通过 `/del/` 删除请求头，并支持 `{apiKey}` 占位符。
- 请求体规则按**规则组**组织，每组声明适用的线路协议；组内规则可按顺序修改、删除字段或递归调整对象/数组中的消息内容。字段值为数组时，「删除」作用于**匹配到的元素**（如按 `type` 摘掉上游不支持的工具），删掉整个字段则需关掉数组开关。
- 「设置字段值」与条件的「等于」都有显式类型档位（字符串 / 数值 / 对象或列表 / 布尔 / null），
	不靠输入内容猜类型 —— 「等于 null」是可选项而非留空的副作用。
	「对象/列表」一档承载全部 JSON 结构体，因此可以整体替换一个对象
	（如把 `text.format` 换成 `{"type":"json_object"}`，一次性丢掉 `schema` 等多余字段）。
- 编辑器的转换预览由后端生产引擎计算，因此预览结果与实际发往上游的请求体出自同一份代码。
- 上游流式响应中的 `thinking`、`reasoning`、`reasoning_text`、`cot_summary` 等字段会统一为
	`reasoning_content`。
- 当前 GitHub Copilot 客户端负责跨请求回放 `reasoning_content`；COSP 不再缓存、注入或定期清理思考内容。
	**这个前提的适用范围是 Chat 线路 + Copilot BYOK** —— 那条路上客户端手上有明文。
	Responses 的加密思考不适用：Codex 只有密文，无法自己回放明文。直连不受影响（原样透传），
	但翻译线路（R2C / R2M / C2R / M2R）必须先就此做决策，见
	[请求侧契约](./PROTOCOL_TRANSLATION_CONTRACT.md) 第 4.8 节。

## MiMo 的 Responses 网关限制（2026-09-13 实测）

两个 MiMo 预设（官方直连与 TokenPlan）自带三个规则组：图片兼容（CHAT）+ 两条 Responses 适配。
后两条来自 Codex 经 COSP 调用时的上游硬拒绝，都是 `responses_feature_not_supported`：

| 限制 | 表达方式 |
|---|---|
| 拒收托管 `web_search` 工具 | 数组模式按 `./type` 条件删除该元素（不能删整个 `tools`，Codex 同时发十来个工具） |
| `text.format` 只接受 `text` / `json_object` | 整体替换 `format` 为 `{"type":"json_object"}`（`json_object` 形态里只有 `type`，只改 `type` 会留下 `schema` 等矛盾字段） |

cc-switch 对 `web_search` 有同源黑名单（`codex_config.rs` 的
`CODEX_WEB_SEARCH_REJECT_HOSTS` / `..._MODEL_PREFIXES`，MiMo 标注为 hard 400），
但它按 base_url 主机名或模型名品牌前缀匹配，**经过 COSP 后两条通道都失配** ——
主机恒为 localhost、模型名形如 `[mimo-tokenplan] mimo-v2.5`（以 `[` 开头）。
因此只能在 COSP 侧摘掉。

这两条只给 MiMo，**不铺给** LongCat / MiniMax 等同被 cc-switch 列入黑名单的供应商 ——
那些未经 COSP 实测，铺开等于把猜测写成预设。

## Responses-only 中转的形态（2026-09-13 实测，gpt-6-astra @ anyrouter）

一个只提供 Responses 路径的模型中转，**行为规范**：事件名全用官方标准名、
`sequence_number` 连续无跳号、`output_text` 的 delta 拼接与 `done.text` 逐字节一致、
工具参数的 delta 拼接与 `function_call_arguments.done.arguments` 逐字节一致。
直连无需任何适配。

但它暴露了几个**官方文档没有的字段**（已逐个比对 `Responses.md`，均为零匹配）：

| 字段 | 位置 |
|---|---|
| `content_filters` | response 对象 |
| `tool_usage` | response 对象 |
| `internal_chat_message_metadata_passthrough` | output item |
| `client_metadata` | 请求体 |

这些是中转层的自留字段（该中转是 new-api，负载超限时回
`{"error":{"type":"new_api_error","code":"get_channel_failed"}}`）。
它们靠 `@JsonAnySetter` 原样透传，**不需处理**。

容易误判的相反情况：以下字段看着陌生但**都是官方的** —— `obfuscation`（流式填充，默认开启）、
`phase`、`moderation`、`max_tool_calls`、`prompt_cache_retention`、`safety_identifier`、
工具的 `type: "namespace"`。查证后再动手，别按印象剔除。

### `phase` 不能丢（官方明文警告）

官方对 `input` 消息的 `phase` 字段写着：

> For models like `gpt-5.3-codex` and beyond, when sending follow-up requests, **preserve and resend
> phase on all assistant messages — dropping it can degrade performance.**

取值是 `commentary` / `final_answer`（实测 Codex 会回传 `commentary`）。

本服务默认原样透传，行为正确。**但如果用户配的请求体规则删掉了 `input` 里的 `phase`，
会静默降低性能** —— 不报错、不告警，只是变差。不要为「清理冗余字段」而删它。

### 思考链加密形态（首次实测）

该中转开启加密思考（下游发 `store: false` + `include: ["reasoning.encrypted_content"]`）：
`reasoning` item 的 `content` 与 `summary` **都是空数组**，只有 `encrypted_content`。
此前实测的 MiMo 与 DeepSeek 走的是明文（`content[]`），这是两种截然不同的形态 ——
判定器把「只有加密块」判为无载荷是对的（该轮靠正文或工具 `name` 通过），
详见[请求侧契约](./PROTOCOL_TRANSLATION_CONTRACT.md) 第 4.8 节。

## 历史范围

旧版专有 Provider 曾包含双请求头认证、图片工具消息改写、Coding 端点字段删除和思考链缓存等逻辑。
这些 Java 分支均已删除。对于仍需适配的 OpenAI 兼容上游，请建立供应商配置并保存相应请求转换规则；
模型实际兼容性见 [模型兼容性](MODEL_COMPATIBILITY.md)。

规则集本身也有版本历史：V1 是单一扁平规则列表、只服务 OpenAI 一条线路，V8.7 迁移把它升为
V2 规则组（`{version:2, groups:[...]}`）。存量规则被包进一个仅适用 OpenAI 的组 ——
那些字段路径是照 OpenAI 请求体写的，作用在 Anthropic 请求体上多数匹配不到，
静默失效比不执行更难排查。约定细节见 [AGENTS.md](../AGENTS.md#请求转换规则)。
