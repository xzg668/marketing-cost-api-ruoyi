package com.sanhua.marketingcost.integration.oa.collaboration;

import com.sanhua.marketingcost.config.OaCollaborationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 关闭模式不持有HTTP客户端，不生成任何OA外部标识。 */
@Component
@ConditionalOnProperty(
    prefix = "quote.collaboration.oa",
    name = "mode",
    havingValue = "DISABLED",
    matchIfMissing = true)
public class DisabledOaCollaborationGateway implements OaCollaborationGateway {

  private final OaCollaborationProperties properties;

  public DisabledOaCollaborationGateway(OaCollaborationProperties properties) {
    this.properties = properties;
  }

  @Override
  public OaCollaborationDispatchReceipt dispatch(OaCollaborationEvent event) {
    if (properties.getMode() != OaCollaborationMode.DISABLED) {
      throw new IllegalStateException("关闭适配器只能在DISABLED模式使用");
    }
    return OaCollaborationDispatchReceipt.deferred(
        "INTEGRATION_DISABLED", "OA协作接口尚未启用，事件已保存在本地HOLD队列");
  }
}
