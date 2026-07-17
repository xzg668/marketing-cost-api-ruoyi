package com.sanhua.marketingcost.dto.financequote;

import java.math.BigDecimal;

/** 财务 Cu 基准指定月份调整请求；价格单位为元/吨。 */
public record FinanceQuoteBasePriceAdjustRequest(
    BigDecimal pricePerTon,
    String changeReason) {
}
