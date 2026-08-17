package com.sanhua.marketingcost.integration.oa.collaboration;

public interface OaCollaborationCallbackPort {

  OaCollaborationCallbackReceipt handle(
      OaCollaborationCallback callback, String signature);
}
