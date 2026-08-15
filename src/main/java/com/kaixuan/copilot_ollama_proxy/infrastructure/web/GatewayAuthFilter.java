package com.kaixuan.copilot_ollama_proxy.infrastructure.web;

import com.kaixuan.copilot_ollama_proxy.application.config.GatewayAuthService;
import com.kaixuan.copilot_ollama_proxy.application.config.GatewayAuthService.AuthDecision;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 下游鉴权过滤器 —— 仅拦截对外聊天接口。
 *
 * <p>这是面向「部署到公网供自己使用」场景的防滥用闸门：当管理员在后台开启下游鉴权后，
 * 来自客户端（如 VS Code Copilot 的 Ollama 供应商、Claude Desktop）的聊天请求
 * 必须携带正确的网关 API Key（{@code Authorization: Bearer <key>}）才会被放行。
 *
 * <p><strong>拦截范围严格限定</strong>为真正消耗上游额度的两个聊天端点
 * （{@code POST /v1/chat/completions} 与 {@code POST /v1/messages}）：
 * <ul>
 *   <li>Ollama 模型发现接口（{@code /api/version}、{@code /api/tags}、{@code /api/show}）
 *       本身不携带 Authorization 头，若一并拦截会导致 Copilot 连模型都发现不了，故放行；</li>
 *   <li>{@code /v1/models} 等其它接口同样放行；</li>
 *   <li>管理后台接口在独立的 Security JWT 链下，与本过滤器互不干扰。</li>
 * </ul>
 *
 * <p>两个聊天端点<strong>共用同一把网关 Key</strong>：它保护的是「谁能用这个代理」，
 * 与下游说哪种协议无关。按协议分设不同 Key 只会增加管理成本而不增加安全性。
 *
 * <p>本过滤器只读取请求头、不消费请求体，天然避开 WebFlux 请求体重放的坑。
 * 具体的开关判断、Key 解密与常量时间比对都内聚在 {@link GatewayAuthService#authorize}。
 *
 * <p>Order 设为 -120，早于请求/响应日志过滤器（-100 / -110），
 * 使被拒绝的请求无需进入日志管线；晚于 SPA 路由回退（-200）。
 */
@Component
@Order(-120)
public class GatewayAuthFilter implements WebFilter {

    /**
     * 受保护的目标路径集合 —— 两个协议的聊天端点。
     *
     * <p>用 {@code Set} 而非单值：两条端点的鉴权语义完全相同（同一把网关 Key、
     * 同一个 401 响应），差别只在路径。将来若再有新的聊天端点，加一行即可。
     */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/v1/chat/completions",   // OpenAI Chat Completions
            "/v1/messages");          // Anthropic Messages

    /** 401 响应体，OpenAI 风格错误结构，便于客户端展示可读信息。 */
    private static final String UNAUTHORIZED_BODY =
            "{\"error\":{\"message\":\"无效或缺失的 API Key\",\"type\":\"invalid_api_key\"}}";

    private final GatewayAuthService gatewayAuthService;

    public GatewayAuthFilter(GatewayAuthService gatewayAuthService) {
        this.gatewayAuthService = gatewayAuthService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // 仅拦截两个聊天端点的 POST，其它路径/方法一律直接放行。
        if (!isProtected(exchange)) {
            return chain.filter(exchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        return gatewayAuthService.authorize(authorization)
                .flatMap(decision -> decision == AuthDecision.PASS
                        ? chain.filter(exchange)
                        : writeUnauthorized(exchange.getResponse()));
    }

    /** 判断当前请求是否为受保护的聊天端点。 */
    private boolean isProtected(ServerWebExchange exchange) {
        return HttpMethod.POST.equals(exchange.getRequest().getMethod())
                && PROTECTED_PATHS.contains(exchange.getRequest().getPath().value());
    }

    /** 写出 401 响应。 */
    private Mono<Void> writeUnauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory()
                .wrap(UNAUTHORIZED_BODY.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
