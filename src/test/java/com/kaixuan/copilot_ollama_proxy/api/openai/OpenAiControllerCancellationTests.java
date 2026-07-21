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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 主动取消（A3 / Phase 2）的端到端确定性测试。
 *
 * <p>核心洞察：后端取消<strong>不依赖前端那 60 秒</strong>——60s 只是前端放开取消按钮的 UX 门槛，
 * cancel 端点是立即生效的；且 RECEIVED 事件与 {@code registry.register} 都在控制器方法体里<strong>同步</strong>执行。
 * 因此用 {@code Mono.never()} / {@code Flux.never()} 模拟「永远挂起的上游」，即可无真实等待、无网络、
 * 完全确定性地验证取消真的能中止请求、回传 504 错误体、并发出 ABORTED 事件。
 *
 * <p>用纯单元测试直接实例化控制器，只 mock {@link ChatCompletionService}，
 * 真实使用 {@link CallCancellationRegistry} 与 {@link CallLifecyclePublisher}。
 */
class OpenAiControllerCancellationTests {

    /** 与 OpenAiController.CANCELED_ERROR_BODY 保持一致，用于断言下游收到的错误体。 */
    private static final String EXPECTED_ERROR_TYPE = "upstream_timeout";

    private final ChatCompletionService chatCompletionService = mock(ChatCompletionService.class);
    private final ApiUsageCollector apiUsageCollector = mock(ApiUsageCollector.class);
    private final ModelCatalogService modelCatalogService = mock(ModelCatalogService.class);
    private final CallLifecyclePublisher lifecyclePublisher = new CallLifecyclePublisher();
    private final CallCancellationRegistry cancellationRegistry = new CallCancellationRegistry();

    private OpenAiController newController() {
        return new OpenAiController(chatCompletionService, new ObjectMapper(), apiUsageCollector,
                modelCatalogService, lifecyclePublisher, cancellationRegistry, "localhost", 11434);
    }

    private OpenAiChatRequest request(boolean stream) {
        OpenAiChatRequest request = new OpenAiChatRequest();
        request.setModel("mimo-v2.5-pro");
        request.setStream(stream);
        request.setMessages(List.of());
        return request;
    }

    @Test
    void cancelingHangingNonStreamRequestReturns504AndEmitsAborted() throws Exception {
        // 上游永远挂起，模拟「已连接但迟迟不吐首字」。
        given(chatCompletionService.chatCompletion(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Mono.never());

        List<CallLifecycleEvent> events = new CopyOnWriteArrayList<>();
        lifecyclePublisher.events().subscribe(events::add);

        OpenAiController controller = newController();
        // RECEIVED 与 register 在此调用中同步发生。
        Mono<ResponseEntity<?>> responseMono = controller.chatCompletions(request(false), HttpHeaders.EMPTY);

        String requestId = requestIdOf(events);
        assertThat(requestId).isNotBlank();

        // 订阅（激活 firstWithSignal 对取消 sink 的订阅），随后触发取消。
        CompletableFuture<ResponseEntity<?>> future = responseMono.toFuture();
        boolean triggered = cancellationRegistry.cancel(requestId);
        assertThat(triggered).isTrue();

        ResponseEntity<?> response = future.get(2, TimeUnit.SECONDS);
        assertThat(response.getStatusCode().value()).isEqualTo(504);
        assertThat(String.valueOf(response.getBody())).contains(EXPECTED_ERROR_TYPE);

        assertThat(hasPhase(events, CallPhase.ABORTED)).isTrue();
        // doFinally 已清理注册表：再次取消返回 false。
        assertThat(cancellationRegistry.cancel(requestId)).isFalse();
    }

    @Test
    void cancelingHangingStreamRequestInjectsErrorFrameAndEmitsAborted() throws Exception {
        given(chatCompletionService.chatCompletionStream(anyMap(), anyString(), any(HttpHeaders.class), anyString()))
                .willReturn(Flux.never());

        List<CallLifecycleEvent> events = new CopyOnWriteArrayList<>();
        lifecyclePublisher.events().subscribe(events::add);

        OpenAiController controller = newController();
        ResponseEntity<?> envelope = controller.chatCompletions(request(true), HttpHeaders.EMPTY).block(Duration.ofSeconds(2));
        assertThat(envelope).isNotNull();

        String requestId = requestIdOf(events);
        assertThat(requestId).isNotBlank();

        @SuppressWarnings("unchecked")
        Flux<ServerSentEvent<String>> body = (Flux<ServerSentEvent<String>>) envelope.getBody();
        assertThat(body).isNotNull();

        // 订阅流（激活 takeUntilOther 对取消 sink 的订阅），随后触发取消。
        CompletableFuture<List<ServerSentEvent<String>>> future = body.collectList().toFuture();
        boolean triggered = cancellationRegistry.cancel(requestId);
        assertThat(triggered).isTrue();

        List<ServerSentEvent<String>> frames = future.get(2, TimeUnit.SECONDS);
        // 取消发生在首字前，流里应只有注入的错误帧。
        assertThat(frames).isNotEmpty();
        ServerSentEvent<String> last = frames.get(frames.size() - 1);
        assertThat(last.event()).isEqualTo("error");
        assertThat(last.data()).contains(EXPECTED_ERROR_TYPE);

        assertThat(hasPhase(events, CallPhase.ABORTED)).isTrue();
        assertThat(cancellationRegistry.cancel(requestId)).isFalse();
    }

    private static String requestIdOf(List<CallLifecycleEvent> events) {
        return events.stream()
                .filter(e -> e.phase() == CallPhase.RECEIVED)
                .map(CallLifecycleEvent::requestId)
                .findFirst()
                .orElse(null);
    }

    private static boolean hasPhase(List<CallLifecycleEvent> events, CallPhase phase) {
        return events.stream().anyMatch(e -> e.phase() == phase);
    }
}
