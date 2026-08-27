package com.sanhua.marketingcost.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 外部协作认证详情的唯一读取入口，业务代码不直接解析 Map。 */
public final class CollaborationPortalAuthentication {
  public static final String TOKEN_TYPE = "technical-collaboration";
  public static final String HEADER = "X-Collaboration-Token";
  public static final String KEY_RESTRICTED = "restrictedCollaboration";
  public static final String KEY_COLLABORATION_ID = "collaborationId";
  public static final String KEY_MODULES = "collaborationModules";

  private CollaborationPortalAuthentication() {}

  public static Scope currentScope() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !(authentication.getDetails() instanceof Map<?, ?> details)
        || !Boolean.TRUE.equals(details.get(KEY_RESTRICTED))) {
      return null;
    }
    Long collaborationId = longValue(details.get(KEY_COLLABORATION_ID));
    Set<CollaborationPortalModule> modules = modules(details.get(KEY_MODULES));
    if (collaborationId == null || modules.isEmpty()) {
      throw new IllegalStateException("外部协作认证范围不完整");
    }
    return new Scope(collaborationId, modules);
  }

  private static Long longValue(Object value) {
    if (value instanceof Number number) return number.longValue();
    if (value != null && value.toString().matches("\\d+")) return Long.valueOf(value.toString());
    return null;
  }

  private static Set<CollaborationPortalModule> modules(Object value) {
    if (!(value instanceof Collection<?> values)) return Set.of();
    Set<CollaborationPortalModule> result = new LinkedHashSet<>();
    for (Object item : values) {
      try {
        result.add(CollaborationPortalModule.valueOf(String.valueOf(item)));
      } catch (RuntimeException ignored) {
        // 非法模块不会被放大成权限；全部非法时由 currentScope 拒绝。
      }
    }
    return Set.copyOf(result);
  }

  public record Scope(Long collaborationId, Set<CollaborationPortalModule> modules) {
    public boolean allows(CollaborationPortalModule module) {
      return modules.contains(module);
    }
  }
}
