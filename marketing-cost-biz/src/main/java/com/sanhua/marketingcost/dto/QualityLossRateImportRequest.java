package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

public class QualityLossRateImportRequest {
  private Integer rateYear;
  private String sourceBatchNo;
  private List<QualityLossRateRow> rows;

  public Integer getRateYear() { return rateYear; }
  public void setRateYear(Integer value) { this.rateYear = value; }
  public String getSourceBatchNo() { return sourceBatchNo; }
  public void setSourceBatchNo(String value) { this.sourceBatchNo = value; }
  public List<QualityLossRateRow> getRows() { return rows; }
  public void setRows(List<QualityLossRateRow> value) { this.rows = value; }

  public static class QualityLossRateRow {
    private Integer rowNo;
    private String bareProductCode;
    private String productName;
    private String materialSpec;
    private String productModel;
    private String businessDivision;
    private String productCategory;
    private String productSubcategory;
    private String categorySpec;
    private String fourthLevel;
    private BigDecimal lossRate;
    private String remark;

    public Integer getRowNo() { return rowNo; }
    public void setRowNo(Integer value) { this.rowNo = value; }
    public String getBareProductCode() { return bareProductCode; }
    public void setBareProductCode(String value) { this.bareProductCode = value; }
    public String getProductName() { return productName; }
    public void setProductName(String value) { this.productName = value; }
    public String getMaterialSpec() { return materialSpec; }
    public void setMaterialSpec(String value) { this.materialSpec = value; }
    public String getProductModel() { return productModel; }
    public void setProductModel(String value) { this.productModel = value; }
    public String getBusinessDivision() { return businessDivision; }
    public void setBusinessDivision(String value) { this.businessDivision = value; }
    public String getProductCategory() { return productCategory; }
    public void setProductCategory(String value) { this.productCategory = value; }
    public String getProductSubcategory() { return productSubcategory; }
    public void setProductSubcategory(String value) { this.productSubcategory = value; }
    public String getCategorySpec() { return categorySpec; }
    public void setCategorySpec(String value) { this.categorySpec = value; }
    public String getFourthLevel() { return fourthLevel; }
    public void setFourthLevel(String value) { this.fourthLevel = value; }
    public BigDecimal getLossRate() { return lossRate; }
    public void setLossRate(BigDecimal value) { this.lossRate = value; }
    public String getRemark() { return remark; }
    public void setRemark(String value) { this.remark = value; }
  }
}
