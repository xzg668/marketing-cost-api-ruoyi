package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class ThreeExpenseRateRequest {
  private String company;
  private String businessUnit;
  private String department;
  private BigDecimal managementExpenseRate;
  private BigDecimal financeExpenseRate;
  private BigDecimal salesExpenseRate;
  private BigDecimal threeExpenseRate2025;
  private BigDecimal threeExpenseRate2026;
  private String overseasSales;
  private String period;

  public String getCompany() {
    return company;
  }

  public void setCompany(String company) {
    this.company = company;
  }

  public String getBusinessUnit() {
    return businessUnit;
  }

  public void setBusinessUnit(String businessUnit) {
    this.businessUnit = businessUnit;
  }

  public String getDepartment() {
    return department;
  }

  public void setDepartment(String department) {
    this.department = department;
  }

  public BigDecimal getManagementExpenseRate() {
    return managementExpenseRate;
  }

  public void setManagementExpenseRate(BigDecimal managementExpenseRate) {
    this.managementExpenseRate = managementExpenseRate;
  }

  public BigDecimal getFinanceExpenseRate() {
    return financeExpenseRate;
  }

  public void setFinanceExpenseRate(BigDecimal financeExpenseRate) {
    this.financeExpenseRate = financeExpenseRate;
  }

  public BigDecimal getSalesExpenseRate() {
    return salesExpenseRate;
  }

  public void setSalesExpenseRate(BigDecimal salesExpenseRate) {
    this.salesExpenseRate = salesExpenseRate;
  }

  public BigDecimal getThreeExpenseRate2025() {
    return threeExpenseRate2025;
  }

  public void setThreeExpenseRate2025(BigDecimal threeExpenseRate2025) {
    this.threeExpenseRate2025 = threeExpenseRate2025;
  }

  public BigDecimal getThreeExpenseRate2026() {
    return threeExpenseRate2026;
  }

  public void setThreeExpenseRate2026(BigDecimal threeExpenseRate2026) {
    this.threeExpenseRate2026 = threeExpenseRate2026;
  }

  public String getOverseasSales() {
    return overseasSales;
  }

  public void setOverseasSales(String overseasSales) {
    this.overseasSales = overseasSales;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }
}
