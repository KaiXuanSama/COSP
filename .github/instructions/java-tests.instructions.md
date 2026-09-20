---
applyTo: "src/test/java/**"
description: "COSP Java 测试约定。Use when: 新增或修改 src/test/java 下的测试，包括 SpringBootTest、JDBC 仓储测试、上游 WebClient stub。"
---

# Java 测试指南

测试依赖只有 `spring-boot-starter-test`（JUnit 5 + Mockito + AssertJ + Reactor Test + WebTestClient）。没有 Testcontainers，没有 OkHttp MockWebServer。

## 命名

项目测试类统一以 `Tests` 结尾，测试包路径镜像主代码分层。这是仓库命名约定；不要依赖
Surefire 的其它默认命名模式，也不要新增 `*IT` 而不显式接入测试生命周期。

## 四类测试

1. **Web 集成**：`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `WebTestClient.bindToServer().baseUrl("http://localhost:" + port)`，用 `@MockBean` 替换服务层。流式断言用 `FluxExchangeResult<ServerSentEvent<String>>`。参考 `api/openai/OpenAiControllerStreamingTests`。
   - 控制器只依赖一两个可直接 new 的 Bean 时，用 `WebTestClient.bindToController(...)` 而非整个上下文 —— 拉起完整应用对「请求体进、响应出」没有额外价值，却要付启动代价。参考 `api/RequestBodyRulePreviewControllerTests`。
2. **JDBC / 迁移**：用 `@TempDir` 建临时 SQLite 文件，**绝不使用根目录 `admin.db`**。参考 `infrastructure/persistence/RepositoryUpsertTests`、`infrastructure/config/SchemaMigrationRunnerTests`。
3. **上游调用**：给 `WebClient` 注入自定义 `ExchangeFunction`，需要 SSE 时用 `DefaultDataBufferFactory` 手工造 `DataBuffer`。参考 `provider/AbstractUpstreamChatServiceTests`（OpenAI）、`provider/generic/anthropic/GenericAnthropicChatServiceTests`（Anthropic，请求构造那批走真实 `HttpServer` 因为 `ClientRequest.body()` 读不出已序列化内容）。
4. **纯单元**：JUnit 5 + AssertJ + Mockito，无 Spring 上下文。逻辑密集的类用 `@Nested` 分组（如 `ModelNameUtilTests`）。

断言统一 `org.assertj.core.api.Assertions.assertThat`。

## 测试配置

`src/test/resources/application.yml` 指向 `jdbc:sqlite:target/test-admin.db`，`COSP_MASTER_KEY: test`。需要临时改属性时用 `@SpringBootTest(properties = {...})`；需要断言日志输出时加 `@ExtendWith(OutputCaptureExtension.class)`（参考 `ResponseLoggingFilterTests`）。

## 运行

```bash
./mvnw test -Dtest=XxxTests    # 单类
./mvnw test                    # 全量，含前端构建
```

## Tag 分层：迁移测试默认不跑

`SchemaMigrationRunnerTests` 整个类带 `@Tag("schema-migration")`，`pom.xml` 的
`surefire.excludedGroups` 默认排除它。**若要让新测试类也默认不跑，给它同一个 Tag 即可，
不必改 pom。**

| 场景 | 命令 |
|---|---|
| 日常全量 | `./mvnw surefire:test`（迁移类被排除） |
| **动了迁移 / schema.sql / 版本常量** | `./mvnw surefire:test -Dsurefire.excludedGroups=none` |
| 只跑迁移 | `./mvnw surefire:test -Dtest=SchemaMigrationRunnerTests` |

覆盖时用 `-Dsurefire.excludedGroups=none`。两个实测过的细节：

- **属性名必须带 `surefire.` 前缀**：`-DexcludedGroups=none` 不生效（静默继续用默认值）。
- 空值 `-Dsurefire.excludedGroups=` 实测**也能工作**（跑满 1080 条、BUILD SUCCESS），
  等价于 `none`。但仍推荐写 `none`：空值靠「Maven 展开为空、插件自行处理」这个细节成立，
  读命令的人看不出意图，而 `none` 是「排除一个不存在的 Tag」，语义自明。

**为何单列这一类而不是按「慢」划分**：它占全量 58%（112~129 秒 / 222 秒），而慢的原因是
每条用例都要在真实 SQLite 文件上从旧版本跑到当前版本，每次事务提交都要 fsync
（实测约 159ms/次）。

**但这类用例不可删减**：它覆盖的正是「链断了」这类只有跑完整链才能发现的问题 ——
V13 曾用 `CURRENT_SCHEMA_VERSION` 而非固定常量，抬高版本号后 V13 把自己写成新版本、
V13 与 V14 一起被跳过。**每一步单独看都是对的，所以单步断言发现不了它。**

Windows PowerShell 使用 `mvnw.cmd` 的等价命令（当前目录执行时加 `./` 或 `.` + 路径分隔符）；多测试类的
`-Dtest=...` 参数整体加引号，避免
PowerShell 把逗号表达式拆成参数。需要跳过前端时沿用 `pom.xml` 已支持的 Maven 属性，不自行删插件执行。

先用编辑器诊断检查改动文件，再跑命令。`tools/mock-upstream`（OpenAI 流式）、`tools/mock-nonstream`（OpenAI 非流式）、`tools/mock-anthropic`（Anthropic 两种模式）、`tools/mock-cosp` 是手动验证工具，不参与自动化测试。

上游 stub 的响应体**必须带实质载荷**（`content` / 思考链 / `tool_calls` 之一），否则会被空响应兜底判空并卷入重试循环，用例表现为超时而非断言失败。`"choices":[]` 这类占位响应已不再安全 —— `ProviderRequestBodyTransformationIntegrationTests` 曾因此踩坑。

覆盖重试的用例要把退避压到毫秒级：测试子类覆盖 `retryFirstBackoff()` / `retryMaxBackoff()`（生产值 2s / 30s，一个「重试到耗尽」的用例按生产值要真实等 62 秒）。少数需要在退避窗口内插入动作的用例把它放宽到几百毫秒，参考 `AbstractUpstreamChatServiceTests` 的 `setBackoff`。
