package com.sanhua.marketingcost.integration.drawing;

/** 只传输核验摘要；报价归属和后续采用规则由业务服务负责。 */
public interface DrawingVerificationClient {
  DrawingVerificationPayload verify(Query query);

  /** 除 drawingNo 外的字段只关联本地日志，不发送给解析服务。 */
  record Query(String drawingNo, String requestId, Long localTaskId,
      String oaNo, Long oaFormItemId, String accountingMonth) {}
}
