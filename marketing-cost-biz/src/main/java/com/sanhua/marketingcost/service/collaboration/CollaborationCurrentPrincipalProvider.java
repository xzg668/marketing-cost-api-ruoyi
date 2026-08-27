package com.sanhua.marketingcost.service.collaboration;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 把当前系统账号映射为协作领域身份。
 *
 * <p>这里只认明确业务角色，不把管理员的 {@code *:*:*} 通配权限提升为技术或财务身份。
 */
@Component
public class CollaborationCurrentPrincipalProvider {
  private final CollaborationCurrentActorProvider actorProvider;

  public CollaborationCurrentPrincipalProvider(CollaborationCurrentActorProvider actorProvider) {
    this.actorProvider = actorProvider;
  }

  public CollaborationPrincipal current() {
    CollaborationActor actor = actorProvider.current();
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    EnumSet<CollaborationRole> roles = EnumSet.noneOf(CollaborationRole.class);
    if (authentication != null && authentication.getAuthorities() != null) {
      for (GrantedAuthority authority : authentication.getAuthorities()) {
        mapRole(authority == null ? null : authority.getAuthority(), roles);
      }
    }
    return new CollaborationPrincipal(actor.userId(), actor.userName(), Set.copyOf(roles));
  }

  public CollaborationPrincipal currentTechnician() {
    CollaborationPrincipal principal = current();
    if (!principal.has(CollaborationRole.TECHNICIAN)) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH,
          "当前账号不是技术协作人员，不能查看或处理技术任务");
    }
    return principal;
  }

  public CollaborationPrincipal currentFinanceReviewer() {
    CollaborationPrincipal principal = current();
    if (!principal.has(CollaborationRole.FINANCE_REVIEWER)
        && !principal.has(CollaborationRole.COSTING_OPERATOR)) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH,
          "当前账号不是报价人员或财务审核人员，不能查看或处理补录审核");
    }
    return principal;
  }

  private static void mapRole(String authority, EnumSet<CollaborationRole> roles) {
    if (authority == null || authority.isBlank()) return;
    String normalized = authority.trim().toUpperCase(Locale.ROOT);
    switch (normalized) {
      case "ROLE_TECHNICAL_COLLABORATOR" -> roles.add(CollaborationRole.TECHNICIAN);
      case "ROLE_FINANCE_REVIEWER" -> roles.add(CollaborationRole.FINANCE_REVIEWER);
      case "ROLE_BU_STAFF", "ROLE_BU_DIRECTOR" -> roles.add(CollaborationRole.COSTING_OPERATOR);
      case "ROLE_ADMIN" -> roles.add(CollaborationRole.ADMINISTRATOR);
      default -> { }
    }
  }
}
