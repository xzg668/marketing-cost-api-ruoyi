package com.sanhua.marketingcost.dto;

import java.util.List;

public class ProductPropertyImportRequest {
  private List<ProductPropertyRow> rows;

  public List<ProductPropertyRow> getRows() {
    return rows;
  }

  public void setRows(List<ProductPropertyRow> rows) {
    this.rows = rows;
  }

  public static class ProductPropertyRow {
    private String level1Code;
    private String level1Name;
    private String parentCode;
    private String parentName;
    private String parentSpec;
    private String parentModel;
    private String period;
    private String productAttr;

    public String getLevel1Code() {
      return level1Code;
    }

    public void setLevel1Code(String level1Code) {
      this.level1Code = level1Code;
    }

    public String getLevel1Name() {
      return level1Name;
    }

    public void setLevel1Name(String level1Name) {
      this.level1Name = level1Name;
    }

    public String getParentCode() {
      return parentCode;
    }

    public void setParentCode(String parentCode) {
      this.parentCode = parentCode;
    }

    public String getParentName() {
      return parentName;
    }

    public void setParentName(String parentName) {
      this.parentName = parentName;
    }

    public String getParentSpec() {
      return parentSpec;
    }

    public void setParentSpec(String parentSpec) {
      this.parentSpec = parentSpec;
    }

    public String getParentModel() {
      return parentModel;
    }

    public void setParentModel(String parentModel) {
      this.parentModel = parentModel;
    }

    public String getPeriod() {
      return period;
    }

    public void setPeriod(String period) {
      this.period = period;
    }

    public String getProductAttr() {
      return productAttr;
    }

    public void setProductAttr(String productAttr) {
      this.productAttr = productAttr;
    }
  }
}
