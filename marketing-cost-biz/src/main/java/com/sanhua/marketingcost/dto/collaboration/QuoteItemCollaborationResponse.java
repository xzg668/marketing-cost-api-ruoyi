package com.sanhua.marketingcost.dto.collaboration;

/** 报价产品行的协作只读投影；页面不得自行拼接业务状态。 */
public record QuoteItemCollaborationResponse(
    Long itemId,
    String bomStatus,
    String bomStatusLabel,
    String priceStatus,
    String priceStatusLabel,
    int priceGapCount,
    Long assigneeUserId,
    String assigneeName,
    String currentStatus,
    String currentStatusLabel,
    Long productTaskId,
    String productTaskNo,
    Long quoteLinkId,
    Integer taskVersion,
    String nextAction,
    String nextActionLabel,
    boolean actionEnabled,
    boolean batchSelectable,
    String projectionVersion,
    String message) {}
