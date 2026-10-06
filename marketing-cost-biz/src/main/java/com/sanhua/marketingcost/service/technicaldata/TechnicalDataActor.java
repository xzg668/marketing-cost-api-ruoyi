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
    Long scopedFormId) {
  public TechnicalDataActor(Long userId, String name, Set<String> authorities) {
    this(userId, name, authorities, null);
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
    return !oaSession() && (admin() || has("ingest:quote:cost-run:execute"));
  }

  /** 报价员只在当前业务单元内查看和分派；管理员可跨业务单元查看。 */
  public boolean canCoordinateTask(QuoteTechTask task) {
    if (task == null || oaSession()) return false;
    return admin() || has("ingest:quote:cost-run:execute")
        && task.getBusinessUnitType() != null
        && Objects.equals(task.getBusinessUnitType(), BusinessUnitContext.getCurrentBusinessUnitType());
  }

  public boolean canAccessTask(QuoteTechTask task) {
    return task != null && canAccessForm(task.getOaFormId());
  }

  public boolean canAccessForm(Long formId) {
    return scopedFormId == null || scopedFormId.equals(formId);
  }

  public boolean canViewSubmittedTask(QuoteTechTask task) {
    return canCoordinateTask(task) || oaSession() && canAccessTask(task);
  }

  public boolean canReadTask(QuoteTechTask task) {
    return canReadTask(task, List.of());
  }

  public boolean canReadTask(QuoteTechTask task, Collection<QuoteTechModule> modules) {
    if (task == null || !canAccessTask(task)) return false;
    if (canViewSubmittedTask(task)) return true;
    if (!Objects.equals(task.getActiveFlag(), 1)) return false;
    if (!technician() && !canEdit()) return false;
    boolean usesTaskAssignee = modules.stream().noneMatch(module -> module.getAssigneeUserId() != null);
    return (usesTaskAssignee && Objects.equals(task.getAssigneeUserId(), userId))
        || modules.stream().anyMatch(module -> Integer.valueOf(1).equals(module.getRequiredFlag())
            && Objects.equals(module.getAssigneeUserId(), userId));
  }

  /** 只有实际办理人可以填写自己的模块；报价员和管理员只查看提交结果。 */
  public boolean canEditModule(QuoteTechTask task, QuoteTechModule module) {
    if (userId == null || canViewSupplementOverview()) return false;
    if (task == null || module == null || Integer.valueOf(0).equals(module.getOaEditAllowed())
        || !canEdit() || !canAccessTask(task)
        || !Integer.valueOf(1).equals(task.getActiveFlag())
        || !Integer.valueOf(1).equals(module.getRequiredFlag())
        || "CANCELLED".equals(task.getTaskStatus())
        || !Set.of("PENDING", "EDITING", "READY", "RETURNED").contains(module.getModuleStatus())
        || (task.getOaAssignmentVersion() != null && task.getOaAssignmentVersion() > 0
            && !"PUBLISHED".equals(task.getExternalTaskStatus()))) return false;
    Long owner = module.getAssigneeUserId() == null ? task.getAssigneeUserId() : module.getAssigneeUserId();
    return Objects.equals(owner, userId);
  }

  public List<String> assignedModules(QuoteTechTask task, Collection<QuoteTechModule> modules) {
    if (userId == null) return List.of();
    return modules.stream().filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag())
        && (module.getAssigneeUserId() != null || task.getOaAssignmentVersion() == null || task.getOaAssignmentVersion() == 0)
        && Objects.equals(module.getAssigneeUserId() == null ? task.getAssigneeUserId() : module.getAssigneeUserId(), userId))
        .map(QuoteTechModule::getModuleType).toList();
  }

  public boolean oaSession() {
    return scopedFormId != null;
  }

  public boolean canPublish() {
    return canViewSupplementOverview();
  }

  public boolean canEdit() {
    return userId != null && (admin() || has("technical:data:task:edit"));
  }

  private Set<String> normalizedAuthorities() {
    return authorities.stream()
        .filter(value -> value != null)
        .map(value -> value.toLowerCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }
}
