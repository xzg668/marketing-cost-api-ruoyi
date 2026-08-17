package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-06 关联报价独立复验")
class CollaborationLinkedQuoteRecheckServiceTest {

  @Test
  @DisplayName("只将活动关联报价转为RECHECKING并保留各自报价上下文")
  void startsEachLinkedQuoteIndependently() {
    QuoteCollaborationTaskRepository repository = mock(QuoteCollaborationTaskRepository.class);
    CollaborationAuthorization authorization = new CollaborationAuthorization();
    CollaborationLinkedQuoteRecheckService service =
        new CollaborationLinkedQuoteRecheckService(repository, authorization);
    CollaborationScope scope = new CollaborationScope("COMMERCIAL", "210");
    CollaborationPrincipal system = new CollaborationPrincipal(
        0L, "系统", Set.of(CollaborationRole.SYSTEM));
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(20L);
    QuoteCollaborationQuoteLink owner = link(30L, "OWNER", "WAIT_SOURCE", 275L, "2026-08");
    QuoteCollaborationQuoteLink first = link(
        31L, "ACTIVE_TASK_LINK", "WAIT_SOURCE", 276L, "2026-08");
    QuoteCollaborationQuoteLink second = link(
        32L, "ACTIVE_TASK_LINK", "WAIT_SOURCE", 277L, "2026-08");
    when(repository.findProductTaskById(20L, scope)).thenReturn(Optional.of(task));
    when(repository.findLinksByProductTask(20L, scope))
        .thenReturn(List.of(owner, first, second));
    when(repository.transitionQuoteLinkStatus(
        31L, "WAIT_SOURCE", "RECHECKING", scope, system.actor()))
        .thenReturn(rechecking(first));
    when(repository.transitionQuoteLinkStatus(
        32L, "WAIT_SOURCE", "RECHECKING", scope, system.actor()))
        .thenReturn(rechecking(second));

    List<QuoteCollaborationQuoteLink> result =
        service.startLinkedQuoteRechecks(20L, scope, system);

    assertThat(result).extracting(QuoteCollaborationQuoteLink::getOaFormItemId)
        .containsExactly(276L, 277L);
    assertThat(result).allSatisfy(link -> {
      assertThat(link.getLinkStatus()).isEqualTo("RECHECKING");
      assertThat(link.getAccountingMonth()).isEqualTo("2026-08");
      assertThat(link.getApplicableOrgCode()).isEqualTo("210");
    });
    verify(repository).transitionQuoteLinkStatus(
        31L, "WAIT_SOURCE", "RECHECKING", scope, system.actor());
    verify(repository).transitionQuoteLinkStatus(
        32L, "WAIT_SOURCE", "RECHECKING", scope, system.actor());
  }

  @Test
  @DisplayName("取消产品任务时关闭OWNER与ACTIVE_TASK_LINK并释放报价关联键")
  void cancelsEveryActiveQuoteLink() {
    QuoteCollaborationTaskRepository repository = mock(QuoteCollaborationTaskRepository.class);
    CollaborationAuthorization authorization = new CollaborationAuthorization();
    CollaborationLinkedQuoteRecheckService service =
        new CollaborationLinkedQuoteRecheckService(repository, authorization);
    CollaborationScope scope = new CollaborationScope("COMMERCIAL", "210");
    CollaborationPrincipal administrator = new CollaborationPrincipal(
        900L, "管理员", Set.of(CollaborationRole.ADMINISTRATOR));
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(20L);
    QuoteCollaborationQuoteLink owner = link(30L, "OWNER", "WAIT_SOURCE", 275L, "2026-08");
    QuoteCollaborationQuoteLink linked = link(
        31L, "ACTIVE_TASK_LINK", "RECHECKING", 276L, "2026-08");
    when(repository.findProductTaskById(20L, scope)).thenReturn(Optional.of(task));
    when(repository.findLinksByProductTask(20L, scope)).thenReturn(List.of(owner, linked));
    when(repository.transitionQuoteLinkStatus(
        30L, "WAIT_SOURCE", "CANCELLED", scope, administrator.actor()))
        .thenReturn(cancelled(owner));
    when(repository.transitionQuoteLinkStatus(
        31L, "RECHECKING", "CANCELLED", scope, administrator.actor()))
        .thenReturn(cancelled(linked));

    List<QuoteCollaborationQuoteLink> result =
        service.cancelActiveQuoteLinks(20L, scope, administrator);

    assertThat(result).hasSize(2).allSatisfy(link -> {
      assertThat(link.getLinkStatus()).isEqualTo("CANCELLED");
      assertThat(link.getActiveFlag()).isZero();
      assertThat(link.getActiveLinkKey()).isNull();
    });
  }

  private static QuoteCollaborationQuoteLink link(
      Long id, String type, String status, Long itemId, String month) {
    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setId(id);
    link.setProductTaskId(20L);
    link.setLinkType(type);
    link.setLinkStatus(status);
    link.setOaFormItemId(itemId);
    link.setAccountingMonth(month);
    link.setApplicableOrgCode("210");
    link.setActiveFlag(1);
    return link;
  }

  private static QuoteCollaborationQuoteLink rechecking(QuoteCollaborationQuoteLink source) {
    QuoteCollaborationQuoteLink updated = link(
        source.getId(), source.getLinkType(), "RECHECKING",
        source.getOaFormItemId(), source.getAccountingMonth());
    return updated;
  }

  private static QuoteCollaborationQuoteLink cancelled(QuoteCollaborationQuoteLink source) {
    QuoteCollaborationQuoteLink updated = link(
        source.getId(), source.getLinkType(), "CANCELLED",
        source.getOaFormItemId(), source.getAccountingMonth());
    updated.setActiveFlag(0);
    updated.setActiveLinkKey(null);
    return updated;
  }
}
