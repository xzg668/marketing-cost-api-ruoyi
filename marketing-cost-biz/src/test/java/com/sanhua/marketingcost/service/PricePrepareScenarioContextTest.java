package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PricePrepareScenarioContextTest {

  @Test
  @DisplayName("未传场景时默认OA_LOCKED并使用空覆盖")
  void defaultsToOaLocked() {
    PricePrepareScenarioContext context =
        new PricePrepareScenarioContext(null, null, null, null);

    assertThat(context.scenarioType()).isEqualTo(QuotePriceScenarioType.OA_LOCKED);
    assertThat(context.scenarioGroupNo()).isNull();
    assertThat(context.sourcePrepareNo()).isNull();
    assertThat(context.variableOverrides()).isEmpty();
  }

  @Test
  @DisplayName("财务场景上下文清理追溯字段并冻结变量覆盖")
  void financeContextIsNormalizedAndImmutable() {
    Map<String, BigDecimal> overrides = new LinkedHashMap<>();
    overrides.put(" Cu ", new BigDecimal("90.000000"));
    PricePrepareScenarioContext context = new PricePrepareScenarioContext(
        QuotePriceScenarioType.FINANCE_QUOTE_BASE,
        " GROUP-001 ",
        " PPR-OA-001 ",
        overrides);

    assertThat(context.scenarioType()).isEqualTo(QuotePriceScenarioType.FINANCE_QUOTE_BASE);
    assertThat(context.scenarioGroupNo()).isEqualTo("GROUP-001");
    assertThat(context.sourcePrepareNo()).isEqualTo("PPR-OA-001");
    assertThat(context.variableOverrides()).containsEntry("Cu", new BigDecimal("90.000000"));
    assertThatThrownBy(() -> context.variableOverrides().put("Al", BigDecimal.ONE))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("财务场景只允许一个正数Cu覆盖且必须引用OA批次")
  void financeContextAllowsOnlyCuAndRequiresSource() {
    assertThatThrownBy(() -> new PricePrepareScenarioContext(
        QuotePriceScenarioType.FINANCE_QUOTE_BASE,
        "GROUP-1",
        "PPR-OA-1",
        Map.of("Cu", new BigDecimal("90"), "Zn", new BigDecimal("21"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("只允许覆盖", "Cu");
    assertThatThrownBy(() -> new PricePrepareScenarioContext(
        QuotePriceScenarioType.FINANCE_QUOTE_BASE,
        "GROUP-1",
        null,
        Map.of("Cu", new BigDecimal("90"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sourcePrepareNo");
  }

  @Test
  @DisplayName("变量覆盖拒绝空编码、空值和清理后重复编码")
  void overrideIdentityMustBeUnambiguous() {
    assertThatThrownBy(() -> new PricePrepareScenarioContext(
        QuotePriceScenarioType.OA_LOCKED, null, null, Map.of(" ", BigDecimal.ONE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("变量编码不能为空");

    Map<String, BigDecimal> nullValue = new LinkedHashMap<>();
    nullValue.put("Cu", null);
    assertThatThrownBy(() -> new PricePrepareScenarioContext(
        QuotePriceScenarioType.OA_LOCKED, null, null, nullValue))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Cu", "不能为空");

    Map<String, BigDecimal> duplicate = new LinkedHashMap<>();
    duplicate.put("Cu", BigDecimal.ONE);
    duplicate.put(" Cu ", BigDecimal.TEN);
    assertThatThrownBy(() -> new PricePrepareScenarioContext(
        QuotePriceScenarioType.OA_LOCKED, null, null, duplicate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复");
  }
}
