package com.sanhua.marketingcost.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PermissionService 单元测试。
 * 覆盖：未登录、超级管理员通配、普通用户具名权限、角色判断。
 */
class PermissionServiceTest {

    private final PermissionService permissionService = new PermissionService();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 未登录时 hasPermi / hasRole 均返回 false，不抛异常 */
    @Test
    void returnFalseWhenNotAuthenticated() {
        assertFalse(permissionService.hasPermi("system:user:list"));
        assertFalse(permissionService.hasRole("ADMIN"));
    }

    /** 超级管理员通配 *:*:* → 任意权限均通过 */
    @Test
    void superAdminWildcardPassesAnyPermi() {
        authenticate("*:*:*", "ROLE_ADMIN");
        assertTrue(permissionService.hasPermi("system:user:list"));
        assertTrue(permissionService.hasPermi("any:thing:here"));
        assertTrue(permissionService.hasRole("ADMIN"));
    }

    /** 普通用户仅拥有具名权限 */
    @Test
    void normalUserOnlyMatchesGrantedPermi() {
        authenticate("cost:run:list", "ROLE_BU_STAFF");
        assertTrue(permissionService.hasPermi("cost:run:list"));
        assertFalse(permissionService.hasPermi("system:user:add"));
        assertTrue(permissionService.hasRole("BU_STAFF"));
        assertFalse(permissionService.hasRole("ADMIN"));
    }

    /** hasAnyPermi / hasAnyRole OR 语义 */
    @Test
    void hasAnyMatchesAtLeastOne() {
        authenticate("cost:run:list", "ROLE_BU_STAFF");
        assertTrue(permissionService.hasAnyPermi("no:such:perm", "cost:run:list"));
        assertFalse(permissionService.hasAnyPermi("no:such:perm", "other:missing:perm"));
        assertTrue(permissionService.hasAnyRole("ADMIN", "BU_STAFF"));
        assertFalse(permissionService.hasAnyRole("ADMIN", "BU_DIRECTOR"));
    }

    /** 空/null 参数返回 false */
    @Test
    void nullOrEmptyReturnsFalse() {
        authenticate("*:*:*");
        assertFalse(permissionService.hasPermi(null));
        assertFalse(permissionService.hasPermi(""));
        assertFalse(permissionService.hasRole(null));
        assertFalse(permissionService.hasAnyPermi());
        assertFalse(permissionService.hasAnyRole());
    }

    /** 传入带 ROLE_ 前缀的角色参数也应识别 */
    @Test
    void hasRoleAcceptsPrefixedArgument() {
        authenticate("ROLE_BU_DIRECTOR");
        assertTrue(permissionService.hasRole("BU_DIRECTOR"));
        assertTrue(permissionService.hasRole("ROLE_BU_DIRECTOR"));
    }

    /** 将指定 authority 字符串放入 SecurityContext */
    private void authenticate(String... authorities) {
        List<SimpleGrantedAuthority> list = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken("testuser", "N/A", list);
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
