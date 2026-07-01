package com.kaixuan.copilot_ollama_proxy.infrastructure.web.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 请求日志过滤器（WebFlux 版本）—— 在请求进入路由前打印 URL 与请求体摘要。
 *
 * 设计要点：
 * 1. 仅在 DEBUG 级别开启时工作，非 DEBUG 模式零开销直接放行
 * 2. 读取并缓存请求体后，同时重放 getBody() 与（对表单请求）重建 getFormData() 缓存，
 *    避免日志读取消费 body 后，下游 controller 的 getFormData() 读到空表单
 * 3. 登录请求（/login）跳过，避免打印明文凭据
 *
 * Order 设为较高优先级（负值），确保在大多数业务 WebFilter 之前执行。
 */
@Component
@Order(-100)
public class RequestLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    /** 用于重放请求体的共享 DataBuffer 工厂。 */
    private static final org.springframework.core.io.buffer.DefaultDataBufferFactory BUFFER_FACTORY =
            new org.springframework.core.io.buffer.DefaultDataBufferFactory();

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
                    ServerHttpRequest decoratedRequest = decorateWithCachedBody(request, bodyBytes);
                    // 表单请求：预解析 form 并通过 exchange 装饰器重建 getFormData() 缓存，
                    // 否则下游 controller 的 exchange.getFormData() 会因 body 已被消费而读到空表单
                    MultiValueMap<String, String> formData = parseFormDataIfApplicable(request, bodyBytes);
                    ServerWebExchange decoratedExchange = new ServerWebExchangeDecorator(exchange) {
                        @Override
                        public ServerHttpRequest getRequest() {
                            return decoratedRequest;
                        }

                        @Override
                        public Mono<MultiValueMap<String, String>> getFormData() {
                            return formData != null ? Mono.just(formData) : super.getFormData();
                        }
                    };
                    return chain.filter(decoratedExchange);
                });
    }

    /**
     * 若请求是 application/x-www-form-urlencoded，则将缓存的字节解析为表单 Map；否则返回 null。
     */
    private MultiValueMap<String, String> parseFormDataIfApplicable(ServerHttpRequest request, byte[] bodyBytes) {
        MediaType contentType = request.getHeaders().getContentType();
        if (bodyBytes.length == 0 || contentType == null
                || !contentType.isCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED)) {
            return null;
        }
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            formData.add(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return formData;
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
     * 用缓存的字节重新构造请求体，使下游处理器（含 getFormData()）可以正常读取 body。
     *
     * 关键：用 Flux.defer 在每次订阅时新建一份 DataBuffer，保证 body 可被重复读取
     * （getFormData() 与下游处理器可能各订阅一次），避免单一 buffer 被消费后无法再读。
     */
    private ServerHttpRequest decorateWithCachedBody(ServerHttpRequest request, byte[] bodyBytes) {
        return new ServerHttpRequestDecorator(request) {
            @Override
            public Flux<DataBuffer> getBody() {
                if (bodyBytes.length == 0) {
                    return Flux.empty();
                }
                return Flux.defer(() -> Flux.just(BUFFER_FACTORY.wrap(bodyBytes)));
            }
        };
    }
}

