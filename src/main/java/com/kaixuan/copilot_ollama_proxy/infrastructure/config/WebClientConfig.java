package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import io.netty.resolver.DefaultAddressResolverGroup;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * WebClient 全局配置。
 *
 * 核心目的：将 Reactor Netty 的 DNS 解析器从默认的异步解析器
 * （DnsAddressResolverGroup）切换为 JDK 系统解析器（DefaultAddressResolverGroup）。
 *
 * 背景问题：Netty 默认的异步 DNS 解析器在 Windows 上无法可靠读取系统
 * nameserver 配置，首次查询超时即放弃（日志表现为 "Failed to resolve 'xxx' [A(1)]"），
 * 并将失败结果负缓存一段时间，导致部分域名间歇性解析失败、过若干分钟后又自动恢复。
 *
 * DefaultAddressResolverGroup 走 JVM 的 InetAddress（即操作系统 DNS 栈 + hosts），
 * 与 nslookup、浏览器走同一条解析路径，行为一致且可靠，从根源消除该问题。
 */
@Configuration
public class WebClientConfig {

    /**
     * 提供全局 WebClient.Builder，底层使用配置了 JDK 系统 DNS 解析器的 Reactor Netty HttpClient。
     *
     * Spring 会以此 Bean 替代默认的 WebClient.Builder，所有通过构造注入获取
     * WebClient.Builder 的组件都会自动受益。
     *
     * @return 配置好系统 DNS 解析器的 WebClient.Builder
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE);
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
