package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.DraftAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.MasterAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.QuoteLinkAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ReviewAction;
import java.util.EnumSet;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** 服务端责任人校验；管理员身份本身不能代替技术或财务责任人。 */
@Component
public class CollaborationAuthorization {

  private static final EnumSet<ProductAction> TECH_ACTIONS = EnumSet.of(
      ProductAction.START_BOM, ProductAction.START_PACKAGE, ProductAction.START_PRICE,
      ProductAction.FAIL_TECH_VALIDATION, ProductAction.RETRY_BOM,
      ProductAction.RETRY_PACKAGE, ProductAction.RETRY_PRICE,
      ProductAction.CONTINUE_PRICE_AFTER_BOM,
      ProductAction.CONTINUE_PRICE_AFTER_PACKAGE, ProductAction.SUBMIT_TECH);
  private static final EnumSet<ProductAction> FINANCE_ACTIONS = EnumSet.of(
      ProductAction.REJECT_TO_TECH, ProductAction.APPROVE_FOR_PUBLISHING);
  private static final EnumSet<ProductAction> SYSTEM_ACTIONS = EnumSet.of(
      ProductAction.ROUTE_TO_FINANCE, ProductAction.FAIL_PUBLISH_OR_REPRICE,
      ProductAction.RETURN_BUSINESS_GAP_TO_TECH,
      ProductAction.RETRY_PUBLISH_OR_REPRICE, ProductAction.MARK_READY_FOR_COSTING);

  public void requireProductAction(
      QuoteCollaborationProductTask task,
      ProductAction action,
      CollaborationPrincipal principal) {
    requirePrincipal(principal);
    if (action == ProductAction.CANCEL) {
      requireAnyRole(principal, CollaborationRole.ADMINISTRATOR, CollaborationRole.SYSTEM);
      return;
    }
    if (TECH_ACTIONS.contains(action)) {
      requireRoleAndAssignee(task, principal, CollaborationRole.TECHNICIAN);
      return;
    }
    if (FINANCE_ACTIONS.contains(action)) {
      requireRoleAndAssignee(task, principal, CollaborationRole.FINANCE_REVIEWER);
      return;
    }
    if (SYSTEM_ACTIONS.contains(action)) {
      requireAnyRole(principal, CollaborationRole.SYSTEM);
      return;
    }
    if (action == ProductAction.START_COSTING) {
      requireAnyRole(principal, CollaborationRole.COSTING_OPERATOR);
      return;
    }
    if (action == ProductAction.COMPLETE_COSTING) {
      if (principal.has(CollaborationRole.SYSTEM)) {
        return;
      }
      requireRoleAndAssignee(task, principal, CollaborationRole.COSTING_OPERATOR);
      return;
    }
    throw mismatch("当前用户不能执行动作" + action);
  }

  public void requireMasterAction(
      QuoteCollaborationTask task,
      MasterAction action,
      CollaborationPrincipal principal) {
    requirePrincipal(principal);
    if (action == MasterAction.CANCEL) {
      requireAnyRole(principal, CollaborationRole.ADMINISTRATOR, CollaborationRole.SYSTEM);
      return;
    }
    if (action == MasterAction.FINANCE_REJECT || action == MasterAction.FINANCE_APPROVE) {
      if (!principal.has(CollaborationRole.FINANCE_REVIEWER)
          || !Objects.equals(task.getFinanceReviewerUserId(), principal.userId())) {
        throw mismatch("当前用户不是本协作单指定财务审核人");
      }
      return;
    }
    requireAnyRole(principal, CollaborationRole.SYSTEM);
  }

  public void requireReviewAction(
      QuoteCollaborationReview review,
      ReviewAction action,
      CollaborationPrincipal principal) {
    requirePrincipal(principal);
    if (action == ReviewAction.SAVE_PARTIAL
        || action == ReviewAction.SUBMIT_REJECTED
        || action == ReviewAction.SUBMIT_APPROVED) {
      if (!principal.has(CollaborationRole.FINANCE_REVIEWER)
          || !Objects.equals(review.getReviewerUserId(), principal.userId())) {
        throw mismatch("当前用户不是本审核单指定财务审核人");
      }
      return;
    }
    requireAnyRole(principal, CollaborationRole.SYSTEM);
  }

  public void requireDraftAction(
      QuotePriceDraft draft,
      QuoteCollaborationProductTask productTask,
      DraftAction action,
      CollaborationPrincipal principal) {
    requirePrincipal(principal);
    if (action == DraftAction.VOID) {
      requireAnyRole(principal, CollaborationRole.ADMINISTRATOR, CollaborationRole.SYSTEM);
      return;
    }
    if (action == DraftAction.APPROVE || action == DraftAction.REJECT) {
      requireRoleAndAssignee(productTask, principal, CollaborationRole.FINANCE_REVIEWER);
      return;
    }
    if (action == DraftAction.PUBLISH) {
      requireAnyRole(principal, CollaborationRole.SYSTEM);
      return;
    }
    if (draft.getProductTaskId() == null
        || !Objects.equals(draft.getProductTaskId(), productTask.getId())) {
      throw mismatch("价格草稿与产品任务不匹配");
    }
    requireRoleAndAssignee(productTask, principal, CollaborationRole.TECHNICIAN);
  }

  public void requireQuoteLinkAction(
      QuoteLinkAction action, CollaborationPrincipal principal) {
    requirePrincipal(principal);
    if (action == QuoteLinkAction.CANCEL) {
      requireAnyRole(principal, CollaborationRole.ADMINISTRATOR, CollaborationRole.SYSTEM);
      return;
    }
    requireAnyRole(principal, CollaborationRole.SYSTEM);
  }

  private static void requireRoleAndAssignee(
      QuoteCollaborationProductTask task,
      CollaborationPrincipal principal,
      CollaborationRole role) {
    if (!principal.has(role)
        || !Objects.equals(task.getCurrentAssigneeUserId(), principal.userId())) {
      throw mismatch("当前用户不是产品任务当前责任人");
    }
  }

  private static void requireAnyRole(
      CollaborationPrincipal principal, CollaborationRole... roles) {
    for (CollaborationRole role : roles) {
      if (principal.has(role)) {
        return;
      }
    }
    throw mismatch("当前用户角色不允许执行该动作");
  }

  private static void requirePrincipal(CollaborationPrincipal principal) {
    if (principal == null) {
      throw mismatch("当前登录人不能为空");
    }
  }

  private static CollaborationDomainException mismatch(String message) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH, message);
  }
}
