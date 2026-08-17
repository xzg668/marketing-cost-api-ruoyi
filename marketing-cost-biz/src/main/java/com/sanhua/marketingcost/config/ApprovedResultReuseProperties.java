package com.sanhua.marketingcost.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 财务审核结果复用策略；每次生成结果时会把命中的策略和值固化到结果表。 */
@Component
@ConfigurationProperties(prefix = "quote.collaboration.approved-result")
public class ApprovedResultReuseProperties {

  private String policyCode = "COLLAB_RESULT_SIX_MONTHS_V1";
  private int validityMonths = 6;

  public String getPolicyCode() {
    return policyCode;
  }

  public void setPolicyCode(String policyCode) {
    this.policyCode = policyCode;
  }

  public int getValidityMonths() {
    return validityMonths;
  }

  public void setValidityMonths(int validityMonths) {
    this.validityMonths = validityMonths;
  }
}
