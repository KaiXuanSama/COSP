package com.kaixuan.copilot_ollama_proxy.api.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.catalog.ModelCatalogService;
import com.kaixuan.copilot_ollama_proxy.application.openai.ChatCompletionService;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.ApiUsageCollector;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallCancellationRegistry;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallLifecyclePublisher;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallLifecycleEvent;
import com.kaixuan.copilot_ollama_proxy.protocol.lifecycle.CallPhase;
import com.kaixuan.copilot_ollama_proxy.protocol.openai.OpenAiChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 流式完成判定（三层判定的 Layer 1）的确定性测试。
 *
 * <p>修复的偶发 bug：部分上游发完 {@code [DONE]} 后不主动关闭 TCP 连接（HTTP keep-alive），
 * 导致 {@code bodyToFlux} 永不 complete、{@code doOnComplete} 永不触发，
 * Toast 永远悬挂在「已产生 chunk：xxx」。
 *
 * <p>Layer 1 的修法：在 {@code doOnNext} 里收到 {@code [DONE]} 即认定完成、立即发出 COMPLETED，
 * 不必等待上游关闭连接。本测试用 {@code Flux.concat(chunks + [DONE], Flux.never())} 模拟
 * 「发完 [DONE] 却不断连」的上游——若 COMPLETED 仍能发出，即证明 Layer 1 不依赖连接关闭。
 *
 * <p>用纯单元测试直接实例化控制器，只 mock {@link ChatCompletionService}，
 * 真实使用 {@link CallCancellationRegistry} 与 {@link CallLifecyclePublisher}。
 */
class OpenAiControllerStreamCompletionTests {

    private final ChatCompletionService chatCompletionService = mock(ChatCompletionService.class);
    private final ApiUsageCollector apiUsageCollector = mock(ApiUsageCollector.class);
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

    @Test
    void streamEmitsCompletedOnDoneMarkerWithoutWaitingForConnectionClose() throws Exception {
        // 上游发 2 个内容 chunk + [DONE]，随后 Flux.never() 模拟 keep-alive 不关闭连接。
        Flux<String> upstream = Flux.concat(
                Flux.just(
                        "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}",
                        "{\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
                        "[DONE]"),
                Flux.never());
        given(chatCompletionService.chatCompletionStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(upstream);

        List<CallLifecycleEvent> events = new CopyOnWriteArrayList<>();
        lifecyclePublisher.events().subscribe(events::add);

        OpenAiController controller = newController();
        ResponseEntity<?> envelope = controller.chatCompletions(streamRequest(), HttpHeaders.EMPTY)
                .block(Duration.ofSeconds(2));
        assertThat(envelope).isNotNull();

        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> body = (Flux<ServerSentEvent<String>>) envelope.getBody();
        assertThat(body).isNotNull();

        // 订阅触发 doOnNext（流不会 complete，因为末尾接了 Flux.never()）。
        Disposable subscription = body.subscribe();
        try {
            CallLifecycleEvent completed = awaitPhase(events, CallPhase.COMPLETED);
            // Layer 1 生效：即使连接从未关闭，收到 [DONE] 也发出了 COMPLETED。
            assertThat(completed).isNotNull();
            // [DONE] 不计入 chunk 数：只有 2 个内容 chunk。
            assertThat(completed.chunkCount()).isEqualTo(2);
            // usage 也在收到 [DONE] 时记录，不再依赖连接关闭。
            org.mockito.Mockito.verify(apiUsageCollector).record(org.mockito.ArgumentMatchers.anyInt(),
                    org.mockito.ArgumentMatchers.anyInt());
        } finally {
            subscription.dispose();
        }
    }

    /** 轮询等待某阶段事件出现，最多等 2 秒（流是同步 emit，通常立即到达）。 */
    private static CallLifecycleEvent awaitPhase(List<CallLifecycleEvent> events, CallPhase phase)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Optional<CallLifecycleEvent> found = events.stream()
                    .filter(e -> e.phase() == phase)
                    .findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(20);
        }
        return null;
    }
}
