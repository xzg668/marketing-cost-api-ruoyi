package com.sanhua.marketingcost.config;

import com.sanhua.marketingcost.security.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SecurityConfig 方法级安全配置校验。
 * <p>
 * 不加载完整 Spring Boot 上下文（避免依赖数据库），仅加载
 * {@code @EnableMethodSecurity} + {@code PermissionService} + 一个带
 * {@code @PreAuthorize} 注解的样例 Bean，验证权限注解对 {@code @ss.hasPermi} 表达式生效。
 */
class SecurityConfigTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("SecurityConfig 类上标注了 @EnableMethodSecurity")
    void securityConfigHasEnableMethodSecurity() {
        assertNotNull(SecurityConfig.class.getAnnotation(EnableMethodSecurity.class),
                "SecurityConfig 应标注 @EnableMethodSecurity");
    }

    @Test
    @DisplayName("@PreAuthorize + @ss.hasPermi 生效 — 有权限时放行")
    void preAuthorize_hasPermission_allows() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestMethodSecurityConfig.class)) {
            ProtectedService service = ctx.getBean(ProtectedService.class);

            // 认证：拥有 system:user:list 权限
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "admin", null,
                            List.of(new SimpleGrantedAuthority("system:user:list"))
                    ));

            assertEquals("ok", service.listUsers(), "有权限应放行");
        }
    }

    @Test
    @DisplayName("@PreAuthorize + @ss.hasPermi 生效 — 无权限时抛 AccessDeniedException")
    void preAuthorize_missingPermission_denies() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestMethodSecurityConfig.class)) {
            ProtectedService service = ctx.getBean(ProtectedService.class);

            // 认证但无 system:user:list
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "staff", null,
                            List.of(new SimpleGrantedAuthority("ROLE_BU_STAFF"))
                    ));

            assertThrows(AccessDeniedException.class, service::listUsers,
                    "无 system:user:list 应被拒绝");
        }
    }

    @Test
    @DisplayName("@PreAuthorize + @ss.hasPermi 生效 — *:*:* 通配放行")
    void preAuthorize_wildcardPermission_allows() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestMethodSecurityConfig.class)) {
            ProtectedService service = ctx.getBean(ProtectedService.class);

            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            "admin", null,
                            List.of(new SimpleGrantedAuthority("*:*:*"))
                    ));

            assertEquals("ok", service.listUsers());
        }
    }

    @Test
    @DisplayName("未认证时调用受保护方法 — 抛异常")
    void preAuthorize_noAuthentication_denies() {
        try (AnnotationConfigApplicationContext ctx =
                     new AnnotationConfigApplicationContext(TestMethodSecurityConfig.class)) {
            ProtectedService service = ctx.getBean(ProtectedService.class);

            SecurityContextHolder.clearContext();

            assertThrows(Exception.class, service::listUsers,
                    "未认证应抛出异常（AccessDeniedException 或 AuthenticationCredentialsNotFoundException）");
        }
    }

    // ========== 测试专用配置和 Bean ==========

    @Configuration
    @EnableMethodSecurity
    static class TestMethodSecurityConfig {
        @Bean("ss")
        public PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        public ProtectedService protectedService() {
            return new ProtectedService();
        }
    }

    /** 带 @PreAuthorize 的样例 Bean */
    @Component
    static class ProtectedService {
        @PreAuthorize("@ss.hasPermi('system:user:list')")
        public String listUsers() {
            return "ok";
        }
    }
}
