package com.sanhua.marketingcost.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Map;

/**
 * 当前登录用户业务单元上下文工具类。
 * <p>
 * 业务单元信息由 {@code JwtAuthenticationFilter}（T09）在认证时写入
 * {@link Authentication#getDetails()} 的 Map 中，键名为 {@code businessUnitType}。
 * <p>
 * 本工具类不依赖具体填充方式，便于单元测试直接设置 Authentication。
 */
public final class BusinessUnitContext {

    /** 存储在 Authentication details Map 中的键名 */
    public static final String KEY_BUSINESS_UNIT_TYPE = "businessUnitType";

    /** 超级管理员角色（带 Spring Security 前缀） */
    private static final String ROLE_ADMIN = "ROLE_ADMIN";

    /** 超级管理员通配权限 */
    private static final String ALL_PERMISSION = "*:*:*";

    private BusinessUnitContext() {
    }

    /**
     * 获取当前用户的业务单元标识（如 {@code COMMERCIAL} / {@code HOUSEHOLD}）。
     *
     * @return 业务单元字符串；无登录态或未携带则返回 null
     */
    public static String getCurrentBusinessUnitType() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object details = auth.getDetails();
        if (details instanceof Map<?, ?> map) {
            Object v = map.get(KEY_BUSINESS_UNIT_TYPE);
            return v == null ? null : v.toString();
        }
        return null;
    }

    /**
     * 判断当前用户是否为超级管理员（ADMIN 角色 或 拥有 *:*:* 通配权限）。
     * 超级管理员不受业务单元数据隔离约束。
     */
    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        Collection<? extends GrantedAuthority> authorities = auth.getAuthorities();
        if (authorities == null) {
            return false;
        }
        for (GrantedAuthority ga : authorities) {
            String a = ga.getAuthority();
            if (ROLE_ADMIN.equalsIgnoreCase(a) || ALL_PERMISSION.equals(a)) {
                return true;
            }
        }
        return false;
    }
}
