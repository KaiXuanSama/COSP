# COSP Copilot 指令

## 当前架构

- Copilot 使用 `/api/version`、`/api/tags`、`/api/show` 发现模型，实际聊天仅走 `/v1/chat/completions`。
- `ModelDiscoveryService` 与 `ChatCompletionService` 是应用层用例服务，分别委派给 `GenericDiscoveryService` 和 `GenericOpenAiChatService`。
- 所有供应商都是数据库记录，不存在内置或专有 provider。通过管理后台配置 Base URL、API Key、模型、能力和转换规则。
- `ProviderRouteResolver` 对前缀模型精确路由；无前缀模型仅在唯一匹配时允许路由。

## 开发规则

- 保持 `api -> application -> provider` 分层；DTO 位于独立 `protocol` 层。
- 能力只能由 `provider_model` 的 `caps_tools`、`caps_vision` 决定，禁止硬编码或通过模型名判断。
- 上下文窗口最小为 8192。
- WebFlux 中所有 JDBC 调用使用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`。
- 对外调用必须复用注入的 `WebClient.Builder`，不要使用裸 `WebClient.builder()`。
- 标准供应商不创建新 Java 包；复杂协议适配先评估请求转换规则能否表达。

## 测试

集成测试使用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 和 `WebTestClient`。修改后先检查编辑器错误，再运行相关 Maven 或前端构建命令。不要主动启动服务或操作 `admin.db`。
