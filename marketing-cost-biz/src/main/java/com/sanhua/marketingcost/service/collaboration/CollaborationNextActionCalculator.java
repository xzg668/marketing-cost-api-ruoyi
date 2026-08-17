package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductTaskStatus;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 按任务真实状态和当前登录人计算一个且仅一个页面主操作。 */
@Component
public class CollaborationNextActionCalculator {

  public CollaborationNextAction calculate(
      QuoteCollaborationProductTask task, CollaborationPrincipal principal) {
    ProductTaskStatus status = parseStatus(task == null ? null : task.getTaskStatus());
    boolean assignee = principal != null
        && Objects.equals(task.getCurrentAssigneeUserId(), principal.userId());
    return switch (status) {
      case WAIT_TECH -> assignee && principal.has(CollaborationRole.TECHNICIAN)
          ? initialTechnicalAction(task) : CollaborationNextAction.NONE;
      case BOM_IN_PROGRESS -> assignee && principal.has(CollaborationRole.TECHNICIAN)
          ? (passed(task)
              ? CollaborationNextAction.SUBMIT_FINANCE_REVIEW
              : task.getSupplementVersionId() == null
              ? CollaborationNextAction.SUPPLEMENT_BOM
              : CollaborationNextAction.VERIFY_ELECTRONIC_BOM)
          : CollaborationNextAction.NONE;
      case PACKAGE_IN_PROGRESS -> assignee && principal.has(CollaborationRole.TECHNICIAN)
          ? (passed(task)
              ? CollaborationNextAction.SUBMIT_FINANCE_REVIEW
              : CollaborationNextAction.SUPPLEMENT_PACKAGE)
          : CollaborationNextAction.NONE;
      case PRICE_IN_PROGRESS -> assignee && principal.has(CollaborationRole.TECHNICIAN)
          ? (passed(task)
              ? CollaborationNextAction.SUBMIT_FINANCE_REVIEW
              : positive(task.getOpenGapCount())
              ? CollaborationNextAction.SUPPLEMENT_PRICE
              : CollaborationNextAction.SUBMIT_FINANCE_REVIEW)
          : CollaborationNextAction.NONE;
      case TECH_VALIDATION_FAILED -> assignee && principal.has(CollaborationRole.TECHNICIAN)
          ? CollaborationNextAction.FIX_VALIDATION_ERRORS : CollaborationNextAction.NONE;
      case TECH_SUBMITTED -> CollaborationNextAction.WAIT_FINANCE;
      case WAIT_FINANCE -> assignee && principal.has(CollaborationRole.FINANCE_REVIEWER)
          ? CollaborationNextAction.REVIEW_TECH_SUBMISSION
          : CollaborationNextAction.WAIT_FINANCE;
      case RETURNED_TO_TECH -> assignee && principal.has(CollaborationRole.TECHNICIAN)
          ? (passed(task)
              ? CollaborationNextAction.SUBMIT_FINANCE_REVIEW
              : CollaborationNextAction.REVISE_RETURNED_ITEMS)
          : CollaborationNextAction.NONE;
      case PUBLISH_OR_REPRICE_FAILED -> principal != null
          && principal.has(CollaborationRole.SYSTEM)
          ? CollaborationNextAction.RETRY_PUBLISH_OR_REPRICE : CollaborationNextAction.NONE;
      case READY_FOR_COSTING -> principal != null
          && principal.has(CollaborationRole.COSTING_OPERATOR)
          ? CollaborationNextAction.START_COSTING : CollaborationNextAction.NONE;
      case COSTING -> assignee && principal.has(CollaborationRole.COSTING_OPERATOR)
          ? CollaborationNextAction.CONTINUE_COSTING : CollaborationNextAction.NONE;
      case APPROVED_PUBLISHING, COMPLETED, CANCELLED -> CollaborationNextAction.NONE;
    };
  }

  private static CollaborationNextAction initialTechnicalAction(
      QuoteCollaborationProductTask task) {
    if (enabled(task.getNeedBom())) {
      return CollaborationNextAction.SUPPLEMENT_BOM;
    }
    if (enabled(task.getNeedPackage())) {
      return CollaborationNextAction.SUPPLEMENT_PACKAGE;
    }
    if (enabled(task.getNeedPrice()) || positive(task.getOpenGapCount())) {
      return CollaborationNextAction.SUPPLEMENT_PRICE;
    }
    return CollaborationNextAction.NONE;
  }

  private static ProductTaskStatus parseStatus(String value) {
    try {
      return ProductTaskStatus.valueOf(value == null ? "" : value);
    } catch (IllegalArgumentException exception) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "未知产品任务状态：" + value);
    }
  }

  private static boolean enabled(Integer value) {
    return value != null && value == 1;
  }

  private static boolean positive(Integer value) {
    return value != null && value > 0;
  }

  private static boolean passed(QuoteCollaborationProductTask task) {
    return CollaborationCodes.ValidationStatus.PASSED.code()
        .equals(task.getLastValidationStatus());
  }
}
