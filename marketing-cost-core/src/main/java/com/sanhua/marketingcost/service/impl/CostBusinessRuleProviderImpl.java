package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.mapper.CostBusinessRuleMapper;
import com.sanhua.marketingcost.service.CostBusinessRuleProvider;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CostBusinessRuleProviderImpl implements CostBusinessRuleProvider {

  private final CostBusinessRuleMapper mapper;

  public CostBusinessRuleProviderImpl(CostBusinessRuleMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public BigDecimal decimalValue(
      String ruleCode,
      String pricingMonth,
      String businessUnitType,
      BigDecimal fallbackValue) {
    if (!StringUtils.hasText(ruleCode)) {
      throw new IllegalArgumentException("ruleCode 不能为空");
    }
    String month = StringUtils.hasText(pricingMonth)
        ? CostPricingPeriodUtils.normalizePricingMonth(pricingMonth)
        : CostPricingPeriodUtils.currentPricingMonth();
    BigDecimal configured =
        mapper.selectEffectiveDecimal(
            ruleCode.trim(), month, StringUtils.hasText(businessUnitType)
                ? businessUnitType.trim() : "*");
    if (configured != null) {
      return configured;
    }
    if (fallbackValue == null) {
      throw new IllegalStateException("未配置成本业务规则：" + ruleCode + " / " + month);
    }
    return fallbackValue;
  }
}
