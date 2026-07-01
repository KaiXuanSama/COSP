package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

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
 */
@Configuration
public class WebClientConfig implements WebFluxConfigurer {

    /** 内存编解码缓冲上限：64MB，足以容纳 Copilot 大上下文请求与上游大响应。 */
    private static final int MAX_IN_MEMORY_SIZE = 64 * 1024 * 1024;

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
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE);
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

