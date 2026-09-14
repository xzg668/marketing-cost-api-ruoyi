package com.sanhua.marketingcost.service.technicaldata;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public record TechnicalDataActor(
    Long userId,
    String name,
    Set<String> authorities,
    Long scopedTaskId,
    String accessPurpose) {
  public TechnicalDataActor(Long userId, String name, Set<String> authorities) {
    this(userId, name, authorities, null, null);
  }

  public TechnicalDataActor {
    authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
  }

  public boolean has(String authority) {
    if (authority == null) return false;
    String target = authority.toLowerCase(Locale.ROOT);
    return normalizedAuthorities().contains(target) || normalizedAuthorities().contains("*:*:*");
  }

  public boolean hasDirect(String authority) {
    return authority != null
        && normalizedAuthorities().contains(authority.toLowerCase(Locale.ROOT));
  }

  public boolean admin() {
    Set<String> values = normalizedAuthorities();
    return values.contains("*:*:*")
        || values.contains("technical:data:admin:operate")
        || values.contains("role_admin");
  }

  public boolean reviewer() {
    return admin() || has("technical:data:review:list");
  }

  public boolean technician() {
    return admin() || has("technical:data:task:list");
  }

  public boolean canReadTasks() {
    // 具备补录权限的技术员必须能够读取并校验本人任务；权限组合不应要求额外隐式依赖。
    return admin() || reviewer() || technician() || canEdit();
  }

  public boolean canAccessTask(Long taskId) {
    return scopedTaskId == null || scopedTaskId.equals(taskId);
  }

  public boolean shortSession() {
    return scopedTaskId != null;
  }

  public boolean canPublish() {
    return admin()
        || has("technical:data:task:edit")
        || has("ingest:quote:cost-run:execute");
  }

  public boolean canEdit() {
    return admin() || has("technical:data:task:edit");
  }

  private Set<String> normalizedAuthorities() {
    return authorities.stream()
        .filter(value -> value != null)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }
}
