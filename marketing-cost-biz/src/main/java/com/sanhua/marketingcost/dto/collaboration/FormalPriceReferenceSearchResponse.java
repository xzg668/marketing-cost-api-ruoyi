package com.sanhua.marketingcost.dto.collaboration;

import java.time.LocalDate;
import java.util.List;

public record FormalPriceReferenceSearchResponse(
    String keyword,
    String priceType,
    String orgCode,
    int total,
    List<Item> items) {

  public FormalPriceReferenceSearchResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      String sourceType,
      Long sourceId,
      String priceType,
      String priceTypeLabel,
      String materialCode,
      String materialName,
      String specModel,
      String orgCode,
      String supplierName,
      String unit,
      String priceSummary,
      String versionText,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {}
}
