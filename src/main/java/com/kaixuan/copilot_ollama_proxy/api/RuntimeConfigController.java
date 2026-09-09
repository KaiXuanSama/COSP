package com.kaixuan.copilot_ollama_proxy.api;

import com.kaixuan.copilot_ollama_proxy.application.config.RuntimeConfigService;
import com.kaixuan.copilot_ollama_proxy.application.config.RuntimeConfigService.RuntimeConfigView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 管理后台运行时配置 API。
 *
 * <p>读取采用聚合接口 {@code GET /config/api/runtime-config}，一次性返回设置页所需的
 * 全部配置（伪造版本号、下游鉴权状态等），敏感值已在服务层脱敏。写入仍由各项专项接口负责。
 */
@RestController
public class RuntimeConfigController {

    private final RuntimeConfigService runtimeConfigService;

    public RuntimeConfigController(RuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    /**
     * 聚合读取全部运行时配置（设置页初次加载调用）。
     *
     * @return 结构化配置视图
     */
    @GetMapping("/config/api/runtime-config")
    public Mono<RuntimeConfigView> getRuntimeConfig() {
        return runtimeConfigService.getRuntimeConfig();
    }

    /**
     * 保存伪造版本号。
     *
     * @param fakeVersion 版本号
     * @return {@code { ok: true }}
     */
    @PostMapping("/config/api/fake-version")
    public Mono<Map<String, Object>> saveFakeVersion(@RequestParam String fakeVersion) {
        return runtimeConfigService.saveFakeVersion(fakeVersion).thenReturn(Map.of("ok", true));
    }

    /**
     * 保存出站代理地址。
     *
     * <p>空值表示清空（没有代理，全部直连）。地址改动即时生效：保存后决策中心的内存值同步更新，
     * 下一个出站连接就用新地址。不校验 {@code host:port} 形态 —— 面向个人使用，填错时表现为
     * 该目标连不上，用户自查即可，不值得为此加一层可能误拒 IPv6 / 带认证代理的格式校验。
     *
     * @param address 形如 {@code host:port} 的地址，空表示清空
     * @return {@code { ok: true }}
     */
    @PostMapping("/config/api/proxy-address")
    public Mono<Map<String, Object>> saveProxyAddress(@RequestParam(required = false, defaultValue = "") String address) {
        return runtimeConfigService.saveProxyAddress(address).thenReturn(Map.of("ok", true));
    }

    /**
     * 保存上游重试次数。
     *
     * <p>值非法（超出 {@code [-1, 100]}）时返回 400 并带上原因，不落库。
     * 没有 {@code @ControllerAdvice}，故在此就地处理。
     *
     * @param maxAttempts {@code -1} 无限重试，{@code 0} 不重试，正数为具体次数
     * @return {@code { ok: true }}；非法值返回 400 与 {@code { ok: false, message }}
     */
    @PostMapping("/config/api/retry-policy")
    public Mono<ResponseEntity<Map<String, Object>>> saveRetryMaxAttempts(@RequestParam int maxAttempts) {
        return runtimeConfigService.saveRetryMaxAttempts(maxAttempts)
                .thenReturn(ResponseEntity.ok(Map.<String, Object>of("ok", true)))
                .onErrorResume(IllegalArgumentException.class, error ->
                        Mono.just(ResponseEntity.badRequest()
                                .body(Map.of("ok", false, "message", error.getMessage()))));
    }
}