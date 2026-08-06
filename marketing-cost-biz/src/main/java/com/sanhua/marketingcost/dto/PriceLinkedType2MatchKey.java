package com.sanhua.marketingcost.dto;

import java.util.Objects;

/** 已标准化的“料号 + 供应商名称”匹配键。 */
public final class PriceLinkedType2MatchKey {

  private final String materialCode;
  private final String supplierName;

  public PriceLinkedType2MatchKey(String materialCode, String supplierName) {
    this.materialCode = materialCode;
    this.supplierName = supplierName;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public String getSupplierName() {
    return supplierName;
  }

  public String asText() {
    return materialCode + " | " + supplierName;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PriceLinkedType2MatchKey that)) {
      return false;
    }
    return Objects.equals(materialCode, that.materialCode)
        && Objects.equals(supplierName, that.supplierName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(materialCode, supplierName);
  }

  @Override
  public String toString() {
    return asText();
  }
}
