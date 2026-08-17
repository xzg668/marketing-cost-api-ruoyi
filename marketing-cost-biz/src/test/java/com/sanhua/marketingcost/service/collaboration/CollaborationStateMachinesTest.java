package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.service.collaboration.CollaborationActions.DraftAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.MasterAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.QuoteLinkAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ReviewAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.DraftStatus;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.MasterStatus;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductTaskStatus;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.QuoteLinkStatus;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ReviewStatus;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-03 协作状态机")
class CollaborationStateMachinesTest {

  @Test
  @DisplayName("产品任务覆盖设计中的每一条合法迁移")
  void productTaskAllowsEveryDesignedTransition() {
    Map<ProductTaskStatus, Map<ProductAction, ProductTaskStatus>> expected = Map.ofEntries(
        Map.entry(ProductTaskStatus.WAIT_TECH, Map.of(
            ProductAction.START_BOM, ProductTaskStatus.BOM_IN_PROGRESS,
            ProductAction.START_PACKAGE, ProductTaskStatus.PACKAGE_IN_PROGRESS,
            ProductAction.START_PRICE, ProductTaskStatus.PRICE_IN_PROGRESS,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.BOM_IN_PROGRESS, Map.of(
            ProductAction.FAIL_TECH_VALIDATION, ProductTaskStatus.TECH_VALIDATION_FAILED,
            ProductAction.CONTINUE_PRICE_AFTER_BOM, ProductTaskStatus.PRICE_IN_PROGRESS,
            ProductAction.SUBMIT_TECH, ProductTaskStatus.TECH_SUBMITTED,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.PACKAGE_IN_PROGRESS, Map.of(
            ProductAction.FAIL_TECH_VALIDATION, ProductTaskStatus.TECH_VALIDATION_FAILED,
            ProductAction.CONTINUE_PRICE_AFTER_PACKAGE, ProductTaskStatus.PRICE_IN_PROGRESS,
            ProductAction.SUBMIT_TECH, ProductTaskStatus.TECH_SUBMITTED,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.PRICE_IN_PROGRESS, Map.of(
            ProductAction.FAIL_TECH_VALIDATION, ProductTaskStatus.TECH_VALIDATION_FAILED,
            ProductAction.SUBMIT_TECH, ProductTaskStatus.TECH_SUBMITTED,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.TECH_VALIDATION_FAILED, Map.of(
            ProductAction.RETRY_BOM, ProductTaskStatus.BOM_IN_PROGRESS,
            ProductAction.RETRY_PACKAGE, ProductTaskStatus.PACKAGE_IN_PROGRESS,
            ProductAction.RETRY_PRICE, ProductTaskStatus.PRICE_IN_PROGRESS,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.TECH_SUBMITTED, Map.of(
            ProductAction.ROUTE_TO_FINANCE, ProductTaskStatus.WAIT_FINANCE,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.WAIT_FINANCE, Map.of(
            ProductAction.REJECT_TO_TECH, ProductTaskStatus.RETURNED_TO_TECH,
            ProductAction.APPROVE_FOR_PUBLISHING, ProductTaskStatus.APPROVED_PUBLISHING,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.RETURNED_TO_TECH, Map.of(
            ProductAction.FAIL_TECH_VALIDATION, ProductTaskStatus.TECH_VALIDATION_FAILED,
            ProductAction.CONTINUE_PRICE_AFTER_BOM, ProductTaskStatus.PRICE_IN_PROGRESS,
            ProductAction.CONTINUE_PRICE_AFTER_PACKAGE, ProductTaskStatus.PRICE_IN_PROGRESS,
            ProductAction.SUBMIT_TECH, ProductTaskStatus.TECH_SUBMITTED,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.APPROVED_PUBLISHING, Map.of(
            ProductAction.FAIL_PUBLISH_OR_REPRICE,
            ProductTaskStatus.PUBLISH_OR_REPRICE_FAILED,
            ProductAction.MARK_READY_FOR_COSTING, ProductTaskStatus.READY_FOR_COSTING,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.PUBLISH_OR_REPRICE_FAILED, Map.of(
            ProductAction.RETURN_BUSINESS_GAP_TO_TECH, ProductTaskStatus.RETURNED_TO_TECH,
            ProductAction.RETRY_PUBLISH_OR_REPRICE, ProductTaskStatus.APPROVED_PUBLISHING,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.READY_FOR_COSTING, Map.of(
            ProductAction.START_COSTING, ProductTaskStatus.COSTING,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.COSTING, Map.of(
            ProductAction.COMPLETE_COSTING, ProductTaskStatus.COMPLETED,
            ProductAction.CANCEL, ProductTaskStatus.CANCELLED)),
        Map.entry(ProductTaskStatus.COMPLETED, Map.of()),
        Map.entry(ProductTaskStatus.CANCELLED, Map.of()));

    assertEveryAllowedAndEveryOtherActionRejected(
        expected, ProductAction.values(), CollaborationStateMachines::transitionProduct);
  }

  @Test
  @DisplayName("主任务覆盖聚合状态的全部合法迁移")
  void masterTaskAllowsEveryDesignedTransition() {
    Map<MasterStatus, Map<MasterAction, MasterStatus>> expected = Map.of(
        MasterStatus.WAIT_TECH, Map.of(
            MasterAction.ROUTE_TO_FINANCE, MasterStatus.WAIT_FINANCE,
            MasterAction.CANCEL, MasterStatus.CANCELLED),
        MasterStatus.WAIT_FINANCE, Map.of(
            MasterAction.FINANCE_REJECT, MasterStatus.PARTIAL_RETURN,
            MasterAction.FINANCE_APPROVE, MasterStatus.PUBLISHING,
            MasterAction.CANCEL, MasterStatus.CANCELLED),
        MasterStatus.PARTIAL_RETURN, Map.of(
            MasterAction.ROUTE_TO_FINANCE, MasterStatus.WAIT_FINANCE,
            MasterAction.CANCEL, MasterStatus.CANCELLED),
        MasterStatus.PUBLISHING, Map.of(
            MasterAction.MARK_PUBLISH_FAILED, MasterStatus.PUBLISH_FAILED,
            MasterAction.MARK_READY_FOR_COSTING, MasterStatus.READY_FOR_COSTING,
            MasterAction.CANCEL, MasterStatus.CANCELLED),
        MasterStatus.PUBLISH_FAILED, Map.of(
            MasterAction.RETURN_BUSINESS_GAP_TO_TECH, MasterStatus.PARTIAL_RETURN,
            MasterAction.RETRY_PUBLISH, MasterStatus.PUBLISHING,
            MasterAction.CANCEL, MasterStatus.CANCELLED),
        MasterStatus.READY_FOR_COSTING, Map.of(
            MasterAction.MARK_COMPLETED, MasterStatus.COMPLETED,
            MasterAction.CANCEL, MasterStatus.CANCELLED),
        MasterStatus.COMPLETED, Map.of(),
        MasterStatus.CANCELLED, Map.of());

    assertEveryAllowedAndEveryOtherActionRejected(
        expected, MasterAction.values(), CollaborationStateMachines::transitionMaster);
  }

  @Test
  @DisplayName("审核、价格草稿和报价关联也只能按集中规则迁移")
  void childAggregatesHaveCentralStateMachines() {
    assertThat(CollaborationStateMachines.transitionReview(
        ReviewStatus.PENDING, ReviewAction.SAVE_PARTIAL)).isEqualTo(ReviewStatus.PARTIAL);
    assertThat(CollaborationStateMachines.transitionReview(
        ReviewStatus.PENDING, ReviewAction.SUBMIT_APPROVED)).isEqualTo(ReviewStatus.APPROVED);
    assertThat(CollaborationStateMachines.transitionReview(
        ReviewStatus.PARTIAL, ReviewAction.SUBMIT_REJECTED)).isEqualTo(ReviewStatus.REJECTED);
    assertThat(CollaborationStateMachines.transitionReview(
        ReviewStatus.APPROVED, ReviewAction.START_PUBLISHING)).isEqualTo(ReviewStatus.PUBLISHING);
    assertThat(CollaborationStateMachines.transitionReview(
        ReviewStatus.PUBLISHING, ReviewAction.MARK_EFFECTIVE)).isEqualTo(ReviewStatus.EFFECTIVE);
    assertThat(CollaborationStateMachines.transitionReview(
        ReviewStatus.PUBLISHING, ReviewAction.MARK_FAILED)).isEqualTo(ReviewStatus.FAILED);
    assertThat(CollaborationStateMachines.transitionReview(
        ReviewStatus.FAILED, ReviewAction.RETRY_PUBLISHING)).isEqualTo(ReviewStatus.PUBLISHING);

    assertThat(CollaborationStateMachines.transitionDraft(
        DraftStatus.EDITING, DraftAction.VALIDATE)).isEqualTo(DraftStatus.VALIDATED);
    assertThat(CollaborationStateMachines.transitionDraft(
        DraftStatus.VALIDATED, DraftAction.MODIFY)).isEqualTo(DraftStatus.EDITING);
    assertThat(CollaborationStateMachines.transitionDraft(
        DraftStatus.VALIDATED, DraftAction.SUBMIT)).isEqualTo(DraftStatus.SUBMITTED);
    assertThat(CollaborationStateMachines.transitionDraft(
        DraftStatus.SUBMITTED, DraftAction.APPROVE)).isEqualTo(DraftStatus.APPROVED);
    assertThat(CollaborationStateMachines.transitionDraft(
        DraftStatus.SUBMITTED, DraftAction.REJECT)).isEqualTo(DraftStatus.REJECTED);
    assertThat(CollaborationStateMachines.transitionDraft(
        DraftStatus.REJECTED, DraftAction.REOPEN)).isEqualTo(DraftStatus.EDITING);
    assertThat(CollaborationStateMachines.transitionDraft(
        DraftStatus.APPROVED, DraftAction.PUBLISH)).isEqualTo(DraftStatus.PUBLISHED);
    for (DraftStatus source : new DraftStatus[]{DraftStatus.EDITING, DraftStatus.VALIDATED,
        DraftStatus.SUBMITTED, DraftStatus.REJECTED, DraftStatus.APPROVED}) {
      assertThat(CollaborationStateMachines.transitionDraft(source, DraftAction.VOID))
          .isEqualTo(DraftStatus.VOIDED);
    }

    assertThat(CollaborationStateMachines.transitionQuoteLink(
        QuoteLinkStatus.WAIT_SOURCE, QuoteLinkAction.START_RECHECK))
        .isEqualTo(QuoteLinkStatus.RECHECKING);
    assertThat(CollaborationStateMachines.transitionQuoteLink(
        QuoteLinkStatus.RECHECKING, QuoteLinkAction.MARK_READY))
        .isEqualTo(QuoteLinkStatus.READY);
    assertThat(CollaborationStateMachines.transitionQuoteLink(
        QuoteLinkStatus.RECHECKING, QuoteLinkAction.MARK_FAILED))
        .isEqualTo(QuoteLinkStatus.FAILED);
    assertThat(CollaborationStateMachines.transitionQuoteLink(
        QuoteLinkStatus.FAILED, QuoteLinkAction.RETRY_RECHECK))
        .isEqualTo(QuoteLinkStatus.RECHECKING);
    assertThat(CollaborationStateMachines.transitionQuoteLink(
        QuoteLinkStatus.READY, QuoteLinkAction.RECHECK_SOURCE_CHANGE))
        .isEqualTo(QuoteLinkStatus.RECHECKING);
    for (QuoteLinkStatus source : new QuoteLinkStatus[]{QuoteLinkStatus.WAIT_SOURCE,
        QuoteLinkStatus.RECHECKING, QuoteLinkStatus.READY, QuoteLinkStatus.FAILED}) {
      assertThat(CollaborationStateMachines.transitionQuoteLink(source, QuoteLinkAction.CANCEL))
          .isEqualTo(QuoteLinkStatus.CANCELLED);
    }

    assertThatThrownBy(() -> CollaborationStateMachines.transitionReview(
        ReviewStatus.EFFECTIVE, ReviewAction.SUBMIT_REJECTED)).isInstanceOfSatisfying(
            CollaborationDomainException.class,
            error -> assertThat(error.code()).isEqualTo(
                CollaborationDomainErrorCode.STATE_TRANSITION_INVALID));
    assertThatThrownBy(() -> CollaborationStateMachines.transitionDraft(
        DraftStatus.PUBLISHED, DraftAction.MODIFY)).isInstanceOf(CollaborationDomainException.class);
    assertThatThrownBy(() -> CollaborationStateMachines.transitionQuoteLink(
        QuoteLinkStatus.CANCELLED, QuoteLinkAction.START_RECHECK))
        .isInstanceOf(CollaborationDomainException.class);
  }

  @Test
  @DisplayName("审核、草稿和报价关联的每个状态拒绝所有未列明动作")
  void childAggregatesRejectEveryUndeclaredTransition() {
    Map<ReviewStatus, Map<ReviewAction, ReviewStatus>> review = Map.of(
        ReviewStatus.PENDING, Map.of(
            ReviewAction.SAVE_PARTIAL, ReviewStatus.PARTIAL,
            ReviewAction.SUBMIT_REJECTED, ReviewStatus.REJECTED,
            ReviewAction.SUBMIT_APPROVED, ReviewStatus.APPROVED),
        ReviewStatus.PARTIAL, Map.of(
            ReviewAction.SAVE_PARTIAL, ReviewStatus.PARTIAL,
            ReviewAction.SUBMIT_REJECTED, ReviewStatus.REJECTED,
            ReviewAction.SUBMIT_APPROVED, ReviewStatus.APPROVED),
        ReviewStatus.REJECTED, Map.of(),
        ReviewStatus.APPROVED, Map.of(
            ReviewAction.START_PUBLISHING, ReviewStatus.PUBLISHING),
        ReviewStatus.PUBLISHING, Map.of(
            ReviewAction.MARK_EFFECTIVE, ReviewStatus.EFFECTIVE,
            ReviewAction.MARK_FAILED, ReviewStatus.FAILED),
        ReviewStatus.EFFECTIVE, Map.of(),
        ReviewStatus.FAILED, Map.of(
            ReviewAction.RETRY_PUBLISHING, ReviewStatus.PUBLISHING));
    assertEveryAllowedAndEveryOtherActionRejected(
        review, ReviewAction.values(), CollaborationStateMachines::transitionReview);

    Map<DraftStatus, Map<DraftAction, DraftStatus>> draft = Map.of(
        DraftStatus.EDITING, Map.of(
            DraftAction.VALIDATE, DraftStatus.VALIDATED,
            DraftAction.VOID, DraftStatus.VOIDED),
        DraftStatus.VALIDATED, Map.of(
            DraftAction.MODIFY, DraftStatus.EDITING,
            DraftAction.SUBMIT, DraftStatus.SUBMITTED,
            DraftAction.VOID, DraftStatus.VOIDED),
        DraftStatus.SUBMITTED, Map.of(
            DraftAction.APPROVE, DraftStatus.APPROVED,
            DraftAction.REJECT, DraftStatus.REJECTED,
            DraftAction.VOID, DraftStatus.VOIDED),
        DraftStatus.REJECTED, Map.of(
            DraftAction.REOPEN, DraftStatus.EDITING,
            DraftAction.VOID, DraftStatus.VOIDED),
        DraftStatus.APPROVED, Map.of(
            DraftAction.PUBLISH, DraftStatus.PUBLISHED,
            DraftAction.VOID, DraftStatus.VOIDED),
        DraftStatus.PUBLISHED, Map.of(),
        DraftStatus.VOIDED, Map.of());
    assertEveryAllowedAndEveryOtherActionRejected(
        draft, DraftAction.values(), CollaborationStateMachines::transitionDraft);

    Map<QuoteLinkStatus, Map<QuoteLinkAction, QuoteLinkStatus>> link = Map.of(
        QuoteLinkStatus.WAIT_SOURCE, Map.of(
            QuoteLinkAction.START_RECHECK, QuoteLinkStatus.RECHECKING,
            QuoteLinkAction.CANCEL, QuoteLinkStatus.CANCELLED),
        QuoteLinkStatus.RECHECKING, Map.of(
            QuoteLinkAction.MARK_READY, QuoteLinkStatus.READY,
            QuoteLinkAction.MARK_FAILED, QuoteLinkStatus.FAILED,
            QuoteLinkAction.CANCEL, QuoteLinkStatus.CANCELLED),
        QuoteLinkStatus.READY, Map.of(
            QuoteLinkAction.RECHECK_SOURCE_CHANGE, QuoteLinkStatus.RECHECKING,
            QuoteLinkAction.CANCEL, QuoteLinkStatus.CANCELLED),
        QuoteLinkStatus.FAILED, Map.of(
            QuoteLinkAction.RETRY_RECHECK, QuoteLinkStatus.RECHECKING,
            QuoteLinkAction.CANCEL, QuoteLinkStatus.CANCELLED),
        QuoteLinkStatus.CANCELLED, Map.of());
    assertEveryAllowedAndEveryOtherActionRejected(
        link, QuoteLinkAction.values(), CollaborationStateMachines::transitionQuoteLink);
  }

  private static <S extends Enum<S>, A extends Enum<A>> void
      assertEveryAllowedAndEveryOtherActionRejected(
          Map<S, Map<A, S>> expected,
          A[] actions,
          Transition<S, A> transition) {
    expected.forEach((source, allowed) -> {
      for (A action : actions) {
        if (allowed.containsKey(action)) {
          assertThat(transition.apply(source, action)).as(source + " + " + action)
              .isEqualTo(allowed.get(action));
        } else {
          assertThatThrownBy(() -> transition.apply(source, action))
              .as(source + " must reject " + action)
              .isInstanceOfSatisfying(CollaborationDomainException.class,
                  error -> assertThat(error.code()).isEqualTo(
                      CollaborationDomainErrorCode.STATE_TRANSITION_INVALID));
        }
      }
    });
  }

  @FunctionalInterface
  private interface Transition<S, A> {
    S apply(S source, A action);
  }
}
