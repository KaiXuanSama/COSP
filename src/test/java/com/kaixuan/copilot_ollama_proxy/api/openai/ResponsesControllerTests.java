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

import java.nio.charset.StandardCharsets;
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
         * {@code response.failed} 收尾成 {@code FAILED}，而不是「完成」。
         *
         * <h2>这条用例此前把缺陷钉成了正确行为</h2>
         * 它原名 {@code failedEventIsAlsoTerminal}，断言的是
         * {@code phase == COMPLETED} —— 出发点没错（失败的流也必须收尾，否则 Toast
         * 悬挂在「已产生 N 个事件」），但顺手把「结局显示成完成」也固定下来了。
         * 上游明确说「我失败了」，界面却显示成功，用户只能靠「回答是空的」间接察觉。
         *
         * <p>两个断言现在分开表达：<strong>要收尾</strong>（不悬挂），
         * 且<strong>结局要对</strong>（不是 COMPLETED）。
         */
        @Test
        void failedEventFinalizesAsFailed() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just(
                            "{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}",
                            "{\"type\":\"response.failed\",\"response\":{\"id\":\"r\"}}"));

            List<CallLifecycleEvent> lifecycle = collectLifecycle(() -> streamEvents(streamRequest()));

            assertThat(lifecycle).filteredOn(e -> e.phase() == CallPhase.FAILED)
                    .singleElement()
                    .satisfies(e -> assertThat(e.chunkCount()).isEqualTo(1));
            // 不能同时发 COMPLETED —— 那会让前端按先到的那个渲染，结果不确定。
            assertThat(lifecycle).noneMatch(e -> e.phase() == CallPhase.COMPLETED);
        }

        /** 无前缀的 {@code error} 事件同样收尾成 {@code FAILED}。 */
        @Test
        void bareErrorEventFinalizesAsFailed() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just(
                            "{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}",
                            "{\"type\":\"error\",\"message\":\"upstream blew up\"}"));

            List<CallLifecycleEvent> lifecycle = collectLifecycle(() -> streamEvents(streamRequest()));

            assertThat(lifecycle).anyMatch(e -> e.phase() == CallPhase.FAILED);
            assertThat(lifecycle).noneMatch(e -> e.phase() == CallPhase.COMPLETED);
        }

        /**
         * 上游取消收尾成 {@code ABORTED}，不是 {@code CANCELED}。
         *
         * <p>{@code CANCELED} 的语义是「下游 Copilot 主动断连」，这里是<strong>上游侧</strong>
         * 取消。两者都不是错误，但成因相反 —— 混用会让「谁取消的」这个信息消失。
         */
        @Test
        void cancelledEventFinalizesAsAborted() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just(
                            "{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}",
                            "{\"type\":\"response.cancelled\",\"response\":{\"id\":\"r\"}}"));

            List<CallLifecycleEvent> lifecycle = collectLifecycle(() -> streamEvents(streamRequest()));

            assertThat(lifecycle).anyMatch(e -> e.phase() == CallPhase.ABORTED);
            assertThat(lifecycle).noneMatch(e -> e.phase() == CallPhase.COMPLETED);
        }

        /**
         * 另一种拼法 {@code response.canceled} 同样归入取消。
         *
         * <p>实测有上游只发其中一种。两种拼法必须走同一条分支，
         * 否则少收的那种会掉进 SUCCESS 兜底而显示成「完成」。
         */
        @Test
        void alternateCanceledSpellingFinalizesAsAborted() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just("{\"type\":\"response.canceled\",\"response\":{\"id\":\"r\"}}"));

            List<CallLifecycleEvent> lifecycle = collectLifecycle(() -> streamEvents(streamRequest()));

            assertThat(lifecycle).anyMatch(e -> e.phase() == CallPhase.ABORTED);
        }

        /**
         * 截断（{@code response.incomplete}）算完成，不算失败。
         *
         * <p>达到 token 上限时内容不完整但确实产出了。与 Chat 侧
         * {@code finish_reason: "length"} 同口径 —— 那边也不因截断而标失败。
         * 标成 FAILED 会让「问了个需要长回答的问题」看起来像上游故障。
         */
        @Test
        void incompleteEventFinalizesAsCompleted() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just(
                            "{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}",
                            "{\"type\":\"response.incomplete\",\"response\":{\"id\":\"r\"}}"));

            List<CallLifecycleEvent> lifecycle = collectLifecycle(() -> streamEvents(streamRequest()));

            assertThat(lifecycle).anyMatch(e -> e.phase() == CallPhase.COMPLETED);
            assertThat(lifecycle).noneMatch(e -> e.phase() == CallPhase.FAILED);
        }

        /**
         * 上游一个终态事件都不发就关连接时，兜底层按成功处理。
         *
         * <p>Layer 2 拿不到事件类型，无从判断结局。按成功处理与 Chat / Anthropic 的
         * 兜底层同口径：连接正常关闭且已有内容，没有任何证据表明它失败了。
         * 若默认成 FAILED，所有省略终态事件的上游都会被误标。
         */
        @Test
        void streamWithoutTerminalEventFallsBackToCompleted() {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.just("{\"type\":\"response.output_text.delta\",\"delta\":\"a\"}"));

            List<CallLifecycleEvent> lifecycle = collectLifecycle(() -> streamEvents(streamRequest()));

            assertThat(lifecycle).anyMatch(e -> e.phase() == CallPhase.COMPLETED);
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
         *
         * <h2>必须同时断言 JSON 顶层的 {@code type}</h2>
         * 只断言 SSE 的 {@code event:} 名是不够的 —— 那正是这条用例此前放过一个缺陷的
         * 原因：错误体当时是 Chat 形态（{@code {"error":{...}}}、顶层无 {@code type}），
         * 而 Responses 客户端是事件状态机、靠 JSON 的 {@code type} 分派。
         * 症状是流挂住而非报错，会被误判成超时。
         */
        @Test
        void streamErrorIsSentAsErrorEvent() throws Exception {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.error(new ProtocolTranslationNotSupportedException(
                            "relay-x", WireProtocol.RESPONSES, WireProtocol.MESSAGES)));

            List<ServerSentEvent<String>> events = streamEvents(streamRequest());

            assertThat(events).hasSize(1);
            ServerSentEvent<String> event = events.get(0);
            assertThat(event.event()).isEqualTo("error");

            JsonNode body = new ObjectMapper().readTree(event.data());
            // 官方 ResponseErrorEvent 是扁平结构且 type 恒为 "error"。
            assertThat(body.path("type").asText()).isEqualTo("error");
            assertThat(body.path("message").asText()).contains("relay-x");
            // 不是 Chat 形态：内容不该被包进 error 对象里。
            assertThat(body.has("error")).isFalse();
            // code / param 按官方定义存在且可为 null —— 缺键与 null 对客户端可能不等价。
            assertThat(body.has("code")).isTrue();
            assertThat(body.path("code").isNull()).isTrue();
            assertThat(body.has("param")).isTrue();
        }

        /**
         * 上游 HTTP 错误的原文消息要保留，但外壳必须是合法 error 事件。
         *
         * <p>上游 4xx 体通常是 REST 错误（Chat 形态、顶层无 {@code type}）。原样下发
         * 等于制造一帧状态机认不出的脏数据；整体丢弃则丢掉唯一说明「为什么失败」的
         * 信息（余额不足、模型不存在、限流）。因此把消息搬进符合协议的外壳里。
         */
        @Test
        void streamUpstreamErrorKeepsMessageInsideProtocolShape() throws Exception {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.error(WebClientResponseException.create(
                            402, "Payment Required", HttpHeaders.EMPTY,
                            "{\"error\":{\"message\":\"余额不足\",\"type\":\"insufficient_quota\"}}"
                                    .getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8)));

            List<ServerSentEvent<String>> events = streamEvents(streamRequest());

            assertThat(events).hasSize(1);
            JsonNode body = new ObjectMapper().readTree(events.get(0).data());
            assertThat(body.path("type").asText()).isEqualTo("error");
            assertThat(body.path("message").asText()).isEqualTo("余额不足");
        }

        /**
         * 上游已给出合法 error 事件时原样透传。
         *
         * <p>少数上游把 SSE 错误帧当响应体返回。那份原文比本地重建的更准确
         * （带 {@code code} / {@code param}），且它本就能被状态机分派，不该再包一层。
         */
        @Test
        void streamUpstreamErrorEventIsPassedThroughVerbatim() throws Exception {
            String upstream = "{\"type\":\"error\",\"code\":\"rate_limit_exceeded\","
                    + "\"message\":\"slow down\",\"param\":null,\"sequence_number\":7}";
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.error(WebClientResponseException.create(
                            429, "Too Many Requests", HttpHeaders.EMPTY,
                            upstream.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));

            List<ServerSentEvent<String>> events = streamEvents(streamRequest());

            assertThat(events).hasSize(1);
            JsonNode body = new ObjectMapper().readTree(events.get(0).data());
            assertThat(body.path("code").asText()).isEqualTo("rate_limit_exceeded");
            // 上游自己的序号保留 —— 本地重建时刻意不编造这个字段。
            assertThat(body.path("sequence_number").asInt()).isEqualTo(7);
        }

        /**
         * 非 JSON 的上游错误体（HTML 错误页等）退回状态码描述。
         *
         * <p>原文可能是几 KB 的 HTML，塞进 message 里对客户端毫无用处，
         * 还会把一帧事件撑得极大。
         */
        @Test
        void streamNonJsonUpstreamErrorFallsBackToStatus() throws Exception {
            given(responsesService.responsesStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Flux.error(WebClientResponseException.create(
                            502, "Bad Gateway", HttpHeaders.EMPTY,
                            "<html>502 Bad Gateway</html>".getBytes(StandardCharsets.UTF_8),
                            StandardCharsets.UTF_8)));

            List<ServerSentEvent<String>> events = streamEvents(streamRequest());

            JsonNode body = new ObjectMapper().readTree(events.get(0).data());
            assertThat(body.path("type").asText()).isEqualTo("error");
            assertThat(body.path("message").asText()).contains("502");
            assertThat(body.path("message").asText()).doesNotContain("<html>");
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
