package com.sanhua.marketingcost.dto.financequote;

import java.util.List;

/** 批量初始化结果；已存在月份只报告跳过，不做覆盖。 */
public record FinanceQuoteBasePriceInitializeResponse(
    int createdCount,
    int skippedCount,
    List<String> createdMonths,
    List<String> skippedMonths,
    List<FinanceQuoteBasePriceResponse> records) {
}
