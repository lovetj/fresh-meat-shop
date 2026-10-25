package com.freshmeat.util;

import com.freshmeat.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    /** 会话标识声明名：用于单设备登录校验（与 sys_user.session_key 对应） */
    public static final String CLAIM_SESSION_KEY = "sk";

    private final JwtProperties jwtProperties;
    private Key signKey;

    @PostConstruct
    public void init() {
        this.signKey = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 JWT Token
     *
     * @param userId     用户ID
     * @param openid     微信 openid
     * @param role       角色 (CUSTOMER / ADMIN)
     * @param sessionKey 当前登录会话标识（单设备登录，登录时由服务端刷新）
     * @return token 字符串
     */
    public String generateToken(String userId, String openid, String role, String sessionKey) {
        Date now = new Date();
        long expireMillis = "ADMIN".equalsIgnoreCase(role)
                ? (jwtProperties.getAdminExpiration() != null ? jwtProperties.getAdminExpiration() : jwtProperties.getExpiration())
                : (jwtProperties.getCustomerExpiration() != null ? jwtProperties.getCustomerExpiration() : jwtProperties.getExpiration());
        Date expiryDate = new Date(now.getTime() + expireMillis);

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("openid", openid);
        claims.put("role", role);
        claims.put(CLAIM_SESSION_KEY, sessionKey);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(userId)
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(signKey)
                .compact();
    }

    /**
     * 解析 JWT Token
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(signKey)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            log.warn("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }
}
