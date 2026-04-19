package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.system.SysUserAssignRolesRequest;
import com.sanhua.marketingcost.dto.system.SysUserResetPasswordRequest;
import com.sanhua.marketingcost.entity.SysRole;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.vo.SysUserVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SysUserControllerTest {

    private SysUserService sysUserService;
    private PasswordEncoder passwordEncoder;
    private SysUserController controller;

    @BeforeEach
    void setUp() {
        sysUserService = mock(SysUserService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        controller = new SysUserController(sysUserService, passwordEncoder);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("用户详情 — 不能查看其他业务单元用户")
    void get_crossBusinessUnit_forbidden() {
        authenticate("COMMERCIAL", "ROLE_bu_director");
        when(sysUserService.getById(2L)).thenReturn(buildUser(2L, "house_user", "HOUSEHOLD"));

        CommonResult<SysUserVO> response = controller.get(2L);

        assertTrue(response.isError());
        assertEquals(403, response.getCode());
        assertEquals("不能操作其他业务单元的用户", response.getMsg());
        verify(sysUserService, never()).findRolesByUserId(2L);
    }

    @Test
    @DisplayName("重置密码 — 同业务单元允许操作")
    void resetPassword_sameBusinessUnit_success() {
        authenticate("COMMERCIAL", "ROLE_bu_director");
        when(sysUserService.getById(3L)).thenReturn(buildUser(3L, "com_user", "COMMERCIAL"));
        when(passwordEncoder.encode("newpass")).thenReturn("encoded");
        SysUserResetPasswordRequest request = new SysUserResetPasswordRequest();
        request.setNewPassword("newpass");

        CommonResult<Void> response = controller.resetPassword(3L, request);

        assertTrue(response.isSuccess());
        verify(sysUserService).resetPassword(3L, "encoded");
    }

    @Test
    @DisplayName("分配角色 — 不能给其他业务单元用户分配角色")
    void assignRoles_crossBusinessUnit_forbidden() {
        authenticate("COMMERCIAL", "ROLE_bu_director");
        when(sysUserService.getById(4L)).thenReturn(buildUser(4L, "house_user", "HOUSEHOLD"));
        SysUserAssignRolesRequest request = new SysUserAssignRolesRequest();
        request.setRoleIds(List.of(11L));

        CommonResult<Void> response = controller.assignRoles(4L, request, null);

        assertTrue(response.isError());
        assertEquals(403, response.getCode());
        verify(sysUserService, never()).assignRoles(any(), any());
    }

    @Test
    @DisplayName("删除用户 — admin 可操作历史未绑定业务单元的 admin 账号")
    void delete_adminCanOperateLegacyAdminWithoutBusinessUnit() {
        authenticate("COMMERCIAL", "ROLE_admin");
        when(sysUserService.getById(1L)).thenReturn(buildUser(1L, "admin", null));
        when(sysUserService.findRolesByUserId(1L)).thenReturn(List.of(buildRole(1L, "admin")));
        when(sysUserService.isLastAdmin(1L)).thenReturn(false);

        CommonResult<Void> response = controller.delete(1L);

        assertTrue(response.isSuccess());
        verify(sysUserService).deleteUser(1L);
    }

    @Test
    @DisplayName("可分配角色 — BU_DIRECTOR 只能看到 BU_STAFF")
    void listRoles_buDirectorOnlySeesBuStaff() {
        authenticate("COMMERCIAL", "ROLE_bu_director");
        when(sysUserService.listAllRoles()).thenReturn(List.of(
                buildRole(1L, "admin"),
                buildRole(10L, "bu_director"),
                buildRole(11L, "bu_staff")));

        CommonResult<List<SysUserVO.RoleSimple>> response = controller.listRoles();

        assertTrue(response.isSuccess());
        assertEquals(1, response.getData().size());
        assertEquals("bu_staff", response.getData().get(0).getRoleKey());
    }

    @Test
    @DisplayName("可分配角色 — admin 能看到全部角色")
    void listRoles_adminSeesAllRoles() {
        authenticate("COMMERCIAL", "ROLE_admin");
        when(sysUserService.listAllRoles()).thenReturn(List.of(
                buildRole(1L, "admin"),
                buildRole(10L, "bu_director"),
                buildRole(11L, "bu_staff")));

        CommonResult<List<SysUserVO.RoleSimple>> response = controller.listRoles();

        assertTrue(response.isSuccess());
        assertEquals(3, response.getData().size());
    }

    private void authenticate(String businessUnitType, String... authorities) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "principal",
                null,
                java.util.Arrays.stream(authorities)
                        .map(SimpleGrantedAuthority::new)
                        .toList());
        token.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private SysUser buildUser(long id, String username, String businessUnitType) {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setUserName(username);
        user.setBusinessUnitType(businessUnitType);
        return user;
    }

    private SysRole buildRole(long id, String roleKey) {
        SysRole role = new SysRole();
        role.setRoleId(id);
        role.setRoleKey(roleKey);
        return role;
    }
}
