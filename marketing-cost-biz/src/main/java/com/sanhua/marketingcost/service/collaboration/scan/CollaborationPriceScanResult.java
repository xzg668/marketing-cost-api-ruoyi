package com.sanhua.marketingcost.service.collaboration.scan;

import java.math.BigDecimal;
import java.util.List;

/** 复用现有价格准备只读计算得到的真实结果。 */
public record CollaborationPriceScanResult(
    Status status,
    int checkedItemCount,
    List<PriceGap> gaps,
    String message) {

  public enum Status {
    NOT_CHECKED,
    PENDING_BOM,
    PENDING_PACKAGE,
    READY,
    GAPS,
    ERROR
  }

  public CollaborationPriceScanResult {
    gaps = gaps == null ? List.of() : List.copyOf(gaps);
  }

  public int gapCount() {
    return gaps.size();
  }

  public static CollaborationPriceScanResult ready(int checkedItemCount) {
    return new CollaborationPriceScanResult(Status.READY, checkedItemCount, List.of(), null);
  }

  public static CollaborationPriceScanResult gaps(
      int checkedItemCount, List<PriceGap> gaps) {
    return new CollaborationPriceScanResult(
        Status.GAPS, checkedItemCount, gaps, "存在当前报价条件下的真实缺价");
  }

  public static CollaborationPriceScanResult pendingBom(String message) {
    return new CollaborationPriceScanResult(Status.PENDING_BOM, 0, List.of(), message);
  }

  public static CollaborationPriceScanResult pendingPackage(String message) {
    return new CollaborationPriceScanResult(Status.PENDING_PACKAGE, 0, List.of(), message);
  }

  public static CollaborationPriceScanResult error(String message) {
    return new CollaborationPriceScanResult(Status.ERROR, 0, List.of(), message);
  }

  public record PriceGap(
      String materialCode,
      String gapType,
      String actionType,
      String reason,
      String sourceTable,
      String existingOfficialPriceType,
      String sourceType,
      Long sourceId,
      String bomNodeKey,
      String bomPath,
      String materialName,
      String materialSpec,
      String materialModel,
      String materialRole,
      BigDecimal bomQuantity,
      String bomUnit,
      String accountingMonth,
      String applicableOrgCode) {

    public PriceGap(
        String materialCode,
        String gapType,
        String actionType,
        String reason,
        String sourceTable,
        String existingOfficialPriceType) {
      this(materialCode, gapType, actionType, reason, sourceTable,
          existingOfficialPriceType, null, null, null, null, null, null, null, null,
          null, null, null, null);
    }
  }
}
