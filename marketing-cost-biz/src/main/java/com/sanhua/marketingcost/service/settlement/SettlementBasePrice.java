package com.sanhua.marketingcost.service.settlement;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.sanhua.marketingcost.service.pricing.*;

/** 计算使用的不可变铜锌依据；新依据来自商用财务发布，旧结构仅供已冻结底稿还原。 */
public record SettlementBasePrice(
    long id, String businessUnitType, String priceMonth, int revisionNo, boolean active,
    BigDecimal copperPrice, BigDecimal zincPrice, String unit, String taxBasis,
    String sourceKind, String sourceFileSha256, String changeReason, String createdBy, LocalDateTime createdAt,
    FactorPriceSnapshot financial) {
  public static final String ZINC = "AVG|1#ZN";

  public SettlementBasePrice(long id, String businessUnitType, String priceMonth, int revisionNo, boolean active,
      BigDecimal copperPrice, BigDecimal zincPrice, String unit, String taxBasis, String sourceKind,
      String sourceFileSha256, String changeReason, String createdBy, LocalDateTime createdAt) {
    this(id, businessUnitType, priceMonth, revisionNo, active, copperPrice, zincPrice, unit, taxBasis,
        sourceKind, sourceFileSha256, changeReason, createdBy, createdAt, null);
  }

  public static SettlementBasePrice fromFinancial(FactorPriceSnapshot snapshot) {
    if (!"210".equals(snapshot.scope().priceOrgCode()) || snapshot.scope().condition() != FactorPriceScope.Condition.GENERAL
        || snapshot.batchId() == null || snapshot.batchId() <= 0 || snapshot.revision() == null || snapshot.revision() < 1)
      throw new IllegalArgumentException("结算铜锌必须引用商用组织210的完整财务发布");
    for (String key : java.util.List.of(PriceFactorContext.COPPER, ZINC)) {
      var value = snapshot.required(key);
      if (value.monthlyPriceId() == null || value.monthlyPriceId() <= 0 || value.sources().isEmpty())
        throw new IllegalArgumentException("商用财务铜锌来源不完整：" + key);
    }
    var context = new PriceFactorContext("SETTLEMENT", new PriceMonthSelection(snapshot.scope().priceMonth(),
        snapshot.scope().priceMonth()), snapshot, null);
    var copper = context.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true);
    var zinc = context.requiredPrice(ZINC, FactorPriceUnit.CNY_KG, true);
    if (copper.signum() <= 0 || zinc.signum() < 0) throw new IllegalArgumentException("商用财务铜锌基价无效");
    return new SettlementBasePrice(snapshot.batchId(), snapshot.scope().businessUnitType(), snapshot.scope().priceMonth(),
        snapshot.revision(), true, copper, zinc, "CNY/KG", "BEFORE_FORMULA_TAX", "FINANCE_PUBLICATION",
        snapshot.fileSha256(), snapshot.publicationNote(), snapshot.publishedBy(), snapshot.publishedAt(),
        context.evidenceForFactors(java.util.Set.of(PriceFactorContext.COPPER, ZINC)).financial());
  }
}
