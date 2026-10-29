package com.sanhua.marketingcost.entity.monthly;

import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.fasterxml.jackson.annotation.JsonIgnore;

/** Exact query identity beside the unchanged master row matched by the database collation. */
public final class MonthlySupplierRatioInput extends SupplierSupplyRatio {
  private String requestedMaterialCode;
  /** Query routing only; price evidence keeps the unchanged SupplierSupplyRatio fields. */
  @JsonIgnore
  public String getRequestedMaterialCode() { return requestedMaterialCode; }
  public void setRequestedMaterialCode(String value) { requestedMaterialCode = value; }
}
