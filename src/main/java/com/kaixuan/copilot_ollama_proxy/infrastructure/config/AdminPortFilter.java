package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 管理端口过滤器 —— 确保 80 端口只响应管理相关路径（/login、/config、/logout）。
 *
 * 由于 Tomcat 的所有 Connector 共享同一个 DispatcherServlet，
 * 如果不加此过滤，访问 http://127.0.0.1:80/api/tags 也会命中代理接口。
 * 本过滤器通过检查本地端口，拦截非管理路径的请求并返回 404。
 */
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class AdminPortFilter extends OncePerRequestFilter {

    private static final Set<String> ADMIN_PATHS = Set.of("/login", "/config", "/logout");

    @Value("${admin.server.port:80}")
    private int adminPort;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        int localPort = request.getLocalPort();

        // 如果请求来自管理端口，但路径不是管理路径，直接返回 404
        if (localPort == adminPort) {
            String path = request.getRequestURI();
            if (!ADMIN_PATHS.contains(path) && !path.startsWith("/admin/css/")) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
