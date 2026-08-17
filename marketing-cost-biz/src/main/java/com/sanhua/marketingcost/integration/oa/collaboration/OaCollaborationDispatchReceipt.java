package com.sanhua.marketingcost.integration.oa.collaboration;

public record OaCollaborationDispatchReceipt(
    OaCollaborationDispatchStatus status,
    String code,
    String message,
    String oaProcessInstanceId,
    String oaExternalTaskId) {

  public static OaCollaborationDispatchReceipt deferred(String code, String message) {
    return new OaCollaborationDispatchReceipt(
        OaCollaborationDispatchStatus.DEFERRED, code, message, null, null);
  }
}
