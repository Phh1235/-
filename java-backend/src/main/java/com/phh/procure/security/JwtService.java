package com.phh.procure.security;

import com.phh.procure.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）。密钥只从配置/环境变量读取，不落库、不入日志。
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expireSeconds;

    public JwtService(AppProperties properties) {
        byte[] bytes = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 至少 32 字节（HS256 要求），请通过环境变量 JWT_SECRET 配置");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireSeconds = properties.getJwt().getExpireSeconds();
    }

    public String issue(LoginUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.username())
                .claim("uid", user.id())
                .claim("name", user.displayName())
                .claim("role", user.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验签名与有效期；失败抛 {@link io.jsonwebtoken.JwtException}。
     */
    public LoginUser parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        Object uid = claims.get("uid");
        Long userId = uid instanceof Number number ? number.longValue() : null;
        return new LoginUser(userId, claims.getSubject(), claims.get("name", String.class),
                claims.get("role", String.class));
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }
}
