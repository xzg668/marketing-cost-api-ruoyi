package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一个已发布财务修订的一份完整方案；API、公式、区间及后台任务共享不可变输入。 */
public record FactorPriceSnapshot(
    FactorPriceScope scope,
    Long batchId,
    Integer revision,
    String fileName,
    String fileSha256,
    String contentHash,
    Map<String, FactorValue> factors,
    List<String> schemeCodes,
    String publishedBy,
    java.time.LocalDateTime publishedAt,
    String publicationNote) {
  public FactorPriceSnapshot {
    factors = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(factors));
    schemeCodes = List.copyOf(schemeCodes);
  }

  public FactorPriceSnapshot copperOnly() {
    return new FactorPriceSnapshot(
        scope,
        batchId,
        revision,
        fileName,
        fileSha256,
        contentHash,
        Map.of("AVG|1#CU", required("AVG|1#CU")),
        schemeCodes,
        publishedBy,
        publishedAt,
        publicationNote);
  }

  public FactorValue required(String canonicalKey) {
    FactorValue value = factors.get(canonicalKey);
    if (value == null)
      throw new IllegalArgumentException("财务方案缺少因素：" + canonicalKey + "，批次=" + batchId);
    return value;
  }

  public record FactorValue(
      Long identityId,
      Long monthlyPriceId,
      String factorName,
      String shortName,
      @com.fasterxml.jackson.annotation.JsonFormat(
              shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING)
          BigDecimal price,
      String unit,
      boolean taxIncluded,
      List<Source> sources) {
    public FactorValue {
      sources = List.copyOf(sources);
    }
  }

  public record Source(
      Long id,
      String sheetName,
      String cellRef,
      String originalValue,
      String originalUnit,
      String formula,
      String numberFormat,
      String conversionBasis) {}
}
