package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.impl.OriginalRangePriceRuntime.Calculation;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** 报价两侧共用的区间证据校验，不通过价格差非零来猜测是否受Cu影响。 */
public final class OriginalRangeCalculationEvidence {
  private final ObjectMapper json;

  public OriginalRangeCalculationEvidence(ObjectMapper json) {
    this.json = json;
  }

  public Calculation read(String serialized) {
    try {
      var saved = json.readValue(serialized, Calculation.class);
      if (!OriginalRangeRuleSnapshot.VERSION.equals(saved.calculatorVersion())
          || saved.ruleId() == null
          || saved.ruleRevision() == null
          || saved.sourceUploadBatchId() == null
          || saved.ruleIdentity() == null
          || saved.context() == null
          || saved.result() == null) throw new IllegalArgumentException("区间取价快照不完整");
      var key = saved.ruleIdentity();
      var scope = saved.context().financial().scope();
      if (!key.materialCode().equals(saved.context().materialCode())
          || !key.businessUnitType().equals(scope.businessUnitType())
          || !key.orgCode().equals(scope.priceOrgCode()))
        throw new IllegalArgumentException("区间取价快照范围不一致");
      var input = saved.context().requiredPrice(saved.factorKey(), saved.factorUnit(), true);
      if (input.compareTo(saved.result().factorValue()) != 0
          || !saved.result().selectedBounds().contains(input))
        throw new IllegalArgumentException("区间取价输入与命中档位不一致");
      return saved;
    } catch (Exception error) {
      throw new IllegalStateException("区间取价快照不可用：" + error.getMessage(), error);
    }
  }

  public boolean validatePair(
      String material,
      String customerJson,
      String financeJson,
      BigDecimal customerPrice,
      BigDecimal financePrice,
      BigDecimal customerCuPerKg,
      BigDecimal financeCuPerKg) {
    Calculation customer = read(customerJson), finance = read(financeJson);
    if (!Objects.equals(customer.ruleId(), finance.ruleId())
        || !Objects.equals(customer.ruleRevision(), finance.ruleRevision())
        || !Objects.equals(customer.ruleMonth(), finance.ruleMonth())
        || !Objects.equals(customer.sourceUploadBatchId(), finance.sourceUploadBatchId())
        || !customer.ruleIdentity().equals(finance.ruleIdentity())
        || !material.equals(customer.ruleIdentity().materialCode())
        || !customer.factorKey().equals(finance.factorKey())
        || customer.factorUnit() != finance.factorUnit()
        || !customer.context().financial().equals(finance.context().financial())
        || !customer.context().months().equals(finance.context().months()))
      throw new IllegalStateException("区间两侧规则/供应商/财务输入版本不一致：" + material);
    if (finance.context().customerCopperPricePerTon() != null)
      throw new IllegalStateException("财务区间场景不能覆盖客户Cu");
    requirePrice(customerPrice, customer.result().priceExcludingTax(), "客户区间单价");
    requirePrice(financePrice, finance.result().priceExcludingTax(), "财务区间单价");
    if (customerCuPerKg != null)
      requirePrice(
          customerCuPerKg,
          customer.context().requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true),
          "客户Cu");
    if (financeCuPerKg != null)
      requirePrice(
          financeCuPerKg,
          finance.context().requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true),
          "财务Cu");
    boolean copper = PriceFactorContext.COPPER.equals(customer.factorKey());
    if (!copper) requirePrice(customerPrice, financePrice, "非Cu区间单价");
    return copper;
  }

  private void requirePrice(BigDecimal actual, BigDecimal expected, String label) {
    if (actual == null
        || expected == null
        || actual
                .setScale(8, RoundingMode.HALF_UP)
                .compareTo(expected.setScale(8, RoundingMode.HALF_UP))
            != 0) throw new IllegalStateException(label + "与区间取价快照不一致");
  }
}
