package com.sanhua.marketingcost.security;

import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.SysUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterTest {

    private JwtUtils jwtUtils;
    private UserDetailsService userDetailsService;
    private SysUserService sysUserService;
    private JwtAuthenticationFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        jwtUtils = mock(JwtUtils.class);
        userDetailsService = mock(UserDetailsService.class);
        sysUserService = mock(SysUserService.class);
        filter = new JwtAuthenticationFilter(jwtUtils, userDetailsService, sysUserService);
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("有效 Token — 设置认证上下文，包含角色和权限标识")
    void validToken_setsAuthenticationWithPermissions() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtUtils.validateToken("valid-token")).thenReturn(true);
        when(jwtUtils.getUsernameFromToken("valid-token")).thenReturn("admin");
        when(jwtUtils.extractBusinessUnitType("valid-token")).thenReturn("COMMERCIAL");

        UserDetails userDetails = User.withUsername("admin").password("pass")
                .authorities("ROLE_ADMIN").build();
        when(userDetailsService.loadUserByUsername("admin")).thenReturn(userDetails);

        SysUser sysUser = new SysUser();
        sysUser.setUserId(1L);
        when(sysUserService.findByUsername("admin")).thenReturn(sysUser);
        when(sysUserService.findPermissionsByUserId(1L)).thenReturn(Set.of("*:*:*"));

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("admin", auth.getName());

        // 验证 authorities 包含角色 + 权限标识
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertTrue(authorities.contains("ROLE_ADMIN"), "应包含角色");
        assertTrue(authorities.contains("*:*:*"), "admin 应包含通配权限");

        // 验证 businessUnitType 写入 details
        assertTrue(auth.getDetails() instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) auth.getDetails();
        assertEquals("COMMERCIAL", details.get(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE));

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("普通用户 Token — authorities 包含具体权限标识")
    void normalUserToken_hasSpecificPermissions() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtUtils.validateToken("user-token")).thenReturn(true);
        when(jwtUtils.getUsernameFromToken("user-token")).thenReturn("staff1");
        when(jwtUtils.extractBusinessUnitType("user-token")).thenReturn("HOUSEHOLD");

        UserDetails userDetails = User.withUsername("staff1").password("pass")
                .authorities("ROLE_BU_STAFF").build();
        when(userDetailsService.loadUserByUsername("staff1")).thenReturn(userDetails);

        SysUser sysUser = new SysUser();
        sysUser.setUserId(10L);
        when(sysUserService.findByUsername("staff1")).thenReturn(sysUser);
        when(sysUserService.findPermissionsByUserId(10L))
                .thenReturn(Set.of("data:import:list", "base:material:query"));

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertTrue(authorities.contains("ROLE_BU_STAFF"));
        assertTrue(authorities.contains("data:import:list"));
        assertTrue(authorities.contains("base:material:query"));

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) auth.getDetails();
        assertEquals("HOUSEHOLD", details.get(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE));
    }

    @Test
    @DisplayName("旧 Token 不含 businessUnitType — details 中值为 null")
    void oldTokenWithoutBusinessUnit_detailsHasNullValue() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer old-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtUtils.validateToken("old-token")).thenReturn(true);
        when(jwtUtils.getUsernameFromToken("old-token")).thenReturn("user1");
        when(jwtUtils.extractBusinessUnitType("old-token")).thenReturn(null);

        UserDetails userDetails = User.withUsername("user1").password("pass")
                .authorities("ROLE_BU_STAFF").build();
        when(userDetailsService.loadUserByUsername("user1")).thenReturn(userDetails);

        SysUser sysUser = new SysUser();
        sysUser.setUserId(5L);
        when(sysUserService.findByUsername("user1")).thenReturn(sysUser);
        when(sysUserService.findPermissionsByUserId(5L)).thenReturn(Set.of("cost:trial:list"));

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) auth.getDetails();
        assertNull(details.get(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE),
                "旧 Token 的 businessUnitType 应为 null");
    }

    @Test
    @DisplayName("无 Authorization Header — 不设置认证，正常放行")
    void noAuthHeader_noAuthentication_chainContinues() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("无效 Token — 不设置认证，正常放行")
    void invalidToken_noAuthentication_chainContinues() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtUtils.validateToken("bad-token")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
        verify(userDetailsService, never()).loadUserByUsername(any());
    }

    @Test
    @DisplayName("非 Bearer 格式 Header — 不设置认证")
    void nonBearerHeader_noAuthentication() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(jwtUtils, never()).validateToken(any());
    }

    @Test
    @DisplayName("空 Bearer 值 — 不设置认证")
    void emptyBearerValue_noAuthentication() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtUtils.validateToken("")).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("FilterChain 无论 Token 是否有效都会继续调用")
    void filterChain_alwaysCalled() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);
        verify(filterChain).doFilter(request, response);

        // 带无效 token 也继续
        FilterChain chain2 = mock(FilterChain.class);
        request.addHeader("Authorization", "Bearer invalid");
        when(jwtUtils.validateToken("invalid")).thenReturn(false);
        filter.doFilterInternal(request, response, chain2);
        verify(chain2).doFilter(request, response);
    }

    @Test
    @DisplayName("用户不存在于 sys_user 时 — 仍设置认证但无权限标识")
    void userNotInSysUser_authSetWithRolesOnly() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtUtils.validateToken("token1")).thenReturn(true);
        when(jwtUtils.getUsernameFromToken("token1")).thenReturn("ghost");
        when(jwtUtils.extractBusinessUnitType("token1")).thenReturn(null);

        UserDetails userDetails = User.withUsername("ghost").password("pass")
                .authorities("ROLE_BU_STAFF").build();
        when(userDetailsService.loadUserByUsername("ghost")).thenReturn(userDetails);
        when(sysUserService.findByUsername("ghost")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertEquals(Set.of("ROLE_BU_STAFF"), authorities, "仅包含角色，无权限标识");
    }
}
