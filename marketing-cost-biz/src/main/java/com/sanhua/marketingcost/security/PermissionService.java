package com.sanhua.marketingcost.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 若依风格权限校验服务（Bean 名称 "ss"）。
 * <p>
 * 用法：{@code @PreAuthorize("@ss.hasPermi('system:user:list')")}
 * <p>
 * 权限来源：{@link org.springframework.security.core.context.SecurityContext} 中当前 Authentication 的 authorities 列表，
 * 其中角色以 {@code ROLE_xxx} 前缀标识，权限标识以 {@code 模块:资源:操作} 形式标识（如 {@code system:user:list}）。
 * 超级管理员权限使用通配符 {@code *:*:*}。
 */
@Component("ss")
public class PermissionService {

    /** 超级管理员通配权限标识 */
    public static final String ALL_PERMISSION = "*:*:*";

    /** 角色前缀（Spring Security 规范） */
    public static final String ROLE_PREFIX = "ROLE_";

    /**
     * 判断当前登录用户是否具有指定权限。
     *
     * @param permission 权限标识，如 {@code system:user:list}
     * @return true=有权限；false=无权限或未登录
     */
    public boolean hasPermi(String permission) {
        if (permission == null || permission.isEmpty()) {
            return false;
        }
        Collection<? extends GrantedAuthority> authorities = currentAuthorities();
        if (authorities == null || authorities.isEmpty()) {
            return false;
        }
        for (GrantedAuthority authority : authorities) {
            String auth = authority.getAuthority();
            if (auth == null) {
                continue;
            }
            // 超级管理员通配符 → 任意权限均放行
            if (ALL_PERMISSION.equals(auth)) {
                return true;
            }
            if (permission.equals(auth)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前登录用户是否具有任一指定权限（OR 语义）。
     *
     * @param permissions 权限标识数组
     * @return true=拥有任一权限
     */
    public boolean hasAnyPermi(String... permissions) {
        if (permissions == null || permissions.length == 0) {
            return false;
        }
        for (String p : permissions) {
            if (hasPermi(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前登录用户是否具有指定角色。
     *
     * @param role 角色编码，如 {@code ADMIN}、{@code BU_DIRECTOR}（无需 ROLE_ 前缀）
     * @return true=拥有该角色
     */
    public boolean hasRole(String role) {
        if (role == null || role.isEmpty()) {
            return false;
        }
        Collection<? extends GrantedAuthority> authorities = currentAuthorities();
        if (authorities == null || authorities.isEmpty()) {
            return false;
        }
        String target = role.startsWith(ROLE_PREFIX) ? role : ROLE_PREFIX + role;
        for (GrantedAuthority authority : authorities) {
            if (target.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前登录用户是否具有任一指定角色（OR 语义）。
     */
    public boolean hasAnyRole(String... roles) {
        if (roles == null || roles.length == 0) {
            return false;
        }
        for (String r : roles) {
            if (hasRole(r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取当前 SecurityContext 的 authorities 列表；未登录时返回 null。
     */
    private Collection<? extends GrantedAuthority> currentAuthorities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return null;
        }
        return authentication.getAuthorities();
    }
}
