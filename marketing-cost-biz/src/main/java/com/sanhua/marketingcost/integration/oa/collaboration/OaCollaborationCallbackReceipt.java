package com.sanhua.marketingcost.integration.oa.collaboration;

public record OaCollaborationCallbackReceipt(
    boolean accepted,
    boolean replay,
    String code,
    String message) {

  public static OaCollaborationCallbackReceipt disabled() {
    return new OaCollaborationCallbackReceipt(
        false, false, "INTEGRATION_DISABLED", "OA协作回调尚未启用");
  }
}
