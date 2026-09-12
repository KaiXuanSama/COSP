package com.kaixuan.copilot_ollama_proxy.application.provider;

import com.kaixuan.copilot_ollama_proxy.infrastructure.config.OutboundProxyDecider;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRepository;
import com.kaixuan.copilot_ollama_proxy.infrastructure.persistence.ProviderConfigRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 把「开了 {@code use_proxy} 的供应商」投影成 {@code host:port} 目标集合，灌给
 * {@link OutboundProxyDecider}。
 *
 * <h2>为什么需要投影这一步</h2>
 * {@code OutboundProxyDecider} 在连接层只能看到目标地址（{@code host:port}），看不到供应商。
 * 而「走不走代理」的真源是供应商的 {@code use_proxy} 开关。这中间的鸿沟由本类跨越：
 * 把每个开了开关的供应商的<strong>端点地址</strong>解析出来，汇成一个集合，
 * 决策中心便能在建连时用纯地址判定。
 *
 * <h2>三个端点都要投影</h2>
 * 一个供应商可能有三个不同的上游端点：Chat 线路的 {@code base_url}、Anthropic 线路的
 * {@code anthropic_base_url}、Responses 线路的 {@code responses_base_url}
 * （后两者为空时回退到前者）。三条线路的出站连接都应遵守该供应商的代理开关，
 * 所以三个端点都要投影 —— 漏掉任一个都会让那条直连线路绕过代理。
 *
 * <p>漏投的症状<strong>特别难认</strong>：用户开了代理，其余线路正常、只有一条直连出去，
 * 看起来像「代理时好时坏」而不是配置缺了一行。新增线路协议时必须同步这里。
 *
 * <h2>整体重投影，不做增量</h2>
 * 每次配置变更后重新扫全表投影一次全集，而非针对单个供应商增删。原因与
 * {@link OutboundProxyDecider#replaceProxiedTargets} 的说明一致：增量要处理开关关闭、
 * 供应商删除、地址修改三种撤销路径，漏一种就会留下永远清不掉的陈旧目标。全量重投影天然幂等。
 */
@Service
public class OutboundProxyTargetProjector {

    private static final Logger log = LoggerFactory.getLogger(OutboundProxyTargetProjector.class);

    private final ProviderConfigRepository providerConfigRepository;
    private final OutboundProxyDecider proxyDecider;

    public OutboundProxyTargetProjector(ProviderConfigRepository providerConfigRepository,
                                        OutboundProxyDecider proxyDecider) {
        this.providerConfigRepository = providerConfigRepository;
        this.proxyDecider = proxyDecider;
    }

    /**
     * 扫描全部供应商，把开了代理开关的那些的端点投影成 {@code host:port} 集合并灌入决策中心。
     *
     * <p><strong>阻塞 JDBC，只能在冷路径调用</strong>：启动加载与供应商配置变更之后。
     * 绝不能放进聊天请求的响应式链或建连热路径。
     */
    public void reprojectProxiedTargets() {
        Set<String> targets = new LinkedHashSet<>();
        for (ProviderConfigRow provider : providerConfigRepository.findAllWithModels()) {
            if (!provider.useProxy()) {
                continue;
            }
            addTarget(targets, provider.baseUrl());
            // 两个独立端点为空时回退 base_url —— 与运行时的 resolveXxxBaseUrl 同口径，
            // 避免「配置里没单独填」的供应商漏掉它实际会连的那个地址。
            // 集合去重，因此回退到同一个地址时不会重复投影。
            addTarget(targets, fallbackToBaseUrl(provider.anthropicBaseUrl(), provider.baseUrl()));
            addTarget(targets, fallbackToBaseUrl(provider.responsesBaseUrl(), provider.baseUrl()));
        }
        proxyDecider.replaceProxiedTargets(targets);
    }

    /**
     * 协议专属端点为空时回退到 {@code base_url}。
     *
     * <p>与 {@code ProviderRuntimeConfiguration.resolveAnthropicBaseUrl} /
     * {@code resolveResponsesBaseUrl} 必须同口径：投影按 {@code host:port} 精确匹配，
     * 这里算出的地址与运行时实际连的地址不一致就等于没配代理。
     */
    private static String fallbackToBaseUrl(String protocolBaseUrl, String baseUrl) {
        return protocolBaseUrl == null || protocolBaseUrl.isBlank() ? baseUrl : protocolBaseUrl;
    }

    /**
     * 从一个基础 URL 解析出 {@code host:port} 并加入集合。
     *
     * <p>端口按 URL 的 scheme 推断：显式端口优先，否则 https→443、http→80。
     * 这必须与 Reactor Netty 建连时实际使用的目标端口一致 —— 决策中心按 {@code host:port}
     * 精确匹配，端口对不上就等于没配。解析失败（URL 畸形、无 host）时跳过并告警，
     * 不让一个坏地址阻断其余供应商的投影。
     */
    private void addTarget(Set<String> targets, String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                log.warn("[Proxy] 供应商端点无法解析出主机，跳过投影: {}", baseUrl);
                return;
            }
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            targets.add((host + ":" + port).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            log.warn("[Proxy] 供应商端点地址畸形，跳过投影: {}", baseUrl);
        }
    }
}
