package com.sanhua.marketingcost.service.collaboration;

/** 发起补录命令；产品、月份和组织均由服务端重新扫描，调用方不能覆盖。 */
public record QuoteCollaborationStartCommand(
    Long oaFormItemId,
    Long technicianUserId,
    String technicianName,
    Long financeReviewerUserId,
    String financeReviewerName,
    CollaborationActor actor) {}
