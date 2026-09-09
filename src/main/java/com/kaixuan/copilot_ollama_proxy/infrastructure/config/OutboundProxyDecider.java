package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 决定某个出站目标是否走 HTTP 代理。
 *
 * <h2>为何是一个谓词而不是两个 HttpClient</h2>
 * 代理是<strong>连接级</strong>设置，{@code WebClient.Builder.clone()} 克隆不出新的
 * connector（所有克隆共享同一个 {@code ReactorClientHttpConnector}），因此无法在克隆
 * 上改代理。表面上的出路是准备「直连」与「走代理」两个 {@code HttpClient}，由调用方按
 * 供应商挑一个 —— 但那样要处理 Bean 歧义，三个出站注入点都得改，代理地址变更时还要清
 * 连接池缓存。
 *
 * <p>Reactor Netty 自带更合适的接缝：{@code ProxyProvider.Builder#nonProxyHostsPredicate}
 * 在<strong>每次建立连接时</strong>询问「这个目标要不要绕过代理」。于是全局只需一个
 * {@code HttpClient}：它始终挂着代理配置，由本类逐个目标回答走或不走。
 *
 * <h2>判定依据是目标地址，不是供应商</h2>
 * 这是本方案唯一的实质代价，必须知道。谓词只能看到 {@link SocketAddress}，拿不到
 * 「这次请求属于哪个供应商」——那个上下文在连接建立时已经不在栈上了。因此两个供应商
 * 若共用同一个 host:port 而代理开关不同，本类无法区分，会按同一种策略处理。
 *
 * <p>接受这个代价的理由：同 host 同端口却要求不同代理策略，在「中转站各有自己域名」
 * 的现实里几乎不存在；而换取的是零 Bean 歧义、出站注入点一行不改、代理地址天然热更新。
 * 若某天真的撞上这种配置，届时再退回双 {@code HttpClient} 方案，
 * <strong>不要试图把供应商上下文塞进连接层</strong>——那需要跨越 Reactor Netty 的连接池，
 * 复杂度远超收益。
 *
 * <h2>只决定连接，不决定 DNS</h2>
 * 本类的判定只喂给 {@code nonProxyHostsPredicate}，即只影响「连接走不走代理」。
 * 上游域名的解析始终由本机负责（{@code WebClientConfig} 职责一），两件事分开。
 *
 * <p>若将来需要让某些目标的 DNS 也交给代理（本机解析不了那个域名时），那个判定<strong>必须
 * 复用本类</strong>而不是另立一份清单，否则会出现「解析交给了代理但连接没走代理」这类
 * 自相矛盾的组合。背景见 {@code WebClientConfig#httpClient} 的风险说明。
 *
 * <h2>当前阶段：只有全局开关</h2>
 * 供应商粒度的开关（{@code provider_config.use_proxy}）尚未落库，因此
 * {@link #proxiedTargets} 现在是空集，而 {@link #isDirect} 在全局开关打开时对
 * <strong>所有</strong>目标返回「走代理」，与本功能之前的全局代理行为一致。
 * 接上那一列之后，只需让目录层调用 {@link #replaceProxiedTargets} 灌入
 * 「开了开关的供应商的 host:port 集合」，判定逻辑本身不用改。
 */
@Component
public class OutboundProxyDecider {

    private static final Logger log = LoggerFactory.getLogger(OutboundProxyDecider.class);

    /**
     * 全局代理总闸。
     *
     * <p>关掉即所有出站直连，等价于没配代理 —— 这一档存在的意义是「代理挂了想快速排除
     * 它的影响」时不必去清空地址，也不必逐个关供应商开关。
     */
    private final boolean enabled;

    /**
     * 应当走代理的目标集合，元素形如 {@code host:port}（小写）。
     *
     * <p>{@code CopyOnWriteArraySet} 而非普通 {@code Set}：读发生在每次建立连接的热路径上
     * 且完全并发，写只在供应商配置变更时发生，读多写极少正是它的适用场景。
     */
    private final Set<String> proxiedTargets = new CopyOnWriteArraySet<>();

    /**
     * 集合为空时的判定。
     *
     * <p>当前恒为 {@code true}（空集 = 全部走代理），保持接上供应商开关之前的既有行为。
     * 供应商粒度落库后应改为 {@code false}（空集 = 谁都不走），否则「所有供应商都关了开关」
     * 会被误判成「所有供应商都走代理」。
     */
    private static final boolean PROXY_ALL_WHEN_EMPTY = true;

    public OutboundProxyDecider(@Value("${http.proxy.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 全局代理是否启用。
     *
     * <p>{@code WebClientConfig} 用它决定要不要给 {@code HttpClient} 装上
     * {@code ProxyProvider} —— 关闭时不装，连谓词都不会被调用。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 目标是否应当<strong>绕过</strong>代理。
     *
     * <p>语义是反的（返回 true 表示直连），因为 Reactor Netty 的钩子叫
     * {@code nonProxyHostsPredicate} —— 保持与它同向，避免在接线处再做一次取反。
     *
     * @param address 即将连接的目标地址；非 {@link InetSocketAddress} 时保守直连
     * @return true 表示不走代理
     */
    public boolean isDirect(SocketAddress address) {
        if (!enabled) {
            return true;
        }
        if (!(address instanceof InetSocketAddress inet)) {
            // Unix domain socket 之类的非 IP 目标：代理无从施加，直连。
            return true;
        }
        if (proxiedTargets.isEmpty()) {
            return !PROXY_ALL_WHEN_EMPTY;
        }
        return !proxiedTargets.contains(targetKey(inet));
    }

    /**
     * 整体替换应走代理的目标集合。
     *
     * <p>整体替换而非增量增删：供应商配置的真源是数据库，每次变更后重新投影一次全集
     * 最不容易漏（增量要处理「开关关掉」「供应商删除」「base_url 改了」三种撤销路径，
     * 漏一种就会留下一个再也清不掉的陈旧目标）。
     *
     * @param targets 形如 {@code host:port} 的目标；null 或空表示回到默认判定
     */
    public void replaceProxiedTargets(Set<String> targets) {
        proxiedTargets.clear();
        if (targets == null || targets.isEmpty()) {
            log.debug("[Proxy] 走代理的目标集合已清空");
            return;
        }
        for (String target : targets) {
            if (target != null && !target.isBlank()) {
                proxiedTargets.add(target.trim().toLowerCase(Locale.ROOT));
            }
        }
        log.info("[Proxy] 走代理的目标集合更新为 {} 项: {}", proxiedTargets.size(), proxiedTargets);
    }

    /**
     * 归一化目标键。
     *
     * <p>取 {@code getHostString()} 而非 {@code getHostName()}：后者会触发反向 DNS 查询，
     * 在建立连接的热路径上做同步 DNS 是不可接受的。
     */
    private static String targetKey(InetSocketAddress address) {
        return (address.getHostString() + ":" + address.getPort()).toLowerCase(Locale.ROOT);
    }
}
