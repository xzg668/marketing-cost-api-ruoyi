package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.DepartmentFundRate;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DepartmentFundRateCalculationModeTest {

  @Test
  void finalQuoteModeUsesQuoteRatioWithoutApplyingUpliftAgain() {
    DepartmentFundRate rate = rate("0.0556204944405979", "1.05");
    rate.setRateCalculationMode(DepartmentFundRate.RATE_CALCULATION_MODE_FINAL_QUOTE);

    assertThat(CostRunCostItemServiceImpl.resolveDepartmentEffectiveRate(rate))
        .isEqualByComparingTo("0.0556204944405979");
  }

  @Test
  void legacyModeKeepsExistingPlanTimesUpliftBehavior() {
    DepartmentFundRate rate = rate("0.053", "1.05");
    rate.setRateCalculationMode(DepartmentFundRate.RATE_CALCULATION_MODE_PLAN_UPLIFT);

    assertThat(CostRunCostItemServiceImpl.resolveDepartmentEffectiveRate(rate))
        .isEqualByComparingTo("0.05565");
  }

  @Test
  void missingModeFallsBackToLegacyBehaviorForBackwardCompatibility() {
    DepartmentFundRate rate = rate("0.053", "1.05");

    assertThat(CostRunCostItemServiceImpl.resolveDepartmentEffectiveRate(rate))
        .isEqualByComparingTo("0.05565");
  }

  private static DepartmentFundRate rate(String quoteRatio, String upliftRatio) {
    DepartmentFundRate rate = new DepartmentFundRate();
    rate.setQuoteRatio(new BigDecimal(quoteRatio));
    rate.setUpliftRatio(new BigDecimal(upliftRatio));
    return rate;
  }
}
