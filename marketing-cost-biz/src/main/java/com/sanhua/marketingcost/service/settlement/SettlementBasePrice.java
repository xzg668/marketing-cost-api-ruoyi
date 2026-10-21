package com.sanhua.marketingcost.service.settlement;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 铜锌成组快照；一个 ID 永远代表同一组原始数值。 */
public record SettlementBasePrice(
    long id, String businessUnitType, String priceMonth, int revisionNo, boolean active,
    BigDecimal copperPrice, BigDecimal zincPrice, String unit, String taxBasis,
    String sourceKind, String sourceFileSha256, String changeReason, String createdBy, LocalDateTime createdAt) {}
