package com.sanhua.marketingcost.security;

import com.sanhua.marketingcost.entity.SysRole;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.SysUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserDetailsServiceImplTest {

    private SysUserService sysUserService;
    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        sysUserService = mock(SysUserService.class);
        userDetailsService = new UserDetailsServiceImpl(sysUserService);
    }

    private SysUser createUser(Long id, String username, String status) {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setUserName(username);
        user.setPassword("$2a$10$encodedPassword");
        user.setStatus(status);
        user.setDelFlag("0");
        return user;
    }

    @Test
    @DisplayName("加载用户 — 正常用户返回正确的 UserDetails")
    void loadUserByUsername_normalUser_returnsUserDetails() {
        SysUser user = createUser(1L, "admin", "0");
        SysRole role = new SysRole();
        role.setRoleKey("admin");
        when(sysUserService.findByUsername("admin")).thenReturn(user);
        when(sysUserService.findRolesByUserId(1L)).thenReturn(List.of(role));

        UserDetails details = userDetailsService.loadUserByUsername("admin");

        assertEquals("admin", details.getUsername());
        assertEquals("$2a$10$encodedPassword", details.getPassword());
        assertTrue(details.isEnabled());
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_admin")));
    }

    @Test
    @DisplayName("加载用户 — 用户不存在抛出 UsernameNotFoundException")
    void loadUserByUsername_notFound_throwsException() {
        when(sysUserService.findByUsername("ghost")).thenReturn(null);

        assertThrows(UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername("ghost"));
    }

    @Test
    @DisplayName("加载用户 — 禁用用户 enabled=false")
    void loadUserByUsername_disabledUser_enabledIsFalse() {
        SysUser user = createUser(2L, "banned", "1"); // status="1" 表示停用
        when(sysUserService.findByUsername("banned")).thenReturn(user);
        when(sysUserService.findRolesByUserId(2L)).thenReturn(Collections.emptyList());

        UserDetails details = userDetailsService.loadUserByUsername("banned");

        assertFalse(details.isEnabled(), "状态为 '1' 的用户应为禁用状态");
    }

    @Test
    @DisplayName("加载用户 — 无角色时权限列表为空")
    void loadUserByUsername_noRoles_emptyAuthorities() {
        SysUser user = createUser(3L, "norole", "0");
        when(sysUserService.findByUsername("norole")).thenReturn(user);
        when(sysUserService.findRolesByUserId(3L)).thenReturn(Collections.emptyList());

        UserDetails details = userDetailsService.loadUserByUsername("norole");

        assertTrue(details.getAuthorities().isEmpty());
    }

    @Test
    @DisplayName("加载用户 — 多角色正确映射为 ROLE_ 前缀")
    void loadUserByUsername_multipleRoles_allMappedWithPrefix() {
        SysUser user = createUser(4L, "multi", "0");
        SysRole r1 = new SysRole();
        r1.setRoleKey("admin");
        SysRole r2 = new SysRole();
        r2.setRoleKey("editor");
        when(sysUserService.findByUsername("multi")).thenReturn(user);
        when(sysUserService.findRolesByUserId(4L)).thenReturn(List.of(r1, r2));

        UserDetails details = userDetailsService.loadUserByUsername("multi");

        assertEquals(2, details.getAuthorities().size());
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_admin")));
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_editor")));
    }
}
