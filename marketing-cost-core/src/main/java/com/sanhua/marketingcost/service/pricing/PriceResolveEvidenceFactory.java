package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 统一构造价格来源证据，避免各价格类型分别维护历史价判断和提醒文案。 */
public final class PriceResolveEvidenceFactory {

  private PriceResolveEvidenceFactory() {
  }

  public static PriceResolveEvidence create(
      Long sourcePriceRecordId,
      String sourcePriceBatchNo,
      String supplierName,
      String supplierCode,
      BigDecimal supplyRatio,
      Long supplyRatioRecordId,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      LocalDate pricingDate) {
    boolean carriedForward =
        effectiveTo != null && pricingDate != null && effectiveTo.isBefore(pricingDate);
    String warningMessage = carriedForward
        ? "沿用历史价：价格有效期至 " + effectiveTo + "，本次核算日 " + pricingDate
        : null;
    return new PriceResolveEvidence(
        sourcePriceRecordId,
        sourcePriceBatchNo,
        supplierName,
        supplierCode,
        supplyRatio,
        supplyRatioRecordId,
        effectiveFrom,
        effectiveTo,
        carriedForward,
        warningMessage);
  }
}
