package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.config.GatewayAuthService;
import com.kaixuan.copilot_ollama_proxy.application.config.GatewayAuthService.GatewayAuthStatus;
import com.kaixuan.copilot_ollama_proxy.application.config.GatewayAuthService.GeneratedKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 下游鉴权（网关 API Key）管理 API。
 *
 * <p>提供设置页「下游鉴权管理」卡片所需的四个操作：读取状态、切换开关、
 * 读取明文（供复制）、重新生成。全部位于 {@code /config/**} 之下，受管理后台
 * JWT 保护，因此明文 Key 只对已登录管理员可见。
 *
 * <p>本控制器只做配置管理，<strong>不涉及</strong>对外聊天接口的实际拦截鉴权。
 */
@RestController
public class GatewayAuthController {

    private final GatewayAuthService gatewayAuthService;

    public GatewayAuthController(GatewayAuthService gatewayAuthService) {
        this.gatewayAuthService = gatewayAuthService;
    }

    /**
     * 读取当前下游鉴权状态（页面初次加载）。
     *
     * @return {@code { enabled, maskedKey, configured }}
     */
    @GetMapping("/config/api/gateway-auth")
    public Mono<GatewayAuthStatus> getStatus() {
        return gatewayAuthService.getStatus();
    }

    /**
     * 切换功能开关。
     *
     * @param enabled 是否开启
     * @return {@code { ok: true }}
     */
    @PostMapping("/config/api/gateway-auth/toggle")
    public Mono<Map<String, Object>> toggle(@RequestParam boolean enabled) {
        return gatewayAuthService.setEnabled(enabled).thenReturn(Map.of("ok", true));
    }

    /**
     * 读取解密后的明文 Key（供前端复制到剪贴板）。
     *
     * @return {@code { apiKey }}；未配置时 apiKey 为空串
     */
    @GetMapping("/config/api/gateway-auth/reveal")
    public Mono<Map<String, Object>> reveal() {
        return gatewayAuthService.revealKey()
                .map(key -> Map.<String, Object>of("apiKey", key == null ? "" : key))
                .defaultIfEmpty(Map.of("apiKey", ""));
    }

    /**
     * 重新生成 Key，返回明文（一次性显示 + 复制）与脱敏值。
     *
     * @return {@code { apiKey, maskedKey }}
     */
    @PostMapping("/config/api/gateway-auth/regenerate")
    public Mono<GeneratedKey> regenerate() {
        return gatewayAuthService.regenerate();
    }
}
