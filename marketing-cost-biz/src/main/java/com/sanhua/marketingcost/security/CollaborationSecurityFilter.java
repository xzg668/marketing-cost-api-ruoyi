package com.sanhua.marketingcost.security;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.service.CollaborationTokenService;
import com.sanhua.marketingcost.service.SysUserService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 外部协作门户安全过滤器。
 * <p>
 * 只接受请求头令牌，并把人员、协作主任务和可处理模块写入认证上下文。
 * <p>
 * 没有携带协作请求头的普通系统请求仍由 JwtAuthenticationFilter 处理。
 */
@Component
public class CollaborationSecurityFilter extends OncePerRequestFilter {

    /** 协作者角色标识 */
    private static final String ROLE_COLLABORATOR = "ROLE_TECHNICAL_COLLABORATOR";
    /** 匹配的统一协作 API。 */
    private static final String PORTAL_API_PATTERN = "/api/v1/collaboration/**";

    private final CollaborationTokenService collaborationTokenService;
    private final CollaborationPortalGrantCodec grantCodec;
    private final SysUserService userService;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public CollaborationSecurityFilter(CollaborationTokenService collaborationTokenService,
                                       CollaborationPortalGrantCodec grantCodec,
                                       SysUserService userService,
                                       ObjectMapper objectMapper) {
        this.collaborationTokenService = collaborationTokenService;
        this.grantCodec = grantCodec;
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        String portalToken = request.getHeader(CollaborationPortalAuthentication.HEADER);
        boolean portalRequest = pathMatcher.match(PORTAL_API_PATTERN, path)
                && StringUtils.hasText(portalToken);
        if (!portalRequest) {
            filterChain.doFilter(request, response);
            return;
        }

        LpCollaborationToken record = collaborationTokenService.validateToken(portalToken);
        if (record == null) {
            writeUnauthorized(response, "协作令牌无效或已过期");
            return;
        }

        authenticatePortal(record, response, filterChain, request);
    }

    private void authenticatePortal(LpCollaborationToken record,
                                    HttpServletResponse response,
                                    FilterChain filterChain,
                                    HttpServletRequest request) throws IOException, ServletException {
        if (!CollaborationPortalAuthentication.TOKEN_TYPE.equals(record.getTokenType())) {
            writeUnauthorized(response, "协作令牌类型无效");
            return;
        }
        CollaborationPortalGrant grant;
        try {
            grant = grantCodec.decode(record.getRemark());
        } catch (IllegalArgumentException exception) {
            writeUnauthorized(response, exception.getMessage());
            return;
        }
        SysUser user = record.getUserId() == null ? null : userService.getById(record.getUserId());
        if (user == null || user.getUserId() == null || !"0".equals(user.getStatus())
                || (StringUtils.hasText(user.getDelFlag()) && !"0".equals(user.getDelFlag()))
                || !StringUtils.hasText(user.getBusinessUnitType())) {
            writeUnauthorized(response, "协作人员账号无效或已停用");
            return;
        }

        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_COLLABORATOR));
        authorities.add(new SimpleGrantedAuthority("collaboration:task:read"));
        authorities.add(new SimpleGrantedAuthority("collaboration:task:edit"));
        authorities.add(new SimpleGrantedAuthority("collaboration:task:submit"));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        "collaborator:" + user.getUserId(), null, authorities);

        Map<String, Object> details = new HashMap<>();
        details.put("tokenId", record.getTokenId());
        details.put("tokenType", record.getTokenType());
        details.put("userId", user.getUserId());
        details.put(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, user.getBusinessUnitType());
        details.put(CollaborationPortalAuthentication.KEY_RESTRICTED, true);
        details.put(CollaborationPortalAuthentication.KEY_COLLABORATION_ID,
                grant.collaborationId());
        details.put(CollaborationPortalAuthentication.KEY_MODULES,
                grant.modules().stream().map(Enum::name).sorted().toList());
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
