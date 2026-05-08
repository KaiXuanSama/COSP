package com.kaixuan.copilot_ollama_proxy.infrastructure.web.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 请求日志过滤器 —— 在所有请求到达 Controller 之前拦截并打印日志。
 * 工作流程：
 * 1. 读取原始请求的 body 字节流并缓存
 * 2. 用缓存后的 body 构建包装请求（{@link CachedBodyHttpServletRequest}）
 * 3. 打印 URL、请求体等日志信息
 * 4. 将包装请求传递给后续的 Filter 和 Controller
 * 为什么需要包装请求？
 * 因为 HTTP 请求的 body 是一次性流，读取一次后就无法再次读取。
 * 如果不包装，日志读了一次 body，Controller 就读不到了。
 */
@Component
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/login".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 非 DEBUG 模式直接放行，不做任何 body 读取和包装，零开销
        if (!log.isDebugEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 读取请求体的原始字节（一次性读完）
        byte[] bodyBytes = request.getInputStream().readAllBytes();
        // 用缓存后的 body 包装原始请求，这样 body 可以被多次读取
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request, bodyBytes);

        // 打印请求日志
        log.debug("================ API 请求 ================");
        log.debug("URL    : {} {}", wrappedRequest.getMethod(), wrappedRequest.getRequestURL());

        // 打印请求体（超过 500 字符则截断，避免刷屏）
        if (bodyBytes.length > 0) {
            String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
            if (bodyStr.length() > 500) {
                bodyStr = bodyStr.substring(0, 500) + "... ( 余 " + (bodyStr.length() - 500) + " 字符 )";
            }
            log.debug("Body   : {}", bodyStr);
        } else {
            log.debug("Body   : (empty)");
        }
        log.debug("==========================================");

        // 将包装后的请求传递给后续的 Controller 处理
        filterChain.doFilter(wrappedRequest, response);
    }

    /**
     * 缓存请求体的 HttpServletRequest 包装器。
     * 核心思想：在构造时就把 body 读出来存到字节数组里，
     * 之后每次调用 {@code getInputStream()} 或 {@code getReader()} 都从这个数组读取，
     * 从而实现 body 的"多次读取"。
     */
    private static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        /** 缓存的请求体字节数组 */
        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
            super(request);
            this.cachedBody = cachedBody;
        }

        /** 重写 getInputStream，返回从缓存数组读取的流 */
        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(cachedBody);
        }

        /** 重写 getReader，返回从缓存数组读取的字符流 */
        @Override
        public BufferedReader getReader() {
            ByteArrayInputStream bais = new ByteArrayInputStream(cachedBody);
            return new BufferedReader(new InputStreamReader(bais, StandardCharsets.UTF_8));
        }

        /**
         * 基于字节数组的 ServletInputStream 实现。
         * 本质上就是一个 ByteArrayInputStream 的包装，满足 Servlet 规范要求的接口。
         */
        private static class CachedBodyServletInputStream extends ServletInputStream {

            private final ByteArrayInputStream bais;

            CachedBodyServletInputStream(byte[] cachedBody) {
                this.bais = new ByteArrayInputStream(cachedBody);
            }

            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read() {
                return bais.read();
            }
        }
    }
}
