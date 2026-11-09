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
    String publicationNote,
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        List<CuBand> cuBands) {
  public FactorPriceSnapshot(FactorPriceScope scope, Long batchId, Integer revision, String fileName,
      String fileSha256, String contentHash, Map<String, FactorValue> factors, List<String> schemeCodes,
      String publishedBy, java.time.LocalDateTime publishedAt, String publicationNote) {
    this(scope, batchId, revision, fileName, fileSha256, contentHash, factors, schemeCodes,
        publishedBy, publishedAt, publicationNote, List.of());
  }

  public FactorPriceSnapshot {
    factors = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(factors));
    schemeCodes = List.copyOf(schemeCodes);
    cuBands = cuBands == null ? List.of() : List.copyOf(cuBands);
  }

  /** A band's identity comes from the actual Cu row at import; later Cu maintenance preserves its boundary. */
  public record CuBand(String schemeCode, BigDecimal copperPerTon, Map<String, FactorValue> factors) {
    public CuBand {
      if (schemeCode == null || copperPerTon == null || copperPerTon.signum() <= 0)
        throw new IllegalArgumentException("财务铜价档位无效");
      factors = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(factors));
    }
    public CuBand retain(java.util.Set<String> keys) {
      var selected = new LinkedHashMap<String, FactorValue>();
      factors.forEach((key, value) -> { if (keys.contains(key)) selected.put(key, value); });
      return new CuBand(schemeCode, copperPerTon, selected);
    }
  }

  public CuBand customerBand(BigDecimal copperPerTon) {
    if (copperPerTon == null || cuBands.isEmpty() || !"210".equals(scope.priceOrgCode())) return null;
    var sorted = cuBands.stream().sorted(java.util.Comparator.comparing(CuBand::copperPerTon)).toList();
    CuBand selected = sorted.getFirst();
    for (var band : sorted) if (band.copperPerTon().compareTo(copperPerTon) <= 0) selected = band;
    return selected;
  }

  public FactorPriceSnapshot withCuBands(List<CuBand> bands) {
    return new FactorPriceSnapshot(scope, batchId, revision, fileName, fileSha256, contentHash,
        factors, schemeCodes, publishedBy, publishedAt, publicationNote, bands);
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
