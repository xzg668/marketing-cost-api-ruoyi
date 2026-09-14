package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SecurityTechnicalDataActorProvider implements TechnicalDataActorProvider {
  private final SysUserService userService;

  public SecurityTechnicalDataActorProvider(SysUserService userService) {
    this.userService = userService;
  }

  @Override
  public TechnicalDataActor current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null) throw new IllegalStateException("当前登录用户无效");
    SysUser user = resolveUser(authentication);
    if (user == null || user.getUserId() == null || user.getUserId() <= 0) {
      throw new IllegalStateException("当前登录用户缺少有效用户ID");
    }
    String name = StringUtils.hasText(user.getNickName()) ? user.getNickName() : user.getUserName();
    Set<String> authorities = authentication.getAuthorities().stream()
        .map(authority -> authority.getAuthority())
        .collect(Collectors.toUnmodifiableSet());
    return new TechnicalDataActor(
        user.getUserId(), name, authorities,
        detailLong(authentication.getDetails(), "technicalDataTaskId"),
        detailText(authentication.getDetails(), "technicalDataPurpose"));
  }

  private SysUser resolveUser(Authentication authentication) {
    String username = username(authentication.getPrincipal());
    SysUser user = StringUtils.hasText(username)
        ? userService.findByUsername(username.trim()) : null;
    Long detailId = detailUserId(authentication.getDetails());
    return user == null && detailId != null ? userService.getById(detailId) : user;
  }

  private String username(Object principal) {
    if (principal instanceof UserDetails details) return details.getUsername();
    return principal == null ? null : principal.toString();
  }

  private Long detailUserId(Object details) {
    if (!(details instanceof Map<?, ?> values)) return null;
    Object value = values.get("userId");
    if (value instanceof Number number) return number.longValue();
    return value != null && value.toString().matches("\\d+")
        ? Long.valueOf(value.toString()) : null;
  }

  private Long detailLong(Object details, String key) {
    if (!(details instanceof Map<?, ?> values)) return null;
    Object value = values.get(key);
    if (value instanceof Number number) return number.longValue();
    return value != null && value.toString().matches("\\d+")
        ? Long.valueOf(value.toString()) : null;
  }

  private String detailText(Object details, String key) {
    if (!(details instanceof Map<?, ?> values)) return null;
    Object value = values.get(key);
    return value == null || !StringUtils.hasText(value.toString()) ? null : value.toString();
  }
}
