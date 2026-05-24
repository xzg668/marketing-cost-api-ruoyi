package com.sanhua.marketingcost.enums;

public enum SupplierSupplyRatioSourceType {
  EXCEL("EXCEL"),
  SRM("SRM"),
  MANUAL("MANUAL");

  private final String code;

  SupplierSupplyRatioSourceType(String code) {
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
