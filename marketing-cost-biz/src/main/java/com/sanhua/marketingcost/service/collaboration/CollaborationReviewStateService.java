package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ReviewAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ReviewStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 财务审核状态的唯一事务入口。 */
@Service
public class CollaborationReviewStateService {

  private final QuoteCollaborationReviewRepository repository;
  private final QuoteCollaborationTaskRepository taskRepository;
  private final CollaborationAuthorization authorization;

  public CollaborationReviewStateService(
      QuoteCollaborationReviewRepository repository,
      QuoteCollaborationTaskRepository taskRepository,
      CollaborationAuthorization authorization) {
    this.repository = repository;
    this.taskRepository = taskRepository;
    this.authorization = authorization;
  }

  @Transactional
  public QuoteCollaborationReview transition(
      Long reviewId,
      Integer expectedSourceTaskVersion,
      String expectedReviewStatus,
      String businessUnitType,
      ReviewAction action,
      CollaborationPrincipal principal) {
    QuoteCollaborationReview review = repository.findReviewById(reviewId, businessUnitType)
        .orElseThrow(() -> new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND,
            "财务审核不存在或不在当前业务范围"));
    if (expectedSourceTaskVersion == null || expectedSourceTaskVersion <= 0
        || !expectedSourceTaskVersion.equals(review.getSourceTaskVersion())) {
      throw versionConflict();
    }
    if (expectedReviewStatus == null
        || !expectedReviewStatus.equals(review.getReviewStatus())) {
      throw versionConflict();
    }
    ReviewStatus source = parse(review.getReviewStatus());
    ReviewStatus target = CollaborationStateMachines.transitionReview(source, action);
    if (action == ReviewAction.SAVE_PARTIAL
        || action == ReviewAction.SUBMIT_REJECTED
        || action == ReviewAction.SUBMIT_APPROVED) {
      Integer currentMasterVersion = taskRepository.findTaskById(
          review.getCollaborationId(), businessUnitType)
          .orElseThrow(() -> new CollaborationDomainException(
              CollaborationDomainErrorCode.TASK_NOT_FOUND,
              "财务审核所属协作主任务不存在"))
          .getTaskVersion();
      if (!expectedSourceTaskVersion.equals(currentMasterVersion)) {
        throw versionConflict();
      }
    }
    authorization.requireReviewAction(review, action, principal);
    try {
      return repository.transitionReviewStatus(
          review.getId(), expectedSourceTaskVersion, source.code(), target.code(),
          businessUnitType, principal.actor());
    } catch (CollaborationOptimisticLockException exception) {
      throw versionConflict();
    }
  }

  private static ReviewStatus parse(String status) {
    try {
      return ReviewStatus.valueOf(status == null ? "" : status);
    } catch (IllegalArgumentException exception) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "未知财务审核状态：" + status);
    }
  }

  private static CollaborationDomainException versionConflict() {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_VERSION_CONFLICT,
        "审核来源版本或状态已变化，请刷新页面后重试");
  }
}
