package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

public class SalaryCostImportRequest {
  private List<SalaryCostRow> rows;

  public List<SalaryCostRow> getRows() {
    return rows;
  }

  public void setRows(List<SalaryCostRow> rows) {
    this.rows = rows;
  }

  public static class SalaryCostRow {
    private String materialCode;
    private String productName;
    private String spec;
    private String model;
    private String refMaterialCode;
    private BigDecimal directLaborCost;
    private BigDecimal indirectLaborCost;
    private String source;
    private String businessUnit;

    public String getMaterialCode() {
      return materialCode;
    }

    public void setMaterialCode(String materialCode) {
      this.materialCode = materialCode;
    }

    public String getProductName() {
      return productName;
    }

    public void setProductName(String productName) {
      this.productName = productName;
    }

    public String getSpec() {
      return spec;
    }

    public void setSpec(String spec) {
      this.spec = spec;
    }

    public String getModel() {
      return model;
    }

    public void setModel(String model) {
      this.model = model;
    }

    public String getRefMaterialCode() {
      return refMaterialCode;
    }

    public void setRefMaterialCode(String refMaterialCode) {
      this.refMaterialCode = refMaterialCode;
    }

    public BigDecimal getDirectLaborCost() {
      return directLaborCost;
    }

    public void setDirectLaborCost(BigDecimal directLaborCost) {
      this.directLaborCost = directLaborCost;
    }

    public BigDecimal getIndirectLaborCost() {
      return indirectLaborCost;
    }

    public void setIndirectLaborCost(BigDecimal indirectLaborCost) {
      this.indirectLaborCost = indirectLaborCost;
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    public String getBusinessUnit() {
      return businessUnit;
    }

    public void setBusinessUnit(String businessUnit) {
      this.businessUnit = businessUnit;
    }

  }
}
