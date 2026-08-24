package com.sanhua.marketingcost.service;

/** 将产品核算的业务阻塞转换成唯一协作任务和缺口事实。 */
public interface ProductCostingCollaborationService {

  CoordinationResult coordinate(CoordinationCommand command);

  record CoordinationCommand(
      String oaNo,
      Long oaFormItemId,
      String periodMonth,
      String blockingStatus,
      String errorCode,
      String initiatedBy,
      String transientPrepareNo) {}

  record CoordinationResult(
      Long productTaskId,
      String status,
      Long assigneeUserId,
      String assigneeName,
      boolean gapFactPersisted,
      boolean transientAttemptDiscarded,
      String message) {

    public static CoordinationResult notCreated(String status, String message) {
      return new CoordinationResult(
          null, status, null, null, false, false, message);
    }
  }
}
