package com.sanhua.marketingcost.config;

import com.sanhua.marketingcost.integration.oa.collaboration.OaCollaborationMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** OA协作适配器开关；本期生产默认且只交付关闭实现。 */
@Component
@ConfigurationProperties(prefix = "quote.collaboration.oa")
public class OaCollaborationProperties {

  private OaCollaborationMode mode = OaCollaborationMode.DISABLED;

  public OaCollaborationMode getMode() {
    return mode;
  }

  public void setMode(OaCollaborationMode mode) {
    this.mode = mode == null ? OaCollaborationMode.DISABLED : mode;
  }
}
