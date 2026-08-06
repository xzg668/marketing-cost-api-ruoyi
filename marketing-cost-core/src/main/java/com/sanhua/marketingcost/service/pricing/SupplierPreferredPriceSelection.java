package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;

public record SupplierPreferredPriceSelection<T>(
    T row,
    String traceMessage,
    int candidateSupplierCount,
    String mainSupplierName,
    String mainSupplierCode,
    BigDecimal supplyRatio,
    String matchMode,
    boolean fallback,
    String fallbackReason) {

  public SupplierPreferredPriceSelection(T row, String traceMessage) {
    this(row, traceMessage, row == null ? 0 : 1, null, null, null, "LEGACY", false, "");
  }
}
