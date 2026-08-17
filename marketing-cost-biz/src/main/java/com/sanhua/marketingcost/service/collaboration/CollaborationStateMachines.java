package com.sanhua.marketingcost.service.collaboration;

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
import java.util.EnumMap;
import java.util.Map;

/** 主任务、产品任务、审核、草稿和报价关联的唯一状态转换定义。 */
public final class CollaborationStateMachines {

  private static final Map<MasterStatus, Map<MasterAction, MasterStatus>> MASTER =
      masterTransitions();
  private static final Map<ProductTaskStatus, Map<ProductAction, ProductTaskStatus>> PRODUCT =
      productTransitions();
  private static final Map<ReviewStatus, Map<ReviewAction, ReviewStatus>> REVIEW =
      reviewTransitions();
  private static final Map<DraftStatus, Map<DraftAction, DraftStatus>> DRAFT =
      draftTransitions();
  private static final Map<QuoteLinkStatus, Map<QuoteLinkAction, QuoteLinkStatus>> QUOTE_LINK =
      quoteLinkTransitions();

  private CollaborationStateMachines() {}

  public static MasterStatus transitionMaster(MasterStatus source, MasterAction action) {
    return transition("协作主任务", MASTER, source, action);
  }

  public static ProductTaskStatus transitionProduct(
      ProductTaskStatus source, ProductAction action) {
    return transition("产品协作任务", PRODUCT, source, action);
  }

  public static ReviewStatus transitionReview(ReviewStatus source, ReviewAction action) {
    return transition("财务审核", REVIEW, source, action);
  }

  public static DraftStatus transitionDraft(DraftStatus source, DraftAction action) {
    return transition("价格草稿", DRAFT, source, action);
  }

  public static QuoteLinkStatus transitionQuoteLink(
      QuoteLinkStatus source, QuoteLinkAction action) {
    return transition("报价关联", QUOTE_LINK, source, action);
  }

  private static <S extends Enum<S>, A extends Enum<A>> S transition(
      String aggregateName, Map<S, Map<A, S>> transitions, S source, A action) {
    if (source == null || action == null) {
      throw invalid(aggregateName, source, action);
    }
    S target = transitions.getOrDefault(source, Map.of()).get(action);
    if (target == null) {
      throw invalid(aggregateName, source, action);
    }
    return target;
  }

  private static CollaborationDomainException invalid(
      String aggregateName, Object source, Object action) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
        aggregateName + "当前状态" + source + "不允许动作" + action);
  }

  private static Map<MasterStatus, Map<MasterAction, MasterStatus>> masterTransitions() {
    EnumMap<MasterStatus, Map<MasterAction, MasterStatus>> result =
        new EnumMap<>(MasterStatus.class);
    result.put(MasterStatus.WAIT_TECH, Map.of(
        MasterAction.ROUTE_TO_FINANCE, MasterStatus.WAIT_FINANCE,
        MasterAction.CANCEL, MasterStatus.CANCELLED));
    result.put(MasterStatus.WAIT_FINANCE, Map.of(
        MasterAction.FINANCE_REJECT, MasterStatus.PARTIAL_RETURN,
        MasterAction.FINANCE_APPROVE, MasterStatus.PUBLISHING,
        MasterAction.CANCEL, MasterStatus.CANCELLED));
    result.put(MasterStatus.PARTIAL_RETURN, Map.of(
        MasterAction.ROUTE_TO_FINANCE, MasterStatus.WAIT_FINANCE,
        MasterAction.CANCEL, MasterStatus.CANCELLED));
    result.put(MasterStatus.PUBLISHING, Map.of(
        MasterAction.MARK_PUBLISH_FAILED, MasterStatus.PUBLISH_FAILED,
        MasterAction.MARK_READY_FOR_COSTING, MasterStatus.READY_FOR_COSTING,
        MasterAction.CANCEL, MasterStatus.CANCELLED));
    result.put(MasterStatus.PUBLISH_FAILED, Map.of(
        MasterAction.RETURN_BUSINESS_GAP_TO_TECH, MasterStatus.PARTIAL_RETURN,
        MasterAction.RETRY_PUBLISH, MasterStatus.PUBLISHING,
        MasterAction.CANCEL, MasterStatus.CANCELLED));
    result.put(MasterStatus.READY_FOR_COSTING, Map.of(
        MasterAction.MARK_COMPLETED, MasterStatus.COMPLETED,
        MasterAction.CANCEL, MasterStatus.CANCELLED));
    result.put(MasterStatus.COMPLETED, Map.of());
    result.put(MasterStatus.CANCELLED, Map.of());
    return Map.copyOf(result);
  }

  private static Map<ProductTaskStatus, Map<ProductAction, ProductTaskStatus>>
      productTransitions() {
    EnumMap<ProductTaskStatus, Map<ProductAction, ProductTaskStatus>> result =
        new EnumMap<>(ProductTaskStatus.class);
    result.put(ProductTaskStatus.WAIT_TECH, Map.of(
        ProductAction.START_BOM, ProductTaskStatus.BOM_IN_PROGRESS,
        ProductAction.START_PACKAGE, ProductTaskStatus.PACKAGE_IN_PROGRESS,
        ProductAction.START_PRICE, ProductTaskStatus.PRICE_IN_PROGRESS,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.BOM_IN_PROGRESS, Map.of(
        ProductAction.FAIL_TECH_VALIDATION, ProductTaskStatus.TECH_VALIDATION_FAILED,
        ProductAction.CONTINUE_PRICE_AFTER_BOM, ProductTaskStatus.PRICE_IN_PROGRESS,
        ProductAction.SUBMIT_TECH, ProductTaskStatus.TECH_SUBMITTED,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.PACKAGE_IN_PROGRESS, Map.of(
        ProductAction.FAIL_TECH_VALIDATION, ProductTaskStatus.TECH_VALIDATION_FAILED,
        ProductAction.CONTINUE_PRICE_AFTER_PACKAGE, ProductTaskStatus.PRICE_IN_PROGRESS,
        ProductAction.SUBMIT_TECH, ProductTaskStatus.TECH_SUBMITTED,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.PRICE_IN_PROGRESS, Map.of(
        ProductAction.FAIL_TECH_VALIDATION, ProductTaskStatus.TECH_VALIDATION_FAILED,
        ProductAction.SUBMIT_TECH, ProductTaskStatus.TECH_SUBMITTED,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.TECH_VALIDATION_FAILED, Map.of(
        ProductAction.RETRY_BOM, ProductTaskStatus.BOM_IN_PROGRESS,
        ProductAction.RETRY_PACKAGE, ProductTaskStatus.PACKAGE_IN_PROGRESS,
        ProductAction.RETRY_PRICE, ProductTaskStatus.PRICE_IN_PROGRESS,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.TECH_SUBMITTED, Map.of(
        ProductAction.ROUTE_TO_FINANCE, ProductTaskStatus.WAIT_FINANCE,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.WAIT_FINANCE, Map.of(
        ProductAction.REJECT_TO_TECH, ProductTaskStatus.RETURNED_TO_TECH,
        ProductAction.APPROVE_FOR_PUBLISHING, ProductTaskStatus.APPROVED_PUBLISHING,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.RETURNED_TO_TECH, Map.of(
        ProductAction.FAIL_TECH_VALIDATION, ProductTaskStatus.TECH_VALIDATION_FAILED,
        ProductAction.CONTINUE_PRICE_AFTER_BOM, ProductTaskStatus.PRICE_IN_PROGRESS,
        ProductAction.CONTINUE_PRICE_AFTER_PACKAGE, ProductTaskStatus.PRICE_IN_PROGRESS,
        ProductAction.SUBMIT_TECH, ProductTaskStatus.TECH_SUBMITTED,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.APPROVED_PUBLISHING, Map.of(
        ProductAction.FAIL_PUBLISH_OR_REPRICE,
        ProductTaskStatus.PUBLISH_OR_REPRICE_FAILED,
        ProductAction.MARK_READY_FOR_COSTING, ProductTaskStatus.READY_FOR_COSTING,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.PUBLISH_OR_REPRICE_FAILED, Map.of(
        ProductAction.RETURN_BUSINESS_GAP_TO_TECH, ProductTaskStatus.RETURNED_TO_TECH,
        ProductAction.RETRY_PUBLISH_OR_REPRICE, ProductTaskStatus.APPROVED_PUBLISHING,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.READY_FOR_COSTING, Map.of(
        ProductAction.START_COSTING, ProductTaskStatus.COSTING,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.COSTING, Map.of(
        ProductAction.COMPLETE_COSTING, ProductTaskStatus.COMPLETED,
        ProductAction.CANCEL, ProductTaskStatus.CANCELLED));
    result.put(ProductTaskStatus.COMPLETED, Map.of());
    result.put(ProductTaskStatus.CANCELLED, Map.of());
    return Map.copyOf(result);
  }

  private static Map<ReviewStatus, Map<ReviewAction, ReviewStatus>> reviewTransitions() {
    EnumMap<ReviewStatus, Map<ReviewAction, ReviewStatus>> result =
        new EnumMap<>(ReviewStatus.class);
    result.put(ReviewStatus.PENDING, Map.of(
        ReviewAction.SAVE_PARTIAL, ReviewStatus.PARTIAL,
        ReviewAction.SUBMIT_REJECTED, ReviewStatus.REJECTED,
        ReviewAction.SUBMIT_APPROVED, ReviewStatus.APPROVED));
    result.put(ReviewStatus.PARTIAL, Map.of(
        ReviewAction.SAVE_PARTIAL, ReviewStatus.PARTIAL,
        ReviewAction.SUBMIT_REJECTED, ReviewStatus.REJECTED,
        ReviewAction.SUBMIT_APPROVED, ReviewStatus.APPROVED));
    result.put(ReviewStatus.APPROVED, Map.of(
        ReviewAction.START_PUBLISHING, ReviewStatus.PUBLISHING));
    result.put(ReviewStatus.PUBLISHING, Map.of(
        ReviewAction.MARK_EFFECTIVE, ReviewStatus.EFFECTIVE,
        ReviewAction.MARK_FAILED, ReviewStatus.FAILED));
    result.put(ReviewStatus.FAILED, Map.of(
        ReviewAction.RETRY_PUBLISHING, ReviewStatus.PUBLISHING));
    result.put(ReviewStatus.REJECTED, Map.of());
    result.put(ReviewStatus.EFFECTIVE, Map.of());
    return Map.copyOf(result);
  }

  private static Map<DraftStatus, Map<DraftAction, DraftStatus>> draftTransitions() {
    EnumMap<DraftStatus, Map<DraftAction, DraftStatus>> result =
        new EnumMap<>(DraftStatus.class);
    result.put(DraftStatus.EDITING, Map.of(
        DraftAction.VALIDATE, DraftStatus.VALIDATED,
        DraftAction.VOID, DraftStatus.VOIDED));
    result.put(DraftStatus.VALIDATED, Map.of(
        DraftAction.MODIFY, DraftStatus.EDITING,
        DraftAction.SUBMIT, DraftStatus.SUBMITTED,
        DraftAction.VOID, DraftStatus.VOIDED));
    result.put(DraftStatus.SUBMITTED, Map.of(
        DraftAction.APPROVE, DraftStatus.APPROVED,
        DraftAction.REJECT, DraftStatus.REJECTED,
        DraftAction.VOID, DraftStatus.VOIDED));
    result.put(DraftStatus.REJECTED, Map.of(
        DraftAction.REOPEN, DraftStatus.EDITING,
        DraftAction.VOID, DraftStatus.VOIDED));
    result.put(DraftStatus.APPROVED, Map.of(
        DraftAction.PUBLISH, DraftStatus.PUBLISHED,
        DraftAction.VOID, DraftStatus.VOIDED));
    result.put(DraftStatus.PUBLISHED, Map.of());
    result.put(DraftStatus.VOIDED, Map.of());
    return Map.copyOf(result);
  }

  private static Map<QuoteLinkStatus, Map<QuoteLinkAction, QuoteLinkStatus>>
      quoteLinkTransitions() {
    EnumMap<QuoteLinkStatus, Map<QuoteLinkAction, QuoteLinkStatus>> result =
        new EnumMap<>(QuoteLinkStatus.class);
    result.put(QuoteLinkStatus.WAIT_SOURCE, Map.of(
        QuoteLinkAction.START_RECHECK, QuoteLinkStatus.RECHECKING,
        QuoteLinkAction.CANCEL, QuoteLinkStatus.CANCELLED));
    result.put(QuoteLinkStatus.RECHECKING, Map.of(
        QuoteLinkAction.MARK_READY, QuoteLinkStatus.READY,
        QuoteLinkAction.MARK_FAILED, QuoteLinkStatus.FAILED,
        QuoteLinkAction.CANCEL, QuoteLinkStatus.CANCELLED));
    result.put(QuoteLinkStatus.FAILED, Map.of(
        QuoteLinkAction.RETRY_RECHECK, QuoteLinkStatus.RECHECKING,
        QuoteLinkAction.CANCEL, QuoteLinkStatus.CANCELLED));
    result.put(QuoteLinkStatus.READY, Map.of(
        QuoteLinkAction.RECHECK_SOURCE_CHANGE, QuoteLinkStatus.RECHECKING,
        QuoteLinkAction.CANCEL, QuoteLinkStatus.CANCELLED));
    result.put(QuoteLinkStatus.CANCELLED, Map.of());
    return Map.copyOf(result);
  }
}
