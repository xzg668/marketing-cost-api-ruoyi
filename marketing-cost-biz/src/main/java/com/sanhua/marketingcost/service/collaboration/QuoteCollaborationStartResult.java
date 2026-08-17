package com.sanhua.marketingcost.service.collaboration;

/** 创建或关联结果；页面据此只展示一个当前状态和一个下一步。 */
public record QuoteCollaborationStartResult(
    CollaborationStartAction action,
    Long productTaskId,
    String productTaskNo,
    Long quoteLinkId,
    String currentStatus,
    Long currentAssigneeUserId,
    String currentAssigneeName,
    CollaborationNextAction nextAction,
    Integer taskVersion,
    boolean idempotentReplay,
    String message) {}
