package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.mapper.CostBusinessRuleMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CostBusinessRuleProviderImplTest {

  @Test
  void prefersEffectiveBusinessUnitRule() {
    CostBusinessRuleMapper mapper = mock(CostBusinessRuleMapper.class);
    when(mapper.selectEffectiveDecimal("PACKAGE_COMPONENT_COEFFICIENT", "2026-08", "COMMERCIAL"))
        .thenReturn(new BigDecimal("1.06000000"));
    CostBusinessRuleProviderImpl provider = new CostBusinessRuleProviderImpl(mapper);

    assertThat(provider.decimalValue(
            " PACKAGE_COMPONENT_COEFFICIENT ", "2026-08", " COMMERCIAL ",
            new BigDecimal("1.05")))
        .isEqualByComparingTo("1.06");
    verify(mapper)
        .selectEffectiveDecimal("PACKAGE_COMPONENT_COEFFICIENT", "2026-08", "COMMERCIAL");
  }

  @Test
  void usesExplicitFallbackAndRejectsMissingRequiredRule() {
    CostBusinessRuleMapper mapper = mock(CostBusinessRuleMapper.class);
    CostBusinessRuleProviderImpl provider = new CostBusinessRuleProviderImpl(mapper);

    assertThat(provider.decimalValue("RULE", "2026-08", null, new BigDecimal("1.05")))
        .isEqualByComparingTo("1.05");
    assertThatThrownBy(() -> provider.decimalValue("RULE", "2026-08", null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未配置成本业务规则");
  }
}
