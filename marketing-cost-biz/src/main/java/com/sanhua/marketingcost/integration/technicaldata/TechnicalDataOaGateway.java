package com.sanhua.marketingcost.integration.technicaldata;

import com.sanhua.marketingcost.integration.oa.OaPeer;

/** 现有一次性身份兑换入口；正式 OAuth2 待 OA 协议确认。流程办理使用原生 OaWorkflowClient。 */
public interface TechnicalDataOaGateway {
  record Identity(String externalUserId, long taskId) {}
  boolean enabled();
  OaPeer peer();
  Identity exchange(long taskId, String code);
}
