package com.sanhua.marketingcost.dto;

import java.util.List;

/** 类型 2 “是否含税”与公式末尾除税规则的规范化结果。 */
public final class PriceLinkedType2TaxNormalizationResult {

  private final String rawTaxIncludedText;
  private final Integer originalTaxIncluded;
  private final Integer normalizedTaxIncluded;
  private final boolean finalVatDivisorStripped;
  private final boolean taxAdjustmentRequired;
  private final List<PriceLinkedType2TaxIssue> warnings;
  private final List<PriceLinkedType2TaxIssue> errors;

  public PriceLinkedType2TaxNormalizationResult(
      String rawTaxIncludedText,
      Integer originalTaxIncluded,
      Integer normalizedTaxIncluded,
      boolean finalVatDivisorStripped,
      boolean taxAdjustmentRequired,
      List<PriceLinkedType2TaxIssue> warnings,
      List<PriceLinkedType2TaxIssue> errors) {
    this.rawTaxIncludedText = rawTaxIncludedText;
    this.originalTaxIncluded = originalTaxIncluded;
    this.normalizedTaxIncluded = normalizedTaxIncluded;
    this.finalVatDivisorStripped = finalVatDivisorStripped;
    this.taxAdjustmentRequired = taxAdjustmentRequired;
    this.warnings = List.copyOf(warnings);
    this.errors = List.copyOf(errors);
  }

  public String getRawTaxIncludedText() {
    return rawTaxIncludedText;
  }

  public Integer getOriginalTaxIncluded() {
    return originalTaxIncluded;
  }

  public Integer getNormalizedTaxIncluded() {
    return normalizedTaxIncluded;
  }

  public boolean isFinalVatDivisorStripped() {
    return finalVatDivisorStripped;
  }

  public boolean isTaxAdjustmentRequired() {
    return taxAdjustmentRequired;
  }

  public List<PriceLinkedType2TaxIssue> getWarnings() {
    return warnings;
  }

  public List<PriceLinkedType2TaxIssue> getErrors() {
    return errors;
  }

  public boolean isSuccess() {
    return errors.isEmpty() && normalizedTaxIncluded != null;
  }
}
