package com.sanhua.marketingcost.controller;

import com.sanhua.marketingcost.service.settlement.SettlementBasePrice;
import java.time.LocalDateTime;

/** 维护 API 的十进制文本契约，防止浏览器编辑时损失精度；领域模型保持 BigDecimal。 */
public record SettlementBasePriceView(long id, String businessUnitType, String priceMonth, int revisionNo,
    boolean active, String copperPrice, String zincPrice, String unit, String taxBasis, String sourceKind,
    String sourceFileSha256, String changeReason, String createdBy, LocalDateTime createdAt) {
  public static SettlementBasePriceView from(SettlementBasePrice row) {
    return new SettlementBasePriceView(row.id(), row.businessUnitType(), row.priceMonth(), row.revisionNo(), row.active(),
        row.copperPrice().toPlainString(), row.zincPrice().toPlainString(), row.unit(), row.taxBasis(), row.sourceKind(),
        row.sourceFileSha256(), row.changeReason(), row.createdBy(), row.createdAt());
  }
}
