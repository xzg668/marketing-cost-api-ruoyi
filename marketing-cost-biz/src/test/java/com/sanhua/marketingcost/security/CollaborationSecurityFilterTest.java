package com.sanhua.marketingcost.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.service.CollaborationTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 协作者安全过滤器单元测试
 */
class CollaborationSecurityFilterTest {

    private CollaborationTokenService tokenService;
    private CollaborationSecurityFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        tokenService = mock(CollaborationTokenService.class);
        filter = new CollaborationSecurityFilter(tokenService, new ObjectMapper());
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
    @DisplayName("/collaborate 路径无 token 参数 — 返回 401")
    void collaboratePath_noToken_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/collaborate/bom-supplement");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("/collaborate 路径 token 无效 — 返回 401")
    void collaboratePath_invalidToken_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/collaborate/bom-supplement");
        request.setParameter("token", "invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(tokenService.validateToken("invalid-token")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("/collaborate 路径 token 有效 — 设置 Authentication 并放行")
    void collaboratePath_validToken_setsAuthAndContinues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/collaborate/bom-supplement");
        request.setParameter("token", "valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        LpCollaborationToken record = new LpCollaborationToken();
        record.setTokenId(10L);
        record.setToken("valid-token");
        record.setUserId(5L);
        record.setTokenType("bom-supplement");
        record.setRemark("OA-2026-001");
        record.setStatus("0");
        record.setExpireTime(LocalDateTime.now().plusHours(10));

        when(tokenService.validateToken("valid-token")).thenReturn(record);

        filter.doFilterInternal(request, response, filterChain);

        // 应放行
        verify(filterChain).doFilter(request, response);

        // 应设置 Authentication
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("collaborator:5", auth.getPrincipal());
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_OA_COLLABORATOR")));

        // 应设置 details
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) auth.getDetails();
        assertEquals(10L, details.get("tokenId"));
        assertEquals("bom-supplement", details.get("tokenType"));
        assertEquals(5L, details.get("userId"));
        assertEquals("OA-2026-001", details.get("remark"));
    }

    @Test
    @DisplayName("/collaborate/price-supplement 路径 — 同样走 Token 校验")
    void priceSupplementPath_alsoChecked() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/collaborate/price-supplement");
        request.setParameter("token", "price-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(tokenService.validateToken("price-token")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }
}
