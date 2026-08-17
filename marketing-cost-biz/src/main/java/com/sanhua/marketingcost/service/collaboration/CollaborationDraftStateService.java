package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.DraftAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.DraftStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 价格草稿状态的唯一事务入口。 */
@Service
public class CollaborationDraftStateService {

  private final QuotePriceDraftRepository draftRepository;
  private final QuoteCollaborationTaskRepository taskRepository;
  private final CollaborationAuthorization authorization;

  public CollaborationDraftStateService(
      QuotePriceDraftRepository draftRepository,
      QuoteCollaborationTaskRepository taskRepository,
      CollaborationAuthorization authorization) {
    this.draftRepository = draftRepository;
    this.taskRepository = taskRepository;
    this.authorization = authorization;
  }

  @Transactional
  public QuotePriceDraft transition(
      Long draftId,
      Integer expectedVersion,
      CollaborationScope scope,
      DraftAction action,
      CollaborationPrincipal principal) {
    QuotePriceDraft draft = draftRepository.findById(draftId, scope)
        .orElseThrow(() -> notFound("价格草稿不存在或不在当前业务范围"));
    requireVersion(draft.getDraftVersion(), expectedVersion);
    DraftStatus source = parse(draft.getDraftStatus());
    DraftStatus target = CollaborationStateMachines.transitionDraft(source, action);
    QuoteCollaborationProductTask productTask = taskRepository.findProductTaskById(
        draft.getProductTaskId(), scope)
        .orElseThrow(() -> notFound("价格草稿所属产品任务不存在"));
    authorization.requireDraftAction(draft, productTask, action, principal);
    try {
      return draftRepository.transitionStatus(
          draft.getId(), expectedVersion, source.code(), target.code(), scope,
          principal.actor());
    } catch (CollaborationOptimisticLockException exception) {
      throw versionConflict();
    }
  }

  private static DraftStatus parse(String status) {
    try {
      return DraftStatus.valueOf(status == null ? "" : status);
    } catch (IllegalArgumentException exception) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "未知价格草稿状态：" + status);
    }
  }

  private static void requireVersion(Integer current, Integer expected) {
    if (expected == null || expected <= 0 || !expected.equals(current)) {
      throw versionConflict();
    }
  }

  private static CollaborationDomainException notFound(String message) {
    return new CollaborationDomainException(CollaborationDomainErrorCode.TASK_NOT_FOUND, message);
  }

  private static CollaborationDomainException versionConflict() {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_VERSION_CONFLICT,
        "价格草稿版本已变化，请刷新页面后重试");
  }
}
