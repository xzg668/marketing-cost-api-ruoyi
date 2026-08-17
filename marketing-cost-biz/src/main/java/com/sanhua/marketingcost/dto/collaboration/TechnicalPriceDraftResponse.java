package com.sanhua.marketingcost.dto.collaboration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TechnicalPriceDraftResponse(
    Long draftId,
    String draftNo,
    Integer draftVersion,
    Long gapId,
    String materialCode,
    String materialName,
    String materialSpec,
    String materialModel,
    String orgCode,
    String priceType,
    String priceTypeLabel,
    String sourceMode,
    String sourceModeLabel,
    String referenceSourceType,
    Long referenceSourceId,
    String referenceLabel,
    String referenceVersionText,
    String supplierCode,
    String supplierName,
    String unit,
    Integer taxIncluded,
    String taxRate,
    LocalDate effectiveFrom,
    LocalDate effectiveTo,
    String status,
    String validationStatus,
    String validationMessage,
    LocalDateTime savedAt,
    TaxConversion taxConversion,
    List<Field> fields,
    List<ReferenceChange> referenceChanges) {

  public TechnicalPriceDraftResponse {
    fields = fields == null ? List.of() : List.copyOf(fields);
    referenceChanges = referenceChanges == null ? List.of() : List.copyOf(referenceChanges);
  }

  public record Field(
      Long fieldId,
      String sectionCode,
      String rowKey,
      String fieldCode,
      String fieldName,
      String valueType,
      String referenceValue,
      String targetValue,
      String unit,
      boolean required,
      boolean techInputRequired,
      boolean changed,
      String validationStatus,
      String validationMessage,
      int sortSeq) {}

  public record ReferenceChange(
      LocalDateTime changedAt,
      String changedBy,
      String beforeReference,
      String afterReference) {}

  public record TaxConversion(String taxIncludedPrice, String taxExcludedPrice) {}
}
