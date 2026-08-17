package com.sanhua.marketingcost.service.collaboration;

import java.time.LocalDate;
import java.util.List;

public record FormalPriceReference(
    String sourceType,
    Long sourceId,
    String priceType,
    String materialCode,
    String materialName,
    String specModel,
    String orgCode,
    String supplierCode,
    String supplierName,
    String unit,
    Integer taxIncluded,
    String taxRate,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String priceSummary,
    String versionText,
    List<Field> fields) {

  public FormalPriceReference {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  public record Field(
      String sectionCode,
      String rowKey,
      String fieldCode,
      String fieldName,
      String valueType,
      String value,
      String unit,
      boolean required,
      boolean techInputRequired,
      int sortSeq) {}
}
