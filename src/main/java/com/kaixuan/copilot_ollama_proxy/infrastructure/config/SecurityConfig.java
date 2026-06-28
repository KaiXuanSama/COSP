package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.net.URI;

/**
 * 响应式（WebFlux）安全配置。
 *
 * 管理后台使用表单登录，CSRF 完全禁用，用户数据存储在 SQLite 中。
 *
 * 用户存储仍复用阻塞式的 JdbcUserDetailsManager（供 AdminPageController 做用户 CRUD），
 * 但通过 ReactiveUserDetailsService 适配器桥接到响应式 Security，
 * 阻塞查询统一调度到 boundedElastic 线程，避免阻塞 event-loop。
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * 配置响应式安全过滤链：表单登录、登出、未认证重定向、禁用 CSRF。
     *
     * @param http ServerHttpSecurity 构建器
     * @return 安全过滤链
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        RedirectServerAuthenticationSuccessHandler successHandler = new RedirectServerAuthenticationSuccessHandler("/overview");
        RedirectServerAuthenticationFailureHandler failureHandler = new RedirectServerAuthenticationFailureHandler("/login?login=error");
        RedirectServerLogoutSuccessHandler logoutSuccessHandler = new RedirectServerLogoutSuccessHandler();
        logoutSuccessHandler.setLogoutSuccessUrl(URI.create("/login?login=logout"));

        http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/login", "/config/**", "/logout", "/overview", "/settings", "/account", "/call-log", "/"))
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/login", "/config/api/stats", "/config/api/providers", "/config/api/heatmap").permitAll()
                        .pathMatchers("/overview", "/settings", "/account", "/call-log").authenticated()
                        .pathMatchers("/config/**").authenticated()
                        .pathMatchers("/logout", "/").permitAll()
                        .anyExchange().permitAll())
                .formLogin(form -> form
                        .loginPage("/login")
                        .authenticationSuccessHandler(successHandler)
                        .authenticationFailureHandler(failureHandler))
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(logoutSuccessHandler))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((exchange, ex) -> redirect(exchange, "/login?unauthorized=true")))
                .csrf(ServerHttpSecurity.CsrfSpec::disable);

        return http.build();
    }

    /**
     * 发送 302 重定向到指定位置。
     */
    private Mono<Void> redirect(ServerWebExchange exchange, String location) {
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().setLocation(URI.create(location));
        return exchange.getResponse().setComplete();
    }

    /**
     * 阻塞式 JDBC 用户管理器 —— 用于 AdminPageController 的用户增删改查。
     *
     * @param dataSource 数据源
     * @return JDBC 用户管理器
     */
    @Bean
    public JdbcUserDetailsManager jdbcUserDetailsManager(DataSource dataSource) {
        return new JdbcUserDetailsManager(dataSource);
    }

    /**
     * 将阻塞式 JdbcUserDetailsManager 适配为响应式 ReactiveUserDetailsService。
     * 阻塞查询调度到 boundedElastic 线程池执行，避免阻塞 Netty event-loop。
     *
     * @param userDetailsManager 阻塞式用户管理器
     * @return 响应式用户详情服务
     */
    @Bean
    public ReactiveUserDetailsService reactiveUserDetailsService(JdbcUserDetailsManager userDetailsManager) {
        return username -> Mono.fromCallable(() -> userDetailsManager.loadUserByUsername(username))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> Mono.empty());
    }

    /**
     * 应用启动时初始化默认管理员账户。
     *
     * @param userDetailsManager 用户管理器
     * @param passwordEncoder 密码编码器
     * @param adminUsername 默认管理员用户名
     * @param adminPassword 默认管理员密码
     * @return ApplicationRunner
     */
    @Bean
    public ApplicationRunner initializeDefaultAdminUser(JdbcUserDetailsManager userDetailsManager,
            PasswordEncoder passwordEncoder, @Value("${admin.username:root}") String adminUsername,
            @Value("${admin.password:root}") String adminPassword) {
        return args -> {
            if (!userDetailsManager.userExists(adminUsername)) {
                UserDetails adminUser = User.withUsername(adminUsername).password(passwordEncoder.encode(adminPassword))
                        .roles("ADMIN").build();
                userDetailsManager.createUser(adminUser);
            }
        };
    }

    /**
     * 密码编码器（BCrypt）。
     *
     * @return 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
