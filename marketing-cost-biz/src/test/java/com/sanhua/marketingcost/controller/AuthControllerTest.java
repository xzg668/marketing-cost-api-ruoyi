package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.LoginRequest;
import com.sanhua.marketingcost.dto.LoginResponse;
import com.sanhua.marketingcost.entity.SysMenu;
import com.sanhua.marketingcost.entity.SysRole;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.entity.system.SysLoginLog;
import com.sanhua.marketingcost.mapper.SysLoginLogMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.JwtUtils;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.vo.RouterVO;
import com.sanhua.marketingcost.vo.UserInfoVO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthControllerTest {

    private AuthenticationManager authenticationManager;
    private JwtUtils jwtUtils;
    private SysUserService sysUserService;
    private SysLoginLogMapper sysLoginLogMapper;
    private AuthController authController;

    @BeforeEach
    void setUp() {
        authenticationManager = mock(AuthenticationManager.class);
        jwtUtils = mock(JwtUtils.class);
        sysUserService = mock(SysUserService.class);
        sysLoginLogMapper = mock(SysLoginLogMapper.class);
        authController = new AuthController(authenticationManager, jwtUtils, sysUserService, sysLoginLogMapper);
        SecurityContextHolder.clearContext();
    }

    /** 构造一个带常见头的模拟 HttpServletRequest */
    private HttpServletRequest mockRequest() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr("192.168.1.10");
        r.addHeader("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        return r;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ========== login ==========

    @Test
    @DisplayName("登录成功 — Token 含 businessUnitType")
    void login_validCredentialsWithBusinessUnit_returnsToken() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        request.setBusinessUnitType("COMMERCIAL");

        UserDetails userDetails = new User("admin", "encoded",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtUtils.generateToken("admin", "COMMERCIAL")).thenReturn("mock-jwt-token");

        CommonResult<LoginResponse> response = authController.login(request, mockRequest());

        assertTrue(response.isSuccess());
        assertEquals("mock-jwt-token", response.getData().getToken());
        assertEquals("admin", response.getData().getUsername());
        verify(jwtUtils).generateToken("admin", "COMMERCIAL");
    }

    @Test
    @DisplayName("登录成功 — 家用业务单元")
    void login_householdUnit_tokenGenerated() {
        LoginRequest request = new LoginRequest();
        request.setUsername("staff1");
        request.setPassword("pass");
        request.setBusinessUnitType("HOUSEHOLD");

        UserDetails userDetails = new User("staff1", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtUtils.generateToken("staff1", "HOUSEHOLD")).thenReturn("token2");

        authController.login(request, mockRequest());

        verify(jwtUtils).generateToken("staff1", "HOUSEHOLD");
    }

    @Test
    @DisplayName("登录失败 — 非 admin 不能选择其他业务单元")
    void login_nonAdminCrossBusinessUnit_throwsBadCredentials() {
        LoginRequest request = new LoginRequest();
        request.setUsername("bu_director_com");
        request.setPassword("123456");
        request.setBusinessUnitType("HOUSEHOLD");

        UserDetails userDetails = new User("bu_director_com", "encoded",
                List.of(new SimpleGrantedAuthority("ROLE_bu_director")));
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        SysUser user = buildUser(10L, "bu_director_com", "COMMERCIAL");
        when(sysUserService.findByUsername("bu_director_com")).thenReturn(user);
        when(sysUserService.findRolesByUserId(10L)).thenReturn(List.of(buildRole(10L, "bu_director")));

        BadCredentialsException ex = assertThrows(BadCredentialsException.class,
                () -> authController.login(request, mockRequest()));

        assertEquals("当前账号不属于所选业务单元", ex.getMessage());
        verify(jwtUtils, never()).generateToken(any(), any());
        ArgumentCaptor<SysLoginLog> captor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(sysLoginLogMapper).insert(captor.capture());
        assertEquals("1", captor.getValue().getStatus());
        assertEquals("当前账号不属于所选业务单元", captor.getValue().getMsg());
    }

    @Test
    @DisplayName("登录成功 — admin 可选择任一业务单元")
    void login_adminCanChooseAnyBusinessUnit() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("123456");
        request.setBusinessUnitType("HOUSEHOLD");

        UserDetails userDetails = new User("admin", "encoded",
                List.of(new SimpleGrantedAuthority("ROLE_admin")));
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        SysUser user = buildUser(1L, "admin", "COMMERCIAL");
        when(sysUserService.findByUsername("admin")).thenReturn(user);
        when(sysUserService.findRolesByUserId(1L)).thenReturn(List.of(buildRole(1L, "admin")));
        when(jwtUtils.generateToken("admin", "HOUSEHOLD")).thenReturn("admin-household-token");

        CommonResult<LoginResponse> response = authController.login(request, mockRequest());

        assertTrue(response.isSuccess());
        assertEquals("admin-household-token", response.getData().getToken());
        verify(jwtUtils).generateToken("admin", "HOUSEHOLD");
    }

    @Test
    @DisplayName("登录失败 — 密码错误")
    void login_invalidPassword_throwsBadCredentials() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");
        request.setBusinessUnitType("COMMERCIAL");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThrows(BadCredentialsException.class,
                () -> authController.login(request, mockRequest()));
    }

    @Test
    @DisplayName("登录调用 AuthenticationManager")
    void login_callsAuthenticationManager() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        request.setBusinessUnitType("COMMERCIAL");

        UserDetails userDetails = new User("admin", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtUtils.generateToken(any(), any())).thenReturn("token");

        authController.login(request, mockRequest());

        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    // ========== 登录日志（T31） ==========

    @Test
    @DisplayName("登录成功 — sys_login_log 记录一条 status=0")
    void login_success_writesLoginLog() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        request.setBusinessUnitType("COMMERCIAL");

        UserDetails userDetails = new User("admin", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtUtils.generateToken(any(), any())).thenReturn("token");

        authController.login(request, mockRequest());

        ArgumentCaptor<SysLoginLog> captor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(sysLoginLogMapper).insert(captor.capture());
        SysLoginLog entry = captor.getValue();
        assertEquals("admin", entry.getUserName());
        assertEquals("0", entry.getStatus());
        assertEquals("登录成功", entry.getMsg());
        assertEquals("192.168.1.10", entry.getIpaddr());
        assertEquals("Chrome", entry.getBrowser());
        assertEquals("Windows", entry.getOs());
        assertNotNull(entry.getLoginTime());
    }

    @Test
    @DisplayName("登录失败 — sys_login_log 记录 status=1 且异常继续抛出")
    void login_failure_writesFailureLog() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("wrong");
        request.setBusinessUnitType("COMMERCIAL");

        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("用户名或密码错误"));

        assertThrows(BadCredentialsException.class,
                () -> authController.login(request, mockRequest()));

        ArgumentCaptor<SysLoginLog> captor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(sysLoginLogMapper).insert(captor.capture());
        SysLoginLog entry = captor.getValue();
        assertEquals("admin", entry.getUserName());
        assertEquals("1", entry.getStatus());
        assertEquals("用户名或密码错误", entry.getMsg());
    }

    @Test
    @DisplayName("登录日志写入失败 — 不影响登录主流程")
    void login_logInsertFails_doesNotAffectFlow() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        request.setBusinessUnitType("COMMERCIAL");

        UserDetails userDetails = new User("admin", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtUtils.generateToken(any(), any())).thenReturn("token");
        when(sysLoginLogMapper.insert(any(SysLoginLog.class)))
                .thenThrow(new RuntimeException("DB 不可用"));

        CommonResult<LoginResponse> response = authController.login(request, mockRequest());
        assertTrue(response.isSuccess());
        assertEquals("token", response.getData().getToken());
    }

    @Test
    @DisplayName("登出 — 已登录时按用户名记录一条退出日志")
    void logout_authenticated_writesLogoutLog() {
        UserDetails userDetails = new User("admin", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);

        CommonResult<Void> response = authController.logout(auth, mockRequest());

        assertTrue(response.isSuccess());
        ArgumentCaptor<SysLoginLog> captor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(sysLoginLogMapper).insert(captor.capture());
        SysLoginLog entry = captor.getValue();
        assertEquals("admin", entry.getUserName());
        assertEquals("0", entry.getStatus());
        assertEquals("退出成功", entry.getMsg());
    }

    @Test
    @DisplayName("登出 — Authentication 为 null 也不抛异常")
    void logout_unauthenticated_stillRecords() {
        CommonResult<Void> response = authController.logout(null, mockRequest());
        assertTrue(response.isSuccess());
        ArgumentCaptor<SysLoginLog> captor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(sysLoginLogMapper).insert(captor.capture());
        assertEquals("", captor.getValue().getUserName());
        assertEquals("退出成功", captor.getValue().getMsg());
    }

    @Test
    @DisplayName("登录日志 — X-Forwarded-For 优先于 remoteAddr，取第一个 IP")
    void loginLog_extractsIpFromXForwardedFor() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("pwd");
        request.setBusinessUnitType("COMMERCIAL");

        UserDetails userDetails = new User("admin", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        when(authenticationManager.authenticate(any())).thenReturn(auth);
        when(jwtUtils.generateToken(any(), any())).thenReturn("t");

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");
        req.addHeader("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                        + "(KHTML, like Gecko) Version/17.1 Safari/605.1.15");

        authController.login(request, req);

        ArgumentCaptor<SysLoginLog> captor = ArgumentCaptor.forClass(SysLoginLog.class);
        verify(sysLoginLogMapper).insert(captor.capture());
        SysLoginLog entry = captor.getValue();
        assertEquals("203.0.113.5", entry.getIpaddr());
        assertEquals("Safari", entry.getBrowser());
        assertEquals("Mac OS", entry.getOs());
    }

    // ========== /me ==========

    @Test
    @DisplayName("/me — 返回用户信息含 roles/permissions/businessUnitType")
    void me_returnsUserInfoWithPermissions() {
        UserDetails userDetails = new User("admin", "encoded",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("*:*:*")));
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        // /me 从 BusinessUnitContext 取业务单元
        setAuthenticationDetails(Map.of(
                BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL"));

        SysUser user = new SysUser();
        user.setUserId(1L);
        user.setUserName("admin");
        user.setNickName("系统管理员");
        when(sysUserService.findByUsername("admin")).thenReturn(user);

        SysRole role = new SysRole();
        role.setRoleId(1L);
        role.setRoleKey("ADMIN");
        when(sysUserService.findRolesByUserId(1L)).thenReturn(List.of(role));
        when(sysUserService.findPermissionsByUserId(1L)).thenReturn(Set.of("*:*:*"));

        CommonResult<UserInfoVO> response = authController.me(auth);

        assertTrue(response.isSuccess());
        UserInfoVO vo = response.getData();
        assertEquals(1L, vo.getUserId());
        assertEquals("admin", vo.getUsername());
        assertEquals("系统管理员", vo.getNickName());
        assertEquals(List.of("ADMIN"), vo.getRoles());
        assertEquals(List.of("*:*:*"), vo.getPermissions());
        assertEquals("COMMERCIAL", vo.getBusinessUnitType());
    }

    @Test
    @DisplayName("/me — 用户不在 sys_user 时返回空角色和权限")
    void me_userNotFound_returnsEmpty() {
        UserDetails userDetails = new User("ghost", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);

        when(sysUserService.findByUsername("ghost")).thenReturn(null);

        CommonResult<UserInfoVO> response = authController.me(auth);

        assertTrue(response.isSuccess());
        assertTrue(response.getData().getRoles().isEmpty());
        assertTrue(response.getData().getPermissions().isEmpty());
    }

    @Test
    @DisplayName("/me — businessUnitType 从 Authentication.details 读取")
    void me_businessUnitFromDetails() {
        UserDetails userDetails = new User("staff1", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        setAuthenticationDetails(Map.of(
                BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "HOUSEHOLD"));

        SysUser user = new SysUser();
        user.setUserId(2L);
        user.setUserName("staff1");
        when(sysUserService.findByUsername("staff1")).thenReturn(user);
        when(sysUserService.findRolesByUserId(2L)).thenReturn(List.of());
        when(sysUserService.findPermissionsByUserId(2L)).thenReturn(Set.of());

        CommonResult<UserInfoVO> response = authController.me(auth);

        assertEquals("HOUSEHOLD", response.getData().getBusinessUnitType());
    }

    // ========== /routers ==========

    @Test
    @DisplayName("/routers — 用户不存在返回空数组")
    void routers_userNotFound_returnsEmpty() {
        UserDetails userDetails = new User("ghost", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);

        when(sysUserService.findByUsername("ghost")).thenReturn(null);

        CommonResult<List<RouterVO>> response = authController.routers(auth);

        assertTrue(response.isSuccess());
        assertTrue(response.getData().isEmpty());
    }

    @Test
    @DisplayName("/routers — 返回扁平菜单组装后的路由树")
    void routers_returnsTree() {
        UserDetails userDetails = new User("admin", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        setAuthenticationDetails(Map.of(
                BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL"));

        SysUser user = new SysUser();
        user.setUserId(1L);
        when(sysUserService.findByUsername("admin")).thenReturn(user);

        // 构造菜单：100=系统管理(目录) / 101=用户管理(菜单,父100) / 200=数据接入(目录)
        SysMenu dir1 = buildMenu(100L, "系统管理", 0L, "system", "M");
        SysMenu sub1 = buildMenu(101L, "用户管理", 100L, "user", "C");
        sub1.setComponent("system/user/index");
        SysMenu dir2 = buildMenu(200L, "数据接入", 0L, "data-import", "M");
        when(sysUserService.findVisibleMenus(1L, "COMMERCIAL"))
                .thenReturn(List.of(dir1, sub1, dir2));

        CommonResult<List<RouterVO>> response = authController.routers(auth);

        assertTrue(response.isSuccess());
        List<RouterVO> tree = response.getData();
        assertEquals(2, tree.size(), "应有 2 个根节点");

        RouterVO systemRoot = tree.stream().filter(r -> r.getMenuId() == 100L).findFirst().orElseThrow();
        assertEquals("系统管理", systemRoot.getMeta().getTitle());
        assertEquals(1, systemRoot.getChildren().size(), "系统管理应有 1 个子菜单");
        assertEquals(101L, systemRoot.getChildren().get(0).getMenuId());

        RouterVO dataRoot = tree.stream().filter(r -> r.getMenuId() == 200L).findFirst().orElseThrow();
        assertNull(dataRoot.getChildren(), "无子项应为 null（@JsonInclude NON_NULL 过滤）");
    }

    @Test
    @DisplayName("/routers — 按 businessUnitType 传递过滤参数")
    void routers_passesBusinessUnitType() {
        UserDetails userDetails = new User("staff1", "encoded", List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(userDetails);
        setAuthenticationDetails(Map.of(
                BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "HOUSEHOLD"));

        SysUser user = new SysUser();
        user.setUserId(5L);
        when(sysUserService.findByUsername("staff1")).thenReturn(user);
        when(sysUserService.findVisibleMenus(5L, "HOUSEHOLD"))
                .thenReturn(Collections.emptyList());

        authController.routers(auth);

        verify(sysUserService).findVisibleMenus(5L, "HOUSEHOLD");
    }

    @Test
    @DisplayName("buildRouterTree — 多层嵌套（二级目录）")
    void buildRouterTree_multiLevel() {
        SysMenu root = buildMenu(300L, "基础数据", 0L, "base", "M");
        SysMenu sub = buildMenu(305L, "辅料管理", 300L, "aux", "M");
        SysMenu leaf = buildMenu(3051L, "辅料科目", 305L, "subject", "C");
        leaf.setComponent("base/aux/subject");

        List<RouterVO> tree = authController.buildRouterTree(List.of(root, sub, leaf));

        assertEquals(1, tree.size());
        RouterVO r = tree.get(0);
        assertEquals(300L, r.getMenuId());
        assertEquals(1, r.getChildren().size());
        RouterVO s = r.getChildren().get(0);
        assertEquals(305L, s.getMenuId());
        assertEquals(1, s.getChildren().size());
        assertEquals(3051L, s.getChildren().get(0).getMenuId());
    }

    @Test
    @DisplayName("buildRouterTree — 空列表返回空结果")
    void buildRouterTree_empty() {
        assertTrue(authController.buildRouterTree(null).isEmpty());
        assertTrue(authController.buildRouterTree(Collections.emptyList()).isEmpty());
    }

    @Test
    @DisplayName("buildRouterTree — 孤儿节点（parent 不存在）视为根")
    void buildRouterTree_orphanAsRoot() {
        SysMenu orphan = buildMenu(999L, "孤儿", 888L, "orphan", "C");
        List<RouterVO> tree = authController.buildRouterTree(List.of(orphan));
        assertEquals(1, tree.size());
        assertEquals(999L, tree.get(0).getMenuId());
    }

    @Test
    @DisplayName("RouterVO.meta.hidden — visible=1 时为 true，visible=0 时为 null")
    void toRouterVO_hiddenFlag() {
        SysMenu visible = buildMenu(1L, "显示", 0L, "show", "M");
        SysMenu hidden = buildMenu(2L, "隐藏", 0L, "hide", "M");
        hidden.setVisible("1");

        List<RouterVO> tree = authController.buildRouterTree(List.of(visible, hidden));
        RouterVO vis = tree.stream().filter(r -> r.getMenuId() == 1L).findFirst().orElseThrow();
        RouterVO hid = tree.stream().filter(r -> r.getMenuId() == 2L).findFirst().orElseThrow();
        assertNull(vis.getMeta().getHidden());
        assertEquals(Boolean.TRUE, hid.getMeta().getHidden());
    }

    // ========== 辅助 ==========

    private SysMenu buildMenu(long id, String name, long parentId, String path, String type) {
        SysMenu m = new SysMenu();
        m.setMenuId(id);
        m.setMenuName(name);
        m.setParentId(parentId);
        m.setPath(path);
        m.setMenuType(type);
        m.setVisible("0");
        m.setStatus("0");
        return m;
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

    /** 把 details Map 塞进一个真实 Authentication 存入 SecurityContext，供 BusinessUnitContext 读取 */
    private void setAuthenticationDetails(Map<String, Object> details) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "principal", null, List.of());
        token.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(token);
    }
}
