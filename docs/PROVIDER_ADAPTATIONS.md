# 供应商适配历史记录

> 本文档记录 V8 之前的专有 Provider 适配历史，不是当前运行时实现说明。

当前 COSP 不再按 DeepSeek、MiMo、Kimi 等名称加载专有 Java 服务。所有供应商均使用数据库配置，
由 `GenericOpenAiChatService` 统一执行。认证差异、上游不支持字段和消息结构差异应通过管理后台的
请求头规则与请求体 RuleSet 表达。

## 当前通用能力

- 默认使用 Bearer 认证；请求头规则可覆盖、添加或通过 `/del/` 删除请求头，并支持 `{apiKey}` 占位符。
- 请求体 RuleSet 可按顺序修改、删除字段或递归调整对象/数组中的消息内容。
- 上游流式响应中的 `thinking`、`reasoning`、`reasoning_text`、`cot_summary` 等字段会统一为
	`reasoning_content`。
- 当前 GitHub Copilot 客户端负责跨请求回放 `reasoning_content`；COSP 不再缓存、注入或定期清理思考内容。

## 历史范围

旧版专有 Provider 曾包含双请求头认证、图片工具消息改写、Coding 端点字段删除和思考链缓存等逻辑。
这些 Java 分支均已删除。对于仍需适配的 OpenAI 兼容上游，请建立供应商配置并保存相应请求转换规则；
模型实际兼容性见 [模型兼容性](MODEL_COMPATIBILITY.md)。
