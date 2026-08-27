package com.sanhua.marketingcost.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.CollaborationTokenService;
import com.sanhua.marketingcost.service.SysUserService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 协作者安全过滤器单元测试
 */
class CollaborationSecurityFilterTest {

    private CollaborationTokenService tokenService;
    private SysUserService userService;
    private CollaborationPortalGrantCodec grantCodec;
    private CollaborationSecurityFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        tokenService = mock(CollaborationTokenService.class);
        userService = mock(SysUserService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        grantCodec = new CollaborationPortalGrantCodec(objectMapper);
        filter = new CollaborationSecurityFilter(tokenService, grantCodec, userService, objectMapper);
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("非 /collaborate 路径 — 直接放行")
    void nonCollaboratePath_passThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/user");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("统一协作 API 携带有效请求头 — 绑定人员、主任务和模块权限")
    void portalApi_validHeader_setsRestrictedAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/collaboration/product-tasks/mine");
        request.addHeader(CollaborationPortalAuthentication.HEADER, "portal-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        LpCollaborationToken record = new LpCollaborationToken();
        record.setTokenId(20L);
        record.setToken("portal-token");
        record.setUserId(6L);
        record.setTokenType(CollaborationPortalAuthentication.TOKEN_TYPE);
        record.setRemark(grantCodec.encode(88L,
                Set.of(CollaborationPortalModule.BOM, CollaborationPortalModule.PRICE)));
        when(tokenService.validateToken("portal-token")).thenReturn(record);
        SysUser user = new SysUser();
        user.setUserId(6L);
        user.setStatus("0");
        user.setDelFlag("0");
        user.setBusinessUnitType("COMMERCIAL");
        when(userService.getById(6L)).thenReturn(user);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(a -> "collaboration:task:edit".equals(a.getAuthority())));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) authentication.getDetails();
        assertEquals(88L, details.get(CollaborationPortalAuthentication.KEY_COLLABORATION_ID));
        assertEquals("COMMERCIAL", details.get(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE));
        assertEquals(List.of("BOM", "PRICE"), details.get(
                CollaborationPortalAuthentication.KEY_MODULES));
    }

    @Test
    @DisplayName("统一协作 API 未带协作请求头 — 放行给普通 JWT 认证")
    void portalApi_withoutCollaborationHeader_passesToJwt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/collaboration/product-tasks/mine");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(tokenService);
    }
}
