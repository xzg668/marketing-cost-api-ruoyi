package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

public class PriceSettleImportRequest {
  private String buyer;
  private String seller;
  private String businessType;
  private String productProperty;
  private BigDecimal copperPrice;
  private String month;
  private String approvalContent;
  private List<PriceSettleItemImportRow> items;

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
  public List<PriceSettleItemImportRow> getItems() { return items; }
  public void setItems(List<PriceSettleItemImportRow> items) { this.items = items; }

  public static class PriceSettleItemImportRow {
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
}
