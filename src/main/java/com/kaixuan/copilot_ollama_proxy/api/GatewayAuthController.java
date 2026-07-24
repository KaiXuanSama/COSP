package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.config.GatewayAuthService;
import com.kaixuan.copilot_ollama_proxy.application.config.GatewayAuthService.GeneratedKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 下游鉴权（网关 API Key）管理 API —— 只负责<strong>写</strong>操作：
 * 切换开关、读取明文（供复制）、重新生成。
 *
 * <p>状态<strong>读取</strong>已并入聚合接口 {@code GET /config/api/runtime-config}
 * （见 {@code RuntimeConfigController}），本控制器不再单独提供 GET 状态接口。
 *
 * <p>全部位于 {@code /config/**} 之下，受管理后台 JWT 保护，明文 Key 只对已登录管理员可见。
 * 本控制器不涉及对外聊天接口的实际拦截鉴权。
 */
@RestController
public class GatewayAuthController {

    private final GatewayAuthService gatewayAuthService;

    public GatewayAuthController(GatewayAuthService gatewayAuthService) {
        this.gatewayAuthService = gatewayAuthService;
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
