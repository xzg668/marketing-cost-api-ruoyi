package com.sanhua.marketingcost.entity.monthly;

import com.sanhua.marketingcost.entity.SupplierSupplyRatio;

/** Exact query identity beside the unchanged master row matched by the database collation. */
public final class MonthlySupplierRatioInput extends SupplierSupplyRatio {
  private String requestedMaterialCode;
  public String getRequestedMaterialCode() { return requestedMaterialCode; }
  public void setRequestedMaterialCode(String value) { requestedMaterialCode = value; }
}
