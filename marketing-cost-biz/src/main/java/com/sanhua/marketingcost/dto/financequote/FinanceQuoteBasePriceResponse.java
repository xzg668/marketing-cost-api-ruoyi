package com.sanhua.marketingcost.dto.financequote;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 财务 Cu 基准查询结果，同时返回数据库口径和页面口径价格。 */
public record FinanceQuoteBasePriceResponse(
    Long id,
    String priceMonth,
    String factorCode,
    String priceSource,
    BigDecimal pricePerKg,
    BigDecimal pricePerTon,
    String unit,
    String businessUnitType,
    LocalDateTime updatedAt,
    String lastModifiedBy,
    String lastChangeReason,
    LocalDateTime lastModifiedAt) {
}
