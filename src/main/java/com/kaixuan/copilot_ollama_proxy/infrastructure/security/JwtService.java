package com.kaixuan.copilot_ollama_proxy.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * JWT令牌服务 ——负责签发与验证 Bearer Token。
 *
 * 使用 HMAC-SHA256 对称签名，密钥从配置读取（默认值仅供开发）。
 * Token 有效期默认24 小时。
 */
@Service
public class JwtService {

 private final SecretKey signingKey;
 private final Duration tokenTtl;

 /**
 * @param secret 配置的签名密钥（jwt.secret），至少32 字节
 * @param ttlHours Token 有效期小时数（jwt.ttl-hours），默认24
 */
 public JwtService(@Value("${jwt.secret:cosp-default-jwt-secret-key-change-me-in-production}") String secret,
 @Value("${jwt.ttl-hours:24}") long ttlHours) {
 this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
 this.tokenTtl = Duration.ofHours(ttlHours);
 }

 /**
 * 为指定用户名签发 JWT。
 *
 * @param username 用户名
 * @param role 角色（如 "ROLE_ADMIN"）
 * @return签名后的 JWT 字符串
 */
 public String generateToken(String username, String role) {
 Date now = new Date();
 Date expiry = new Date(now.getTime() + tokenTtl.toMillis());
 return Jwts.builder()
 .subject(username)
 .claim("role", role)
 .issuedAt(now)
 .expiration(expiry)
 .signWith(signingKey)
 .compact();
 }

 /**
 * 解析并验证 JWT，返回其中的 Claims。
 *
 * @param token JWT 字符串
 * @return 解析出的 Claims
 * @throws JwtException 如果 token 无效或已过期
 */
 public Claims parseToken(String token) {
 return Jwts.parser()
 .verifyWith(signingKey)
 .build()
 .parseSignedClaims(token)
 .getPayload();
 }

 /**
 * 从 Authorization头中提取 Bearer Token。
 *
 * @param authHeader Authorization头的值
 * @return去掉 "Bearer "前缀后的 token，如果不匹配则返回 null
 */
 public String extractBearerToken(String authHeader) {
 if (authHeader != null && authHeader.startsWith("Bearer ")) {
 return authHeader.substring(7);
 }
 return null;
 }
}
