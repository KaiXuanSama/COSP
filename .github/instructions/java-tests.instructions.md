---
applyTo: "src/test/java/**"
description: "COSP Java 测试约定。Use when: 新增或修改 src/test/java 下的测试，包括 SpringBootTest、JDBC 仓储测试、上游 WebClient stub。"
---

# Java 测试指南

测试依赖只有 `spring-boot-starter-test`（JUnit 5 + Mockito + AssertJ + Reactor Test + WebTestClient）。没有 Testcontainers，没有 OkHttp MockWebServer。

## 命名

**类名必须以 `Tests` 结尾** —— Surefire 只拾取这个后缀，`*Test` / `*IT` 都不会被执行。测试包路径镜像主代码分层。

## 四类测试

1. **Web 集成**：`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient.bindToServer().baseUrl("http://localhost:" + port)`，用 `@MockBean` 替换服务层。流式断言用 `FluxExchangeResult<ServerSentEvent<String>>`。参考 `api/openai/OpenAiControllerStreamingTests`。
2. **JDBC / 迁移**：用 `@TempDir` 建临时 SQLite 文件，**绝不使用根目录 `admin.db`**。参考 `infrastructure/persistence/RepositoryUpsertTests`、`infrastructure/config/SchemaMigrationRunnerTests`。
3. **上游调用**：给 `WebClient` 注入自定义 `ExchangeFunction`，需要 SSE 时用 `DefaultDataBufferFactory` 手工造 `DataBuffer`。参考 `provider/AbstractUpstreamChatServiceTests`。
4. **纯单元**：JUnit 5 + AssertJ + Mockito，无 Spring 上下文。逻辑密集的类用 `@Nested` 分组（如 `ModelNameUtilTests`）。

断言统一 `org.assertj.core.api.Assertions.assertThat`。

## 测试配置

`src/test/resources/application.yml` 指向 `jdbc:sqlite:target/test-admin.db`，`COSP_MASTER_KEY: test`。需要临时改属性时用 `@SpringBootTest(properties = {...})`；需要断言日志输出时加 `@ExtendWith(OutputCaptureExtension.class)`（参考 `ResponseLoggingFilterTests`）。

## 运行

```bash
./mvnw test -Dtest=XxxTests    # 单类
./mvnw test                    # 全量，含前端构建
```

先用编辑器诊断检查改动文件，再跑命令。`tools/mock-upstream`、`tools/mock-cosp` 是手动验证工具，不参与自动化测试。
