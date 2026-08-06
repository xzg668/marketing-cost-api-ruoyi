package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PriceLinkedItem;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** PLI2-10 编排器传给导入依据写入服务的单个类型 2 公式版本。 */
public final class PriceLinkedImportBasisSaveRequest {

  private final PriceLinkedItem candidateVersion;
  private final Long sourceUploadBatchId;
  private final PriceLinkedType2MergedRow mergedRow;
  private final PriceLinkedType2FormulaConversionResult formulaConversion;
  private final PriceLinkedType2TaxNormalizationResult taxNormalization;
  private final PriceLinkedType2PriceReconcileResult priceReconcile;
  private final LocalDate effectiveDate;
  private final Map<Long, Long> factorMonthlyPriceIds;

  public PriceLinkedImportBasisSaveRequest(
      PriceLinkedItem candidateVersion,
      Long sourceUploadBatchId,
      PriceLinkedType2MergedRow mergedRow,
      PriceLinkedType2FormulaConversionResult formulaConversion,
      PriceLinkedType2TaxNormalizationResult taxNormalization,
      PriceLinkedType2PriceReconcileResult priceReconcile,
      LocalDate effectiveDate,
      Map<Long, Long> factorMonthlyPriceIds) {
    this.candidateVersion = candidateVersion;
    this.sourceUploadBatchId = sourceUploadBatchId;
    this.mergedRow = mergedRow;
    this.formulaConversion = formulaConversion;
    this.taxNormalization = taxNormalization;
    this.priceReconcile = priceReconcile;
    this.effectiveDate = effectiveDate;
    this.factorMonthlyPriceIds = Map.copyOf(
        factorMonthlyPriceIds == null
            ? Map.of()
            : new LinkedHashMap<>(factorMonthlyPriceIds));
  }

  public PriceLinkedItem getCandidateVersion() {
    return candidateVersion;
  }

  public Long getSourceUploadBatchId() {
    return sourceUploadBatchId;
  }

  public PriceLinkedType2MergedRow getMergedRow() {
    return mergedRow;
  }

  public PriceLinkedType2FormulaConversionResult getFormulaConversion() {
    return formulaConversion;
  }

  public PriceLinkedType2TaxNormalizationResult getTaxNormalization() {
    return taxNormalization;
  }

  public PriceLinkedType2PriceReconcileResult getPriceReconcile() {
    return priceReconcile;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public Map<Long, Long> getFactorMonthlyPriceIds() {
    return factorMonthlyPriceIds;
  }
}
