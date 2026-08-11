# COSP Copilot 指令

## 当前架构

- Copilot 使用 `/api/version`、`/api/tags`、`/api/show` 发现模型，实际聊天仅走 `/v1/chat/completions`。
- `ModelDiscoveryService` 与 `ChatCompletionService` 是应用层用例服务，分别委派给 `GenericDiscoveryService` 和 `GenericOpenAiChatService`。
- 所有供应商都是数据库记录，不存在内置或专有 provider。通过管理后台配置 Base URL、API Key、模型、能力和转换规则。
- `ProviderRouteResolver` 对前缀模型精确路由；无前缀模型仅在唯一匹配时允许路由。
- 单端口 11434 同时提供对外 API 与管理后台（`/config/api/**` + JWT）。

## 开发规则

- 保持 `api -> application -> provider` 分层；DTO 位于独立 `protocol` 层，持久化在 `infrastructure/persistence`（`JdbcTemplate`，无 JPA / 无 Lombok）。
- 能力只能由 `provider_model` 的 `caps_tools`、`caps_vision` 决定，禁止硬编码或通过模型名判断。
- 上下文窗口最小为 8192。
- WebFlux 中所有 JDBC 调用使用 `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`。
- 对外调用必须复用注入的 `WebClient.Builder`，不要使用裸 `WebClient.builder()`。
- 标准供应商不创建新 Java 包；供应商差异先用 `provider_request_transform` 的请求头/请求体规则表达。
- `GatewayAuthFilter` 只拦截 `POST /v1/chat/completions`，Ollama 发现接口必须保持匿名可访。
- 流式与非流式共用同一份重试预算（`buildRetrySpec`）与同一份空响应判定（`UpstreamChunkContentDetector.payloadHasContent`），不要为某一侧另起 `retryWhen` 或放宽判定口径。

## 测试

测试类名以 `Tests` 结尾（Surefire 只拾取这个后缀）。集成测试使用 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 和 `WebTestClient`；JDBC 测试用 `@TempDir` 临时 SQLite；上游用自定义 `ExchangeFunction` stub，没有 MockWebServer。修改后先检查编辑器错误，再运行 `./mvnw test` 或 `cd frontend && npm run test:run`。不要主动启动服务或操作 `admin.db`。

更完整的架构与验证约定见 [AGENTS.md](../AGENTS.md)。
