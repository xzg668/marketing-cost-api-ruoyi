package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.List;

/** 类型 2 公式求值、税转换及 Excel 含税/不含税价格对账结果。 */
public final class PriceLinkedType2PriceReconcileResult {

  private final BigDecimal formulaResult;
  private final BigDecimal finalPrice;
  private final BigDecimal vatRate;
  private final BigDecimal tolerance;
  private final Integer normalizedTaxIncluded;
  private final PriceLinkedType2PriceComparison taxIncludedComparison;
  private final PriceLinkedType2PriceComparison taxExcludedComparison;
  private final List<PriceLinkedType2TaxIssue> warnings;
  private final List<PriceLinkedType2TaxIssue> errors;

  public PriceLinkedType2PriceReconcileResult(
      BigDecimal formulaResult,
      BigDecimal finalPrice,
      BigDecimal vatRate,
      BigDecimal tolerance,
      Integer normalizedTaxIncluded,
      PriceLinkedType2PriceComparison taxIncludedComparison,
      PriceLinkedType2PriceComparison taxExcludedComparison,
      List<PriceLinkedType2TaxIssue> warnings,
      List<PriceLinkedType2TaxIssue> errors) {
    this.formulaResult = formulaResult;
    this.finalPrice = finalPrice;
    this.vatRate = vatRate;
    this.tolerance = tolerance;
    this.normalizedTaxIncluded = normalizedTaxIncluded;
    this.taxIncludedComparison = taxIncludedComparison;
    this.taxExcludedComparison = taxExcludedComparison;
    this.warnings = List.copyOf(warnings);
    this.errors = List.copyOf(errors);
  }

  public BigDecimal getFormulaResult() {
    return formulaResult;
  }

  public BigDecimal getFinalPrice() {
    return finalPrice;
  }

  public BigDecimal getVatRate() {
    return vatRate;
  }

  public BigDecimal getTolerance() {
    return tolerance;
  }

  public Integer getNormalizedTaxIncluded() {
    return normalizedTaxIncluded;
  }

  public PriceLinkedType2PriceComparison getTaxIncludedComparison() {
    return taxIncludedComparison;
  }

  public PriceLinkedType2PriceComparison getTaxExcludedComparison() {
    return taxExcludedComparison;
  }

  public List<PriceLinkedType2TaxIssue> getWarnings() {
    return warnings;
  }

  public List<PriceLinkedType2TaxIssue> getErrors() {
    return errors;
  }

  public boolean isSuccess() {
    return errors.isEmpty()
        && taxIncludedComparison != null
        && taxIncludedComparison.passed()
        && (taxExcludedComparison == null || taxExcludedComparison.passed());
  }
}
