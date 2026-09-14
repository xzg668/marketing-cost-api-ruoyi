package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 兼容当前 JWT、Yudao 登录态和 OA 统一登录态的操作人解析。 */
@Component
public class SecurityElectronicDrawingActorProvider implements ElectronicDrawingActorProvider {
  private final SysUserService userService;

  public SecurityElectronicDrawingActorProvider(SysUserService userService) {
    this.userService = userService;
  }

  @Override
  public ElectronicDrawingActor current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) {
      throw new IllegalStateException("当前登录用户无效，不能确认电子图库物料");
    }
    String username = username(authentication.getPrincipal());
    SysUser user = StringUtils.hasText(username)
        ? userService.findByUsername(username.trim()) : null;
    Long detailId = detailUserId(authentication.getDetails());
    if (user == null && detailId != null) user = userService.getById(detailId);
    if (user == null || user.getUserId() == null || user.getUserId() <= 0) {
      throw new IllegalStateException("当前登录用户缺少有效用户ID，不能确认电子图库物料");
    }
    String name = StringUtils.hasText(user.getNickName()) ? user.getNickName() : user.getUserName();
    return new ElectronicDrawingActor(user.getUserId(), name);
  }

  private static String username(Object principal) {
    if (principal instanceof UserDetails details) return details.getUsername();
    return principal == null ? null : principal.toString();
  }

  private static Long detailUserId(Object details) {
    if (!(details instanceof Map<?, ?> values)) return null;
    Object value = values.get("userId");
    if (value instanceof Number number) return number.longValue();
    return value != null && value.toString().matches("\\d+")
        ? Long.valueOf(value.toString()) : null;
  }
}
