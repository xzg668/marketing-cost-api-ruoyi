package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

/** 类型 2 单个税口径的系统价格与 Excel 快照对账结果。 */
public record PriceLinkedType2PriceComparison(
    String priceType,
    BigDecimal systemPrice,
    BigDecimal excelPrice,
    BigDecimal absoluteDifference,
    BigDecimal tolerance,
    boolean compared,
    boolean passed) {
}
