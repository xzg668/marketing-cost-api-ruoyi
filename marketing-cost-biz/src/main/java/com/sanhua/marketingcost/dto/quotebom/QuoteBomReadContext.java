package com.sanhua.marketingcost.dto.quotebom;

import java.time.LocalDate;

/** 带报价隔离维度的正式 BOM 读取上下文。 */
public record QuoteBomReadContext(
    String oaNo,
    Long oaFormItemId,
    String topProductCode,
    String periodMonth,
    String priceOrgCode,
    String materialOrganizationCode,
    String businessUnitType,
    String bomPurpose,
    LocalDate quoteDate) {
}
