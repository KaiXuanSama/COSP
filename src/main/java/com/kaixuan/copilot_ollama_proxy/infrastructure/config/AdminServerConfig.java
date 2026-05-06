package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 管理端口配置 —— 在主端口（11434）之外额外开启 80 端口，专供 Web 前端页面使用。
 *
 * 工作原理：
 * 向 Tomcat 添加第二个 Connector，监听 80 端口。
 * 配合 AdminPortFilter 确保 80 端口只响应 /login、/config、/logout 等管理路径。
 */
@Configuration
public class AdminServerConfig {

    @Value("${admin.server.port:80}")
    private int adminPort;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> adminPortCustomizer() {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(adminPort);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
