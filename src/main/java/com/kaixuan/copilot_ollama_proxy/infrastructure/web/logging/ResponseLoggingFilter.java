package com.kaixuan.copilot_ollama_proxy.infrastructure.web.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.reactivestreams.Publisher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 响应日志过滤器（WebFlux 版本）—— 在响应返回客户端时打印状态码、内容类型和响应体摘要。
 *
 * 设计目标：
 * 1. 非 DEBUG 模式零开销直接放行
 * 2. 普通 JSON/文本响应打印 body（截断/跳过二进制、HTML、CSS）
 * 3. SSE/NDJSON 等流式响应不缓冲 body，仅记录元信息，避免内存堆积
 *
 * Order 设为最高优先级（更小的负值），确保最外层包裹响应，能观测到最终写出的内容。
 */
@Component
@Order(-110)
public class ResponseLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ResponseLoggingFilter.class);

    /** 响应体捕获上限，避免大响应导致内存堆积（仅 DEBUG 模式生效）。 */
    private static final int MAX_CAPTURE_BYTES = 64 * 1024;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 非 DEBUG 模式直接放行，不做任何包装和日志记录
        if (!log.isDebugEnabled()) {
            return chain.filter(exchange);
        }

        long startedAtNanos = System.nanoTime();
        ServerHttpResponse originalResponse = exchange.getResponse();
        LoggingResponseDecorator decorated = new LoggingResponseDecorator(originalResponse);

        return chain.filter(exchange.mutate().response(decorated).build())
                .doFinally(signal -> logResponse(exchange, decorated, startedAtNanos));
    }

    /**
     * 打印响应元信息与（非流式时的）响应体摘要。
     */
    private void logResponse(ServerWebExchange exchange, LoggingResponseDecorator response, long startedAtNanos) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        String contentType = response.getHeaders().getFirst("Content-Type");

        log.debug("=============== API 响应 ================");
        log.debug("URL    : {} {}", exchange.getRequest().getMethod(), exchange.getRequest().getURI());
        log.debug("Status : {}", response.getStatusCode() != null ? response.getStatusCode().value() : "(unknown)");
        log.debug("Type   : {}", contentType != null ? contentType : "(unknown)");
        log.debug("Time   : {} ms", elapsedMillis);
        if (isStreamingResponse(contentType)) {
            log.debug("Body   : (streaming, skipped)");
        } else {
            log.debug("Body   : {}", renderBody(response.getCapturedBody(), contentType));
        }
        log.debug("==========================================");
    }

    /**
     * 渲染响应体摘要，按内容类型决定是否打印。
     */
    private String renderBody(byte[] body, String contentType) {
        if (body.length == 0) {
            return "(empty)";
        }
        if (!isTextual(contentType)) {
            return "(binary " + body.length + " bytes captured)";
        }
        if (isHtmlResponse(contentType)) {
            return "(html " + body.length + " bytes, skipped)";
        }
        if (isCssResponse(contentType)) {
            return "(css " + body.length + " bytes, skipped)";
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private boolean isTextual(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("text/") || normalized.contains("json") || normalized.contains("xml")
                || normalized.contains("javascript") || normalized.contains("x-www-form-urlencoded") || normalized.contains("ndjson");
    }

    private boolean isHtmlResponse(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("text/html") || normalized.contains("application/xhtml+xml");
    }

    private boolean isCssResponse(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("text/css") || normalized.contains("application/css") || normalized.contains("x-css");
    }

    private boolean isStreamingResponse(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("event-stream") || normalized.contains("ndjson");
    }

    /**
     * 响应装饰器：在写出响应体的同时，对非流式响应捕获前 N 字节用于日志打印。
     * 流式响应（SSE/NDJSON）不捕获，直接透传，避免缓冲整个流。
     */
    private final class LoggingResponseDecorator extends ServerHttpResponseDecorator {

        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        private LoggingResponseDecorator(ServerHttpResponse delegate) {
            super(delegate);
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            String contentType = getHeaders().getFirst("Content-Type");
            // 流式响应不捕获，避免缓冲整个流
            if (isStreamingResponse(contentType)) {
                return super.writeWith(body);
            }
            Flux<? extends DataBuffer> captureFlux = Flux.from(body).doOnNext(buffer -> {
                int remaining = MAX_CAPTURE_BYTES - captured.size();
                if (remaining > 0) {
                    int toCopy = Math.min(remaining, buffer.readableByteCount());
                    if (toCopy > 0) {
                        byte[] bytes = new byte[toCopy];
                        // 从读指针位置复制，不移动 readPosition，避免破坏后续写出
                        buffer.read(bytes, 0, toCopy);
                        buffer.readPosition(buffer.readPosition() - toCopy);
                        captured.write(bytes, 0, toCopy);
                    }
                }
            });
            return super.writeWith(captureFlux);
        }

        @Override
        public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
            // 流式刷写场景一律不捕获 body
            return super.writeAndFlushWith(body);
        }

        private byte[] getCapturedBody() {
            return captured.toByteArray();
        }
    }
}
