package com.sanhua.marketingcost.dto;

import java.util.List;

/** 单条类型 2 公式的引用分类结果。 */
public final class PriceLinkedType2FormulaReferenceClassification {

  private final List<PriceLinkedType2FormulaReference> references;
  private final List<PriceLinkedType2FormulaError> errors;

  public PriceLinkedType2FormulaReferenceClassification(
      List<PriceLinkedType2FormulaReference> references,
      List<PriceLinkedType2FormulaError> errors) {
    this.references = List.copyOf(references);
    this.errors = List.copyOf(errors);
  }

  public List<PriceLinkedType2FormulaReference> getReferences() {
    return references;
  }

  public List<PriceLinkedType2FormulaError> getErrors() {
    return errors;
  }

  public boolean isSuccess() {
    return errors.isEmpty();
  }
}
