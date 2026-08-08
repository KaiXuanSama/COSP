package com.kaixuan.copilot_ollama_proxy.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallLogService;
import com.kaixuan.copilot_ollama_proxy.application.logging.ApiCallUsageService;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.usage.UsageTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段三验证与锁定：{@code AbstractUpstreamChatService} 的 usage 落库联动语义。
 *
 * <p>覆盖（写入时序 A，方案 a）：
 * <ul>
 *   <li>流式成功且有 usage → 写一行，log_id 关联阶段一自增 id，token 按存在性解析，ttfb 有值；</li>
 *   <li>非流式成功且有 usage → 写一行，ttfb 为 null（非流式无首字概念）；</li>
 *   <li>成功但无 usage → 不写（方案 a）；</li>
 *   <li>失败往返 → 不写；</li>
 *   <li>log 写入失败（返回 null）→ 仍写孤儿行（log_id=null，软链接容错）。</li>
 * </ul>
 */
class AbstractUpstreamChatServiceUsagePersistenceTests {

    private TestOpenAiService service;
    private ApiCallLogService logService;
    private ApiCallUsageService usageService;

    @BeforeEach
    void setUp() {
        service = new TestOpenAiService();
        logService = mock(ApiCallLogService.class);
        usageService = mock(ApiCallUsageService.class);
        service.setApiCallLog(logService);
        service.setApiCallUsage(usageService);
    }

    @Test
    void streamSuccessWithUsageWritesRowLinkedToLogIdWithTtfb() {
        when(logService.saveStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(4242L);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            Flux<DataBuffer> body = Flux.<DataBuffer>concat(
                    Mono.just(sseData(factory, "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}")),
                    // 尾 chunk：choices 为空、携带 usage（标准嵌套 cached）。
                    Mono.just(sseData(factory, "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":80}}}")))
                    .concatWith(Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        service.exposeChatCompletionStream(request, "model-a", provider()).blockLast(Duration.ofSeconds(20));

        var logIdCaptor = org.mockito.ArgumentCaptor.forClass(Long.class);
        var tokensCaptor = org.mockito.ArgumentCaptor.forClass(UsageTokens.class);
        var ttfbCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(usageService, times(1)).save(logIdCaptor.capture(), eq("stub"), eq("model-a"), eq(true),
                anyString(), tokensCaptor.capture(), ttfbCaptor.capture());

        assertThat(logIdCaptor.getValue()).isEqualTo(4242L);
        UsageTokens tokens = tokensCaptor.getValue();
        assertThat(tokens.promptTokens()).isEqualTo(100);
        assertThat(tokens.completionTokens()).isEqualTo(20);
        assertThat(tokens.cachedTokens()).isEqualTo(80);
        assertThat(ttfbCaptor.getValue()).isNotNull();
        assertThat(ttfbCaptor.getValue()).isGreaterThanOrEqualTo(0);
    }

    /**
     * 回归：纯思考响应（只有 reasoning_content，无任何 content）也必须测得首字。
     *
     * <p>首字语义为"首个 chunk 到达"，与 chunk 载荷形态无关。此前打点挂在
     * {@code contentEmitted}（仅正文非空才翻转）上，导致这类响应漏记 ttfb。
     */
    @Test
    void reasoningOnlyStreamStillRecordsTtfb() {
        when(logService.saveStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(11L);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            Flux<DataBuffer> body = Flux.<DataBuffer>concat(
                    // 只有思考内容，全程没有 delta.content
                    Mono.just(sseData(factory, "{\"id\":\"r1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"思考中\"},\"finish_reason\":null}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"r1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"r1\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":5}}")))
                    .concatWith(Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        service.exposeChatCompletionStream(request, "model-a", provider()).blockLast(Duration.ofSeconds(20));

        var ttfbCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(usageService, times(1)).save(any(), anyString(), anyString(), eq(true),
                anyString(), any(UsageTokens.class), ttfbCaptor.capture());

        assertThat(ttfbCaptor.getValue()).isNotNull();
        assertThat(ttfbCaptor.getValue()).isGreaterThanOrEqualTo(0);
    }

    /**
     * 回归：纯工具调用响应（只有 tool_calls，无任何 content）也必须测得首字。
     */
    @Test
    void toolCallOnlyStreamStillRecordsTtfb() {
        when(logService.saveStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(12L);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            Flux<DataBuffer> body = Flux.<DataBuffer>concat(
                    Mono.just(sseData(factory, "{\"id\":\"t1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"createFile\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"t1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"t1\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":7}}")))
                    .concatWith(Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        service.exposeChatCompletionStream(request, "model-a", provider()).blockLast(Duration.ofSeconds(20));

        var ttfbCaptor = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(usageService, times(1)).save(any(), anyString(), anyString(), eq(true),
                anyString(), any(UsageTokens.class), ttfbCaptor.capture());

        assertThat(ttfbCaptor.getValue()).isNotNull();
        assertThat(ttfbCaptor.getValue()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void nonStreamSuccessWithUsageWritesRowWithNullTtfb() {
        when(logService.saveNonStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(7L);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request ->
                Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"choices\":[{\"message\":{\"content\":\"hi\"}}],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":8}}")
                        .build())));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        service.exposeChatCompletion(request, "model-a", provider()).block(Duration.ofSeconds(20));

        var tokensCaptor = org.mockito.ArgumentCaptor.forClass(UsageTokens.class);
        verify(usageService, times(1)).save(eq(7L), eq("stub"), eq("model-a"), eq(false),
                anyString(), tokensCaptor.capture(), isNull());

        UsageTokens tokens = tokensCaptor.getValue();
        assertThat(tokens.promptTokens()).isEqualTo(50);
        assertThat(tokens.completionTokens()).isEqualTo(8);
        assertThat(tokens.cachedTokens()).isNull();
    }

    @Test
    void streamSuccessWithoutUsageDoesNotWrite() {
        when(logService.saveStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(1L);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            Flux<DataBuffer> body = Flux.<DataBuffer>concat(
                    Mono.just(sseData(factory, "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}")))
                    .concatWith(Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        service.exposeChatCompletionStream(request, "model-a", provider()).blockLast(Duration.ofSeconds(20));

        verify(usageService, never()).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void nonStreamFailureDoesNotWrite() {
        when(logService.saveNonStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(9L);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request ->
                Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"boom\"}").build())));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        catchThrowable(() -> service.exposeChatCompletion(request, "model-a", provider()).block(Duration.ofSeconds(20)));

        verify(usageService, never()).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
    }

    @Test
    void streamSuccessWritesOrphanRowWhenLogIdIsNull() {
        // 日志写入失败返回 null → usage 仍写孤儿行（log_id=null，软链接容错）。
        when(logService.saveStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(null);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            Flux<DataBuffer> body = Flux.<DataBuffer>concat(
                    Mono.just(sseData(factory, "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}")))
                    .concatWith(Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        service.exposeChatCompletionStream(request, "model-a", provider()).blockLast(Duration.ofSeconds(20));

        verify(usageService, times(1)).save(isNull(), eq("stub"), eq("model-a"), eq(true),
                anyString(), any(UsageTokens.class), any());
    }

    /**
     * 变更信号必须晚于用量写入 —— 消费者视角的 token 不得出现空窗。
     *
     * <p>信号是同步投递的，故「发布点在哪一行」就是可观测的时序边界。若沿用旧实现
     * （在日志 INSERT 内部发信号），前端收到通知去查时用量行尚未写入，
     * 那一行的 token 会短暂显示为空。这里用 InOrder 把顺序钉死。
     */
    @Test
    void callRecordedSignalIsPublishedAfterUsageWrite() {
        when(logService.saveStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(7L);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            Flux<DataBuffer> body = Flux.<DataBuffer>concat(
                    Mono.just(sseData(factory, "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}")),
                    Mono.just(sseData(factory, "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}")))
                    .concatWith(Mono.just(sseData(factory, "[DONE]")));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        service.exposeChatCompletionStream(request, "model-a", provider()).blockLast(Duration.ofSeconds(20));

        InOrder order = inOrder(logService, usageService);
        order.verify(logService).saveStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong());
        order.verify(usageService).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
        order.verify(logService).publishCallRecorded();
    }

    /**
     * 成功但上游未返回 usage：不写用量行，但仍须发信号。
     *
     * <p>这是 finally 语义的核心 —— 判据是「落库流程走完」而非「两张表都写了」。
     */
    @Test
    void signalIsPublishedEvenWhenNoUsageRowIsWritten() {
        when(logService.saveStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(8L);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            DefaultDataBufferFactory factory = new DefaultDataBufferFactory();
            Flux<DataBuffer> body = Mono.just(sseData(factory,
                            "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}"))
                    .concatWith(Mono.just(sseData(factory, "[DONE]")))
                    .cast(DataBuffer.class);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(body).build());
        }));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        service.exposeChatCompletionStream(request, "model-a", provider()).blockLast(Duration.ofSeconds(20));

        verify(usageService, never()).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
        verify(logService, times(1)).publishCallRecorded();
    }

    /**
     * 失败往返同样要发信号 —— 否则错误行永远不会实时出现在前端。
     *
     * <p>失败路径根本不走用量写入，若按「两张表都写了」判定，这些记录就没有信号。
     * 而错误行恰恰是最需要立刻看到的。
     */
    @Test
    void signalIsPublishedForFailedRoundTrip() {
        when(logService.saveNonStream(anyString(), anyString(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(9L);

        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request ->
                Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"boom\"}").build())));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "model-a");
        catchThrowable(() -> service.exposeChatCompletion(request, "model-a", provider()).block(Duration.ofSeconds(20)));

        verify(usageService, never()).save(any(), anyString(), anyString(), anyBoolean(), any(), any(), any());
        // 每个落库分支各发一次：500 会被重试，故信号数等于往返次数，此处只断言"至少发过"。
        verify(logService, org.mockito.Mockito.atLeastOnce()).publishCallRecorded();
    }

    /** 把一段 JSON 包装成 SSE data 帧的 DataBuffer。 */
    private static DataBuffer sseData(DefaultDataBufferFactory factory, String json) {
        byte[] bytes = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
        return factory.wrap(bytes);
    }

    private ProviderRuntimeConfiguration provider() {
        return new ProviderRuntimeConfiguration("stub", "", "", List.of());
    }

    private static final class TestOpenAiService extends AbstractUpstreamChatService {

        private TestOpenAiService() {
            super(new ObjectMapper(), "default-model", new ProviderRequestHeaderService(new ObjectMapper()));
        }

        private Flux<String> exposeChatCompletionStream(Map<String, Object> request, String model,
                                                        ProviderRuntimeConfiguration provider) {
            return chatCompletionStream(request, model, provider, HttpHeaders.EMPTY, null);
        }

        private Mono<String> exposeChatCompletion(Map<String, Object> request, String model,
                                                  ProviderRuntimeConfiguration provider) {
            return chatCompletion(request, model, provider, HttpHeaders.EMPTY, null);
        }

        @Override
        protected String defaultBaseUrl() {
            return "https://example.com";
        }

        /** 退避压成毫秒级：本类验证落库联动，不验证等待时长。理由同姊妹测试类。 */
        @Override
        protected Duration retryFirstBackoff() {
            return Duration.ofMillis(5);
        }

        @Override
        protected Duration retryMaxBackoff() {
            return Duration.ofMillis(20);
        }

        @Override
        protected String chatCompletionsUri() {
            return "/v1/chat/completions";
        }

        @Override
        protected void customizeRequestBody(Map<String, Object> body, String resolvedModel,
                                            ProviderRuntimeConfiguration provider) {
            body.put("customized", true);
        }
    }
}
