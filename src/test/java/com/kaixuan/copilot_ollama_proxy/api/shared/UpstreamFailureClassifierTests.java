package com.kaixuan.copilot_ollama_proxy.api.shared;

import com.kaixuan.copilot_ollama_proxy.api.shared.UpstreamFailureClassifier.Failure;
import com.kaixuan.copilot_ollama_proxy.api.shared.UpstreamFailureClassifier.FailureKind;
import com.kaixuan.copilot_ollama_proxy.application.protocol.NoSupportedProtocolException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ProtocolTranslationNotSupportedException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.RequestTranslationException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.ResponseTranslationException;
import com.kaixuan.copilot_ollama_proxy.application.protocol.WireProtocol;
import com.kaixuan.copilot_ollama_proxy.application.runtime.UnresolvedModelRouteException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link UpstreamFailureClassifier} 的分类判定。
 *
 * <h2>为何这些用例值得存在</h2>
 * 本类取代的是三份逐字相同的 {@code findXxx} 拷贝。那套写法没有测试 ——
 * 分类错误的表现是「同一件事在三条线路上得到不同结论」，
 * 而三条线路各自的用例只看得到自己那一侧，看不出不一致。
 * 收归之后这里成为唯一的判定处，覆盖它等于一次覆盖三条线路。
 *
 * <h2>为何单独断言「优先级」与「解包」两组</h2>
 * 它们是分类器里唯二有<strong>多种可能答案</strong>的判定：
 * 链上套了几层、同时出现多个类别时，选谁不选谁都是决策而非事实。
 * 其余用例（一类异常归一类）答案唯一，写在这里主要是防回归。
 */
class UpstreamFailureClassifierTests {

    /** 模拟 Reactor 重试耗尽时的包装层。类型名不重要，重要的是「它在链的外层」。 */
    private static RuntimeException wrappedInRetryExhausted(Throwable cause) {
        return new RuntimeException("Retries exhausted: 5/5", cause);
    }

    /**
     * 把若干异常按<strong>传入顺序</strong>串成一条链：第一个是最外层，最后一个是根因。
     *
     * <p>用它而不是嵌套构造：链上「谁在外层」在构造多类冲突的用例时是<strong>被测变量</strong>，
     * 必须一眼可见，而不是要读三层括号才知道。
     */
    private static Throwable chain(Throwable... nodesInOrder) {
        Throwable current = nodesInOrder[nodesInOrder.length - 1];
        for (int i = nodesInOrder.length - 2; i >= 0; i--) {
            current = new RuntimeException(nodesInOrder[i].getClass().getSimpleName(), current);
        }
        return current;
    }

    private static WebClientResponseException upstreamError(int status, String body) {
        return WebClientResponseException.create(status, "Upstream " + status, HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), null);
    }

    @Nested
    @DisplayName("六种异常各归入对应类别")
    class KindMapping {

        @Test
        void protocolUnsupported() {
            Failure failure = UpstreamFailureClassifier.classify(
                    new ProtocolTranslationNotSupportedException("relay-x", WireProtocol.CHAT, WireProtocol.MESSAGES));

            assertThat(failure.kind()).isEqualTo(FailureKind.PROTOCOL_UNSUPPORTED);
            assertThat(failure.message()).contains("relay-x");
        }

        @Test
        void noSupportedProtocol() {
            Failure failure = UpstreamFailureClassifier.classify(new NoSupportedProtocolException("relay-x"));

            assertThat(failure.kind()).isEqualTo(FailureKind.NO_SUPPORTED_PROTOCOL);
            assertThat(failure.message()).contains("至少勾选一种协议");
        }

        @Test
        void requestTranslation() {
            Failure failure = UpstreamFailureClassifier.classify(
                    new RequestTranslationException("messages[2].role", "是未知角色 narrator"));

            assertThat(failure.kind()).isEqualTo(FailureKind.REQUEST_TRANSLATION);
            // 字段路径是这个异常存在的意义，分类过程不能把它丢掉。
            assertThat(failure.message()).contains("messages[2].role");
        }

        @Test
        void unresolvedModelRoute() {
            Failure failure = UpstreamFailureClassifier.classify(new UnresolvedModelRouteException("ghost-model"));

            assertThat(failure.kind()).isEqualTo(FailureKind.UNRESOLVED_MODEL_ROUTE);
            assertThat(failure.message()).contains("ghost-model");
        }

        @Test
        void upstreamHttp() {
            Failure failure = UpstreamFailureClassifier.classify(upstreamError(429, "{\"error\":\"slow down\"}"));

            assertThat(failure.kind()).isEqualTo(FailureKind.UPSTREAM_HTTP);
            // 载荷要能取出来：渲染需要状态码与错误体原文。
            assertThat(failure.asHttpFailure().getStatusCode().value()).isEqualTo(429);
            assertThat(failure.asHttpFailure().getResponseBodyAsString()).contains("slow down");
        }

        @Test
        void responseTranslation() {
            Failure failure = UpstreamFailureClassifier.classify(
                    new ResponseTranslationException("上游响应不是合法 JSON"));

            assertThat(failure.kind()).isEqualTo(FailureKind.RESPONSE_TRANSLATION);
        }

        @Test
        void unknownFallsBackToTheOutermostException() {
            RuntimeException boom = new RuntimeException("socket closed");

            Failure failure = UpstreamFailureClassifier.classify(boom);

            assertThat(failure.kind()).isEqualTo(FailureKind.UNKNOWN);
            // 兜底要带最外层：各控制器的兜底日志从它身上提取根因与请求 URL，
            // 那套「跳过无意义包装」的判据认的是最外层。
            assertThat(failure.cause()).isSameAs(boom);
        }

        @Test
        void nullIsUnknownRatherThanACrash() {
            assertThat(UpstreamFailureClassifier.classify(null).kind()).isEqualTo(FailureKind.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("解包：套在重试耗尽包装里也能认出来")
    class Unwrapping {

        @Test
        void findsProtocolExceptionInsideRetryExhausted() {
            Failure failure = UpstreamFailureClassifier.classify(wrappedInRetryExhausted(
                    new ProtocolTranslationNotSupportedException("relay-x", WireProtocol.CHAT, WireProtocol.RESPONSES)));

            assertThat(failure.kind()).isEqualTo(FailureKind.PROTOCOL_UNSUPPORTED);
        }

        @Test
        void findsUpstreamHttpInsideRetryExhausted() {
            Failure failure = UpstreamFailureClassifier.classify(wrappedInRetryExhausted(
                    upstreamError(503, "upstream unavailable")));

            assertThat(failure.kind()).isEqualTo(FailureKind.UPSTREAM_HTTP);
            assertThat(failure.asHttpFailure().getStatusCode().value()).isEqualTo(503);
        }

        @Test
        void findsTargetThroughMultipleNestingLayers() {
            Throwable deeplyNested = new RuntimeException("outer",
                    new IllegalStateException("middle",
                            wrappedInRetryExhausted(new UnresolvedModelRouteException("ghost-model"))));

            assertThat(UpstreamFailureClassifier.classify(deeplyNested).kind())
                    .isEqualTo(FailureKind.UNRESOLVED_MODEL_ROUTE);
        }
    }

    @Nested
    @DisplayName("优先级：链上出现多个类别时取优先级高者")
    class Priority {

        /**
         * 这不是一个假想的冲突。
         *
         * <p>「协议类失败必须在 {@code WebClientResponseException} 之前判定」是既有约束
         * （原 {@code findProtocolException} 的 Javadoc 写明），而它的成立依赖
         * <strong>全链扫描</strong>：按逐节点判定时，HTTP 异常只要更靠链首就会抢先胜出，
         * 于是一次「该去改协议勾选」的失败被报成上游故障。
         *
         * <h2>构造方式</h2>
         * 用 {@code (消息, cause)} 显式串链，而不是包装现成异常 ——
         * 这样「谁在外层」是肉眼可见的，且与「协议异常在里、HTTP 在外」这个待验证的
         * 不利情形严格对应。
         */
        @Test
        void protocolFailureWinsOverUpstreamHttpEvenWhenHttpIsCloserToTheFront() {
            // 外层是上游 HTTP 错误，内层才是协议不可用。逐节点判定会选出 HTTP。
            Throwable httpOutsideProtocolInside = chain(
                    upstreamError(500, "boom"),
                    new ProtocolTranslationNotSupportedException(
                            "relay-x", WireProtocol.CHAT, WireProtocol.MESSAGES));

            Failure failure = UpstreamFailureClassifier.classify(httpOutsideProtocolInside);

            assertThat(failure.kind())
                    .as("协议类失败优先于上游 HTTP 失败")
                    .isEqualTo(FailureKind.PROTOCOL_UNSUPPORTED);
        }

        @Test
        void upstreamHttpWinsOverResponseTranslation() {
            // 上游回了错误响应，而链上还有「解析不了」——「上游说了什么」更接近事实。
            Throwable onlyTranslation = wrappedInRetryExhausted(new ResponseTranslationException("无法解析"));
            Throwable onlyHttp = wrappedInRetryExhausted(upstreamError(400, "bad model"));

            assertThat(UpstreamFailureClassifier.classify(onlyTranslation).kind())
                    .isEqualTo(FailureKind.RESPONSE_TRANSLATION);
            assertThat(UpstreamFailureClassifier.classify(onlyHttp).kind())
                    .isEqualTo(FailureKind.UPSTREAM_HTTP);
        }

        @Test
        void requestTranslationWinsOverUnresolvedRoute() {
            // 两者回同一个状态码，但消息不同；规则表里请求翻译在前，取它。
            Throwable onlyRoute = wrappedInRetryExhausted(new UnresolvedModelRouteException("ghost-model"));
            Throwable both = new RuntimeException("wrapper",
                    new RequestTranslationException("messages[0].role", "未知角色"));

            assertThat(UpstreamFailureClassifier.classify(onlyRoute).kind())
                    .isEqualTo(FailureKind.UNRESOLVED_MODEL_ROUTE);
            assertThat(UpstreamFailureClassifier.classify(both).kind())
                    .isEqualTo(FailureKind.REQUEST_TRANSLATION);
        }
    }

    @Nested
    @DisplayName("asHttpFailure 只在 UPSTREAM_HTTP 下合法")
    class HttpPayloadAccess {

        @Test
        void throwsIllegalStateWhenMisused() {
            Failure failure = UpstreamFailureClassifier.classify(new UnresolvedModelRouteException("m"));

            // 抛 IllegalStateException 而非裸 ClassCastException：后者在日志里只留两行类名，
            // 看不出「哪个类别被当成了 HTTP 失败」。
            assertThatThrownBy(failure::asHttpFailure)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UNRESOLVED_MODEL_ROUTE");
        }
    }

    @Nested
    @DisplayName("客户端断连：独立谓词，不在类别枚举里")
    class ClientDisconnect {

        /** JDK 的 EOFException；另三种在 WebFlux 运行时未必可见，用同名的嵌套类替身。 */
        @Test
        void recognizesEofException() {
            assertThat(UpstreamFailureClassifier.isClientDisconnect(new EOFException("gone"))).isTrue();
        }

        @Test
        void recognizesReactorNettyAbortedException() {
            assertThat(UpstreamFailureClassifier.isClientDisconnect(new AbortedException("downstream gone")))
                    .isTrue();
        }

        @Test
        void recognizesServletClientAbortException() {
            assertThat(UpstreamFailureClassifier.isClientDisconnect(new ClientAbortException("abort")))
                    .isTrue();
        }

        @Test
        void recognizesSpringAsyncRequestNotUsableException() {
            assertThat(UpstreamFailureClassifier.isClientDisconnect(
                    new AsyncRequestNotUsableException("not usable"))).isTrue();
        }

        /**
         * 断连要被包几层也能认出来。
         *
         * <p>漏判的症状不是报错而是<strong>僵尸 Toast</strong>：终态事件不发，
         * 前端那条记录永远停在 CHUNK 上。
         */
        @Test
        void recognizesThroughNestingLayers() {
            Throwable nested = new RuntimeException("outer",
                    new IllegalStateException("middle", new EOFException("gone")));

            assertThat(UpstreamFailureClassifier.isClientDisconnect(nested)).isTrue();
        }

        @Test
        void doesNotMistakeUpstreamFailureForDisconnect() {
            assertThat(UpstreamFailureClassifier.isClientDisconnect(upstreamError(500, "boom"))).isFalse();
            assertThat(UpstreamFailureClassifier.isClientDisconnect(new RuntimeException("connect timeout")))
                    .isFalse();
        }

        /**
         * {@code isClientDisconnect} 与 {@code classify} 是两条正交的判定：
         * 断连<strong>不是</strong>一种失败，因此分类器不会把它归入任何具名类别。
         * 调用方必须先问断连、再分类 —— 这个顺序是接口契约的一部分。
         */
        @Test
        void disconnectIsNotAFailureKind() {
            Throwable disconnect = new EOFException("gone");

            assertThat(UpstreamFailureClassifier.isClientDisconnect(disconnect)).isTrue();
            assertThat(UpstreamFailureClassifier.classify(disconnect).kind()).isEqualTo(FailureKind.UNKNOWN);
        }

        // ── 同名替身：这几个异常在测试 classpath 上未必可见，按简单类名匹配的是名字。 ──

        private static final class AbortedException extends RuntimeException {
            AbortedException(String message) {
                super(message);
            }
        }

        private static final class ClientAbortException extends RuntimeException {
            ClientAbortException(String message) {
                super(message);
            }
        }

        private static final class AsyncRequestNotUsableException extends RuntimeException {
            AsyncRequestNotUsableException(String message) {
                super(message);
            }
        }
    }
}
