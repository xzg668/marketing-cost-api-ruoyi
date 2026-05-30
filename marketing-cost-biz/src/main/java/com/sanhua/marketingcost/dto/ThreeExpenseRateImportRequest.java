package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

public class ThreeExpenseRateImportRequest {
  private List<ThreeExpenseRateRow> rows;

  public List<ThreeExpenseRateRow> getRows() {
    return rows;
  }

  public void setRows(List<ThreeExpenseRateRow> rows) {
    this.rows = rows;
  }

  public static class ThreeExpenseRateRow {
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
    private String periodMonth;
    private Integer periodYear;
    private String standardCompany;
    private String productionDivision;
    private String applicantDepartment;
    private String applicantOffice;
    /** productCategory、productLine 来自三项费用配置区域标题拆分。 */
    private String productCategory;
    private String productLine;
    private BigDecimal threeExpenseTotalRate;
    /** OEM费用率当前只导入展示，不参与三项费用计算。 */
    private BigDecimal oemExpenseRate;
    private String sourceType;
    private String importBatchNo;
    private String businessUnitType;

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

    public String getPeriodMonth() {
      return periodMonth;
    }

    public void setPeriodMonth(String periodMonth) {
      this.periodMonth = periodMonth;
    }

    public Integer getPeriodYear() {
      return periodYear;
    }

    public void setPeriodYear(Integer periodYear) {
      this.periodYear = periodYear;
    }

    public String getStandardCompany() {
      return standardCompany;
    }

    public void setStandardCompany(String standardCompany) {
      this.standardCompany = standardCompany;
    }

    public String getProductionDivision() {
      return productionDivision;
    }

    public void setProductionDivision(String productionDivision) {
      this.productionDivision = productionDivision;
    }

    public String getApplicantDepartment() {
      return applicantDepartment;
    }

    public void setApplicantDepartment(String applicantDepartment) {
      this.applicantDepartment = applicantDepartment;
    }

    public String getApplicantOffice() {
      return applicantOffice;
    }

    public void setApplicantOffice(String applicantOffice) {
      this.applicantOffice = applicantOffice;
    }

    public String getProductCategory() {
      return productCategory;
    }

    public void setProductCategory(String productCategory) {
      this.productCategory = productCategory;
    }

    public String getProductLine() {
      return productLine;
    }

    public void setProductLine(String productLine) {
      this.productLine = productLine;
    }

    public BigDecimal getThreeExpenseTotalRate() {
      return threeExpenseTotalRate;
    }

    public void setThreeExpenseTotalRate(BigDecimal threeExpenseTotalRate) {
      this.threeExpenseTotalRate = threeExpenseTotalRate;
    }

    public BigDecimal getOemExpenseRate() {
      return oemExpenseRate;
    }

    public void setOemExpenseRate(BigDecimal oemExpenseRate) {
      this.oemExpenseRate = oemExpenseRate;
    }

    public String getSourceType() {
      return sourceType;
    }

    public void setSourceType(String sourceType) {
      this.sourceType = sourceType;
    }

    public String getImportBatchNo() {
      return importBatchNo;
    }

    public void setImportBatchNo(String importBatchNo) {
      this.importBatchNo = importBatchNo;
    }

    public String getBusinessUnitType() {
      return businessUnitType;
    }

    public void setBusinessUnitType(String businessUnitType) {
      this.businessUnitType = businessUnitType;
    }
  }
}
