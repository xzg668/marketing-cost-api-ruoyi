package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class OaProductPropertyUsageSyncRow {
  private String oaLineId;
  private Integer seq;
  private String productCode;
  private BigDecimal annualUsage;

  public String getOaLineId() {
    return oaLineId;
  }

  public void setOaLineId(String oaLineId) {
    this.oaLineId = oaLineId;
  }

  public Integer getSeq() {
    return seq;
  }

  public void setSeq(Integer seq) {
    this.seq = seq;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
  }

  public BigDecimal getAnnualUsage() {
    return annualUsage;
  }

  public void setAnnualUsage(BigDecimal annualUsage) {
    this.annualUsage = annualUsage;
  }
}
