package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.PriceLinkedType2FormulaReferenceType;
import java.math.BigDecimal;

/** 一个已分类的公式引用及其转换依据。 */
public record PriceLinkedType2FormulaReference(
    String rawReference,
    String sheetName,
    String cellRef,
    String header,
    String unit,
    BigDecimal numericValue,
    String sourceCellFormula,
    PriceLinkedType2FormulaReferenceType referenceType,
    Long factorIdentityId,
    String factorShortName,
    String replacement) {
}
