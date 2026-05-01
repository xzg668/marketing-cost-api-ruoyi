package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 废料回收价 批量导入请求 (V48) */
public class PriceScrapImportRequest {
  private List<PriceScrapImportRow> rows;

  public List<PriceScrapImportRow> getRows() { return rows; }
  public void setRows(List<PriceScrapImportRow> rows) { this.rows = rows; }

  public static class PriceScrapImportRow {
    private String pricingMonth;
    private String scrapCode;
    private String scrapName;
    private String specModel;
    private String unit;
    private BigDecimal recyclePrice;
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
}
