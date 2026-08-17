package com.sanhua.marketingcost.dto.collaboration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record TechnicalPriceGapWorkspaceResponse(
    Long taskId,
    Integer taskVersion,
    String productCode,
    String productName,
    String accountingMonth,
    String applicableOrgCode,
    int totalCount,
    int savedCount,
    List<Item> items) {

  public TechnicalPriceGapWorkspaceResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      Long gapId,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String materialRole,
      String bomPath,
      BigDecimal bomQuantity,
      String bomUnit,
      String reason,
      String status,
      String statusLabel,
      Long draftId,
      String priceType,
      String priceTypeLabel,
      String sourceMode,
      String sourceModeLabel,
      String referenceLabel,
      String validationStatus,
      String validationMessage,
      LocalDateTime savedAt) {}
}
