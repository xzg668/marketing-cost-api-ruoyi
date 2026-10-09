package com.sanhua.marketingcost.service.pricing;

import java.time.LocalDate;

/** 确认只对本报价产品、核算日期、组织、物料和价格类型生效。 */
public record SupplierPriceScope(
    String businessUnitType, String oaNo, Long oaFormItemId, String periodMonth,
    LocalDate pricingDate, String orgCode, String materialCode, String priceType) {}
