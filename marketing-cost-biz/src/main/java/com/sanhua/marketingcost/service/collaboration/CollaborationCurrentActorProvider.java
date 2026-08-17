package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CollaborationCurrentActorProvider {
  private final SysUserService userService;

  public CollaborationCurrentActorProvider(SysUserService userService) {
    this.userService = userService;
  }

  public CollaborationActor current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      throw new IllegalStateException("当前登录用户无效，不能发起协作");
    }
    String username = username(authentication.getPrincipal());
    SysUser user = StringUtils.hasText(username) ? userService.findByUsername(username) : null;
    Long detailId = detailUserId(authentication.getDetails());
    if (user == null && detailId != null) {
      user = userService.getById(detailId);
    }
    if (user == null || user.getUserId() == null || user.getUserId() <= 0) {
      throw new IllegalStateException("当前登录用户缺少有效用户ID，不能发起协作");
    }
    String name = StringUtils.hasText(user.getNickName()) ? user.getNickName() : user.getUserName();
    return new CollaborationActor(user.getUserId(), name);
  }

  private static String username(Object principal) {
    if (principal instanceof UserDetails details) return details.getUsername();
    return principal == null ? null : principal.toString();
  }

  private static Long detailUserId(Object details) {
    if (!(details instanceof Map<?, ?> map)) return null;
    Object value = map.get("userId");
    if (value instanceof Number number) return number.longValue();
    return value != null && value.toString().matches("\\d+") ? Long.valueOf(value.toString()) : null;
  }
}
