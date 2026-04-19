package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class PriceSettleUpdateRequest {
  private String buyer;
  private String seller;
  private String businessType;
  private String productProperty;
  private BigDecimal copperPrice;
  private String month;
  private String approvalContent;

  public String getBuyer() { return buyer; }
  public void setBuyer(String buyer) { this.buyer = buyer; }
  public String getSeller() { return seller; }
  public void setSeller(String seller) { this.seller = seller; }
  public String getBusinessType() { return businessType; }
  public void setBusinessType(String businessType) { this.businessType = businessType; }
  public String getProductProperty() { return productProperty; }
  public void setProductProperty(String productProperty) { this.productProperty = productProperty; }
  public BigDecimal getCopperPrice() { return copperPrice; }
  public void setCopperPrice(BigDecimal copperPrice) { this.copperPrice = copperPrice; }
  public String getMonth() { return month; }
  public void setMonth(String month) { this.month = month; }
  public String getApprovalContent() { return approvalContent; }
  public void setApprovalContent(String approvalContent) { this.approvalContent = approvalContent; }
}
