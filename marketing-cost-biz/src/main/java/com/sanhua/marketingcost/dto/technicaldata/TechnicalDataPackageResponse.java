package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

public record TechnicalDataPackageResponse(
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
    int itemCount,
    List<Item> items) {

  public TechnicalDataPackageResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      Long id,
      Integer lineNo,
      String componentMaterialNo,
      String componentName,
      String componentSpec,
      BigDecimal quantity,
      String unit,
      String priceBasisType,
      String priceBasisLabel,
      String remark) {}
}
