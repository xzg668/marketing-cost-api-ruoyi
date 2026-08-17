package com.sanhua.marketingcost.integration.oa.collaboration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 禁用模式不验签、不写Inbox，更不能委托任何业务状态变更。 */
@Component
@ConditionalOnProperty(
    prefix = "quote.collaboration.oa",
    name = "mode",
    havingValue = "DISABLED",
    matchIfMissing = true)
public class DisabledOaCollaborationCallbackPort implements OaCollaborationCallbackPort {

  @Override
  public OaCollaborationCallbackReceipt handle(
      OaCollaborationCallback callback, String signature) {
    return OaCollaborationCallbackReceipt.disabled();
  }
}
