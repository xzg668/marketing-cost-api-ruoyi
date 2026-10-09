package com.sanhua.marketingcost.service.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 各价格源先确定每家供应商的有效报价，再交给统一的供货比率规则。 */
public record SupplierPriceCandidate(
    String key, String supplierCode, String supplierName, BigDecimal unitPrice,
    LocalDate effectiveFrom, LocalDate effectiveTo) {}
