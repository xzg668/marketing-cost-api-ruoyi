package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.security.CollaborationPortalAuthentication;
import com.sanhua.marketingcost.security.CollaborationPortalModule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CollaborationPortalAccessPolicyTest {
  private final CollaborationPortalAccessPolicy policy = new CollaborationPortalAccessPolicy();

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void ordinaryLoginKeepsExistingOwnTaskBehavior() {
    setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    QuoteCollaborationProductTask task = task(1L, 10L);

    assertThat(policy.requireTask(task, CollaborationPortalModule.PRICE)).isSameAs(task);
  }

  @Test
  void portalCanOnlySeeItsMasterTaskAndGrantedModule() {
    setDetails(Map.of(
        CollaborationPortalAuthentication.KEY_RESTRICTED, true,
        CollaborationPortalAuthentication.KEY_COLLABORATION_ID, 10L,
        CollaborationPortalAuthentication.KEY_MODULES, List.of("BOM")));
    QuoteCollaborationProductTask allowed = task(1L, 10L);
    QuoteCollaborationProductTask anotherMaster = task(2L, 20L);

    assertThat(policy.visibleTasks(List.of(allowed, anotherMaster))).containsExactly(allowed);
    assertThat(policy.requireTask(allowed, CollaborationPortalModule.BOM)).isSameAs(allowed);
    assertThatThrownBy(() -> policy.requireTask(allowed, CollaborationPortalModule.PRICE))
        .isInstanceOfSatisfying(CollaborationDomainException.class,
            error -> assertThat(error.code()).isEqualTo(CollaborationDomainErrorCode.TASK_NOT_FOUND));
    assertThatThrownBy(() -> policy.requireTask(anotherMaster))
        .isInstanceOf(CollaborationDomainException.class);
  }

  private static void setDetails(Map<String, Object> details) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("collaborator", null, List.of());
    authentication.setDetails(details);
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  private static QuoteCollaborationProductTask task(Long id, Long masterId) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(id);
    task.setOriginCollaborationId(masterId);
    return task;
  }
}
