package com.sanhua.marketingcost.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class BcryptVerifyTest {

    @Test
    void verifyAdmin123Hash() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String storedHash = "$2a$10$7JB720yubVSZvUIVWMi80uOXTHG6.0WFXwM.2z9.K8Y/tG.v1EGaG";

        boolean matches = encoder.matches("admin123", storedHash);
        System.out.println("=== admin123 matches stored hash: " + matches + " ===");

        // 生成一个新的正确的 hash
        String newHash = encoder.encode("admin123");
        System.out.println("=== New BCrypt hash for admin123: " + newHash + " ===");

        assertTrue(encoder.matches("admin123", newHash), "新生成的 hash 应该匹配");
    }
}
