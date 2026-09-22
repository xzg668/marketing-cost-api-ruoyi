package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

public record TechnicalDataSalaryResponse(Long taskId, Long productId, Integer expectedVersion,
    Long draftVersionId, String versionStatus, String moduleStatus, String entryMode,
    boolean editable, boolean historical,
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal totalAmount,
    List<Item> items, List<String> issues) {
  public TechnicalDataSalaryResponse { items = List.copyOf(items); issues = List.copyOf(issues); }
  public record Item(Long id, String laborType,
      @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal sourceAmount,
      @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amount,
      TechnicalDataSalaryContent.ItemEvidence source) {}
}
