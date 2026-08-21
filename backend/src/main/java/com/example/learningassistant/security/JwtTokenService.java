package com.example.learningassistant.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具：access_token（2h）/ refresh_token（7d）双 Token。
 * 会话状态可存 Redis 实现强制下线（骨架阶段未启用）。
 */
@Component
public class JwtTokenService {

    @Value("${app.security.jwt-secret:learning-assistant-v2-jwt-secret-please-change-me-0123456789}")
    private String secret;

    @Value("${app.security.access-ttl-ms:7200000}")
    private long accessTtlMs;

    @Value("${app.security.refresh-ttl-ms:604800000}")
    private long refreshTtlMs;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(AuthUser user) {
        return build(user, accessTtlMs);
    }

    public String createRefreshToken(AuthUser user) {
        return build(user, refreshTtlMs);
    }

    private String build(AuthUser user, long ttl) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(user.id()))
                .claim("username", user.username())
                .claim("nickname", user.nickname())
                .claim("tenantId", user.tenantId() == null ? 1L : user.tenantId())
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl))
                .signWith(key())
                .compact();
    }

    /**
     * 解析并校验 Token；非法/过期返回 null。
     */
    public AuthUser parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key()).build()
                    .parseSignedClaims(token).getPayload();
            Object tid = c.get("tenantId");
            return new AuthUser(
                    Long.valueOf(c.getSubject()),
                    c.get("username", String.class),
                    c.get("nickname", String.class),
                    tid == null ? 1L : Long.valueOf(String.valueOf(tid)));
        } catch (Exception e) {
            return null;
        }
    }
}
