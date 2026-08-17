package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.QuoteLinkStatus;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.QuoteLinkType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原任务来源准备完成后，让每个关联报价以自己的上下文进入独立复验。 */
@Service
public class CollaborationLinkedQuoteRecheckService {

  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationAuthorization authorization;

  public CollaborationLinkedQuoteRecheckService(
      QuoteCollaborationTaskRepository repository,
      CollaborationAuthorization authorization) {
    this.repository = repository;
    this.authorization = authorization;
  }

  @Transactional
  public List<QuoteCollaborationQuoteLink> startLinkedQuoteRechecks(
      Long productTaskId,
      CollaborationScope scope,
      CollaborationPrincipal principal) {
    authorization.requireQuoteLinkAction(
        CollaborationActions.QuoteLinkAction.START_RECHECK, principal);
    repository.findProductTaskById(productTaskId, scope).orElseThrow(() ->
        new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND,
            "产品任务不存在或不在当前业务范围"));

    List<QuoteCollaborationQuoteLink> updated = new ArrayList<>();
    for (QuoteCollaborationQuoteLink link : repository.findLinksByProductTask(
        productTaskId, scope)) {
      if (!Integer.valueOf(1).equals(link.getActiveFlag())
          || !QuoteLinkType.ACTIVE_TASK_LINK.code().equals(link.getLinkType())
          || !QuoteLinkStatus.WAIT_SOURCE.code().equals(link.getLinkStatus())) {
        continue;
      }
      updated.add(repository.transitionQuoteLinkStatus(
          link.getId(), QuoteLinkStatus.WAIT_SOURCE.code(),
          QuoteLinkStatus.RECHECKING.code(), scope, principal.actor()));
    }
    return List.copyOf(updated);
  }

  /** 产品任务取消时同步关闭所有仍活动的报价关联，避免旧关联阻塞后续重新发起。 */
  @Transactional
  public List<QuoteCollaborationQuoteLink> cancelActiveQuoteLinks(
      Long productTaskId,
      CollaborationScope scope,
      CollaborationPrincipal principal) {
    authorization.requireQuoteLinkAction(
        CollaborationActions.QuoteLinkAction.CANCEL, principal);
    repository.findProductTaskById(productTaskId, scope).orElseThrow(() ->
        new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND,
            "产品任务不存在或不在当前业务范围"));

    List<QuoteCollaborationQuoteLink> updated = new ArrayList<>();
    for (QuoteCollaborationQuoteLink link : repository.findLinksByProductTask(
        productTaskId, scope)) {
      if (!Integer.valueOf(1).equals(link.getActiveFlag())
          || QuoteLinkStatus.CANCELLED.code().equals(link.getLinkStatus())) {
        continue;
      }
      updated.add(repository.transitionQuoteLinkStatus(
          link.getId(), link.getLinkStatus(), QuoteLinkStatus.CANCELLED.code(),
          scope, principal.actor()));
    }
    return List.copyOf(updated);
  }
}
