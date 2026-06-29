package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import com.kaixuan.copilot_ollama_proxy.infrastructure.security.JwtService;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.util.List;

/**
 * 响应式（WebFlux）安全配置 —— JWT Bearer Token 模式。
 *
 * 管理后台使用无状态 JWT 认证，CSRF 完全禁用，用户数据存储在 SQLite 中。
 * 前端登录后持有 JWT，每次请求通过 Authorization: Bearer xxx 头传递。
 *
 * 用户存储仍复用阻塞式的 JdbcUserDetailsManager（供 AdminPageController 做用户 CRUD），
 * 但通过 ReactiveUserDetailsService 适配器桥接到响应式 Security，
 * 阻塞查询统一调度到 boundedElastic 线程，避免阻塞 event-loop。
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * 配置响应式安全过滤链：JWT Bearer Token 认证、禁用 CSRF、无状态 session。
     *
     * @param http ServerHttpSecurity 构建器
     * @param jwtService JWT 服务
     * @return 安全过滤链
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, JwtService jwtService) {
        http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers("/config/**", "/auth/me", "/overview", "/settings", "/account", "/call-log"))
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/config/api/stats", "/config/api/providers", "/config/api/heatmap").permitAll()
                        .pathMatchers("/overview", "/settings", "/account", "/call-log").authenticated()
                        .pathMatchers("/config/**").authenticated()
                        .pathMatchers("/auth/me").authenticated()
                        .anyExchange().permitAll())
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((exchange, ex) -> {
                            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                            return exchange.getResponse().setComplete();
                        }));

        // 注册 JWT 认证过滤器
        http.addFilterAt(jwtAuthenticationFilter(jwtService), SecurityWebFiltersOrder.AUTHENTICATION);

        return http.build();
    }

    /**
     * JWT 认证过滤器：从 Authorization 头提取 Bearer Token，验证后注入 Authentication。
     *
     * @param jwtService JWT 服务
     * @return AuthenticationWebFilter
     */
    private AuthenticationWebFilter jwtAuthenticationFilter(JwtService jwtService) {
        AuthenticationWebFilter filter = new AuthenticationWebFilter((org.springframework.security.authentication.ReactiveAuthenticationManager) authentication -> Mono.just(authentication));

        filter.setServerAuthenticationConverter(exchange -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            String token = jwtService.extractBearerToken(authHeader);
            if (token == null) {
                return Mono.empty();
            }
            try {
                Claims claims = jwtService.parseToken(token);
                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        username, null, List.of(new SimpleGrantedAuthority(role)));
                return Mono.just(authentication);
            } catch (Exception e) {
                return Mono.empty();
            }
        });

        return filter;
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

