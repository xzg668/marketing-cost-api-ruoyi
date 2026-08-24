package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 兼容Yudao登录态、现有JWT登录态和技术协作登录态的操作人解析。 */
@Component
public class SecurityQuoteEffectiveBomActorProvider
    implements QuoteEffectiveBomActorProvider {

  private final SysUserService sysUserService;

  public SecurityQuoteEffectiveBomActorProvider(SysUserService sysUserService) {
    this.sysUserService = sysUserService;
  }

  @Override
  public Long currentUserId() {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      throw new IllegalStateException("当前登录用户缺少用户ID，不能生成报价物料");
    }
    Long detailUserId = detailUserId(authentication.getDetails());
    if (positive(detailUserId)) {
      return detailUserId;
    }
    String username = username(authentication.getPrincipal());
    SysUser user =
        StringUtils.hasText(username)
            ? sysUserService.findByUsername(username.trim())
            : null;
    if (user == null || !positive(user.getUserId())) {
      throw new IllegalStateException("当前登录用户缺少用户ID，不能生成报价物料");
    }
    return user.getUserId();
  }

  private static Long detailUserId(Object details) {
    if (!(details instanceof Map<?, ?> values)) {
      return null;
    }
    Object value = values.get("userId");
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value != null && value.toString().matches("\\d+")) {
      return Long.valueOf(value.toString());
    }
    return null;
  }

  private static String username(Object principal) {
    if (principal instanceof UserDetails userDetails) {
      return userDetails.getUsername();
    }
    return principal == null ? null : principal.toString();
  }

  private static boolean positive(Long value) {
    return value != null && value > 0;
  }
}
