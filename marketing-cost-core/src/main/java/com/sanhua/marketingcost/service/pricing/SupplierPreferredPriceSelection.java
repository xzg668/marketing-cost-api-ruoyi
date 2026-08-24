package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;

public record SupplierPreferredPriceSelection<T>(
    T row,
    String traceMessage,
    int candidateSupplierCount,
    String mainSupplierName,
    String mainSupplierCode,
    BigDecimal supplyRatio,
    Long supplyRatioRecordId,
    String matchMode,
    String failureCode,
    String failureMessage) {

  public SupplierPreferredPriceSelection(T row, String traceMessage) {
    this(row, traceMessage, row == null ? 0 : 1, null, null, null, null, "NO_SUPPLIER_DIMENSION", "", "");
  }

  public boolean failed() {
    return failureCode != null && !failureCode.isBlank();
  }
}
