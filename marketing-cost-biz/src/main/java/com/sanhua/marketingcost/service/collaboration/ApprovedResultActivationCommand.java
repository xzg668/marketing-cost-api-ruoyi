package com.sanhua.marketingcost.service.collaboration;

/** 财务审核正式生效后，为一个结构类产品任务生成可复用结果。 */
public record ApprovedResultActivationCommand(
    Long sourceProductTaskId,
    Long sourceReviewId,
    String businessUnitType,
    String applicableOrgCode,
    CollaborationActor actor) {}
