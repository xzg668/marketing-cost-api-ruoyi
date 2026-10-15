package com.sanhua.marketingcost.service.pricing;

import static com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeKeyPart;

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

  public SupplierPreferredPriceSelection<T> withRow(T selectedRow) {
    return new SupplierPreferredPriceSelection<>(selectedRow, traceMessage, candidateSupplierCount,
        mainSupplierName, mainSupplierCode, supplyRatio, supplyRatioRecordId, matchMode,
        failureCode, failureMessage);
  }

  /** 选定供应商之后只在该供应商内找档位，不能用目标行情筛出另一个供应商。 */
  public boolean matchesSupplier(String name, String code) {
    if ("NO_SUPPLIER_DIMENSION".equals(matchMode)) return !hasText(name) && !hasText(code);
    String candidateCode = normalizeKeyPart(code);
    String selectedCode = normalizeKeyPart(mainSupplierCode);
    if (hasText(candidateCode) && hasText(selectedCode)) return candidateCode.equals(selectedCode);
    String candidateName = normalizeKeyPart(name);
    return hasText(candidateName) && candidateName.equals(normalizeKeyPart(mainSupplierName));
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
