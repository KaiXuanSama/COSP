# 供应商适配历史记录

> 本文档记录 V8 之前的专有 Provider 适配历史，不是当前运行时实现说明。

当前 COSP 不再按 DeepSeek、MiMo、Kimi 等名称加载专有 Java 服务。所有供应商均使用数据库配置，
由 `GenericOpenAiChatService`（OpenAI 线路）与 `GenericAnthropicChatService`（Anthropic 线路）统一执行。
认证差异、上游不支持字段和消息结构差异应通过管理后台的请求头规则与请求体规则组表达。

## 当前通用能力

- 默认使用 Bearer 认证；请求头规则可覆盖、添加或通过 `/del/` 删除请求头，并支持 `{apiKey}` 占位符。
- 请求体规则按**规则组**组织，每组声明适用的线路协议；组内规则可按顺序修改、删除字段或递归调整对象/数组中的消息内容。
- 「设置字段值」与条件的「等于」都有显式类型档位（字符串 / 数值 / 列表 / 布尔 / null），
	不靠输入内容猜类型 —— 「等于 null」是可选项而非留空的副作用。
- 编辑器的转换预览由后端生产引擎计算，因此预览结果与实际发往上游的请求体出自同一份代码。
- 上游流式响应中的 `thinking`、`reasoning`、`reasoning_text`、`cot_summary` 等字段会统一为
	`reasoning_content`。
- 当前 GitHub Copilot 客户端负责跨请求回放 `reasoning_content`；COSP 不再缓存、注入或定期清理思考内容。

## 历史范围

旧版专有 Provider 曾包含双请求头认证、图片工具消息改写、Coding 端点字段删除和思考链缓存等逻辑。
这些 Java 分支均已删除。对于仍需适配的 OpenAI 兼容上游，请建立供应商配置并保存相应请求转换规则；
模型实际兼容性见 [模型兼容性](MODEL_COMPATIBILITY.md)。

规则集本身也有版本历史：V1 是单一扁平规则列表、只服务 OpenAI 一条线路，V8.7 迁移把它升为
V2 规则组（`{version:2, groups:[...]}`）。存量规则被包进一个仅适用 OpenAI 的组 ——
那些字段路径是照 OpenAI 请求体写的，作用在 Anthropic 请求体上多数匹配不到，
静默失效比不执行更难排查。约定细节见 [AGENTS.md](../AGENTS.md#请求转换规则)。
