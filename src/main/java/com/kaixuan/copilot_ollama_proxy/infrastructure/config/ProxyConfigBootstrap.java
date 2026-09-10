package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import com.kaixuan.copilot_ollama_proxy.application.config.RuntimeConfigService;
import com.kaixuan.copilot_ollama_proxy.application.provider.OutboundProxyTargetProjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时把持久化的代理配置带进内存。
 *
 * <p>两件事都是「冷路径灌入内存」，与 {@link OutboundProxyDecider} 的两个内存槽一一对应：
 * <ul>
 *   <li>代理地址：从 {@code app_config} 读出灌给 decider，见
 *       {@link RuntimeConfigService#loadProxyAddressIntoDecider()}；</li>
 *   <li>代理目标集合：把当前开了 {@code use_proxy} 的供应商投影成 {@code host:port} 集合，见
 *       {@link OutboundProxyTargetProjector#reprojectProxiedTargets()}。</li>
 * </ul>
 *
 * <h2>为何要在启动时投影一次</h2>
 * {@code proxiedTargets} 是内存缓存，不重启就不会自己填充。若只在「供应商配置变更」时投影，
 * 那么重启之后、到用户第一次改配置之前，集合一直是空的 —— 期间所有本该走代理的供应商都会
 * 直连。启动投影一次消除这个空窗。
 *
 * <h2>顺序</h2>
 * {@code @Order} 放到较大值，确保排在 {@code SchemaMigrationRunner}（默认顺序）之后：
 * 迁移把 {@code use_proxy} 列补齐、基线建好之后再读，才不会在空库或缺列的库上查询失败。
 */
@Component
@Order(100)
public class ProxyConfigBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProxyConfigBootstrap.class);

    private final RuntimeConfigService runtimeConfigService;
    private final OutboundProxyTargetProjector proxyTargetProjector;

    public ProxyConfigBootstrap(RuntimeConfigService runtimeConfigService,
                                OutboundProxyTargetProjector proxyTargetProjector) {
        this.runtimeConfigService = runtimeConfigService;
        this.proxyTargetProjector = proxyTargetProjector;
    }

    @Override
    public void run(ApplicationArguments args) {
        runtimeConfigService.loadProxyAddressIntoDecider();
        proxyTargetProjector.reprojectProxiedTargets();
        log.info("[Proxy] 启动期代理配置已载入内存");
    }
}
