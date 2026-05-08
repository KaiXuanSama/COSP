package com.kaixuan.copilot_ollama_proxy.infrastructure.web.logging;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 响应日志过滤器 —— 在响应返回给客户端时打印状态码、内容类型和响应体摘要。
 *
 * 设计目标：
 * 1. 普通 JSON/文本响应要能看到完整或截断后的 body
 * 2. SSE/NDJSON 等流式响应不能因为日志而被整体缓冲
 * 3. Spring MVC 异步响应（Mono/Flux/SseEmitter）完成后仍然可以记录最终结果
 */
@Component
public class ResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ResponseLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 非 DEBUG 模式直接放行，不做任何包装和日志记录
        if (!log.isDebugEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 强制将响应字符编码设置为 UTF-8，防止 Spring 的 StringHttpMessageConverter
        // 使用 ISO-8859-1 导致汉字在日志的 Tee 缓冲区中出现乱码。
        if (response.getCharacterEncoding() == null
                || StandardCharsets.ISO_8859_1.name().equalsIgnoreCase(response.getCharacterEncoding())) {
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        }

        long startedAtNanos = System.nanoTime();
        LoggingHttpServletResponse wrappedResponse = new LoggingHttpServletResponse(response);

        filterChain.doFilter(request, wrappedResponse);

        if (request.isAsyncStarted()) {
            AtomicBoolean logged = new AtomicBoolean(false);
            request.getAsyncContext().addListener(
                    new ResponseLoggingAsyncListener(this, request, wrappedResponse, startedAtNanos, logged));
            return;
        }

        logResponse(request, wrappedResponse, startedAtNanos);
    }

    private void logResponse(HttpServletRequest request, LoggingHttpServletResponse response, long startedAtNanos) {
        try {
            response.flushBuffer();
        } catch (IOException exception) {
            log.debug("响应刷新失败，继续输出日志", exception);
        }

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

        log.debug("=============== API 响应 ================");
        log.debug("URL    : {} {}", request.getMethod(), request.getRequestURL());
        log.debug("Status : {}", response.getStatus());
        log.debug("Type   : {}", response.getContentType() != null ? response.getContentType() : "(unknown)");
        log.debug("Time   : {} ms", elapsedMillis);
        // 流式响应（SSE/NDJSON）不打印 body，因为流式事件日志已在协议转换层逐条打印
        if (isStreamingResponse(response)) {
            log.debug("Body   : (streaming, skipped)");
        } else {
            log.debug("Body   : {}", renderBody(response));
        }
        log.debug("==========================================");
    }

    private String renderBody(LoggingHttpServletResponse response) {
        byte[] body = response.getCapturedBody();
        if (body.length == 0) {
            return "(empty)";
        }

        if (!isTextual(response.getContentType())) {
            return "(binary " + body.length + " bytes captured)";
        }

        if (isHtmlResponse(response.getContentType())) {
            return "(html " + body.length + " bytes, skipped)";
        }

        Charset charset = resolveCharset(response.getCharacterEncoding());
        return new String(body, charset);
    }

    private boolean isTextual(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }

        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("text/") || normalized.contains("json") || normalized.contains("xml")
                || normalized.contains("javascript") || normalized.contains("x-www-form-urlencoded")
                || normalized.contains("ndjson");
    }

    /**
     * 判断当前响应是否为 HTML 响应（Content-Type 包含 text/html 或 application/xhtml+xml）。
     * HTML 响应体通常较长且包含大量模板内容，打印到日志中意义不大，直接跳过。
     */
    private boolean isHtmlResponse(String contentType) {
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("text/html") || normalized.contains("application/xhtml+xml");
    }

    /**
     * 判断当前响应是否为流式响应（Content-Type 包含 event-stream 或 ndjson）。
     * 在 controller 设置完 Content-Type 之后调用，因此能准确判断。
     */
    private boolean isStreamingResponse(HttpServletResponse response) {
        String contentType = response.getContentType();
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("event-stream") || normalized.contains("ndjson");
    }

    private Charset resolveCharset(String encoding) {
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }

        try {
            return Charset.forName(encoding);
        } catch (Exception exception) {
            return StandardCharsets.UTF_8;
        }
    }

    private static final class ResponseLoggingAsyncListener implements AsyncListener {

        private final ResponseLoggingFilter filter;
        private final HttpServletRequest request;
        private final LoggingHttpServletResponse response;
        private final long startedAtNanos;
        private final AtomicBoolean logged;

        private ResponseLoggingAsyncListener(ResponseLoggingFilter filter, HttpServletRequest request,
                LoggingHttpServletResponse response, long startedAtNanos, AtomicBoolean logged) {
            this.filter = filter;
            this.request = request;
            this.response = response;
            this.startedAtNanos = startedAtNanos;
            this.logged = logged;
        }

        @Override
        public void onComplete(AsyncEvent event) {
            if (logged.compareAndSet(false, true)) {
                filter.logResponse(request, response, startedAtNanos);
            }
        }

        @Override
        public void onTimeout(AsyncEvent event) {
        }

        @Override
        public void onError(AsyncEvent event) {
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            event.getAsyncContext().addListener(this);
        }
    }

    private static final class LoggingHttpServletResponse extends HttpServletResponseWrapper {

        private final TeeBuffer teeBuffer;

        private TeeServletOutputStream outputStream;
        private PrintWriter writer;

        private LoggingHttpServletResponse(HttpServletResponse response) {
            super(response);
            this.teeBuffer = new TeeBuffer();
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (writer != null) {
                throw new IllegalStateException("getWriter() has already been called on this response.");
            }

            if (outputStream == null) {
                outputStream = new TeeServletOutputStream(super.getOutputStream(), teeBuffer);
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (outputStream != null) {
                throw new IllegalStateException("getOutputStream() has already been called on this response.");
            }

            if (writer == null) {
                outputStream = new TeeServletOutputStream(super.getOutputStream(), teeBuffer);
                writer = new PrintWriter(new OutputStreamWriter(outputStream, resolveEncoding(getCharacterEncoding())),
                        true);
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            if (outputStream != null) {
                outputStream.flush();
            }
            super.flushBuffer();
        }

        private byte[] getCapturedBody() {
            return teeBuffer.toByteArray();
        }

        private Charset resolveEncoding(String encoding) {
            if (encoding == null || encoding.isBlank()) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(encoding);
            } catch (Exception exception) {
                return StandardCharsets.UTF_8;
            }
        }
    }

    private static final class TeeServletOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final TeeBuffer teeBuffer;

        private TeeServletOutputStream(ServletOutputStream delegate, TeeBuffer teeBuffer) {
            this.delegate = delegate;
            this.teeBuffer = teeBuffer;
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            teeBuffer.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            teeBuffer.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class TeeBuffer {

        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        private void write(int value) {
            captured.write(value);
        }

        private void write(byte[] bytes, int offset, int length) {
            captured.write(bytes, offset, length);
        }

        private byte[] toByteArray() {
            return captured.toByteArray();
        }
    }
}