package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

public record TechnicalDataPackageResponse(Long taskId, Long productId, String materialNo,
    String productName, String productModel, String accountingMonth, Long draftVersionId,
    Integer draftVersionNo, String draftVersionStatus, Integer expectedVersion, String moduleStatus,
    String entryMode, boolean editable, boolean historical, TechnicalDataSupplementContent.Packaging packaging,
    List<Item> items, List<String> issues) {
  public int itemCount() { return items.size(); }

  public record Item(Long id, Integer lineNo, String componentMaterialNo, String componentName,
      String componentModel, String componentSpec, BigDecimal quantity, String unit, String remark,
      TechnicalDataSupplementContent.PackageItemEvidence evidence, BigDecimal quantityPerProduct) {}
}
