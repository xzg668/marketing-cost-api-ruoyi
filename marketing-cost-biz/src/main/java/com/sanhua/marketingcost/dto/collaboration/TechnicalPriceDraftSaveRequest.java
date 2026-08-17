package com.sanhua.marketingcost.dto.collaboration;

import java.time.LocalDate;
import java.util.List;

public record TechnicalPriceDraftSaveRequest(
    Integer expectedVersion,
    String supplierCode,
    String supplierName,
    String unit,
    Integer taxIncluded,
    String taxRate,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    List<FieldValue> fields) {

  public TechnicalPriceDraftSaveRequest {
    fields = fields == null ? List.of() : List.copyOf(fields);
  }

  public record FieldValue(
      String sectionCode,
      String rowKey,
      String fieldCode,
      String value) {}
}
