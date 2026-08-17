package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.DraftAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.QuoteLinkAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ReviewAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductTaskStatus;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-03 唯一主操作与责任人规则")
class CollaborationNextActionAndAuthorizationTest {

  private final CollaborationNextActionCalculator calculator =
      new CollaborationNextActionCalculator();
  private final CollaborationAuthorization authorization = new CollaborationAuthorization();
  private final CollaborationResponsibilityRules responsibilityRules =
      new CollaborationResponsibilityRules();
  private final CollaborationPrincipal technician = principal(601L, CollaborationRole.TECHNICIAN);
  private final CollaborationPrincipal finance = principal(701L, CollaborationRole.FINANCE_REVIEWER);
  private final CollaborationPrincipal costing = principal(801L, CollaborationRole.COSTING_OPERATOR);
  private final CollaborationPrincipal admin = principal(901L, CollaborationRole.ADMINISTRATOR);
  private final CollaborationPrincipal outsider = principal(999L, CollaborationRole.TECHNICIAN);

  @Test
  @DisplayName("产品在任何时刻最多只有一个页面主操作")
  void calculatesExactlyOnePrimaryAction() {
    QuoteCollaborationProductTask task = task(ProductTaskStatus.WAIT_TECH, 601L);
    task.setNeedBom(1);
    task.setNeedPackage(1);
    task.setNeedPrice(1);
    assertThat(calculator.calculate(task, technician))
        .isEqualTo(CollaborationNextAction.SUPPLEMENT_BOM);

    task.setNeedBom(0);
    task.setNeedPackage(0);
    task.setNeedPrice(0);
    assertThat(calculator.calculate(task, technician))
        .isEqualTo(CollaborationNextAction.NONE);

    task.setNeedBom(1);

    task.setTaskStatus(ProductTaskStatus.BOM_IN_PROGRESS.code());
    task.setSupplementVersionId(88L);
    assertThat(calculator.calculate(task, technician))
        .isEqualTo(CollaborationNextAction.VERIFY_ELECTRONIC_BOM);

    task.setTaskStatus(ProductTaskStatus.PRICE_IN_PROGRESS.code());
    task.setOpenGapCount(2);
    assertThat(calculator.calculate(task, technician))
        .isEqualTo(CollaborationNextAction.SUPPLEMENT_PRICE);
    task.setOpenGapCount(0);
    assertThat(calculator.calculate(task, technician))
        .isEqualTo(CollaborationNextAction.SUBMIT_FINANCE_REVIEW);

    task.setTaskStatus(ProductTaskStatus.WAIT_FINANCE.code());
    task.setCurrentAssigneeUserId(701L);
    assertThat(calculator.calculate(task, finance))
        .isEqualTo(CollaborationNextAction.REVIEW_TECH_SUBMISSION);
    assertThat(calculator.calculate(task, technician)).isEqualTo(CollaborationNextAction.WAIT_FINANCE);

    task.setTaskStatus(ProductTaskStatus.READY_FOR_COSTING.code());
    task.setCurrentAssigneeUserId(null);
    assertThat(calculator.calculate(task, costing))
        .isEqualTo(CollaborationNextAction.START_COSTING);
    assertThat(calculator.calculate(task, technician)).isEqualTo(CollaborationNextAction.NONE);
  }

  @Test
  @DisplayName("技术、财务、管理员和非任务人员权限矩阵严格按角色和当前责任人")
  void enforcesActorMatrix() {
    QuoteCollaborationProductTask techTask = task(ProductTaskStatus.BOM_IN_PROGRESS, 601L);
    authorization.requireProductAction(techTask, ProductAction.SUBMIT_TECH, technician);
    assertAssigneeMismatch(() -> authorization.requireProductAction(
        techTask, ProductAction.SUBMIT_TECH, outsider));
    assertAssigneeMismatch(() -> authorization.requireProductAction(
        techTask, ProductAction.SUBMIT_TECH, admin));

    QuoteCollaborationProductTask financeTask = task(ProductTaskStatus.WAIT_FINANCE, 701L);
    authorization.requireProductAction(
        financeTask, ProductAction.APPROVE_FOR_PUBLISHING, finance);
    assertAssigneeMismatch(() -> authorization.requireProductAction(
        financeTask, ProductAction.APPROVE_FOR_PUBLISHING, admin));
    assertAssigneeMismatch(() -> authorization.requireProductAction(
        financeTask, ProductAction.APPROVE_FOR_PUBLISHING, technician));

    QuoteCollaborationProductTask ready = task(ProductTaskStatus.READY_FOR_COSTING, null);
    authorization.requireProductAction(ready, ProductAction.START_COSTING, costing);
    assertAssigneeMismatch(() -> authorization.requireProductAction(
        ready, ProductAction.START_COSTING, finance));

    authorization.requireProductAction(techTask, ProductAction.CANCEL, admin);
  }

  @Test
  @DisplayName("状态和责任人矛盾时领域层立即拒绝")
  void rejectsContradictoryStateAndAssignee() {
    QuoteCollaborationProductTask task = task(ProductTaskStatus.WAIT_FINANCE, 601L);
    com.sanhua.marketingcost.entity.QuoteCollaborationTask master =
        new com.sanhua.marketingcost.entity.QuoteCollaborationTask();
    master.setFinanceReviewerUserId(701L);

    assertThatThrownBy(() -> responsibilityRules.requireConsistent(task, master))
        .isInstanceOfSatisfying(CollaborationDomainException.class,
            error -> assertThat(error.code()).isEqualTo(
                CollaborationDomainErrorCode.STATE_TRANSITION_INVALID));
  }

  @Test
  @DisplayName("审核、草稿和报价关联同样按指定责任人及系统角色鉴权")
  void enforcesChildAggregateActorMatrix() {
    QuoteCollaborationReview review = new QuoteCollaborationReview();
    review.setReviewerUserId(701L);
    authorization.requireReviewAction(review, ReviewAction.SUBMIT_APPROVED, finance);
    assertAssigneeMismatch(() -> authorization.requireReviewAction(
        review, ReviewAction.SUBMIT_APPROVED, admin));
    authorization.requireReviewAction(review, ReviewAction.START_PUBLISHING,
        principal(0L, CollaborationRole.SYSTEM));

    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setProductTaskId(88L);
    QuoteCollaborationProductTask product = task(ProductTaskStatus.PRICE_IN_PROGRESS, 601L);
    product.setId(88L);
    authorization.requireDraftAction(draft, product, DraftAction.VALIDATE, technician);
    assertAssigneeMismatch(() -> authorization.requireDraftAction(
        draft, product, DraftAction.VALIDATE, admin));
    product.setCurrentAssigneeUserId(701L);
    authorization.requireDraftAction(draft, product, DraftAction.APPROVE, finance);
    assertAssigneeMismatch(() -> authorization.requireDraftAction(
        draft, product, DraftAction.APPROVE, technician));

    authorization.requireQuoteLinkAction(
        QuoteLinkAction.START_RECHECK, principal(0L, CollaborationRole.SYSTEM));
    assertAssigneeMismatch(() -> authorization.requireQuoteLinkAction(
        QuoteLinkAction.START_RECHECK, finance));
    authorization.requireQuoteLinkAction(QuoteLinkAction.CANCEL, admin);
  }

  private static QuoteCollaborationProductTask task(
      ProductTaskStatus status, Long assigneeUserId) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setTaskStatus(status.code());
    task.setOriginalTechnicianUserId(601L);
    task.setOriginalTechnicianName("王工");
    task.setCurrentAssigneeUserId(assigneeUserId);
    task.setCurrentAssigneeName(assigneeUserId == null ? null : "当前责任人");
    task.setNeedBom(0);
    task.setNeedPackage(0);
    task.setNeedPrice(0);
    task.setOpenGapCount(0);
    return task;
  }

  private static CollaborationPrincipal principal(Long id, CollaborationRole role) {
    return new CollaborationPrincipal(id, "用户" + id, Set.of(role));
  }

  private static void assertAssigneeMismatch(Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(
        CollaborationDomainException.class,
        error -> assertThat(error.code()).isEqualTo(
            CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH));
  }
}
