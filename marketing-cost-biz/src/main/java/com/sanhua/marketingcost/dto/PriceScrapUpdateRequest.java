package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 废料回收价 创建/修改请求体 (V48) */
public class PriceScrapUpdateRequest {
  private String pricingMonth;
  private String scrapCode;
  private String scrapName;
  private String specModel;
  private String unit;
  private BigDecimal recyclePrice;
  /** 是否含税：true=1 / false=0；null 不动 */
  private Boolean taxIncluded;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String remark;

  public String getPricingMonth() { return pricingMonth; }
  public void setPricingMonth(String pricingMonth) { this.pricingMonth = pricingMonth; }

  public String getScrapCode() { return scrapCode; }
  public void setScrapCode(String scrapCode) { this.scrapCode = scrapCode; }

  public String getScrapName() { return scrapName; }
  public void setScrapName(String scrapName) { this.scrapName = scrapName; }

  public String getSpecModel() { return specModel; }
  public void setSpecModel(String specModel) { this.specModel = specModel; }

  public String getUnit() { return unit; }
  public void setUnit(String unit) { this.unit = unit; }

  public BigDecimal getRecyclePrice() { return recyclePrice; }
  public void setRecyclePrice(BigDecimal recyclePrice) { this.recyclePrice = recyclePrice; }

  public Boolean getTaxIncluded() { return taxIncluded; }
  public void setTaxIncluded(Boolean taxIncluded) { this.taxIncluded = taxIncluded; }

  public LocalDate getEffectiveFrom() { return effectiveFrom; }
  public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }

  public LocalDate getEffectiveTo() { return effectiveTo; }
  public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }

  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }
}
