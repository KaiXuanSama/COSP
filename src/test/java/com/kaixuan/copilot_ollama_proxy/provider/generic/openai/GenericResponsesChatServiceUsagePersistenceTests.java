package com.kaixuan.copilot_ollama_proxy.provider.generic.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.config.RetryPolicyService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.provider.ChunkLogPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Responses 线路的<strong>落库联动</strong>语义：{@code api_call_log} + {@code api_call_usage}
 * 到底写了什么、写了几条、protocol 列填的什么。
 *
 * <h2>为什么需要这个类</h2>
 * {@code GenericResponsesChatServiceTests} 验证的是「调用路径通」——
 * 它只注入了 usage 替身，{@code apiCallLog} 始终为 {@code null}，
 * 于是三条 {@code saveXxxLog} 分支全部走早退，<strong>一条都没被执行过</strong>。
 * 落库是静默的：写错了不报错、不抛异常，只是管理后台的日志页少东西或字段不对，
 * 而那正是平时没人会逐条核对的地方。
 *
 * <p>类的形状照 {@code AbstractUpstreamChatServiceUsagePersistenceTests}（Chat 侧）
 * 那套写：mock 两个领域接口，靠 {@code ArgumentCaptor} 断言真正传下去的参数。
 *
 * <h2>两个重载的坑</h2>
 * {@code ApiCallLogService} 的带协议重载都是 {@code default} 方法，而 Mockito mock 接口时
 * <strong>不执行 default 实现</strong>。因此必须 stub 实际被调用的那个
 * {@code ChunkLogPayload} / 10 参版本，stub 短版本会被静默忽略（返回 null）。
 */
class GenericResponsesChatServiceUsagePersistenceTests {

    /** 默认预算下每轮空响应都会各写一条日志，因此总条数是首发加上重试。 */
    private static final int ATTEMPTS_ON_DEFAULT_BUDGET = 1 + RetryPolicyService.DEFAULT_MAX_ATTEMPTS;

    private ApiCallLogService logService;
    private ApiCallUsageService usageService;

    @BeforeEach
    void setUp() {
        logService = mock(ApiCallLogService.class);
        usageService = mock(ApiCallUsageService.class);
    }

    // ==================== 非流式 ====================

    /**
     * 成功往返：日志与用量各一行，用量用日志的自增 id 做软链接。
     *
     * <p>{@code ttfb} 必须是 {@code null} —— 非流式没有首字概念，随便填一个
     * 会让日志页显示一个编造的首字耗时。
     */
    @Test
    void nonStreamSuccessWritesLogAndUsageLinkedByLogId() {
        when(logService.saveNonStream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyInt(), any(), anyLong())).thenReturn(4242L);
        TestService service = stubService(jsonBody(okBody()));

        service.exposeResponses(newRequest(), route()).block(Duration.ofSeconds(10));

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(logService).saveNonStream(eq("oai"), eq("gpt-5"),
                eq("RESPONSES"), eq("RESPONSES"),
                any(), body.capture(), any(), eq(200), eq(okBody()), anyLong());
        // 落库的请求体是真正发出去的那份，不是下游原文 —— 否则日志解释不了实际行为。
        assertThat(body.getValue()).containsEntry("stream", false);

        ArgumentCaptor<UsageTokens> tokens = ArgumentCaptor.forClass(UsageTokens.class);
        verify(usageService).save(eq(4242L), eq("oai"), eq("gpt-5"), eq(false),
                anyString(), tokens.capture(), isNull());
        assertThat(tokens.getValue().promptTokens()).isEqualTo(10);
    }

    /**
     * 成功但上游没给 usage：只写日志，不写用量行。
     *
     * <p>凭空补一行 token 全零的记录会污染用量统计 —— 那不是「这次调用用了 0 个 token」，
     * 而是「上游没说」。这个区别正是两个 token 列允许为 null 的理由。
     */
    @Test
    void nonStreamSuccessWithoutUsageWritesLogOnly() {
        TestService service = stubService(jsonBody(okBodyWithoutUsage()));

        service.exposeResponses(newRequest(), route()).block(Duration.ofSeconds(10));

        verify(logService).saveNonStream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyInt(), any(), anyLong());
        verify(usageService, never()).save(any(), anyString(), anyString(),
                anyBoolean(), any(), any(), any());
    }

    /**
     * 失败往返：日志记下上游状态码与错误体，不写用量。
     *
     * <p>401 刻意选来验证另一件事 —— 它不可重试，因此日志只有一条，
     * 「每轮往返写一条」的语义在这种情形下不会让人误以为漏记了。
     */
    @Test
    void nonStreamUpstreamErrorWritesErrorLogWithoutUsage() {
        String errorBody = "{\"error\":{\"message\":\"bad key\"}}";
        TestService service = stubService(jsonBody(HttpStatus.UNAUTHORIZED, errorBody));

        // 错误会从 block 抛出（401 不可重试），而本用例关注的是落库副作用而非异常本身。
        catchThrowable(() -> service.exposeResponses(newRequest(), route()).block(Duration.ofSeconds(10)));

        verify(logService).saveNonStream(eq("oai"), eq("gpt-5"),
                eq("RESPONSES"), eq("RESPONSES"),
                any(), any(), any(), eq(401), eq(errorBody), anyLong());
        verify(usageService, never()).save(any(), anyString(), anyString(),
                anyBoolean(), any(), any(), any());
    }

    /**
     * 日志写入失败（返回 null）不影响用量落库 —— 用量行以孤儿身份存活。
     *
     * <p>{@code api_call_usage} 独立成表就是为了让 token 记录不受日志裁剪影响，
     * 那么「日志写失败」当然也不该连累它。
     */
    @Test
    void nonStreamLogWriteFailureStillWritesOrphanUsageRow() {
        when(logService.saveNonStream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyInt(), any(), anyLong())).thenReturn(null);
        TestService service = stubService(jsonBody(okBody()));

        service.exposeResponses(newRequest(), route()).block(Duration.ofSeconds(10));

        verify(usageService).save(isNull(), eq("oai"), eq("gpt-5"), eq(false),
                anyString(), any(UsageTokens.class), isNull());
    }

    // ==================== 流式 ====================

    /**
     * 流式成功：日志记下已下发的事件，用量带上首字耗时。
     *
     * <p>{@code ttfb} 是这条线路上唯一能测到首字的地方（非流式恒 null），
     * 空事件流下它是负数会被转成 null —— 所以用例必须给一个真会产出事件的响应。
     */
    @Test
    void streamSuccessWritesLogWithChunksAndUsageWithTtfb() {
        when(logService.saveStream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyInt(), any(ChunkLogPayload.class), anyLong())).thenReturn(77L);
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(sseResponse(Flux.just(
                sse(factory, "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}"),
                sse(factory, """
                        {"type":"response.completed","response":{"id":"r",\
                        "usage":{"input_tokens":10,"output_tokens":2}}}"""))));

        service.exposeResponsesStream(newRequest(), route()).collectList().block(Duration.ofSeconds(10));

        ArgumentCaptor<ChunkLogPayload> chunks = ArgumentCaptor.forClass(ChunkLogPayload.class);
        verify(logService).saveStream(eq("oai"), eq("gpt-5"),
                eq("RESPONSES"), eq("RESPONSES"),
                any(), any(), any(), eq(200), chunks.capture(), anyLong());
        // 直连路线下下游收到的就是上游事件，因此上游那一份等于下游那一份。
        assertThat(chunks.getValue().translated()).hasSize(2);
        assertThat(chunks.getValue().translated().get(0)).contains("output_text.delta");

        ArgumentCaptor<Integer> ttfb = ArgumentCaptor.forClass(Integer.class);
        verify(usageService).save(eq(77L), eq("oai"), eq("gpt-5"), eq(true),
                anyString(), any(UsageTokens.class), ttfb.capture());
        assertThat(ttfb.getValue()).isNotNull().isGreaterThanOrEqualTo(0);
    }

    /**
     * 上游返回错误响应：只写一条错误日志，<strong>不</strong>再写一条普通日志。
     *
     * <p>错误响应在 {@code exchangeToFlux} 分支里落库，随后的 {@code doOnError}
     * 用 {@code findWebResponseException != null} 排除自己。少了那道排除就会双记 ——
     * 日志页上同一次调用出现两条，且其中一条的 chunks 为空。
     */
    @Test
    void streamUpstreamErrorWritesErrorLogExactlyOnce() {
        String errorBody = "{\"error\":{\"message\":\"bad key\"}}";
        TestService service = stubService(jsonBody(HttpStatus.UNAUTHORIZED, errorBody));

        catchThrowable(() -> service.exposeResponsesStream(newRequest(), route())
                .collectList().block(Duration.ofSeconds(10)));

        verify(logService).saveStreamWithError(eq("oai"), eq("gpt-5"),
                eq("RESPONSES"), eq("RESPONSES"),
                any(), any(), any(), eq(401), any(ChunkLogPayload.class),
                any(), eq(401), eq(errorBody), anyLong());
        verify(logService, never()).saveStream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyInt(), any(ChunkLogPayload.class), anyLong());
    }

    /**
     * 空响应重试耗尽：每轮各写一条日志，此后<strong>不再</strong>补写收尾那一条。
     *
     * <p>「每轮上游往返都写一条 {@code api_call_log}」是既定语义，因此条数等于
     * 首发加重试。真正的看点是最后那个 {@code emptyResponsePassthrough} 标记 ——
     * 耗尽后放行的事件会以 ON_COMPLETE 收场，若没有那个标记，
     * {@code doFinally} 会再补一条内容重复的日志。
     */
    @Test
    void streamEmptyResponseWritesOneLogPerAttemptAndNoneAfterPassthrough() {
        when(logService.saveStream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyInt(), any(ChunkLogPayload.class), anyLong())).thenReturn(1L);
        TestService service = stubService(sseResponse(Flux.just(
                sse(new DefaultDataBufferFactory(), "{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}"))));

        service.exposeResponsesStream(newRequest(), route()).collectList().block(Duration.ofSeconds(10));

        verify(logService, times(ATTEMPTS_ON_DEFAULT_BUDGET)).saveStream(anyString(), anyString(),
                anyString(), anyString(), any(), any(), any(), anyInt(),
                any(ChunkLogPayload.class), anyLong());
        verify(usageService, never()).save(any(), anyString(), anyString(),
                anyBoolean(), any(), any(), any());
    }

    /**
     * 日志里的 chunk 是上游 {@code data} 原文，逐字节，且<strong>不含</strong> SSE 的
     * {@code event:} 名。
     *
     * <p>这决定了两件事：
     * <ol>
     *   <li>排查时日志与下游收到的内容<strong>同源</strong> —— 看日志等于看下游看到了什么；</li>
     *   <li>事件名不另存一列，而是靠 JSON 的 {@code type} 字段还原。
     *       实测两家上游 363 个事件的 {@code event:} 与 {@code type} 完全一致，因此可还原。</li>
     * </ol>
     *
     * <p>载荷刻意打乱键顺序并加上多余空格：若日志走的是「解析后重新序列化」，
     * 这条会失败。Chat 侧确实是那样做的（{@code normalizeUpstreamChunk}），
     * 因此本用例也是「Responses 没有继承那个行为」的证据。
     */
    @Test
    void 日志记录上游data原文而非事件名() {
        String payload = "{\"type\":\"response.output_text.delta\",\"z_first\":1,\"delta\":\"hi\"}";
        when(logService.saveStream(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyInt(), any(ChunkLogPayload.class), anyLong())).thenReturn(1L);
        DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
        TestService service = stubService(sseResponse(Flux.just(factory.wrap(
                ("event: upstream.custom.name\ndata: " + payload + "\n\n")
                        .getBytes(StandardCharsets.UTF_8)))));

        service.exposeResponsesStream(newRequest(), route()).collectList().block(Duration.ofSeconds(10));

        ArgumentCaptor<ChunkLogPayload> chunks = ArgumentCaptor.forClass(ChunkLogPayload.class);
        verify(logService).saveStream(eq("oai"), eq("gpt-5"), eq("RESPONSES"), eq("RESPONSES"),
                any(), any(), any(), eq(200), chunks.capture(), anyLong());
        assertThat(chunks.getValue().translated()).containsExactly(payload);
        assertThat(String.join("", chunks.getValue().translated()))
                .doesNotContain("upstream.custom.name");
    }

    /**
     * 「记录已就绪」信号在<strong>没有</strong>用量行时也要发出。
     *
     * <p>这是 finally 语义的体现：失败调用与上游未返回 usage 的调用本就不写用量行，
     * 若按「两张表都写了」判定，这些记录永远不会实时出现在前端 ——
     * 表现为日志页只在成功调用后才刷新。
     */
    @Test
    void callRecordedSignalIsEmittedEvenWithoutUsageRow() {
        TestService service = stubService(jsonBody(okBodyWithoutUsage()));

        service.exposeResponses(newRequest(), route()).block(Duration.ofSeconds(10));

        verify(logService).publishCallRecorded();
    }

    /** 失败往返同样发出该信号 —— 失败调用也要能实时出现在日志页上。 */
    @Test
    void callRecordedSignalIsEmittedOnFailure() {
        TestService service = stubService(jsonBody(HttpStatus.UNAUTHORIZED, "{\"error\":{}}"));

        catchThrowable(() -> service.exposeResponses(newRequest(), route()).block(Duration.ofSeconds(10)));

        verify(logService).publishCallRecorded();
    }

    // ==================== 辅助 ====================

    private TestService stubService(Function<ClientRequest, Mono<ClientResponse>> handler) {
        TestService service = new TestService();
        service.setApiCallLog(logService);
        service.setApiCallUsage(usageService);
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(handler::apply));
        return service;
    }

    /** 非流式响应 stub。 */
    private static Function<ClientRequest, Mono<ClientResponse>> jsonBody(String body) {
        return jsonBody(HttpStatus.OK, body);
    }

    private static Function<ClientRequest, Mono<ClientResponse>> jsonBody(HttpStatus status, String body) {
        return request -> Mono.just(ClientResponse.create(status)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
    }

    /** SSE 响应 stub。 */
    private static Function<ClientRequest, Mono<ClientResponse>> sseResponse(Flux<DataBuffer> body) {
        return request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(body).build());
    }

    private static DataBuffer sse(DefaultDataBufferFactory factory, String json) {
        return factory.wrap(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private static String okBody() {
        return """
                {"id":"resp_1","output":[{"type":"message","role":"assistant",\
                "content":[{"type":"output_text","text":"hello"}]}],\
                "usage":{"input_tokens":10,"output_tokens":2}}""";
    }

    private static String okBodyWithoutUsage() {
        return """
                {"id":"resp_1","output":[{"type":"message","role":"assistant",\
                "content":[{"type":"output_text","text":"hello"}]}]}""";
    }

    private static Map<String, Object> newRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "gpt-5");
        request.put("input", "hi");
        return request;
    }

    private static ResolvedProviderRoute route() {
        return new ResolvedProviderRoute(
                new ProviderRuntimeConfiguration("oai", "http://upstream.invalid", "test-key", List.of()),
                "gpt-5", "[oai] gpt-5");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    /**
     * 测试子类：暴露受保护入口、压缩退避。
     *
     * <p>这里不复用 {@code GenericResponsesChatServiceTests} 的那个子类 ——
     * 嵌套私有类无法跨文件共享，而抽成独立夹具文件只为省十行不划算。
     *
     * <p>两个入口返回<strong>未订阅</strong>的 Publisher，与那个子类同一形状：
     * 失败用例需要 {@code catchThrowable} 包裹 {@code block} 才能断言落库副作用
     * （关注的是「写了什么」而非「抛了什么」），内部替你订阅就挡住了那个写法。
     */
    private static final class TestService extends GenericResponsesChatService {

        private TestService() {
            super(new ObjectMapper(), new ProviderRequestHeaderService(new ObjectMapper()),
                    new RequestBodyRuleEngine(new ObjectMapper()));
        }

        private Mono<String> exposeResponses(Map<String, Object> request, ResolvedProviderRoute route) {
            return responses(request, route, HttpHeaders.EMPTY, "req-persist");
        }

        private Flux<String> exposeResponsesStream(Map<String, Object> request, ResolvedProviderRoute route) {
            return responsesStream(request, route, HttpHeaders.EMPTY, "req-persist");
        }

        @Override
        protected Duration retryFirstBackoff() {
            return Duration.ofMillis(5);
        }

        @Override
        protected Duration retryMaxBackoff() {
            return Duration.ofMillis(20);
        }
    }
}
