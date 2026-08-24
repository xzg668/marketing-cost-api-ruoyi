package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 一次取价的最小必要底稿。
 *
 * <p>这些字段会随最终价格固化，使历史成本不依赖后续变更的价格或供货比例主数据。
 */
public record PriceResolveEvidence(
    Long sourcePriceRecordId,
    String sourceBatchNo,
    String supplierName,
    String supplierCode,
    BigDecimal supplyRatio,
    Long supplyRatioRecordId,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    boolean carriedForward,
    String warningMessage) {

  public static PriceResolveEvidence none() {
    return new PriceResolveEvidence(
        null, null, null, null, null, null, null, null, false, null);
  }

  public boolean hasEvidence() {
    return sourcePriceRecordId != null
        || hasText(sourceBatchNo)
        || hasText(supplierName)
        || hasText(supplierCode)
        || supplyRatio != null
        || supplyRatioRecordId != null
        || effectiveFrom != null
        || effectiveTo != null
        || carriedForward
        || hasText(warningMessage);
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
