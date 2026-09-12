package com.kaixuan.copilot_ollama_proxy.api.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.openai.ResponsesService;
import com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.ApiUsageCollector;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.ResponsesRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@code POST /v1/responses} 的请求还原、事件下发与错误分类。
 *
 * <p>直接实例化控制器、只 mock {@link ResponsesService}，与
 * {@code OpenAiControllerCancellationTests} 同一范式：真实使用
 * {@link CallLifecyclePublisher} 与 {@link CallCancellationRegistry}，
 * 因为生命周期事件正是要断言的东西之一。
 *
 * <h2>这条线路上最容易坏、且坏了不报错的两件事</h2>
 * <ol>
 *   <li><strong>{@code event:} 名没回填。</strong>Responses 客户端是状态机，
 *       靠事件名驱动。只发 {@code data:} 时下游收得到字节但解析不出任何东西 ——
 *       表现为「一直转圈」而非报错。</li>
 *   <li><strong>未建模字段没透传。</strong>DTO 只建模了三个字段，
 *       其余全靠 {@code additionalProperties}。漏了的症状是用户配的
 *       {@code tools} / {@code instructions} 静默消失。</li>
 * </ol>
 */
class ResponsesControllerTests {

    private final ResponsesService responsesService = mock(ResponsesService.class);
    private final ApiUsageCollector apiUsageCollector = mock(ApiUsageCollector.class);
    private final CallLifecyclePublisher lifecyclePublisher = new CallLifecyclePublisher();
    private final CallCancellationRegistry cancellationRegistry = new CallCancellationRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ResponsesController newController() {
        return new ResponsesController(responsesService, objectMapper, apiUsageCollector,
                lifecyclePublisher, cancellationRegistry);
    }

    // ==================== 请求还原 ====================

    @Nested
    class 请求还原 {

        /**
         * 未建模字段原样进出站请求体。
         *
         * <p>DTO 只建模 {@code model} / {@code input} / {@code stream} 三个字段是刻意的：
         * Responses 的请求字段又多又在演进，逐个建模等于维护一份必然过期的白名单。
         * 本用例钉住那个「不建模」的兑现方式。
         */
        @Test
        void unmodeledFieldsArePassedThrough() throws Exception {
            ResponsesRequest request = objectMapper.readValue("""
                    {"model":"gpt-5","input":"hi","instructions":"be brief",
                     "tools":[{"type":"web_search"}],"include":["reasoning.encrypted_content"],
                     "truncation":"auto"}""", ResponsesRequest.class);
            given(responsesService.responses(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.just("{\"id\":\"resp_1\"}"));

            newController().responses(request, HttpHeaders.EMPTY).block(Duration.ofSeconds(5));

            Map<String, Object> body = capturedBody();
            assertThat(body).containsEntry("instructions", "be brief")
                    .containsEntry("truncation", "auto")
                    .containsKeys("tools", "include");
        }

        /**
         * {@code input} 的两种形态都原样透传。
         *
         * <p>它在协议里既可以是纯字符串也可以是结构化数组，因此 DTO 用 {@code Object}。
         * 收窄成 {@code String} 或 {@code List} 都会让另一种形态在反序列化时就失败。
         */
        @Test
        void inputAcceptsBothStringAndArrayForms() throws Exception {
            given(responsesService.responses(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.just("{\"id\":\"resp_1\"}"));

            ResponsesRequest stringForm = objectMapper.readValue(
                    "{\"model\":\"gpt-5\",\"input\":\"hi\"}", ResponsesRequest.class);
            newController().responses(stringForm, HttpHeaders.EMPTY).block(Duration.ofSeconds(5));
            assertThat(capturedBody().get("input")).isEqualTo("hi");

            ResponsesRequest arrayForm = objectMapper.readValue("""
                    {"model":"gpt-5","input":[{"role":"user","content":[
                     {"type":"input_text","text":"hi"}]}]}""", ResponsesRequest.class);
            newController().responses(arrayForm, HttpHeaders.EMPTY).block(Duration.ofSeconds(5));
            assertThat(capturedBody().get("input")).isInstanceOf(List.class);
        }

        /**
         * {@code stream} 不进出站请求体 —— 由上游服务按入口决定。
         *
         * <p>控制器只用它选分支。带过去会让「下游说 true 但走了非流式分支」这种
         * 不一致状态传到上游。
         */
        @Test
        void streamFlagIsNotForwardedInBody() throws Exception {
            ResponsesRequest request = objectMapper.readValue(
                    "{\"model\":\"gpt-5\",\"input\":\"hi\",\"stream\":false}", ResponsesRequest.class);
            given(responsesService.responses(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.just("{\"id\":\"resp_1\"}"));

            newController().responses(request, HttpHeaders.EMPTY).block(Duration.ofSeconds(5));

            assertThat(capturedBody()).doesNotContainKey("stream");
        }

        /** 未知字段不导致反序列化失败 —— 协议在演进，收到新字段是常态。 */
        @Test
        void unknownFieldsDoNotBreakDeserialization() throws Exception {
            ResponsesRequest request = objectMapper.readValue(
                    "{\"model\":\"gpt-5\",\"input\":\"hi\",\"some_future_field\":123}",
                    ResponsesRequest.class);

            assertThat(request.getModel()).isEqualTo("gpt-5");
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> capturedBody() {
            var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
            org.mockito.Mockito.verify(responsesService, org.mockito.Mockito.atLeastOnce())
                    .responses(captor.capture(), anyString(), any(HttpHeaders.class), anyString());
            return (Map<String, Object>) captor.getValue();
        }
    }

    // ==================== 流式下发 ====================

    @Nested
    class 流式下发 {

        /**
         * <strong>每个事件的 {@code event:} 名从 JSON 的 {@code type} 回填。</strong>
         *
         * <p>Responses 客户端靠事件名驱动状态机。缺了它下游收得到字节却解析不出内容 ——
         * 表现为一直转圈而非报错，是这条线路上最难定位的一类故障。
         */
        @Test
        void eventNameIsBackfilledFromTypeField() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just(
                            "{\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}",
                            "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}",
                            "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}"));

            List<ServerSentEvent<String>> events = streamEvents(streamRequest());

            assertThat(events).extracting(ServerSentEvent::event)
                    .containsExactly("response.created", "response.output_text.delta",
                            "response.completed");
        }

        /** data 部分是上游原文，不改写。 */
        @Test
        void eventDataIsForwardedVerbatim() {
            String raw = "{\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}";
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just(raw));

            assertThat(streamEvents(streamRequest()).get(0).data()).isEqualTo(raw);
        }

        /** 非 JSON 事件仍下发，只是没有 event 名 —— 不因解析失败丢帧。 */
        @Test
        void nonJsonEventIsStillForwardedWithoutEventName() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just("not-json"));

            List<ServerSentEvent<String>> events = streamEvents(streamRequest());

            assertThat(events).hasSize(1);
            assertThat(events.get(0).event()).isNull();
            assertThat(events.get(0).data()).isEqualTo("not-json");
        }

        /**
         * 终态事件不计入 CHUNK 计数。
         *
         * <p>它是协议终止标记而非内容。计进去会让 Toast 显示的事件数比实际内容多一个 ——
         * 一个不影响功能但会让人怀疑数据准确性的偏差。
         */
        @Test
        void terminalEventIsNotCountedAsChunk() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just(
                            "{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}",
                            "{\"type\":\"response.output_text.delta\",\"delta\":\"b\"}",
                            "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}"));

            List<CallLifecycleEvent> lifecycle = collectLifecycle(() -> streamEvents(streamRequest()));

            assertThat(lifecycle).filteredOn(e -> e.phase() == CallPhase.CHUNK).hasSize(2);
            assertThat(lifecycle).filteredOn(e -> e.phase() == CallPhase.COMPLETED)
                    .singleElement()
                    .satisfies(e -> assertThat(e.chunkCount()).isEqualTo(2));
        }

        /**
         * 终态事件缺失时靠上游关连接兜底，COMPLETED 仍只发一次。
         *
         * <p>两层判定 + CAS 去重的意义：Layer 1 让不关连接的上游也能收尾，
         * Layer 2 让不发终态事件的上游也能收尾，两者同时发生时不重复。
         */
        @Test
        void completionFallsBackToStreamCloseWhenTerminalEventMissing() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just("{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}"));

            List<CallLifecycleEvent> lifecycle = collectLifecycle(() -> streamEvents(streamRequest()));

            assertThat(lifecycle).filteredOn(e -> e.phase() == CallPhase.COMPLETED).hasSize(1);
        }

        /** 收到终态事件后即便上游不关连接也已 COMPLETED，且只发一次。 */
        @Test
        void completedIsEmittedOnceEvenWhenBothLayersWouldFire() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just(
                            "{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}",
                            "{\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}"));

            List<CallLifecycleEvent> lifecycle = collectLifecycle(() -> streamEvents(streamRequest()));

            assertThat(lifecycle).filteredOn(e -> e.phase() == CallPhase.COMPLETED).hasSize(1);
        }

        /**
         * {@code response.failed} 也是终态。
         *
         * <p>只认 {@code response.completed} 会让失败的流永远收不到收尾 ——
         * Toast 悬挂在「已产生 N 个事件」。清单集中在 {@code ResponsesStreamEvents}，
         * 那里也是空响应判定的终态来源。
         */
        @Test
        void failedEventIsAlsoTerminal() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just(
                            "{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}",
                            "{\"type\":\"response.failed\",\"response\":{\"id\":\"r\"}}"));

            List<CallLifecycleEvent> lifecycle = collectLifecycle(() -> streamEvents(streamRequest()));

            assertThat(lifecycle).filteredOn(e -> e.phase() == CallPhase.COMPLETED)
                    .singleElement()
                    .satisfies(e -> assertThat(e.chunkCount()).isEqualTo(1));
        }
    }

    // ==================== 错误分类 ====================

    @Nested
    class 错误分类 {

        /** 跨协议未实现是本地配置问题，400 而非 502。 */
        @Test
        void translationNotSupportedBecomes400() {
            given(responsesService.responses(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.error(new ProtocolTranslationNotSupportedException(
                            "relay-x", WireProtocol.RESPONSES, WireProtocol.CHAT)));

            ResponseEntity<?> response = newController()
                    .responses(nonStreamRequest(), HttpHeaders.EMPTY).block(Duration.ofSeconds(5));

            assertThat(response).isNotNull();
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(errorMessageOf(response)).contains("relay-x");
        }

        /** 一个协议都没勾同样是 400，不能落进 502 兜底。 */
        @Test
        void noSupportedProtocolBecomes400() {
            given(responsesService.responses(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.error(new NoSupportedProtocolException("relay-x")));

            ResponseEntity<?> response = newController()
                    .responses(nonStreamRequest(), HttpHeaders.EMPTY).block(Duration.ofSeconds(5));

            assertThat(response).isNotNull();
            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        /** 上游状态码与错误体原样透传，不包一层自己的解释。 */
        @Test
        void upstreamErrorStatusAndBodyArePassedThrough() {
            String upstreamBody = "{\"error\":{\"message\":\"model not found\",\"type\":\"invalid_request_error\"}}";
            given(responsesService.responses(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.error(WebClientResponseException.create(
                            404, "Not Found", HttpHeaders.EMPTY, upstreamBody.getBytes(), null)));

            ResponseEntity<?> response = newController()
                    .responses(nonStreamRequest(), HttpHeaders.EMPTY).block(Duration.ofSeconds(5));

            assertThat(response).isNotNull();
            assertThat(response.getStatusCode().value()).isEqualTo(404);
            assertThat(response.getBody()).isEqualTo(upstreamBody);
        }

        /** 连接类失败落 502。 */
        @Test
        void connectionFailureBecomes502() {
            given(responsesService.responses(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.error(new IllegalStateException("connection reset")));

            ResponseEntity<?> response = newController()
                    .responses(nonStreamRequest(), HttpHeaders.EMPTY).block(Duration.ofSeconds(5));

            assertThat(response).isNotNull();
            assertThat(response.getStatusCode().value()).isEqualTo(502);
        }

        /**
         * 错误体是 OpenAI 形态，<strong>不带</strong> Anthropic 那个外层 {@code "type":"error"}。
         *
         * <p>Responses 与 Chat 同为 OpenAI 系，客户端的错误解析代码通常共用。
         * 照抄 Anthropic 的形态会让那些代码取不到 {@code error.message}。
         */
        @Test
        void errorBodyUsesOpenAiShape() throws Exception {
            given(responsesService.responses(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.error(new IllegalStateException("boom")));

            ResponseEntity<?> response = newController()
                    .responses(nonStreamRequest(), HttpHeaders.EMPTY).block(Duration.ofSeconds(5));

            assertThat(response).isNotNull();
            JsonNode body = new ObjectMapper().readTree((String) response.getBody());
            assertThat(body.path("error").path("type").asText()).isEqualTo("api_error");
            // 顶层不该有 type —— 那是 Anthropic 的形态。
            assertThat(body.has("type")).isFalse();
        }

        /**
         * 流式错误以 {@code event: error} 帧下发，而非 500 JSON。
         *
         * <p>流式响应的状态码在第一帧就提交了，之后再改状态码没有意义；
         * 客户端只能通过事件名识别失败。
         */
        @Test
        void streamErrorIsSentAsErrorEvent() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.error(new ProtocolTranslationNotSupportedException(
                            "relay-x", WireProtocol.RESPONSES, WireProtocol.MESSAGES)));

            List<ServerSentEvent<String>> events = streamEvents(streamRequest());

            assertThat(events).singleElement()
                    .satisfies(event -> {
                        assertThat(event.event()).isEqualTo("error");
                        assertThat(event.data()).contains("relay-x");
                    });
        }

        /** 错误体里的引号与换行不能把错误体本身变成非法 JSON。 */
        @Test
        void errorMessageWithQuotesStaysValidJson() throws Exception {
            given(responsesService.responses(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.error(new NoSupportedProtocolException("relay\"x\nbroken")));

            ResponseEntity<?> response = newController()
                    .responses(nonStreamRequest(), HttpHeaders.EMPTY).block(Duration.ofSeconds(5));

            assertThat(response).isNotNull();
            // 解析不抛即证明转义正确。
            assertThat(new ObjectMapper().readTree((String) response.getBody())
                    .path("error").path("message").asText()).contains("broken");
        }

        private String errorMessageOf(ResponseEntity<?> response) {
            try {
                return new ObjectMapper().readTree((String) response.getBody())
                        .path("error").path("message").asText();
            } catch (Exception exception) {
                throw new AssertionError("错误体不是合法 JSON: " + response.getBody(), exception);
            }
        }
    }

    // ==================== 辅助 ====================

    private ResponsesRequest nonStreamRequest() {
        ResponsesRequest request = new ResponsesRequest();
        request.setModel("gpt-5");
        request.setInput("hi");
        return request;
    }

    private ResponsesRequest streamRequest() {
        ResponsesRequest request = nonStreamRequest();
        request.setStream(true);
        return request;
    }

    /**
     * 取出流式响应体里的 SSE 事件。
     *
     * <p>用 {@code take(n)} 会引入「预期几个事件」这个额外假设；这里靠上游 Flux 自然完成，
     * 心跳则因 {@code takeUntilOther(streamEnd)} 随之停止。5 秒超时只是防挂死的护栏。
     */
    @SuppressWarnings("unchecked")
    private List<ServerSentEvent<String>> streamEvents(ResponsesRequest request) {
        ResponseEntity<?> response = newController().responses(request, HttpHeaders.EMPTY)
                .block(Duration.ofSeconds(5));
        assertThat(response).isNotNull();
        Flux<ServerSentEvent<String>> body = (Flux<ServerSentEvent<String>>) response.getBody();
        assertThat(body).isNotNull();
        List<ServerSentEvent<String>> collected = body.collectList().block(Duration.ofSeconds(5));
        // 心跳是注释帧（data 与 event 都为 null），不属于协议事件，过滤掉。
        List<ServerSentEvent<String>> result = new ArrayList<>();
        for (ServerSentEvent<String> event : collected == null ? List.<ServerSentEvent<String>>of() : collected) {
            if (event.data() != null) {
                result.add(event);
            }
        }
        return result;
    }

    /** 收集一次调用期间发出的生命周期事件。 */
    private List<CallLifecycleEvent> collectLifecycle(Runnable action) {
        List<CallLifecycleEvent> events = new CopyOnWriteArrayList<>();
        var subscription = lifecyclePublisher.events().subscribe(events::add);
        try {
            action.run();
        } finally {
            subscription.dispose();
        }
        return events;
    }
}
