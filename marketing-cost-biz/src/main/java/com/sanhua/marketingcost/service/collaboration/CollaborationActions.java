package com.sanhua.marketingcost.service.collaboration;

/**
 * 领域动作而不是目标状态。调用方只能表达业务意图，目标状态统一由状态机决定。
 */
public final class CollaborationActions {

  private CollaborationActions() {}

  public enum ProductAction {
    START_BOM,
    START_PACKAGE,
    START_PRICE,
    FAIL_TECH_VALIDATION,
    RETRY_BOM,
    RETRY_PACKAGE,
    RETRY_PRICE,
    CONTINUE_PRICE_AFTER_BOM,
    CONTINUE_PRICE_AFTER_PACKAGE,
    SUBMIT_TECH,
    ROUTE_TO_FINANCE,
    REJECT_TO_TECH,
    APPROVE_FOR_PUBLISHING,
    FAIL_PUBLISH_OR_REPRICE,
    RETURN_BUSINESS_GAP_TO_TECH,
    RETRY_PUBLISH_OR_REPRICE,
    MARK_READY_FOR_COSTING,
    START_COSTING,
    COMPLETE_COSTING,
    CANCEL
  }

  public enum MasterAction {
    ROUTE_TO_FINANCE,
    FINANCE_REJECT,
    FINANCE_APPROVE,
    MARK_PUBLISH_FAILED,
    RETURN_BUSINESS_GAP_TO_TECH,
    RETRY_PUBLISH,
    MARK_READY_FOR_COSTING,
    MARK_COMPLETED,
    CANCEL
  }

  public enum ReviewAction {
    SAVE_PARTIAL,
    SUBMIT_REJECTED,
    SUBMIT_APPROVED,
    START_PUBLISHING,
    MARK_EFFECTIVE,
    MARK_FAILED,
    RETRY_PUBLISHING
  }

  public enum DraftAction {
    VALIDATE,
    MODIFY,
    SUBMIT,
    APPROVE,
    REJECT,
    REOPEN,
    PUBLISH,
    VOID
  }

  public enum QuoteLinkAction {
    START_RECHECK,
    MARK_READY,
    MARK_FAILED,
    RETRY_RECHECK,
    RECHECK_SOURCE_CHANGE,
    CANCEL
  }
}
