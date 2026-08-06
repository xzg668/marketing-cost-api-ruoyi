package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.PriceLinkedType2FormulaReferenceType;
import java.util.List;

/** 类型 2 公式转换结果和完整只读依据。 */
public final class PriceLinkedType2FormulaConversionResult {

  private final String sourceSheetName;
  private final Integer sourceRowNumber;
  private final String sourceFormulaCellRef;
  private final String sourceFormula;
  private final String convertedFormula;
  private final boolean finalVatDivisorStripped;
  private final List<Integer> strippedRoundScales;
  private final List<PriceLinkedType2CellSnapshot> inputSnapshots;
  private final List<PriceLinkedType2FormulaReference> references;
  private final List<PriceLinkedType2FormulaError> errors;

  public PriceLinkedType2FormulaConversionResult(
      String sourceSheetName,
      Integer sourceRowNumber,
      String sourceFormulaCellRef,
      String sourceFormula,
      String convertedFormula,
      boolean finalVatDivisorStripped,
      List<Integer> strippedRoundScales,
      List<PriceLinkedType2CellSnapshot> inputSnapshots,
      List<PriceLinkedType2FormulaReference> references,
      List<PriceLinkedType2FormulaError> errors) {
    this.sourceSheetName = sourceSheetName;
    this.sourceRowNumber = sourceRowNumber;
    this.sourceFormulaCellRef = sourceFormulaCellRef;
    this.sourceFormula = sourceFormula;
    this.convertedFormula = convertedFormula;
    this.finalVatDivisorStripped = finalVatDivisorStripped;
    this.strippedRoundScales = List.copyOf(strippedRoundScales);
    this.inputSnapshots = List.copyOf(inputSnapshots);
    this.references = List.copyOf(references);
    this.errors = List.copyOf(errors);
  }

  public String getSourceSheetName() {
    return sourceSheetName;
  }

  public Integer getSourceRowNumber() {
    return sourceRowNumber;
  }

  public String getSourceFormulaCellRef() {
    return sourceFormulaCellRef;
  }

  public String getSourceFormula() {
    return sourceFormula;
  }

  public String getConvertedFormula() {
    return convertedFormula;
  }

  public boolean isFinalVatDivisorStripped() {
    return finalVatDivisorStripped;
  }

  public List<Integer> getStrippedRoundScales() {
    return strippedRoundScales;
  }

  public List<PriceLinkedType2CellSnapshot> getInputSnapshots() {
    return inputSnapshots;
  }

  public List<PriceLinkedType2FormulaReference> getReferences() {
    return references;
  }

  public List<PriceLinkedType2FormulaReference> getFactorReplacements() {
    return references.stream()
        .filter(reference ->
            reference.referenceType() == PriceLinkedType2FormulaReferenceType.FACTOR_DYNAMIC)
        .toList();
  }

  public List<PriceLinkedType2FormulaError> getErrors() {
    return errors;
  }

  public boolean isSuccess() {
    return errors.isEmpty() && convertedFormula != null && !convertedFormula.isBlank();
  }
}
