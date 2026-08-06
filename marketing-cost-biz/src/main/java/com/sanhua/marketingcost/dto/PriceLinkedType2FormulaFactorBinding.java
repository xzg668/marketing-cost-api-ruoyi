package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

/** 公式中某个因素价格单元格与统一主身份的绑定。 */
public record PriceLinkedType2FormulaFactorBinding(
    String sheetName,
    String cellRef,
    String shortName,
    Long factorIdentityId,
    BigDecimal importedPrice) {
}
