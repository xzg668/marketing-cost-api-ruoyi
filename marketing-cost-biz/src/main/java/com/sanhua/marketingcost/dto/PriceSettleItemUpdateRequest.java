package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class PriceSettleItemUpdateRequest {
  private String materialCode;
  private String materialName;
  private String model;
  private BigDecimal plannedPrice;
  private BigDecimal markupRatio;
  private BigDecimal baseSettlePrice;
  private BigDecimal linkedSettlePrice;
  private String remark;

  public String getMaterialCode() { return materialCode; }
  public void setMaterialCode(String materialCode) { this.materialCode = materialCode; }
  public String getMaterialName() { return materialName; }
  public void setMaterialName(String materialName) { this.materialName = materialName; }
  public String getModel() { return model; }
  public void setModel(String model) { this.model = model; }
  public BigDecimal getPlannedPrice() { return plannedPrice; }
  public void setPlannedPrice(BigDecimal plannedPrice) { this.plannedPrice = plannedPrice; }
  public BigDecimal getMarkupRatio() { return markupRatio; }
  public void setMarkupRatio(BigDecimal markupRatio) { this.markupRatio = markupRatio; }
  public BigDecimal getBaseSettlePrice() { return baseSettlePrice; }
  public void setBaseSettlePrice(BigDecimal baseSettlePrice) { this.baseSettlePrice = baseSettlePrice; }
  public BigDecimal getLinkedSettlePrice() { return linkedSettlePrice; }
  public void setLinkedSettlePrice(BigDecimal linkedSettlePrice) { this.linkedSettlePrice = linkedSettlePrice; }
  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }
}
