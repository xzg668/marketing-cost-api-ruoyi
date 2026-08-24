package com.sanhua.marketingcost.service.collaboration;

/** 发起补录命令；产品和组织由服务端重扫，核算流水线可传入本次已校验的核算月份。 */
public record QuoteCollaborationStartCommand(
    Long oaFormItemId,
    Long technicianUserId,
    String technicianName,
    Long financeReviewerUserId,
    String financeReviewerName,
    String accountingMonth,
    CollaborationActor actor) {

  public QuoteCollaborationStartCommand(
      Long oaFormItemId,
      Long technicianUserId,
      String technicianName,
      Long financeReviewerUserId,
      String financeReviewerName,
      CollaborationActor actor) {
    this(oaFormItemId, technicianUserId, technicianName, financeReviewerUserId,
        financeReviewerName, null, actor);
  }
}
