package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

public class OtherExpenseRateImportRequest {
  private List<OtherExpenseRateRow> rows;

  public List<OtherExpenseRateRow> getRows() {
    return rows;
  }

  public void setRows(List<OtherExpenseRateRow> rows) {
    this.rows = rows;
  }

  public static class OtherExpenseRateRow {
    private String materialCode;
    private String productName;
    private String spec;
    private String model;
    private String customer;
    private String expenseType;
    private BigDecimal expenseAmount;

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

    public String getCustomer() {
      return customer;
    }

    public void setCustomer(String customer) {
      this.customer = customer;
    }

    public String getExpenseType() {
      return expenseType;
    }

    public void setExpenseType(String expenseType) {
      this.expenseType = expenseType;
    }

    public BigDecimal getExpenseAmount() {
      return expenseAmount;
    }

    public void setExpenseAmount(BigDecimal expenseAmount) {
      this.expenseAmount = expenseAmount;
    }
  }
}
