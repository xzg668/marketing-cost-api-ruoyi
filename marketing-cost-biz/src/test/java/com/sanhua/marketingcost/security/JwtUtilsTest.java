package com.sanhua.marketingcost.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilsTest {

    private JwtUtils jwtUtils;
    // 测试用密钥 (Base64 编码，至少 256 bits)
    private static final String TEST_SECRET = "dGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS1mb3ItdW5pdC10ZXN0aW5nLW9ubHk=";
    private static final long EXPIRATION_MS = 3600000; // 1 小时

    @BeforeEach
    void setUp() {
        jwtUtils = new JwtUtils(TEST_SECRET, EXPIRATION_MS);
    }

    @Test
    @DisplayName("生成 Token 不为空")
    void generateToken_returnsNonEmptyString() {
        String token = jwtUtils.generateToken("admin");
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    @DisplayName("生成的 Token 包含三段（Header.Payload.Signature）")
    void generateToken_hasThreeParts() {
        String token = jwtUtils.generateToken("admin");
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "JWT 应包含三段");
    }

    @Test
    @DisplayName("从 Token 解析出正确的用户名")
    void getUsernameFromToken_returnsCorrectUsername() {
        String token = jwtUtils.generateToken("testuser");
        String username = jwtUtils.getUsernameFromToken(token);
        assertEquals("testuser", username);
    }

    @Test
    @DisplayName("不同用户名生成不同 Token")
    void generateToken_differentUsersGetDifferentTokens() {
        String token1 = jwtUtils.generateToken("user1");
        String token2 = jwtUtils.generateToken("user2");
        assertNotEquals(token1, token2);
    }

    @Test
    @DisplayName("验证有效 Token 返回 true")
    void validateToken_validToken_returnsTrue() {
        String token = jwtUtils.generateToken("admin");
        assertTrue(jwtUtils.validateToken(token));
    }

    @Test
    @DisplayName("验证无效 Token 返回 false")
    void validateToken_invalidToken_returnsFalse() {
        assertFalse(jwtUtils.validateToken("invalid.token.string"));
    }

    @Test
    @DisplayName("验证空字符串 Token 返回 false")
    void validateToken_emptyToken_returnsFalse() {
        assertFalse(jwtUtils.validateToken(""));
    }

    @Test
    @DisplayName("验证 null Token 返回 false")
    void validateToken_nullToken_returnsFalse() {
        assertFalse(jwtUtils.validateToken(null));
    }

    @Test
    @DisplayName("验证被篡改的 Token 返回 false")
    void validateToken_tamperedToken_returnsFalse() {
        String token = jwtUtils.generateToken("admin");
        // 篡改 Token 的最后一个字符
        String tampered = token.substring(0, token.length() - 1) + (token.charAt(token.length() - 1) == 'a' ? 'b' : 'a');
        assertFalse(jwtUtils.validateToken(tampered));
    }

    @Test
    @DisplayName("验证过期 Token 返回 false")
    void validateToken_expiredToken_returnsFalse() {
        // 创建一个过期时间为 0ms 的 JwtUtils
        JwtUtils expiredJwtUtils = new JwtUtils(TEST_SECRET, 0);
        String token = expiredJwtUtils.generateToken("admin");
        assertFalse(expiredJwtUtils.validateToken(token));
    }

    @Test
    @DisplayName("使用错误密钥签发的 Token 验证失败")
    void validateToken_wrongSecret_returnsFalse() {
        String anotherSecret = "YW5vdGhlci10ZXN0LXNlY3JldC1rZXktdGhhdC1pcy1kaWZmZXJlbnQtZnJvbS1vcmlnaW5hbA==";
        JwtUtils otherJwtUtils = new JwtUtils(anotherSecret, EXPIRATION_MS);
        String token = otherJwtUtils.generateToken("admin");
        assertFalse(jwtUtils.validateToken(token), "使用不同密钥签发的 Token 不应通过验证");
    }

    @Test
    @DisplayName("同一用户多次生成 Token 均可验证")
    void generateToken_multipleTokensForSameUser_allValid() {
        String token1 = jwtUtils.generateToken("admin");
        String token2 = jwtUtils.generateToken("admin");
        assertTrue(jwtUtils.validateToken(token1));
        assertTrue(jwtUtils.validateToken(token2));
    }

    @Test
    @DisplayName("中文用户名可正常处理")
    void generateToken_chineseUsername_worksCorrectly() {
        String token = jwtUtils.generateToken("管理员");
        assertTrue(jwtUtils.validateToken(token));
        assertEquals("管理员", jwtUtils.getUsernameFromToken(token));
    }

    // ========== v1.3 新增：businessUnitType ==========

    @Test
    @DisplayName("Token 携带 businessUnitType — 可正确提取")
    void generateTokenWithBusinessUnit_extractable() {
        String token = jwtUtils.generateToken("admin", "COMMERCIAL");
        assertTrue(jwtUtils.validateToken(token));
        assertEquals("admin", jwtUtils.getUsernameFromToken(token));
        assertEquals("COMMERCIAL", jwtUtils.extractBusinessUnitType(token));
    }

    @Test
    @DisplayName("Token 携带 HOUSEHOLD 业务单元")
    void generateTokenWithHousehold_extractable() {
        String token = jwtUtils.generateToken("user1", "HOUSEHOLD");
        assertEquals("HOUSEHOLD", jwtUtils.extractBusinessUnitType(token));
    }

    @Test
    @DisplayName("不含 businessUnitType 的旧 Token — extractBusinessUnitType 返回 null")
    void oldTokenWithoutBusinessUnit_returnsNull() {
        String token = jwtUtils.generateToken("admin");
        assertNull(jwtUtils.extractBusinessUnitType(token),
                "旧格式 Token 的 businessUnitType 应为 null");
    }

    @Test
    @DisplayName("businessUnitType 为 null 时不写入 claims")
    void generateTokenWithNullBusinessUnit_notInClaims() {
        String token = jwtUtils.generateToken("admin", null);
        assertNull(jwtUtils.extractBusinessUnitType(token));
    }

    @Test
    @DisplayName("businessUnitType 为空字符串时不写入 claims")
    void generateTokenWithEmptyBusinessUnit_notInClaims() {
        String token = jwtUtils.generateToken("admin", "");
        assertNull(jwtUtils.extractBusinessUnitType(token));
    }

    @Test
    @DisplayName("含 businessUnitType 的 Token 用户名解析不受影响")
    void generateTokenWithBusinessUnit_usernameStillCorrect() {
        String token = jwtUtils.generateToken("testuser", "COMMERCIAL");
        assertEquals("testuser", jwtUtils.getUsernameFromToken(token));
    }
}
