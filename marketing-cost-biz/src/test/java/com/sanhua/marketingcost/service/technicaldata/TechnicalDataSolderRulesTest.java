package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TechnicalDataSolderRulesTest {
  @Test void classificationUsesCodeAndExcludesPasteInsteadOfGuessingFromName() {
    for (String code : java.util.List.of("181811431", "181811432", "181811434", "181811986")) assertThat(TechnicalDataSolderRules.eligible(code)).isTrue();
    for (String code : java.util.List.of("181811435", "1818", "焊丝", "")) assertThat(TechnicalDataSolderRules.eligible(code)).isFalse();
  }
  @Test void gramsConvertExactlyWithoutRoundingSmallConsumptionToZero() {
    assertThat(TechnicalDataSolderRules.toKg(new BigDecimal("0.1"), "g")).isEqualByComparingTo("0.0001");
    assertThat(TechnicalDataSolderRules.quantity(TechnicalDataSolderRules.toKg(new BigDecimal("0.00000001"), "克"))).isEqualByComparingTo("0.00000000001");
    assertThat(TechnicalDataSolderRules.quantity(null)).isNull();
    assertThatThrownBy(() -> TechnicalDataSolderRules.quantity(BigDecimal.ZERO)).hasMessageContaining("大于 0");
    assertThatThrownBy(() -> TechnicalDataSolderRules.toKg(BigDecimal.ONE, "只")).hasMessageContaining("不能换算");
  }
}
