package com.kaixuan.copilot_ollama_proxy.api.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.catalog.ModelCatalogService;
import com.kaixuan.copilot_ollama_proxy.control.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.observability.publisher.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.observability.record.ApiUsageDailyService;
import com.kaixuan.copilot_ollama_proxy.pipeline.entry.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.pipeline.protocol.UpstreamEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.OpenAiChatRequest;
import com.kaixuan.copilot_ollama_proxy.protocol.usage.UsageTokens;
import com.kaixuan.copilot_ollama_proxy.testing.UpstreamStreams;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 阶段 5 步 7b-1「usage 消重复」的出口侧不变式。
 *
 * <h2>本步修的是什么</h2>
 * 同一份上游字节此前被解析<strong>两遍</strong>：块 2 解析一次写 {@code api_call_usage}（明细），
 * 出口又解析一次写 {@code api_usage_daily}（聚合）。后一遍把协议语义
 * （Chat 后到覆盖 / Anthropic 跨事件 merge / Responses 最后非空胜出）复制到了出口。
 *
 * <p>7b-1 把解析收到生产者（各协议执行器 / 翻译器），结果挂在
 * {@link UpstreamEvent#usage()} 上；出口只做一条<strong>与协议无关</strong>的
 * 「取最后一份非 null」。
 *
 * <h2>本测试钉住三条</h2>
 * <ol>
 *   <li><strong>槽被消费</strong>：事件挂 usage → 出口按它记账（值与挂上的完全一致）；</li>
 *   <li><strong>不再解析字节</strong>：事件<strong>不挂</strong> usage 但 data 里<strong>含</strong>
 *       合法 usage JSON 时，出口<strong>不</strong>记真实值 —— 若它仍在解析，这里会记出 999。
 *       这是本步的核心判据，也是唯一能抓住「偷偷回退到解析」的断言；</li>
 *   <li><strong>恒记不变式</strong>：流式无 usage 时仍记 <code>0,0</code>（调用次数不能少算）。</li>
 * </ol>
 *
 * <p>第 2 条是刻意的「反直觉」断言：让 data 里带着 usage 却期望它被忽略。
 * 它保护的是分层本身 —— 出口一旦重新解析，就又会成为协议语义的第二份实现。
 */
class OpenAiControllerUsageSlotTests {

    private final ChatCompletionService chatCompletionService = mock(ChatCompletionService.class);
    private final ApiUsageDailyService apiUsageCollector = mock(ApiUsageDailyService.class);
    private final ModelCatalogService modelCatalogService = mock(ModelCatalogService.class);
    private final CallLifecyclePublisher lifecyclePublisher = new CallLifecyclePublisher();
    private final CallCancellationRegistry cancellationRegistry = new CallCancellationRegistry();

    private OpenAiController newController() {
        return new OpenAiController(chatCompletionService, new ObjectMapper(), apiUsageCollector,
                modelCatalogService, lifecyclePublisher, cancellationRegistry, "localhost", 11434);
    }

    private OpenAiChatRequest streamRequest() {
        OpenAiChatRequest request = new OpenAiChatRequest();
        request.setModel("mimo-v2.5-pro");
        request.setStream(true);
        request.setMessages(List.of());
        return request;
    }

    private OpenAiChatRequest nonStreamRequest() {
        OpenAiChatRequest request = new OpenAiChatRequest();
        request.setModel("mimo-v2.5-pro");
        request.setStream(false);
        request.setMessages(List.of());
        return request;
    }

    /** 一段含合法 usage 的 OpenAI 响应体 —— 若出口仍在解析，会记出这些数字。 */
    private static final String BODY_WITH_USAGE =
            "{\"id\":\"c1\",\"choices\":[],\"usage\":{\"prompt_tokens\":999,\"completion_tokens\":888}}";

    @Nested
    @DisplayName("流式：读槽记账")
    class Stream {

        @Test
        @DisplayName("事件挂了 usage → 按槽记账（不再解析 data）")
        void recordsFromSlot() {
            Flux<UpstreamEvent> upstream = Flux.just(
                    UpstreamEvent.body("{\"id\":\"c1\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"),
                    // 故意让 data 里的 usage 与槽上的不同：出口应取槽上的 11/22。
                    UpstreamEvent.terminal("[DONE]", new UsageTokens(11, 22, null)));
            given(chatCompletionService.chatCompletionStream(
                    anyMap(), anyString(), any(HttpHeaders.class), anyString())).willReturn(upstream);

            subscribeToStream(streamRequest());

            verify(apiUsageCollector).record(11, 22);
        }

        @Test
        @DisplayName("data 含 usage 但槽为空 → 不解析，记 0,0（钉住「出口不再解析协议字节」）")
        void doesNotParseDataWhenSlotAbsent() {
            // 关键：data 里有合法 usage（999/888），但没有任何事件挂槽。
            // 若出口退回解析，这里会记 (999, 888) —— 断言会失败。
            Flux<UpstreamEvent> upstream = Flux.just(
                    UpstreamEvent.body(BODY_WITH_USAGE),
                    UpstreamEvent.terminal("[DONE]"));
            given(chatCompletionService.chatCompletionStream(
                    anyMap(), anyString(), any(HttpHeaders.class), anyString())).willReturn(upstream);

            subscribeToStream(streamRequest());

            // 流式恒记：无 usage 时记 0,0（调用次数仍被计入）。
            verify(apiUsageCollector).record(0, 0);
            verify(apiUsageCollector, never()).record(999, 888);
        }

        @Test
        @DisplayName("取最后一份非 null：后到的槽覆盖先到的")
        void takesLastNonNull() {
            Flux<UpstreamEvent> upstream = UpstreamStreams.chat(
                            "{\"id\":\"c1\",\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}")
                    // 先挂一份不完整的，再挂结算值 —— 出口应取后者。
                    .map(event -> event.withUsage(new UsageTokens(5, null, null)))
                    .concatWith(Flux.just(
                            UpstreamEvent.terminal("[DONE]", new UsageTokens(100, 200, 7))));
            given(chatCompletionService.chatCompletionStream(
                    anyMap(), anyString(), any(HttpHeaders.class), anyString())).willReturn(upstream);

            subscribeToStream(streamRequest());

            verify(apiUsageCollector).record(100, 200);
        }

        private void subscribeToStream(OpenAiChatRequest request) {
            ResponseEntity<?> envelope = newController().chatCompletions(request, HttpHeaders.EMPTY)
                    .block(Duration.ofSeconds(2));
            assertThat(envelope).isNotNull();
            @SuppressWarnings("unchecked")
            Flux<ServerSentEvent<String>> body = (Flux<ServerSentEvent<String>>) envelope.getBody();
            assertThat(body).isNotNull();
            Disposable subscription = body.subscribe();
            try {
                // 等一小会儿让 doOnNext 链路跑完（流是同步 emit）。
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                subscription.dispose();
            }
        }
    }

    @Nested
    @DisplayName("非流式：读槽记账")
    class NonStream {

        @Test
        @DisplayName("事件挂了 usage → 按槽记账")
        void recordsFromSlot() {
            given(chatCompletionService.chatCompletion(
                    anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.just(UpstreamEvent.body("{}", new UsageTokens(31, 42, 5))));

            newController().chatCompletions(nonStreamRequest(), HttpHeaders.EMPTY)
                    .block(Duration.ofSeconds(2));

            verify(apiUsageCollector).record(31, 42);
        }

        @Test
        @DisplayName("data 含 usage 但槽为空 → 不记（非流式无 usage 不记，与旧行为一致）")
        void doesNotParseDataWhenSlotAbsent() {
            given(chatCompletionService.chatCompletion(
                    anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.just(UpstreamEvent.body(BODY_WITH_USAGE)));

            newController().chatCompletions(nonStreamRequest(), HttpHeaders.EMPTY)
                    .block(Duration.ofSeconds(2));

            verify(apiUsageCollector, never()).record(any(Integer.class), any(Integer.class));
        }

        @Test
        @DisplayName("槽上是 EMPTY（上游报了但三字段全缺）→ 不记，保持 null 与空语义")
        void emptyTokensNotRecorded() {
            given(chatCompletionService.chatCompletion(
                    anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                    .willReturn(Mono.just(UpstreamEvent.body("{}", UsageTokens.EMPTY)));

            newController().chatCompletions(nonStreamRequest(), HttpHeaders.EMPTY)
                    .block(Duration.ofSeconds(2));

            verify(apiUsageCollector, never()).record(any(Integer.class), any(Integer.class));
        }
    }
}
