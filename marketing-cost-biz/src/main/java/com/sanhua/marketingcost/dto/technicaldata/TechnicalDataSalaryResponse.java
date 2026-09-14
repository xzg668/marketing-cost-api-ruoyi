package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

public record TechnicalDataSalaryResponse(
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

  public TechnicalDataSalaryResponse {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(
      Long id,
      Integer lineNo,
      String processCode,
      String processName,
      String laborType,
      String laborTypeLabel,
      BigDecimal workingHours,
      String timeUnit,
      BigDecimal standardHours,
      String standardTimeUnit,
      BigDecimal timeConversionFactor,
      BigDecimal wageRate,
      String rateUnit,
      BigDecimal hourlyRate,
      BigDecimal personCoefficient,
      BigDecimal amount,
      String calculationExpression,
      String remark) {}
}
