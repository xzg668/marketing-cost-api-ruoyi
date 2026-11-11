package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductPartCostRuleTest {
  static ProductPartCostRule rule(long id, String code, String model, String rate) {
    return new ProductPartCostRule(id, 1, "COMMERCIAL", "P", "成品", "规格", "型号", code,
        "零件", model, "采购件", new BigDecimal(rate));
  }
  @Test void codeWinsEvenWhenAnotherModelRuleHasADifferentRate() {
    var coded = rule(1, "00123", "X", ".1"); var modeled = rule(2, null, "M", ".2");
    assertThat(ProductPartCostRule.match(List.of(modeled, coded), "00123", "M")).isEqualTo(coded);
    assertThat(ProductPartCostRule.match(List.of(coded, modeled), "NOT-FOUND", "M")).isEqualTo(modeled);
    assertThat(ProductPartCostRule.match(List.of(coded), "123", "M")).isNull();
    assertThat(ProductPartCostRule.match(List.of(coded), null, null)).isNull();
  }
  @Test void ambiguousModelsCannotSilentlyPickAnyRowButExactCodesRemainUsable() {
    var first = rule(1, "A", "M", ".1"); var second = rule(2, "B", "M", ".2");
    assertThat(ProductPartCostRule.match(List.of(first, second), "A", "M")).isEqualTo(first);
    assertThatThrownBy(() -> ProductPartCostRule.match(List.of(first, second), "C", "M")).hasMessageContaining("不唯一");
  }
  @Test void zeroIsValidWhileNegativeOrOneAreNot() {
    assertThat(rule(1, "A", null, "0").manufactureRate()).isZero();
    assertThatThrownBy(() -> rule(1, "A", null, "1")).hasMessageContaining("小于100%");
    assertThatThrownBy(() -> rule(1, "A", null, "-.1")).hasMessageContaining("大于等于0");
  }
}
