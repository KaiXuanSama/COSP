package com.kaixuan.copilot_ollama_proxy.infrastructure.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证控制器 ——提供 JWT 登录与当前用户信息查询。
 *
 * POST /auth/login：验证用户名密码，签发 JWT
 * GET /auth/me：返回当前 token 持有者的用户信息
 */
@RestController
public class AuthController {

 private final ReactiveUserDetailsService userDetailsService;
 private final PasswordEncoder passwordEncoder;
 private final JwtService jwtService;

 public AuthController(ReactiveUserDetailsService userDetailsService, PasswordEncoder passwordEncoder, JwtService jwtService) {
 this.userDetailsService = userDetailsService;
 this.passwordEncoder = passwordEncoder;
 this.jwtService = jwtService;
 }

 /**
 * 登录端点：验证用户名密码后签发 JWT。
 *
 * 请求体 JSON：{"username":"...","password":"..."}
 * 成功响应 JSON：{"token":"...","username":"...","role":"..."}
 * 失败响应：401 + {"error":"用户名或密码错误"}
 *
 * @param body 包含 username 和 password 的 JSON
 * @return 包含 token 的响应
 */
 @PostMapping("/auth/login")
 public Mono<ResponseEntity<Map<String, Object>>> login(@RequestBody Map<String, String> body) {
 String username = body.get("username");
 String password = body.get("password");

 if (username == null || username.isBlank() || password == null || password.isBlank()) {
 return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
 .body(Map.of("error", "用户名或密码错误")));
 }

 return userDetailsService.findByUsername(username)
 .map(userDetails -> {
 if (passwordEncoder.matches(password, userDetails.getPassword())) {
 String role = userDetails.getAuthorities().stream().findFirst().map(Object::toString).orElse("ROLE_USER");
 String token = jwtService.generateToken(username, role);
 Map<String, Object> result = new LinkedHashMap<>();
 result.put("token", token);
 result.put("username", username);
 result.put("role", role);
 return ResponseEntity.ok(result);
 } else {
 return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
 .body(Map.<String, Object>of("error", "用户名或密码错误"));
 }
 })
 .switchIfEmpty(Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
 .body(Map.<String, Object>of("error", "用户名或密码错误"))));
 }

 /**
 * 获取当前 token 持有者的用户信息。
 *
 * 由 Security 过滤链注入 Authentication，无需手动解析 token。
 *
 * @param auth Spring Security 注入的认证信息
 * @return 当前用户信息
 */
 @GetMapping("/auth/me")
 public Mono<ResponseEntity<Map<String, Object>>> me(Mono<org.springframework.security.core.Authentication> auth) {
 return auth.map(a -> {
 Map<String, Object> result = new LinkedHashMap<>();
 result.put("username", a.getName());
 result.put("role", a.getAuthorities().stream().findFirst().map(Object::toString).orElse(""));
 return ResponseEntity.ok(result);
 });
 }
}

