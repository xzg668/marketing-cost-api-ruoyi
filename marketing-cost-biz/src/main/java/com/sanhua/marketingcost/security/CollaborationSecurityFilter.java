package com.sanhua.marketingcost.security;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.service.CollaborationTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OA 协作者安全过滤器
 * <p>
 * 拦截 /collaborate/** 请求，从请求参数中提取 token 并校验。
 * 校验通过后构建 OA_COLLABORATOR 角色的 Authentication，
 * 将 tokenType 和 remark（oaNo）存入 details Map 供后续业务使用。
 * <p>
 * 非 /collaborate/** 路径的请求直接放行（由 JwtAuthenticationFilter 处理）。
 */
@Component
public class CollaborationSecurityFilter extends OncePerRequestFilter {

    /** 协作者角色标识 */
    private static final String ROLE_COLLABORATOR = "ROLE_OA_COLLABORATOR";
    /** 匹配的 URL 模式 */
    private static final String COLLABORATE_PATTERN = "/collaborate/**";

    private final CollaborationTokenService collaborationTokenService;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public CollaborationSecurityFilter(CollaborationTokenService collaborationTokenService,
                                       ObjectMapper objectMapper) {
        this.collaborationTokenService = collaborationTokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // 只处理 /collaborate/** 路径
        if (!pathMatcher.match(COLLABORATE_PATTERN, path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 从请求参数中提取 token
        String token = request.getParameter("token");
        if (!StringUtils.hasText(token)) {
            writeUnauthorized(response, "缺少协作令牌");
            return;
        }

        // 校验 token
        LpCollaborationToken record = collaborationTokenService.validateToken(token);
        if (record == null) {
            writeUnauthorized(response, "协作令牌无效或已过期");
            return;
        }

        // 构建协作者 Authentication
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        "collaborator:" + record.getUserId(),
                        null,
                        List.of(new SimpleGrantedAuthority(ROLE_COLLABORATOR))
                );

        // 将协作信息存入 details，供 Controller 读取
        Map<String, Object> details = new HashMap<>();
        details.put("tokenId", record.getTokenId());
        details.put("tokenType", record.getTokenType());
        details.put("userId", record.getUserId());
        details.put("remark", record.getRemark());
        authentication.setDetails(details);

        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    /**
     * 输出 401 未认证响应
     */
    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(),
                CommonResult.error(GlobalErrorCodeConstants.UNAUTHORIZED.getCode(), message));
    }
}
