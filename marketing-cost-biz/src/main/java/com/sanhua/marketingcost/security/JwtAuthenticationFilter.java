package com.sanhua.marketingcost.security;

import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.SysUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * JWT 认证过滤器。
 * <p>
 * v1.3 改造：
 * <ul>
 *   <li>从 Token 解析 businessUnitType，写入 Authentication.details 的 Map 中</li>
 *   <li>加载用户权限标识（sys_role_menu → sys_menu.perms）到 authorities</li>
 *   <li>authorities 包含角色（ROLE_xxx）和权限标识（如 system:user:list）</li>
 * </ul>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;
    private final SysUserService sysUserService;

    public JwtAuthenticationFilter(JwtUtils jwtUtils,
                                   UserDetailsService userDetailsService,
                                   SysUserService sysUserService) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
        this.sysUserService = sysUserService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (token != null && jwtUtils.validateToken(token)) {
            String username = jwtUtils.getUsernameFromToken(token);

            // 加载用户基本信息（含角色 → ROLE_xxx authorities）
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            // 查询用户ID，用于加载权限标识
            SysUser sysUser = sysUserService.findByUsername(username);

            // 合并角色 authorities + 权限标识 authorities
            List<SimpleGrantedAuthority> allAuthorities = new ArrayList<>();
            // 保留原有角色 authorities（ROLE_xxx）
            userDetails.getAuthorities().forEach(
                    ga -> allAuthorities.add(new SimpleGrantedAuthority(ga.getAuthority()))
            );
            // 追加权限标识（如 system:user:list）
            if (sysUser != null) {
                Set<String> perms = sysUserService.findPermissionsByUserId(sysUser.getUserId());
                perms.forEach(p -> allAuthorities.add(new SimpleGrantedAuthority(p)));
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, allAuthorities);

            // 将 businessUnitType 存入 details Map，供 BusinessUnitContext 读取
            String businessUnitType = jwtUtils.extractBusinessUnitType(token);
            Map<String, Object> detailsMap = new HashMap<>();
            detailsMap.put(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType);
            authentication.setDetails(detailsMap);

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
