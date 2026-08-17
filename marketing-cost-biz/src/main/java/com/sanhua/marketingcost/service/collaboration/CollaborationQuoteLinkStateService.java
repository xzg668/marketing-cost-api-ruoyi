package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.QuoteLinkAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.QuoteLinkStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 报价关联状态的唯一事务入口。 */
@Service
public class CollaborationQuoteLinkStateService {

  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationAuthorization authorization;

  public CollaborationQuoteLinkStateService(
      QuoteCollaborationTaskRepository repository,
      CollaborationAuthorization authorization) {
    this.repository = repository;
    this.authorization = authorization;
  }

  @Transactional
  public QuoteCollaborationQuoteLink transition(
      Long linkId,
      CollaborationScope scope,
      QuoteLinkAction action,
      CollaborationPrincipal principal) {
    QuoteCollaborationQuoteLink link = repository.findQuoteLinkById(linkId, scope)
        .orElseThrow(() -> new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND,
            "报价关联不存在或不在当前业务范围"));
    QuoteLinkStatus source = parse(link.getLinkStatus());
    QuoteLinkStatus target = CollaborationStateMachines.transitionQuoteLink(source, action);
    authorization.requireQuoteLinkAction(action, principal);
    try {
      return repository.transitionQuoteLinkStatus(
          link.getId(), source.code(), target.code(), scope, principal.actor());
    } catch (CollaborationOptimisticLockException exception) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_VERSION_CONFLICT,
          "报价关联状态已变化，请刷新页面后重试");
    }
  }

  private static QuoteLinkStatus parse(String status) {
    try {
      return QuoteLinkStatus.valueOf(status == null ? "" : status);
    } catch (IllegalArgumentException exception) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "未知报价关联状态：" + status);
    }
  }
}
