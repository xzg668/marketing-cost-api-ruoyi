package com.sanhua.marketingcost.dto;

import java.time.LocalDate;
import java.util.List;

public class MaterialPriceTypeRangeApplyRequest {
  private List<Row> rows;

  public List<Row> getRows() {
    return rows;
  }

  public void setRows(List<Row> rows) {
    this.rows = rows;
  }

  public static class Row {
    private String materialCode;
    private String materialName;
    private String businessUnitType;
    private String period;
    private LocalDate effectiveFrom;
    private String source;

    public String getMaterialCode() {
      return materialCode;
    }

    public void setMaterialCode(String materialCode) {
      this.materialCode = materialCode;
    }

    public String getMaterialName() {
      return materialName;
    }

    public void setMaterialName(String materialName) {
      this.materialName = materialName;
    }

    public String getBusinessUnitType() {
      return businessUnitType;
    }

    public void setBusinessUnitType(String businessUnitType) {
      this.businessUnitType = businessUnitType;
    }

    public String getPeriod() {
      return period;
    }

    public void setPeriod(String period) {
      this.period = period;
    }

    public LocalDate getEffectiveFrom() {
      return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
      this.effectiveFrom = effectiveFrom;
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }
  }
}
