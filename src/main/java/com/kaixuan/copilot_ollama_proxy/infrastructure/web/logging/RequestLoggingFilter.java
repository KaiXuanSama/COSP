package com.kaixuan.copilot_ollama_proxy.infrastructure.web.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 请求日志过滤器（WebFlux 版本）—— 在请求进入路由前打印 URL 与请求体摘要。
 *
 * 设计要点：
 * 1. 仅在 DEBUG 级别开启时工作，非 DEBUG 模式零开销直接放行
 * 2. 通过 ServerHttpRequestDecorator 缓存并重放请求体，避免日志读取后下游读不到 body
 * 3. 登录请求（/login）跳过，避免打印明文凭据
 *
 * Order 设为较高优先级（负值），确保在大多数业务 WebFilter 之前执行。
 */
@Component
@Order(-100)
public class RequestLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 非 DEBUG 模式或登录请求：零开销直接放行
        if (!log.isDebugEnabled() || "/login".equals(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        // 聚合请求体字节用于打印，同时构造可重放 body 的装饰请求
        return DataBufferUtils.join(request.getBody())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return bytes;
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(bodyBytes -> {
                    logRequest(request, bodyBytes);
                    ServerHttpRequest decorated = decorateWithCachedBody(request, bodyBytes);
                    return chain.filter(exchange.mutate().request(decorated).build());
                });
    }

    /**
     * 打印请求行与请求体摘要（超过 500 字符截断）。
     */
    private void logRequest(ServerHttpRequest request, byte[] bodyBytes) {
        log.debug("================ API 请求 ================");
        log.debug("URL    : {} {}", request.getMethod(), request.getURI());
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
    }

    /**
     * 用缓存的字节重新构造请求体，使下游处理器可以正常读取 body。
     */
    private ServerHttpRequest decorateWithCachedBody(ServerHttpRequest request, byte[] bodyBytes) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                if (bodyBytes.length == 0) {
                    return Flux.empty();
                }
                DataBuffer buffer = new org.springframework.core.io.buffer.DefaultDataBufferFactory().wrap(bodyBytes);
                return Flux.just(buffer);
            }
        };
    }
}

