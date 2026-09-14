package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

public record TechnicalDataAuxiliaryResponse(
    Long taskId,
    Long productId,
    String materialNo,
    String productName,
    String productModel,
    String accountingMonth,
    Long draftVersionId,
    Integer draftVersionNo,
    String draftVersionStatus,
    Integer expectedVersion,
    Integer draftRowVersion,
    String moduleStatus,
    String entryMode,
    String referenceSourceType,
    String referenceSourceId,
    String referenceSourceVersion,
    BigDecimal totalAmount,
    int itemCount,
    List<Item> items) {

  public TechnicalDataAuxiliaryResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      Long id,
      Integer lineNo,
      String subjectCode,
      String subjectName,
      String auxiliaryMaterialNo,
      String auxiliaryName,
      String auxiliarySpec,
      String pricingMethod,
      String pricingMethodLabel,
      BigDecimal quantity,
      String unit,
      BigDecimal standardQuantity,
      String standardUnit,
      BigDecimal conversionFactor,
      BigDecimal referenceUnitPrice,
      String priceUnit,
      BigDecimal lossRate,
      BigDecimal amount,
      String remark) {}
}
