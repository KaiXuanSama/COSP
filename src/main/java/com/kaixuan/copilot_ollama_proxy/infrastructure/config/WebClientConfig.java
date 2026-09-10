package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.net.InetSocketAddress;

/**
 * WebClient 与 WebFlux 编解码全局配置。
 *
 * 职责一：将 Reactor Netty 的 DNS 解析器从默认的异步解析器
 * （DnsAddressResolverGroup）切换为 JDK 系统解析器（DefaultAddressResolverGroup）。
 *
 * 背景问题：Netty 默认的异步 DNS 解析器在 Windows 上无法可靠读取系统
 * nameserver 配置，首次查询超时即放弃（日志表现为 "Failed to resolve 'xxx' [A(1)]"），
 * 并将失败结果负缓存一段时间，导致部分域名间歇性解析失败、过若干分钟后又自动恢复。
 *
 * DefaultAddressResolverGroup 走 JVM 的 InetAddress（即操作系统 DNS 栈 + hosts），
 * 与 nslookup、浏览器走同一条解析路径，行为一致且可靠，从根源消除该问题。
 *
 * 职责二：硬编码内存编解码缓冲上限为 64MB。
 *
 * 背景问题：WebFlux 默认内存缓冲上限仅 256KB，Copilot 大上下文请求体（含历史
 * 消息、文件上下文等）会超限触发 413 Payload Too Large。此处在代码层统一硬编码，
 * 同时覆盖服务端（接收 Copilot 请求）与客户端（WebClient 调用上游）两条链路，
 * 不再依赖 application.yml 的 spring.codec.max-in-memory-size 配置。
 *
 * 之所以保留上限（而非设为无限制 -1），是为了保留一道 OOM 防护：
 * 64MB 对实际场景已绰绰有余，又能避免异常超大请求/响应耗尽堆内存拖垮服务。
 *
 * 职责三：出站 HTTP 代理，按目标逐个判定是否绕过，见 {@link #httpClient}。
 *
 * DNS 仍由本机解析（即职责一继续生效），代理只承担连接转发。这个分工对本地全局代理成立，
 * 但对「域名在本机根本解析不了」的上游不成立 —— 那种情况当前刻意不支持，
 * 风险与修法记在 {@link #httpClient} 的说明里。
 */
@Configuration
public class WebClientConfig implements WebFluxConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    /** 内存编解码缓冲上限：64MB，足以容纳 Copilot 大上下文请求与上游大响应。 */
    private static final int MAX_IN_MEMORY_SIZE = 64 * 1024 * 1024;

    /** 代理端点默认端口 —— 仅当地址只给了 host 没给 port 时兜底。 */
    private static final int DEFAULT_PROXY_PORT = 7890;

    /** 出站代理决策中心：既持有代理地址，又逐目标回答「这个目标要不要绕过代理」。 */
    private final OutboundProxyDecider proxyDecider;

    public WebClientConfig(OutboundProxyDecider proxyDecider) {
        this.proxyDecider = proxyDecider;
    }

    /**
     * 提供统一配置的 Reactor Netty HttpClient。
     *
     * 独立暴露该 Bean，使上游调用可以基于同一客户端派生请求级 doOnRequest 钩子，
     * 在传输层记录 User-Agent、Host 等由 Reactor Netty 最后补入的请求头。
     *
     * <h2>出站代理</h2>
     * 代理只在本 Bean 上装一次，因为它是全部出站请求的唯一源头：两个聊天服务都从注入的
     * 这个实例派生 {@code doAfterRequest} 钩子，模型拉取走 {@code webClientBuilder} 的
     * connector，而后者同样基于本 Bean。改这一处即全链路生效。
     *
     * <h2>为何只需一个 HttpClient</h2>
     * 「部分供应商走代理、部分直连」看似需要两个客户端，实际不必：
     * {@code nonProxyHostsPredicate} 会在<strong>每次建立连接时</strong>询问目标该不该绕过代理，
     * 于是一个常驻挂着代理的客户端就能表达两种行为。判定本身在
     * {@link OutboundProxyDecider}，那里也记着这个选择的代价（判定依据是目标地址而非供应商）。
     *
     * <p>双客户端方案需要处理 Bean 歧义、改三个出站注入点，代理地址变更时还要清连接池缓存；
     * 当前方案这三项都不需要。
     *
     * <h2>ProxyProvider 永久装上，走不走由谓词现答</h2>
     * 本 Bean 在启动时创建一次，而代理地址在 {@code app_config} 里、运行时可改。因此
     * <strong>不能</strong>用「启动时地址是否为空」来决定装不装 {@code ProxyProvider} ——
     * 那会让「启动时没配、运行时才配」的地址永远不生效。正确做法是永久装上，把「有没有代理」
     * 让给 {@link OutboundProxyDecider#isDirect} 现答：地址为空时它对一切目标返回直连，
     * {@code ProxyProvider} 挂着也等于没挂。
     *
     * <h2>地址每次连接现读</h2>
     * 用 {@code socketAddress(Supplier)} 而非 {@code host()} + {@code port()}：前者每次建连时求值，
     * 于是设置页改了地址不用重启、不用清缓存，下一个连接就用新值。地址真源是
     * {@code app_config}，由 {@link OutboundProxyDecider#currentProxyAddress} 现读。
     *
     * <p>Supplier 只在 {@code isDirect} 判为「走代理」后才会被 Netty 调用，所以进到这里时
     * 地址必非空；但仍防御性处理空值（回退回环占位），避免万一的竞态让它构造出通配地址。
     *
     * <p>不用 {@code address(...)}：它的两个重载均已弃用，由
     * {@code socketAddress(...)} 取代 —— 后者把类型从 {@code InetSocketAddress}
     * 放宽到 {@code SocketAddress}，以便表达 Unix domain socket 这类代理端点。
     *
     * <h2>代理端点必须是<strong>已解析</strong>的地址</h2>
     * 这里曾经用 {@code InetSocketAddress.createUnresolved}，理由是「只是在描述代理的位置，
     * 不应顺手发起 DNS」—— 那是<strong>错的</strong>，代价是开启代理后每一个请求都抛
     * {@code java.nio.channels.UnresolvedAddressException}。
     *
     * <p>因为本参数给出的是 Netty <strong>真正要 connect 的目标</strong>，不是一个待解析的描述；
     * NIO 层拿到未解析地址会立即失败。
     *
     * <p>容易与它混淆的是「<strong>上游目标域名</strong>由谁解析」（见下一节）——
     * 那才是有讨论空间的问题；代理自身的地址没有，它必须在本机解析，通常就是个回环地址。
     * 改动前先想清楚说的是哪一段地址。
     *
     * <h2>上游域名仍由本机解析，这是刻意的</h2>
     * 出站流程是「本机解析上游域名 → 拿到 IP → 经代理 CONNECT 到该 IP」。也就是说
     * <strong>DNS 走本机、连接走代理</strong>，两者分开。这对本地全局代理（Clash / FlClash 一类）
     * 是可行的，已实测验证。
     *
     * <p>reactor-netty 本身在 {@code proxy(...)} 里有个默认行为：若此前没显式设过
     * {@code resolver}，就自动装 {@code NoopAddressResolverGroup}，把解析一并交给代理。
     * 本类职责一显式设了 resolver，因此那个默认不生效 —— 这是<strong>有意的</strong>，
     * 不是疏漏：接受它等于全局放弃职责一那条 Windows DNS 规避。
     *
     * <h2>已知风险：本机解析不了的域名会失败</h2>
     * 上面那条分工有一个前提 —— 本机 DNS 能解析出上游域名。若某个上游的域名在本机
     * <strong>根本解析不了</strong>（DNS 层面被污染或被拦），解析会在建立连接之前失败，
     * 代理根本没机会介入。症状是该供应商恒定不可用，且日志里能看到明确的 DNS 解析失败。
     *
     * <p><strong>当前刻意不处理这种情况</strong>，因为实际使用中尚未遇到：需要代理的站点通常是
     * 「域名能解析、但 IP 不可达」，而非「域名解析不了」。真遇到时的修法是让那些目标改用
     * {@code NoopAddressResolverGroup}（把解析也交给代理），但<strong>不能全局替换</strong>，
     * 否则直连的供应商会一并失去 Windows DNS 规避；正确形态是自定义一个
     * {@code AddressResolverGroup} 按目标分派，且分派依据必须与
     * {@code nonProxyHostsPredicate} 同源，否则会出现「解析交给了代理但连接没走代理」
     * 这类自相矛盾的组合。
     *
     * <p>那一版曾经写出来过，因为当时误判了故障成因；确认不需要后删掉了。
     * 需要时从这段说明重建即可，不必重新推导。
     */
    @Bean
    public HttpClient httpClient() {
        log.info("[WebClient] 出站代理由 OutboundProxyDecider 逐个目标判定（地址来自 app_config，"
                + "DNS 始终由本机解析）；未配置代理地址或供应商未开开关时该目标直连");
        return HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .proxy(spec -> spec.type(ProxyProvider.Proxy.HTTP)
                        // Supplier 形式：每次建连现读代理地址，设置页改了下一个连接即生效。
                        // 必须用普通构造（已解析），createUnresolved 会让每个请求抛
                        // UnresolvedAddressException，详见上方说明。
                        .socketAddress(this::currentProxyEndpoint)
                        .nonProxyHostsPredicate(proxyDecider::isDirect));
    }

    /**
     * 从决策中心现读代理端点并解析成已解析的 {@link InetSocketAddress}。
     *
     * <p>只在谓词判为「走代理」后被调用，因此正常情况下地址必非空。空值回退到回环占位
     * （{@code 127.0.0.1:7890}）只是防御性兜底，防止万一的竞态构造出通配地址 ——
     * 真要没有代理，谓词早已让目标直连，这里根本到不了。
     */
    private InetSocketAddress currentProxyEndpoint() {
        String address = proxyDecider.currentProxyAddress();
        if (address == null || address.isBlank()) {
            return new InetSocketAddress("127.0.0.1", DEFAULT_PROXY_PORT);
        }
        String host = address.trim();
        int port = DEFAULT_PROXY_PORT;
        int colon = host.lastIndexOf(':');
        if (colon > 0) {
            try {
                port = Integer.parseInt(host.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                // 端口段不是数字：保留默认端口，host 仍取冒号前的部分。
            }
            host = host.substring(0, colon).trim();
        }
        return new InetSocketAddress(host, port);
    }

    /**
     * 提供全局 WebClient.Builder，底层使用配置了 JDK 系统 DNS 解析器的 Reactor Netty HttpClient，
     * 并将编解码内存缓冲上限提升至 64MB。
     *
     * Spring 会以此 Bean 替代默认的 WebClient.Builder，所有通过构造注入获取
     * WebClient.Builder 的组件都会自动受益。
     *
     * @return 配置好系统 DNS 解析器与内存缓冲上限的 WebClient.Builder
     */
    @Bean
    public WebClient.Builder webClientBuilder(HttpClient httpClient) {
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .build();
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies);
    }

    /**
     * 硬编码服务端（WebFlux 接收请求）的编解码内存缓冲上限为 64MB，
     * 避免 Copilot 大上下文请求体触发 413 Payload Too Large。
     *
     * @param configurer 服务端编解码配置器
     */
    @Override
    public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE);
    }
}

