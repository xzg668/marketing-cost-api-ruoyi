package com.sanhua.marketingcost.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ManufactureRateMatchSupportTest {

  @Test
  @DisplayName("型号级匹配键同时包含事业部和型号")
  void buildsDivisionScopedModelKey() {
    assertThat(
            ManufactureRateMatchSupport.divisionModelKey(
                " 电子产品事业部 ", " FQ-A20110-000001 "))
        .isEqualTo("电子产品事业部::FQ-A20110-000001");
  }

  @Test
  @DisplayName("事业部或型号为空时不生成型号级匹配键")
  void rejectsIncompleteModelKey() {
    assertThat(ManufactureRateMatchSupport.divisionModelKey(null, "MODEL-A")).isNull();
    assertThat(ManufactureRateMatchSupport.divisionModelKey("电子产品事业部", " ")).isNull();
  }
}
