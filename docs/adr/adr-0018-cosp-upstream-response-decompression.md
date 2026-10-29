# ADR-0018: COSP 出站响应解压支持（gzip/deflate/zstd/brotli）

- **状态**：Accepted
- **日期**：2026-10-29
- **取代**：（无；新增能力，不推翻既有决策）
- **相关**：
  [WebClientConfig](../../src/main/java/com/kaixuan/copilot_ollama_proxy/infrastructure/config/WebClientConfig.java)（httpClient Bean）、
  [ProviderRequestHeaderService](../../src/main/java/com/kaixuan/copilot_ollama_proxy/application/provider/ProviderRequestHeaderService.java)（头透传与规则层）、
  [AgentRouter 前端预设](../../frontend/src/features/provider-config/presets.ts)（现状删头的先例）、
  [DeepSeek 实机日志](../../tools/live-test/deepseek-detail.json)（content-encoding: zstd）

## 背景

2026-10-29 实机验证发现：Claude CLI / Copilot 带 `Accept-Encoding: gzip, deflate, br, zstd`
请求 DeepSeek 时，上游（实际由腾讯云 EdgeOne CDN 承载，响应头含 `Server: openresty`、`EO-LOG-UUID`）
返回 `status 200` + `Content-Type: text/event-stream` + **`content-encoding: zstd`**。

COSP 当前行为：

- `ProviderRequestHeaderService` 刻意不拦截 `Accept-Encoding`，与 Cookie 一样交由规则层决定
  （NON_FORWARDABLE_HEADERS 只收 hop-by-hop 与必须重算的头）。
- `WebClientConfig.httpClient()` 未启用压缩/解压，也没有 zstd/brotli 解码器。
- 结果是 `bodyToFlux(ServerSentEvent)` 把 zstd 二进制当 SSE 解析，读不到任何 `data:` 行；
  `EmptyResponseGate` 判空并对重试预算耗尽，最终表现为上游空返。

现有临时办法是给供应商配置「删除 Accept-Encoding」请求头规则（AgentRouter 预设同源，针对 Brotli）。
该办法能工作，但它把「上游压缩能力」问题转嫁成每个供应商手工配置，且失去压缩带来的带宽收益。

## 决策

**COSP 自身增加出站响应解压能力，覆盖 gzip / deflate / zstd / brotli；不再依赖逐个供应商删除 `Accept-Encoding`。**

要点：

1. 添加依赖：`io.netty:netty-codec-compression`（提供 Netty 的 Zstd/Brotli 解码器封装）、
   `com.github.luben:zstd-jni`（zstd 原生实现）、`com.aayushatharva.brotli4j:brotli4j`
   及其平台原生包（Windows/Linux 按运行环境引入）。
2. 在 `WebClientConfig.httpClient()` 开启解压，使 `HttpContentDecompressor` 按响应
   `Content-Encoding` 选择解码器（gzip/deflate 内置；zstd/brotli 在可选依赖存在时启用）。
3. **不修改下游 `Accept-Encoding` 透传语义**；规则层删除仍是「个别上游行为异常时的兜底」，
   不作为默认形态。
4. **请求体不主动压缩**：只做响应解码，避免对上游兼容性引入新变量。

## 后果

**正面**

- 彻底解决 CDN 选择 zstd/br 导致的 SSE 空返，不要求每个供应商手工配删头。
- 保留压缩带宽收益（高并发用户场景对服务商、CDN 成本有意义）。
- 与「头透传由规则层决定」的既有设计兼容：正常上游无需任何规则。

**负面**

- 新增依赖含原生库（zstd-jni、brotli4j），需验证 Windows / Linux 的加载与版本。
- 解码涉及的 CPU/内存开销极低，但需回归确认流式逐帧解码不破坏边界。
- 若上游返回仍不可解码的编码（如 snappy、xz），保留「错误可见」而非静默判空；
  解析失败语义明确为上游错误而不是空响应。
- 版本兼容需跟随 Netty 4.1.132（当前）与 Reactor Netty 1.2.17。

## 备选方案

### 维持按供应商删除 Accept-Encoding（现状）
拒绝：AgentRouter / DeepSeek 已暴露该模式不可扩展；每新增一个 CDN 上游就要手工配置，
且丢掉压缩收益，治标不治本。

### 全局改写 Accept-Encoding = identity
拒绝：与「下游头透传、规则层决定」原则冲突；一刀切禁用所有压缩，
损失带宽收益，也不需要解决真实解码需求。

### 仅启用 gzip/deflate（不加 zstd/brotli 依赖）
拒绝：DeepSeek/EdgeOne 实测已返回 zstd；只支持 gzip 无法覆盖真实场景，
问题会原样复发。

## 参考

- Netty 4.1.132 `io.netty.handler.codec.http.HttpContentDecompressor`
  （默认 gzip/deflate；检测到 Zstd/Brotli 解码器类时支持对应编码）
- Reactor Netty 1.2.17 `HttpClient` 压缩配置
- DeepSeek 实机响应 `content-encoding: zstd`（`tools/live-test/deepseek-detail.json`）
- 前端 Provider 预设中的 AgentRouter 删头先例
