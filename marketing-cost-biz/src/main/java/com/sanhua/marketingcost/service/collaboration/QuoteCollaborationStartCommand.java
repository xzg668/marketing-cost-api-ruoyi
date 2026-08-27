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

  /** 核算发起人就是补录完成后的审核责任人，避免任务创建后再补配审核人。 */
  public static QuoteCollaborationStartCommand initiatedByCostingOperator(
      Long oaFormItemId,
      Long technicianUserId,
      String technicianName,
      String accountingMonth,
      CollaborationActor actor) {
    if (actor == null || actor.userId() == null || actor.userId() <= 0) {
      throw new IllegalArgumentException("核算发起人未关联有效系统账号，不能创建补录任务");
    }
    return new QuoteCollaborationStartCommand(
        oaFormItemId,
        technicianUserId,
        technicianName,
        actor.userId(),
        actor.userName(),
        accountingMonth,
        actor);
  }
}
