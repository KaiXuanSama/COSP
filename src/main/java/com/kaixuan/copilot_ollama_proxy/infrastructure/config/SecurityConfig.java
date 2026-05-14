package com.kaixuan.copilot_ollama_proxy.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.JdbcUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;

@Configuration @EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/login", "/config/**", "/logout")
                .authorizeHttpRequests(
                        auth -> auth.requestMatchers("/login", "/config/api/stats", "/config/api/providers", "/config/api/heatmap").permitAll()
                                .requestMatchers("/config/**").authenticated()
                                .requestMatchers("/logout").permitAll()
                                .anyRequest().permitAll())
                .formLogin(form -> form.loginPage("/login").loginProcessingUrl("/login")
                        .defaultSuccessUrl("/overview", true).failureUrl("/login?login=error"))
                .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?login=logout"))
                .exceptionHandling(exception -> exception.authenticationEntryPoint(
                        (request, response, authException) -> response.sendRedirect("/login?unauthorized=true")))
                .csrf(csrf -> csrf.disable());

        return http.build();
    }

    @Bean
    public JdbcUserDetailsManager jdbcUserDetailsManager(DataSource dataSource) {
        return new JdbcUserDetailsManager(dataSource);
    }

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

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}