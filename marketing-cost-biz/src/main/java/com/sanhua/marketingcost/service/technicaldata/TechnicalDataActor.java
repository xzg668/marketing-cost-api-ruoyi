package com.sanhua.marketingcost.service.technicaldata;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Collection;
import java.util.List;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import java.util.stream.Collectors;
import com.sanhua.marketingcost.security.BusinessUnitContext;

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

  public boolean technician() {
    return admin() || has("technical:data:task:list");
  }

  public boolean canReadTasks() {
    // 具备补录权限的技术员必须能够读取并校验本人任务；权限组合不应要求额外隐式依赖。
    return admin() || technician() || canEdit() || has("ingest:quote:cost-run:execute");
  }

  public boolean canViewSupplementOverview() {
    return !shortSession() && (admin() || has("ingest:quote:cost-run:execute"));
  }

  /** 报价员只在当前业务单元内验收和补录；管理员可跨业务单元处理。 */
  public boolean canCoordinateTask(QuoteTechTask task) {
    if (task == null || shortSession()) return false;
    return admin() || has("ingest:quote:cost-run:execute")
        && task.getBusinessUnitType() != null
        && Objects.equals(task.getBusinessUnitType(), BusinessUnitContext.getCurrentBusinessUnitType());
  }

  public boolean canAccessTask(Long taskId) {
    return scopedTaskId == null || scopedTaskId.equals(taskId);
  }

  public boolean canReadTask(QuoteTechTask task) {
    return canReadTask(task, List.of());
  }

  public boolean canReadTask(QuoteTechTask task, Collection<QuoteTechModule> modules) {
    if (task == null || !canAccessTask(task.getId())) return false;
    if (canCoordinateTask(task)) return true;
    if (!Objects.equals(task.getActiveFlag(), 1)) return false;
    if (!technician() && !canEdit()) return false;
    boolean usesTaskAssignee = modules.stream().noneMatch(module -> module.getAssigneeUserId() != null);
    return (usesTaskAssignee && Objects.equals(task.getAssigneeUserId(), userId))
        || modules.stream().anyMatch(module -> Integer.valueOf(1).equals(module.getRequiredFlag())
            && Objects.equals(module.getAssigneeUserId(), userId));
  }

  /** 历史未分工模块沿用默认办理人；管理员仍受任务范围、阶段及分派锁约束。 */
  public boolean canEditModule(QuoteTechTask task, QuoteTechModule module) {
    boolean coordinator = canCoordinateTask(task);
    if (task == null || module == null || Integer.valueOf(0).equals(module.getOaEditAllowed())
        || (!canEdit() && !coordinator) || !canAccessTask(task.getId())
        || !Integer.valueOf(1).equals(task.getActiveFlag())
        || !Integer.valueOf(1).equals(module.getRequiredFlag())
        || "CANCELLED".equals(task.getTaskStatus())
        || !Set.of("PENDING", "EDITING", "READY", "RETURNED").contains(module.getModuleStatus())
        || (task.getOaAssignmentVersion() != null && task.getOaAssignmentVersion() > 0
            && !"PUBLISHED".equals(task.getExternalTaskStatus()))) return false;
    Long owner = module.getAssigneeUserId() == null ? task.getAssigneeUserId() : module.getAssigneeUserId();
    return coordinator || Objects.equals(owner, userId);
  }

  public List<String> assignedModules(QuoteTechTask task, Collection<QuoteTechModule> modules) {
    return modules.stream().filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag())
        && Objects.equals(module.getAssigneeUserId() == null ? task.getAssigneeUserId() : module.getAssigneeUserId(), userId))
        .map(QuoteTechModule::getModuleType).toList();
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
