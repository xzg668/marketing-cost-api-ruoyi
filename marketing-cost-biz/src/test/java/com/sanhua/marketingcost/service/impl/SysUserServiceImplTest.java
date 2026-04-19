package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SysUserServiceImplTest {

    private SysUserMapper sysUserMapper;
    private SysRoleMapper sysRoleMapper;
    private SysUserRoleMapper sysUserRoleMapper;
    private SysRoleMenuMapper sysRoleMenuMapper;
    private SysMenuMapper sysMenuMapper;
    private SysUserServiceImpl sysUserService;

    @BeforeEach
    void setUp() {
        sysUserMapper = mock(SysUserMapper.class);
        sysRoleMapper = mock(SysRoleMapper.class);
        sysUserRoleMapper = mock(SysUserRoleMapper.class);
        sysRoleMenuMapper = mock(SysRoleMenuMapper.class);
        sysMenuMapper = mock(SysMenuMapper.class);
        sysUserService = new SysUserServiceImpl(sysUserMapper, sysRoleMapper,
                sysUserRoleMapper, sysRoleMenuMapper, sysMenuMapper);
    }

    @Test
    @DisplayName("按用户名查询 — 用户存在")
    void findByUsername_userExists_returnsUser() {
        SysUser expected = new SysUser();
        expected.setUserId(1L);
        expected.setUserName("admin");
        expected.setPassword("encoded");
        expected.setDelFlag("0");
        when(sysUserMapper.selectByUsername("admin")).thenReturn(expected);

        SysUser result = sysUserService.findByUsername("admin");

        assertNotNull(result);
        assertEquals("admin", result.getUserName());
        assertEquals(1L, result.getUserId());
        verify(sysUserMapper).selectByUsername("admin");
    }

    @Test
    @DisplayName("按用户名查询 — 用户不存在返回 null")
    void findByUsername_userNotFound_returnsNull() {
        when(sysUserMapper.selectByUsername("nonexistent")).thenReturn(null);

        SysUser result = sysUserService.findByUsername("nonexistent");

        assertNull(result);
    }

    @Test
    @DisplayName("查询用户角色 — 有角色")
    void findRolesByUserId_hasRoles_returnsRoles() {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(1L);
        ur.setRoleId(1L);
        when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(ur));

        SysRole role = new SysRole();
        role.setRoleId(1L);
        role.setRoleName("超级管理员");
        role.setRoleKey("admin");
        when(sysRoleMapper.selectList(any())).thenReturn(List.of(role));

        List<SysRole> roles = sysUserService.findRolesByUserId(1L);

        assertEquals(1, roles.size());
        assertEquals("admin", roles.get(0).getRoleKey());
    }

    @Test
    @DisplayName("查询用户角色 — 无关联角色返回空列表")
    void findRolesByUserId_noRoles_returnsEmptyList() {
        when(sysUserRoleMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<SysRole> roles = sysUserService.findRolesByUserId(999L);

        assertTrue(roles.isEmpty());
        verify(sysRoleMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("查询用户角色 — 多个角色")
    void findRolesByUserId_multipleRoles_returnsAll() {
        SysUserRole ur1 = new SysUserRole();
        ur1.setUserId(1L);
        ur1.setRoleId(1L);
        SysUserRole ur2 = new SysUserRole();
        ur2.setUserId(1L);
        ur2.setRoleId(2L);
        when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(ur1, ur2));

        SysRole role1 = new SysRole();
        role1.setRoleId(1L);
        role1.setRoleKey("admin");
        SysRole role2 = new SysRole();
        role2.setRoleId(2L);
        role2.setRoleKey("editor");
        when(sysRoleMapper.selectList(any())).thenReturn(List.of(role1, role2));

        List<SysRole> roles = sysUserService.findRolesByUserId(1L);

        assertEquals(2, roles.size());
    }

    // ========== findPermissionsByUserId ==========

    @Test
    @DisplayName("admin 角色用户 — 返回通配权限 *:*:*")
    void findPermissions_admin_returnsWildcard() {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(1L);
        ur.setRoleId(1L);
        when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(ur));

        SysRole adminRole = new SysRole();
        adminRole.setRoleId(1L);
        adminRole.setRoleKey("ADMIN");
        adminRole.setStatus("0");
        adminRole.setDelFlag("0");
        when(sysRoleMapper.selectList(any())).thenReturn(List.of(adminRole));

        Set<String> perms = sysUserService.findPermissionsByUserId(1L);

        assertEquals(Set.of("*:*:*"), perms);
        // admin 不需要查询 role_menu 和 menu
        verify(sysRoleMenuMapper, never()).selectList(any());
        verify(sysMenuMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("普通角色用户 — 返回菜单权限标识集合")
    void findPermissions_normalUser_returnsMenuPerms() {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(10L);
        ur.setRoleId(11L);
        when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(ur));

        SysRole staffRole = new SysRole();
        staffRole.setRoleId(11L);
        staffRole.setRoleKey("bu_staff");
        staffRole.setStatus("0");
        staffRole.setDelFlag("0");
        when(sysRoleMapper.selectList(any())).thenReturn(List.of(staffRole));

        SysRoleMenu rm1 = new SysRoleMenu();
        rm1.setRoleId(11L);
        rm1.setMenuId(201L);
        SysRoleMenu rm2 = new SysRoleMenu();
        rm2.setRoleId(11L);
        rm2.setMenuId(202L);
        when(sysRoleMenuMapper.selectList(any())).thenReturn(List.of(rm1, rm2));

        SysMenu menu1 = new SysMenu();
        menu1.setMenuId(201L);
        menu1.setPerms("data:import:list");
        menu1.setStatus("0");
        SysMenu menu2 = new SysMenu();
        menu2.setMenuId(202L);
        menu2.setPerms("data:export:list");
        menu2.setStatus("0");
        when(sysMenuMapper.selectList(any())).thenReturn(List.of(menu1, menu2));

        Set<String> perms = sysUserService.findPermissionsByUserId(10L);

        assertEquals(Set.of("data:import:list", "data:export:list"), perms);
    }

    @Test
    @DisplayName("无角色用户 — 返回空集合")
    void findPermissions_noRoles_returnsEmpty() {
        when(sysUserRoleMapper.selectList(any())).thenReturn(Collections.emptyList());

        Set<String> perms = sysUserService.findPermissionsByUserId(999L);

        assertTrue(perms.isEmpty());
    }

    // ========== findVisibleMenus ==========

    @Test
    @DisplayName("findVisibleMenus — admin 不经 role_menu 过滤，直接查全部")
    void findVisibleMenus_admin_skipsRoleMenu() {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(1L);
        ur.setRoleId(1L);
        when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(ur));

        SysRole adminRole = new SysRole();
        adminRole.setRoleId(1L);
        adminRole.setRoleKey("ADMIN");
        adminRole.setStatus("0");
        adminRole.setDelFlag("0");
        when(sysRoleMapper.selectList(any())).thenReturn(List.of(adminRole));

        SysMenu m = new SysMenu();
        m.setMenuId(100L);
        when(sysMenuMapper.selectList(any())).thenReturn(List.of(m));

        List<SysMenu> result = sysUserService.findVisibleMenus(1L, "COMMERCIAL");

        assertEquals(1, result.size());
        // admin 不应查询 role_menu
        verify(sysRoleMenuMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("findVisibleMenus — 普通角色按 role_menu 过滤")
    void findVisibleMenus_normalUser_usesRoleMenu() {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(10L);
        ur.setRoleId(11L);
        when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(ur));

        SysRole staffRole = new SysRole();
        staffRole.setRoleId(11L);
        staffRole.setRoleKey("bu_staff");
        staffRole.setStatus("0");
        staffRole.setDelFlag("0");
        when(sysRoleMapper.selectList(any())).thenReturn(List.of(staffRole));

        SysRoleMenu rm = new SysRoleMenu();
        rm.setRoleId(11L);
        rm.setMenuId(200L);
        when(sysRoleMenuMapper.selectList(any())).thenReturn(List.of(rm));

        SysMenu m = new SysMenu();
        m.setMenuId(200L);
        when(sysMenuMapper.selectList(any())).thenReturn(List.of(m));

        List<SysMenu> result = sysUserService.findVisibleMenus(10L, "COMMERCIAL");

        assertEquals(1, result.size());
        // 普通角色必须查询 role_menu
        verify(sysRoleMenuMapper).selectList(any());
    }

    @Test
    @DisplayName("findVisibleMenus — 无角色返回空")
    void findVisibleMenus_noRole_empty() {
        when(sysUserRoleMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<SysMenu> result = sysUserService.findVisibleMenus(999L, "COMMERCIAL");

        assertTrue(result.isEmpty());
        verify(sysMenuMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("findVisibleMenus — 普通角色无 role_menu 关联返回空")
    void findVisibleMenus_normalUser_noRoleMenu_empty() {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(10L);
        ur.setRoleId(11L);
        when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(ur));

        SysRole staffRole = new SysRole();
        staffRole.setRoleId(11L);
        staffRole.setRoleKey("bu_staff");
        staffRole.setStatus("0");
        staffRole.setDelFlag("0");
        when(sysRoleMapper.selectList(any())).thenReturn(List.of(staffRole));

        when(sysRoleMenuMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<SysMenu> result = sysUserService.findVisibleMenus(10L, "COMMERCIAL");

        assertTrue(result.isEmpty());
        verify(sysMenuMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("菜单 perms 为空 — 过滤掉不返回")
    void findPermissions_emptyPerms_filtered() {
        SysUserRole ur = new SysUserRole();
        ur.setUserId(10L);
        ur.setRoleId(11L);
        when(sysUserRoleMapper.selectList(any())).thenReturn(List.of(ur));

        SysRole staffRole = new SysRole();
        staffRole.setRoleId(11L);
        staffRole.setRoleKey("bu_staff");
        staffRole.setStatus("0");
        staffRole.setDelFlag("0");
        when(sysRoleMapper.selectList(any())).thenReturn(List.of(staffRole));

        SysRoleMenu rm = new SysRoleMenu();
        rm.setRoleId(11L);
        rm.setMenuId(200L);
        when(sysRoleMenuMapper.selectList(any())).thenReturn(List.of(rm));

        // 目录菜单没有 perms
        SysMenu dirMenu = new SysMenu();
        dirMenu.setMenuId(200L);
        dirMenu.setPerms("");
        dirMenu.setStatus("0");
        when(sysMenuMapper.selectList(any())).thenReturn(List.of(dirMenu));

        Set<String> perms = sysUserService.findPermissionsByUserId(10L);

        assertTrue(perms.isEmpty(), "空 perms 应被过滤");
    }
}
