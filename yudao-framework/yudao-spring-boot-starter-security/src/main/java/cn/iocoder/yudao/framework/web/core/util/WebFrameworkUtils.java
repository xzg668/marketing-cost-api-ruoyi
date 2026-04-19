package cn.iocoder.yudao.framework.web.core.util;

import cn.hutool.core.util.NumberUtil;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Web 相关工具类。
 *
 * 这里提供的是当前项目集成阶段所需的最小实现，
 * 用于解除 security 模块对 yudao-spring-boot-starter-web 的硬依赖。
 */
public final class WebFrameworkUtils {

    private static final String REQUEST_ATTRIBUTE_LOGIN_USER_ID = "login_user_id";
    private static final String REQUEST_ATTRIBUTE_LOGIN_USER_TYPE = "login_user_type";

    public static final String HEADER_TENANT_ID = "tenant-id";

    private WebFrameworkUtils() {
    }

    public static Long getTenantId(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String tenantId = request.getHeader(HEADER_TENANT_ID);
        return NumberUtil.isNumber(tenantId) ? Long.valueOf(tenantId) : null;
    }

    public static void setLoginUserId(ServletRequest request, Long userId) {
        request.setAttribute(REQUEST_ATTRIBUTE_LOGIN_USER_ID, userId);
    }

    public static void setLoginUserType(ServletRequest request, Integer userType) {
        request.setAttribute(REQUEST_ATTRIBUTE_LOGIN_USER_TYPE, userType);
    }

    public static Integer getLoginUserType(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object userType = request.getAttribute(REQUEST_ATTRIBUTE_LOGIN_USER_TYPE);
        return userType instanceof Integer ? (Integer) userType : null;
    }
}
