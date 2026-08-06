package com.sanhua.marketingcost.dto;

/** 类型 2 公式转换阻断原因。 */
public record PriceLinkedType2FormulaError(
    String code,
    String message,
    String sheetName,
    String cellRef) {
}
