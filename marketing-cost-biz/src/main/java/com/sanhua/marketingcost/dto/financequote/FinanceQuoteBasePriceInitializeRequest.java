package com.sanhua.marketingcost.dto.financequote;

import java.math.BigDecimal;

/** 财务 Cu 基准按月份范围初始化请求；价格单位为元/吨。 */
public record FinanceQuoteBasePriceInitializeRequest(
    String startMonth,
    String endMonth,
    BigDecimal pricePerTon) {
}
