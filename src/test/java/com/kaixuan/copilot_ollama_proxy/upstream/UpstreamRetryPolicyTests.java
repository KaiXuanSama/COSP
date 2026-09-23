package com.kaixuan.copilot_ollama_proxy.upstream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UpstreamRetryPolicy} 的可重试判定。
 *
 * <h2>为何这些用例值得存在</h2>
 * 本类取代的是三个上游执行器里三份逐字相同的副本，而那三份<strong>此前都没有直接单测</strong>
 * —— 它们只被「重试到耗尽」那类集成用例间接覆盖，而那种用例需要压缩退避、
 * 起真实上游 stub，成本高且只说「重试了」不说「为什么重试」。
 *
 * <p>判定错误有两个方向，症状完全不同：
 * <ul>
 *   <li><strong>少判（该重试的不重试）</strong> —— 偶发的 429 / 网络抖动直接变成硬失败，
 *       用户看到一次本可自愈的错误；</li>
 *   <li><strong>多判（不该重试的却重试）</strong> —— 401 / 报文错误这类确定性失败
 *       白等 62 秒（2+4+8+16+32）后仍然失败，用户以为服务卡死。</li>
 * </ul>
 * 两个方向都要有用例，只测其中一边会漏掉一半。
 */
class UpstreamRetryPolicyTests {

    private static WebClientResponseException upstreamError(HttpStatus status, String body) {
        return WebClientResponseException.create(status.value(), status.getReasonPhrase(),
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), null);
    }

    @Nested
    @DisplayName("可重试的四类")
    class Retryable {

        /** 第 1 类：上游不可达 / 未响应。 */
        @Test
        void connectionEstablishmentFailure() {
            WebClientRequestException unreachable = new WebClientRequestException(
                    new ConnectException("Connection refused"),
                    org.springframework.http.HttpMethod.POST,
                    URI.create("https://upstream.invalid/v1/chat/completions"),
                    HttpHeaders.EMPTY);

            assertThat(UpstreamRetryPolicy.isRetryableFailure(unreachable)).isTrue();
        }

        /** 第 2 类：TLS 握手失败，常被 Netty 的 DecoderException 包着。 */
        @Test
        void sslHandshakeFailureInsideAnotherWrapper() {
            Throwable wrapped = new RuntimeException("decoder",
                    new SSLHandshakeException("unable to find valid certification path"));

            assertThat(UpstreamRetryPolicy.isRetryableFailure(wrapped)).isTrue();
        }

        /**
         * 裸的 {@code SSLException} 也要认。
         *
         * <p>这一条定义了 {@code hasSslHandshakeFailure} <strong>从自身起扫</strong>
         * （而非像 {@code hasNetworkCause} 那样从 cause 起）：
         * 调用它时还不知道外层是什么类型，异常本身就可能直接是个 SSL 异常。
         */
        @Test
        void sslExceptionItselfIsRecognized() {
            assertThat(UpstreamRetryPolicy.isRetryableFailure(new SSLException("handshake aborted"))).isTrue();
        }

        @Test
        void rateLimit429() {
            assertThat(UpstreamRetryPolicy.isRetryableFailure(upstreamError(HttpStatus.TOO_MANY_REQUESTS, "slow")))
                    .isTrue();
        }

        @Test
        void serverError5xx() {
            assertThat(UpstreamRetryPolicy.isRetryableFailure(
                    upstreamError(HttpStatus.SERVICE_UNAVAILABLE, "maintenance"))).isTrue();
            assertThat(UpstreamRetryPolicy.isRetryableFailure(
                    upstreamError(HttpStatus.INTERNAL_SERVER_ERROR, "boom"))).isTrue();
        }

        /**
         * 400 也在可重试之列 —— 看起来与「确定性 4xx 不重试」矛盾，但是有意为之：
         * 部分中转站在后端抖动时短暂回 400，重发一次就恢复。
         */
        @Test
        void badRequest400IsDeliberatelyRetryable() {
            assertThat(UpstreamRetryPolicy.isRetryableFailure(upstreamError(HttpStatus.BAD_REQUEST, "transient")))
                    .isTrue();
        }

        /**
         * HTTP 200 之后 SSE 流中途断开。
         *
         * <p>真实形态是 {@link WebClientResponseException} 的 cause 链里包着
         * {@code IOException} —— 表面看状态码是 200，实际是网络层失败。
         * 判据是 {@code hasNetworkCause}，因此它必须<strong>从 cause 起扫</strong>：
         * 那个容器本身不是 {@code IOException}。
         */
        @Test
        void ioCauseInsideResponseExceptionIsRetryable() {
            WebClientResponseException midStreamBreak = upstreamError(HttpStatus.OK, "");
            // initCause 而非构造器：WebClientResponseException 没有带 cause 的工厂方法，
            // 而这里的被测变量正是「cause 链里有什么」。
            midStreamBreak.initCause(new IOException("Connection reset by peer"));

            assertThat(UpstreamRetryPolicy.hasNetworkCause(midStreamBreak)).isTrue();
            assertThat(UpstreamRetryPolicy.isRetryableFailure(midStreamBreak)).isTrue();
        }

        /**
         * 没有 {@code IOException} 时判否。
         *
         * <p>这一条只保证「不会无条件返回 true」，<strong>不</strong>区分
         * {@code hasNetworkCause} 是从自身起扫还是从 cause 起扫 —— 两种写法在这里同为 false。
         * 实际上两种写法在调用场景下等价：它只在已确认外层是
         * {@code WebClientResponseException} 之后被调用，而那个容器不是 {@code IOException}。
         * 之所以仍从 cause 起扫，是因为「问的是『这里面有没有』而非『它本身是不是』」
         * 更能表达意图，且不依赖上面那个容器类型的细节。
         */
        @Test
        void noIoCauseMeansNotRetryable() {
            assertThat(UpstreamRetryPolicy.hasNetworkCause(new RuntimeException("no io here"))).isFalse();
        }

        /** 第 4 类：空响应复用同一份预算，避免出现第二套重试次数配置。 */
        @Test
        void emptyUpstreamResponse() {
            assertThat(UpstreamRetryPolicy.isRetryableFailure(
                    new EmptyUpstreamResponseException(List.of("{}")))).isTrue();
        }
    }

    @Nested
    @DisplayName("不可重试的：确定性失败重试结果必然相同")
    class NotRetryable {

        @Test
        void unauthorized401() {
            assertThat(UpstreamRetryPolicy.isRetryableFailure(
                    upstreamError(HttpStatus.UNAUTHORIZED, "bad key"))).isFalse();
        }

        @Test
        void forbidden403() {
            assertThat(UpstreamRetryPolicy.isRetryableFailure(
                    upstreamError(HttpStatus.FORBIDDEN, "no access"))).isFalse();
        }

        @Test
        void notFound404() {
            assertThat(UpstreamRetryPolicy.isRetryableFailure(
                    upstreamError(HttpStatus.NOT_FOUND, "no such model"))).isFalse();
        }

        @Test
        void unprocessable422() {
            assertThat(UpstreamRetryPolicy.isRetryableFailure(
                    upstreamError(HttpStatus.UNPROCESSABLE_ENTITY, "bad shape"))).isFalse();
        }

        /** 编程错误、字段缺失这类异常与上游无关，重试无意义。 */
        @Test
        void unrelatedRuntimeException() {
            assertThat(UpstreamRetryPolicy.isRetryableFailure(new IllegalStateException("bug"))).isFalse();
            assertThat(UpstreamRetryPolicy.isRetryableFailure(new NullPointerException())).isFalse();
        }
    }

    @Nested
    @DisplayName("状态码判定")
    class StatusCodes {

        @Test
        void retryableCodes() {
            assertThat(UpstreamRetryPolicy.isRetryableStatus(HttpStatus.TOO_MANY_REQUESTS)).isTrue();
            assertThat(UpstreamRetryPolicy.isRetryableStatus(HttpStatus.BAD_REQUEST)).isTrue();
            assertThat(UpstreamRetryPolicy.isRetryableStatus(HttpStatus.INTERNAL_SERVER_ERROR)).isTrue();
            assertThat(UpstreamRetryPolicy.isRetryableStatus(HttpStatus.BAD_GATEWAY)).isTrue();
            assertThat(UpstreamRetryPolicy.isRetryableStatus(HttpStatus.GATEWAY_TIMEOUT)).isTrue();
        }

        @Test
        void nonRetryableCodes() {
            assertThat(UpstreamRetryPolicy.isRetryableStatus(HttpStatus.OK)).isFalse();
            assertThat(UpstreamRetryPolicy.isRetryableStatus(HttpStatus.UNAUTHORIZED)).isFalse();
            assertThat(UpstreamRetryPolicy.isRetryableStatus(HttpStatus.FORBIDDEN)).isFalse();
            assertThat(UpstreamRetryPolicy.isRetryableStatus(HttpStatus.NOT_FOUND)).isFalse();
            assertThat(UpstreamRetryPolicy.isRetryableStatus(HttpStatus.CONFLICT)).isFalse();
        }

        /** 全部 5xx 都在列，不只是 500 / 502 / 503 / 504。 */
        @Test
        void every5xxIsRetryable() {
            for (HttpStatus status : HttpStatus.values()) {
                if (status.is5xxServerError()) {
                    assertThat(UpstreamRetryPolicy.isRetryableStatus(status))
                            .as("5xx %s 应可重试", status)
                            .isTrue();
                }
            }
        }
    }

    @Nested
    @DisplayName("空响应异常的解包")
    class Unwrapping {

        @Test
        void findsThroughRetryExhaustedWrapper() {
            EmptyUpstreamResponseException empty = new EmptyUpstreamResponseException(List.of("{}"));
            Throwable exhausted = new RuntimeException("Retries exhausted: 5/5", empty);

            assertThat(UpstreamRetryPolicy.findEmptyUpstreamException(exhausted)).isSameAs(empty);
        }

        @Test
        void findsThroughMultipleLayers() {
            EmptyUpstreamResponseException empty = new EmptyUpstreamResponseException(List.of("{}"));
            Throwable nested = new RuntimeException("outer",
                    new IllegalStateException("middle",
                            new RuntimeException("Retries exhausted", empty)));

            assertThat(UpstreamRetryPolicy.findEmptyUpstreamException(nested)).isSameAs(empty);
        }

        /**
         * 解包必须递归，否则「空响应耗尽后放行最后一轮内容」那条兜底永远不生效 ——
         * 症状是下游收到一个 502 而不是上游那批空帧。
         */
        @Test
        void returnsNullWhenAbsent() {
            assertThat(UpstreamRetryPolicy.findEmptyUpstreamException(
                    upstreamError(HttpStatus.BAD_GATEWAY, "boom"))).isNull();
            assertThat(UpstreamRetryPolicy.findEmptyUpstreamException(new RuntimeException("other"))).isNull();
        }

        @Test
        void handlesUnrelatedIoChain() {
            // IOException 是网络层判据，不该被误当成空响应。
            assertThat(UpstreamRetryPolicy.findEmptyUpstreamException(
                    new RuntimeException("net", new EOFException("closed")))).isNull();
        }
    }

    /**
     * {@code findWebResponseException} 的解包 —— 阶段 3.6c-1 从三个执行器收归。
     *
     * <p>它与空响应解包同族同因（都要穿透 {@code RetryExhaustedException}），
     * 但两个方向都更隐蔽：
     * <ul>
     *   <li><strong>漏解</strong> —— 「错误响应已在 exchangeToFlux 落过库」这条排除失效，
     *       于是同一次失败落<strong>两条</strong>日志；而多出来的那条会被当成网络失败，
     *       状态码记 {@code -1} 且无错误体 —— 排查时指向网络，实际是上游回了 4xx；</li>
     *   <li><strong>误解</strong> —— 把不含 HTTP 响应的失败认成 HTTP 失败，去读一个不存在的状态码。</li>
     * </ul>
     * 三条线路此前各有一份实现（Chat 是实例方法、另两条是静态私有），
     * 都<strong>没有直接单测</strong>，只被「错误响应只落一条日志」那类集成用例间接覆盖。
     */
    @Nested
    @DisplayName("HTTP 错误响应的解包")
    class WebResponseUnwrapping {

        @Test
        @DisplayName("穿透 RetryExhaustedException 包装")
        void findsThroughRetryExhaustedWrapper() {
            WebClientResponseException upstream = upstreamError(HttpStatus.TOO_MANY_REQUESTS, "slow down");
            Throwable exhausted = new RuntimeException("Retries exhausted: 5/5", upstream);

            assertThat(UpstreamRetryPolicy.findWebResponseException(exhausted)).isSameAs(upstream);
        }

        @Test
        @DisplayName("穿透多层包装")
        void findsThroughMultipleLayers() {
            WebClientResponseException upstream = upstreamError(HttpStatus.UNAUTHORIZED, "bad key");
            Throwable nested = new RuntimeException("outer",
                    new IllegalStateException("middle",
                            new RuntimeException("Retries exhausted", upstream)));

            assertThat(UpstreamRetryPolicy.findWebResponseException(nested)).isSameAs(upstream);
        }

        /** 纯网络失败（无上游错误响应）必须返回 null —— 否则会被记成 HTTP 失败并去读状态码。 */
        @Test
        @DisplayName("无 HTTP 响应时返回 null")
        void returnsNullWhenAbsent() {
            assertThat(UpstreamRetryPolicy.findWebResponseException(
                    new RuntimeException("net", new EOFException("closed")))).isNull();
            assertThat(UpstreamRetryPolicy.findWebResponseException(
                    new EmptyUpstreamResponseException(List.of("{}")))).isNull();
        }

        /**
         * 空响应与 HTTP 错误响应<strong>是两个轴</strong>，不得互相误认。
         *
         * <p>两者都可能出现在同一个换行链上（如「空响应耗尽」又套一层 HTTP 包装），
         * 但各自的解包器只认自己那一种 —— 混用会让两条不同的兜底路径同时错位。
         */
        @Test
        @DisplayName("与空响应解包互不误认")
        void doesNotConfuseEmptyResponseWithHttpError() {
            WebClientResponseException upstream = upstreamError(HttpStatus.BAD_GATEWAY, "boom");

            assertThat(UpstreamRetryPolicy.findEmptyUpstreamException(upstream)).isNull();
            assertThat(UpstreamRetryPolicy.findWebResponseException(upstream)).isSameAs(upstream);
        }
    }
}
