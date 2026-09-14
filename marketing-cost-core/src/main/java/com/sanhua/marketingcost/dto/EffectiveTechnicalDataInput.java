package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Immutable, reviewed technical-data input selected for one costing object and month. */
public record EffectiveTechnicalDataInput(
    Long productId,
    Long versionId,
    Integer versionNo,
    String accountingMonth,
    String sourceType,
    String contentFingerprint,
    LocalDateTime retrievedAt,
    boolean packageRequired,
    boolean auxiliaryRequired,
    boolean salaryRequired,
    BigDecimal packageTotalAmount,
    BigDecimal auxiliaryTotalAmount,
    BigDecimal salaryTotalAmount,
    List<PackageLine> packageItems,
    List<AuxiliaryLine> auxiliaryItems,
    List<SalaryLine> salaryItems) {

  public static final String SOURCE_EFFECTIVE_VERSION = "QUOTE_TECH_EFFECTIVE_VERSION";

  public EffectiveTechnicalDataInput {
    packageItems = packageItems == null ? List.of() : List.copyOf(packageItems);
    auxiliaryItems = auxiliaryItems == null ? List.of() : List.copyOf(auxiliaryItems);
    salaryItems = salaryItems == null ? List.of() : List.copyOf(salaryItems);
  }

  public record PackageLine(
      Long id,
      Integer lineNo,
      String materialNo,
      String name,
      BigDecimal quantity,
      String unit,
      BigDecimal unitPrice,
      BigDecimal amount) {}

  public record AuxiliaryLine(
      Long id,
      Integer lineNo,
      String subjectCode,
      String subjectName,
      String name,
      BigDecimal standardQuantity,
      String standardUnit,
      BigDecimal unitPrice,
      BigDecimal lossRate,
      BigDecimal amount) {}

  public record SalaryLine(
      Long id,
      Integer lineNo,
      String processCode,
      String processName,
      String laborType,
      BigDecimal standardHours,
      BigDecimal hourlyRate,
      BigDecimal personCoefficient,
      BigDecimal amount) {}
}
