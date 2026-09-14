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
 * 本类必须在 {@code SchemaMigrationRunner} <strong>之后</strong>运行：它读的
 * {@code use_proxy} / {@code responses_base_url} 等列可能正是那次迁移才补上的，
 * 早于迁移查询会直接 {@code no such column} 让整个应用启动失败。
 *
 * <p>该次序由<strong>迁移器自己</strong>的 {@code @Order(HIGHEST_PRECEDENCE)} 保证，
 * 不靠本类这个 {@code @Order(100)}。此处的 100 只表达「不必最早」，没有别的含义。
 *
 * <p>这段注释曾写着「{@code @Order} 放到较大值，确保排在迁移器（默认顺序）之后」——
 * <strong>方向是反的</strong>：{@code ApplicationRunner} 按 {@code @Order} 升序执行，
 * 无注解的迁移器取 {@code LOWEST_PRECEDENCE}（最大值），于是本类反而跑在了它前面。
 * 那个错误假设直到 V13 才暴露，成因见迁移器的类注释。
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
