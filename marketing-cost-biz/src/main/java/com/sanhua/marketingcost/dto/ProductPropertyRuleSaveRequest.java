package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class ProductPropertyRuleSaveRequest {
  private Integer propertyYear;
  private String businessUnitType;
  private List<RuleRow> rules = new ArrayList<>();

  public Integer getPropertyYear() { return propertyYear; }
  public void setPropertyYear(Integer value) { this.propertyYear = value; }
  public String getBusinessUnitType() { return businessUnitType; }
  public void setBusinessUnitType(String value) { this.businessUnitType = value; }
  public List<RuleRow> getRules() { return rules; }
  public void setRules(List<RuleRow> value) { this.rules = value == null ? new ArrayList<>() : value; }

  public static class RuleRow {
    private String productAttr;
    private BigDecimal upliftRate;
    public String getProductAttr() { return productAttr; }
    public void setProductAttr(String value) { this.productAttr = value; }
    public BigDecimal getUpliftRate() { return upliftRate; }
    public void setUpliftRate(BigDecimal value) { this.upliftRate = value; }
  }
}
