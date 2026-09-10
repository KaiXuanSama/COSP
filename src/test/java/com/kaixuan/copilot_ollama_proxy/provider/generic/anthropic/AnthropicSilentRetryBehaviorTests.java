package com.kaixuan.copilot_ollama_proxy.provider.generic.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaixuan.copilot_ollama_proxy.application.provider.ProviderRequestHeaderService;
import com.kaixuan.copilot_ollama_proxy.application.provider.RequestBodyRuleEngine;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ProviderRuntimeConfiguration;
import com.kaixuan.copilot_ollama_proxy.application.runtime.ResolvedProviderRoute;
import com.kaixuan.copilot_ollama_proxy.infrastructure.web.CallRetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anthropic 流式线路的手动（静默）重试行为。
 *
 * <p>与 {@code AnthropicSilentRetryGapTests} 的分工：那边用反射确认注入点存在，
 * 这边验证<strong>触发之后真的重发了</strong> —— 注入点存在但循环没接上，
 * 表现同样是「点了没反应」，只靠反射测不出来。
 */
class AnthropicSilentRetryBehaviorTests {

    private final DefaultDataBufferFactory factory = new DefaultDataBufferFactory();

    /**
     * 触发静默重试后上游被重新请求，两轮事件先后下发到同一条下游流。
     *
     * <p>第一轮故意做成「不终结的流」（发一个事件后挂住），模拟真实卡顿场景 ——
     * 静默重试正是为这种情形准备的：不等它自己超时，直接切断重发。
     *
     * <p>手动订阅而非用 {@code StepVerifier}：本项目没引 {@code reactor-test}。
     * 靠 {@code CountDownLatch} 等首轮事件到达后再触发，时序与 StepVerifier 等价。
     */
    @Test
    @DisplayName("触发后重新发起上游请求，事件追加下发")
    void silentRetryReissuesUpstreamRequest() throws Exception {
        CallRetryRegistry registry = new CallRetryRegistry();
        AtomicInteger upstreamCalls = new AtomicInteger();

        TestService service = new TestService();
        service.setCallRetryRegistry(registry);
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            int attempt = upstreamCalls.incrementAndGet();
            if (attempt == 1) {
                // 第一轮：发一个事件后永不完成，等待被静默重试切断。
                return sse(Flux.concat(
                        Flux.just(event("first-round")),
                        Flux.never()));
            }
            return sse(Flux.just(event("second-round"), messageStop()));
        }));

        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch firstArrived = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        service.exposeMessagesStream(newRequest(), route())
                .doOnNext(data -> {
                    received.add(data);
                    if (data.contains("first-round")) {
                        firstArrived.countDown();
                    }
                })
                .doFinally(signal -> completed.countDown())
                .subscribe();

        assertThat(firstArrived.await(10, TimeUnit.SECONDS))
                .as("首轮事件应当先到达")
                .isTrue();

        // 首轮已卡在 Flux.never() 上，此刻触发静默重试。
        assertThat(registry.retry("req-test"))
                .as("注册表应当持有本次调用的信号")
                .isTrue();

        assertThat(completed.await(10, TimeUnit.SECONDS))
                .as("重发的那一轮应当正常结束整条流")
                .isTrue();

        assertThat(upstreamCalls.get())
                .as("静默重试必须真的再打一次上游，而不是只中断当前轮")
                .isEqualTo(2);
        assertThat(received).anyMatch(data -> data.contains("first-round"));
        assertThat(received).anyMatch(data -> data.contains("second-round"));
    }

    /**
     * 未触发重试时流正常结束，不额外重发。
     *
     * <p>反向保证：{@code concatWith} 里的递归只在标志为真时进入，
     * 否则每条正常结束的流都会无限重发。
     */
    @Test
    @DisplayName("未触发时不重发，流正常结束")
    void streamCompletesWithoutRetryWhenNotTriggered() {
        CallRetryRegistry registry = new CallRetryRegistry();
        AtomicInteger upstreamCalls = new AtomicInteger();

        TestService service = new TestService();
        service.setCallRetryRegistry(registry);
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request -> {
            upstreamCalls.incrementAndGet();
            return sse(Flux.just(event("only-round"), messageStop()));
        }));

        List<String> received = service.exposeMessagesStream(newRequest(), route())
                .collectList().block(Duration.ofSeconds(10));

        assertThat(received).hasSize(2);
        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    /**
     * 流结束后注册表条目被清理，迟到的重试请求不再生效。
     *
     * <p>不清理的话注册表会随调用量单向增长，且对一个已结束的调用点重试会「成功」，
     * 而那次触发不会有任何效果 —— 一个说谎的返回值比明确的 false 更难排查。
     */
    @Test
    @DisplayName("流结束后注册表条目被移除")
    void registryEntryIsRemovedAfterCompletion() {
        CallRetryRegistry registry = new CallRetryRegistry();

        TestService service = new TestService();
        service.setCallRetryRegistry(registry);
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request ->
                sse(Flux.just(event("done"), messageStop()))));

        service.exposeMessagesStream(newRequest(), route()).collectList().block(Duration.ofSeconds(10));

        assertThat(registry.retry("req-test"))
                .as("调用已结束，注册表不该再持有它的信号")
                .isFalse();
    }

    /** 未注入注册表时（如某些单测装配）流照常工作，不因缺少协调器而失败。 */
    @Test
    @DisplayName("未注入注册表时流照常工作")
    void streamWorksWithoutRegistry() {
        TestService service = new TestService();
        service.setWebClientBuilder(WebClient.builder().exchangeFunction(request ->
                sse(Flux.just(event("no-registry"), messageStop()))));

        List<String> received = service.exposeMessagesStream(newRequest(), route())
                .collectList().block(Duration.ofSeconds(10));

        assertThat(received).hasSize(2);
    }

    // ==================== 辅助 ====================

    private Mono<ClientResponse> sse(Flux<DataBuffer> body) {
        return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
                .body(body).build());
    }

    /** 一个带实质载荷的 text_delta 事件 —— 必须有载荷，否则会被空响应兜底卷入重试。 */
    private DataBuffer event(String marker) {
        return buffer("""
                data: {"type":"content_block_delta","index":0,\
                "delta":{"type":"text_delta","text":"%s"}}

                """.formatted(marker));
    }

    private DataBuffer messageStop() {
        return buffer("""
                data: {"type":"message_stop"}

                """);
    }

    private DataBuffer buffer(String text) {
        return factory.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ResolvedProviderRoute route() {
        return new ResolvedProviderRoute(
                new ProviderRuntimeConfiguration("anthro", "https://anthro.invalid/v1", "test-key", List.of()),
                "claude-x", "[anthro] claude-x");
    }

    private static Map<String, Object> newRequest() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", "claude-x");
        request.put("messages", List.of(Map.of("role", "user", "content", "hi")));
        request.put("stream", true);
        return request;
    }

    /** 测试子类：暴露受保护入口并把退避压到毫秒级。 */
    private static final class TestService extends GenericAnthropicChatService {

        private TestService() {
            super(new ObjectMapper(), new ProviderRequestHeaderService(new ObjectMapper()),
                    new RequestBodyRuleEngine(new ObjectMapper()));
        }

        private Flux<String> exposeMessagesStream(Map<String, Object> request, ResolvedProviderRoute route) {
            return messagesStream(request, route, HttpHeaders.EMPTY, "req-test");
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
