package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

/** 类型 2 公式版本的不可变导入依据快照，序列化到 source_input_snapshot_json。 */
public record PriceLinkedImportBasisSnapshot(
    String sourceFormula,
    List<InputCell> inputCells,
    List<FactorInput> factorInputs,
    TaxBasis taxBasis,
    ReconcileBasis reconcileBasis) {

  public PriceLinkedImportBasisSnapshot {
    inputCells = List.copyOf(inputCells);
    factorInputs = List.copyOf(factorInputs);
  }

  public record InputCell(
      String sheetName,
      String cellRef,
      String header,
      String displayValue,
      BigDecimal numericValue,
      BigDecimal calculationValue,
      boolean blankDefaultedToZero,
      String sourceCellFormula,
      String unit,
      String sourceCellType) {
  }

  public record FactorInput(
      String rawReference,
      String originalName,
      String sheetName,
      String cellRef,
      Long factorIdentityId,
      BigDecimal importedPrice,
      String systemVariable) {
  }

  public record TaxBasis(
      String rawTaxIncludedText,
      Integer originalTaxIncluded,
      Integer normalizedTaxIncluded,
      boolean finalVatDivisorStripped,
      boolean taxAdjustmentRequired,
      List<PriceLinkedType2TaxIssue> warnings) {

    public TaxBasis {
      warnings = List.copyOf(warnings);
    }
  }

  public record PriceDifference(
      String priceType,
      BigDecimal systemPrice,
      BigDecimal excelPrice,
      BigDecimal absoluteDifference,
      BigDecimal tolerance,
      boolean compared,
      boolean passed) {
  }

  public record ReconcileBasis(
      BigDecimal formulaResult,
      BigDecimal finalPrice,
      BigDecimal vatRate,
      BigDecimal tolerance,
      PriceDifference taxIncluded,
      PriceDifference taxExcluded) {
  }
}
