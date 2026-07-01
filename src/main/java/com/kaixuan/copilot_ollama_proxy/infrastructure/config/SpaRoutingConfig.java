package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * 前端 SPA 路由配置（WebFlux）。
 *
 * Vue Router 使用 createWebHistory() 模式，所有前端路由（如 /login、/overview）
 * 需要回退返回 index.html，由 Vue 在浏览器端接管路由。静态资源（/assets、/img）
 * 由 Spring Boot 的静态资源处理器负责。
 *
 * 取代旧的 MVC @Controller 中 "forward:/index.html" 写法。
 */
@Configuration
public class SpaRoutingConfig {

    private final Resource indexHtml = new ClassPathResource("static/index.html");

    /**
     * 为各 SPA 前端路由返回 index.html。
     *
     * @return SPA 路由的 RouterFunction
     */
    @Bean
    public RouterFunction<ServerResponse> spaRoutes() {
        return RouterFunctions.route()
                .GET("/login", req -> serveIndex())
                .GET("/overview", req -> serveIndex())
                .GET("/settings", req -> serveIndex())
                .GET("/account", req -> serveIndex())
                .GET("/call-log", req -> serveIndex())
                .build();
    }

    /**
     * 返回 index.html 资源。
     */
    private reactor.core.publisher.Mono<ServerResponse> serveIndex() {
        return ServerResponse.ok().contentType(MediaType.TEXT_HTML).bodyValue(indexHtml);
    }
}
