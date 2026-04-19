package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

public class DepartmentFundRateImportRequest {
  private List<DepartmentFundRateRow> rows;

  public List<DepartmentFundRateRow> getRows() {
    return rows;
  }

  public void setRows(List<DepartmentFundRateRow> rows) {
    this.rows = rows;
  }

  public static class DepartmentFundRateRow {
    private String businessUnit;
    private BigDecimal overhaulRate;
    private BigDecimal toolingRepairRate;
    private BigDecimal waterPowerRate;
    private BigDecimal otherRate;
    private BigDecimal upliftRate;
    private BigDecimal manhourRate;

    public String getBusinessUnit() {
      return businessUnit;
    }

    public void setBusinessUnit(String businessUnit) {
      this.businessUnit = businessUnit;
    }

    public BigDecimal getOverhaulRate() {
      return overhaulRate;
    }

    public void setOverhaulRate(BigDecimal overhaulRate) {
      this.overhaulRate = overhaulRate;
    }

    public BigDecimal getToolingRepairRate() {
      return toolingRepairRate;
    }

    public void setToolingRepairRate(BigDecimal toolingRepairRate) {
      this.toolingRepairRate = toolingRepairRate;
    }

    public BigDecimal getWaterPowerRate() {
      return waterPowerRate;
    }

    public void setWaterPowerRate(BigDecimal waterPowerRate) {
      this.waterPowerRate = waterPowerRate;
    }

    public BigDecimal getOtherRate() {
      return otherRate;
    }

    public void setOtherRate(BigDecimal otherRate) {
      this.otherRate = otherRate;
    }

    public BigDecimal getUpliftRate() {
      return upliftRate;
    }

    public void setUpliftRate(BigDecimal upliftRate) {
      this.upliftRate = upliftRate;
    }

    public BigDecimal getManhourRate() {
      return manhourRate;
    }

    public void setManhourRate(BigDecimal manhourRate) {
      this.manhourRate = manhourRate;
    }
  }
}
