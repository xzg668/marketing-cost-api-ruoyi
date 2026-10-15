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
    String warningMessage,
    String inputSnapshotJson) {

  private static final com.fasterxml.jackson.databind.ObjectMapper SNAPSHOT_JSON =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  public PriceResolveEvidence(Long sourcePriceRecordId, String sourceBatchNo, String supplierName,
      String supplierCode, BigDecimal supplyRatio, Long supplyRatioRecordId, LocalDate effectiveFrom,
      LocalDate effectiveTo, boolean carriedForward, String warningMessage) {
    this(sourcePriceRecordId, sourceBatchNo, supplierName, supplierCode, supplyRatio, supplyRatioRecordId,
        effectiveFrom, effectiveTo, carriedForward, warningMessage, null);
  }

  public static PriceResolveEvidence none() {
    return new PriceResolveEvidence(
        null, null, null, null, null, null, null, null, false, null);
  }

  /** 与价格来源底稿一起冻结本次实际采用的顺序，后续财务调整不改历史。 */
  public PriceResolveEvidence withPriority(com.sanhua.marketingcost.dto.PriceTypeRoute route) {
    if (route == null || route.priorityConfiguration() == null) return this;
    try {
      var json = SNAPSHOT_JSON;
      var original = hasText(inputSnapshotJson) ? json.readTree(inputSnapshotJson) : json.createObjectNode();
      if (!(original instanceof com.fasterxml.jackson.databind.node.ObjectNode snapshot)) {
        throw new IllegalStateException("价格输入底稿必须为JSON对象");
      }
      var priority = json.valueToTree(route.priorityConfiguration());
      snapshot.set("pricePriority", priority);
      snapshot.put("selectedPriceType", route.selectedSourceType());
      snapshot.put("selectedPriceSourceKind", route.sourceKind());
      return new PriceResolveEvidence(sourcePriceRecordId, sourceBatchNo, supplierName, supplierCode,
          supplyRatio, supplyRatioRecordId, effectiveFrom, effectiveTo, carriedForward, warningMessage,
          json.writeValueAsString(snapshot));
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException("取价优先级底稿保存失败", error);
    }
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
        || hasText(inputSnapshotJson)
        || hasText(warningMessage);
  }

  private boolean hasText(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
