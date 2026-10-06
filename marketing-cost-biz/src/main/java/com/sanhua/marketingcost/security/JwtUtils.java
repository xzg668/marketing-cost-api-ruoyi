package com.sanhua.marketingcost.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.time.Instant;
import java.util.UUID;

/**
 * JWT 工具类。
 * <p>
 * v1.3 改造：Token payload 增加 {@code businessUnitType} 字段，
 * 用于携带用户登录时选择的业务单元（COMMERCIAL / HOUSEHOLD）。
 */
@Component
public class JwtUtils {

    /** JWT claims 中业务单元字段名 */
    static final String CLAIM_BUSINESS_UNIT_TYPE = "businessUnitType";

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtils(@Value("${app.jwt.secret}") String secret,
                    @Value("${app.jwt.expiration-ms}") long expirationMs) {
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    /**
     * 生成 JWT Token（不含业务单元，向后兼容）。
     */
    public String generateToken(String username) {
        return generateToken(username, null);
    }

    /**
     * 生成 JWT Token，payload 中携带业务单元类型。
     *
     * @param username         用户名（subject）
     * @param businessUnitType 业务单元标识，可为 null
     */
    public String generateToken(String username, String businessUnitType) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);
        var builder = Jwts.builder()
                .subject(username)
                .issuedAt(now)
                .expiration(expiry);
        if (businessUnitType != null && !businessUnitType.isEmpty()) {
            builder.claim(CLAIM_BUSINESS_UNIT_TYPE, businessUnitType);
        }
        return builder.signWith(key).compact();
    }

    public String generateOaSessionToken(String employeeNo, String name, String businessUnit,
            long formId, String environment, long ttlSeconds) {
        Instant now = Instant.now();
        return Jwts.builder().subject(employeeNo).id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("tokenType", "OA_COLLABORATION").claim("formId", formId)
                .claim("displayName", name).claim("environment", environment)
                .claim(CLAIM_BUSINESS_UNIT_TYPE, businessUnit).signWith(key).compact();
    }

    public String getUsernameFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 从 Token 中提取业务单元类型。
     *
     * @return 业务单元字符串；旧 Token 或未携带时返回 null
     */
    public String extractBusinessUnitType(String token) {
        Claims claims = parseClaims(token);
        return claims.get(CLAIM_BUSINESS_UNIT_TYPE, String.class);
    }

    public boolean validateToken(String token) {
        try {
            String type = parseClaims(token).get("tokenType", String.class);
            return type == null || "OA_COLLABORATION".equals(type);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isOaSession(String token) {
        try {
            return "OA_COLLABORATION".equals(
                    parseClaims(token).get("tokenType", String.class));
        } catch (JwtException | IllegalArgumentException exception) {
            return false;
        }
    }

    public Long extractOaFormId(String token) {
        Number value = parseClaims(token).get("formId", Number.class);
        return value == null ? null : value.longValue();
    }

    public String extractOaName(String token) { return parseClaims(token).get("displayName", String.class); }
    public String extractOaEnvironment(String token) { return parseClaims(token).get("environment", String.class); }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
