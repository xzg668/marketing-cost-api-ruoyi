package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class BoardPriceContextTest {
  @Test
  void customerPricesConvertExactlyOnceAndRemainIdenticalOnBothCopperSides() {
    var board = new BoardPriceInputs(new BigDecimal("20000"), new BigDecimal("32500"), true);
    var p0 = financial(true).withBoardInputs(board);
    var p1 = p0.withCustomerCopperPerTon(new BigDecimal("100000"));
    for (var context : List.of(p0, p1, p1.financialOnly())) {
      assertThat(context.requiredPrice("AVG|304/2B/1219", FactorPriceUnit.CNY_KG, true))
          .isEqualByComparingTo("20");
      assertThat(context.requiredPrice("AVG|316L/2B", FactorPriceUnit.CNY_KG, true))
          .isEqualByComparingTo("32.5");
      assertThat(context.requiredPrice("AVG|1#AG", FactorPriceUnit.CNY_KG, true))
          .isEqualByComparingTo("15772.86");
      assertThat(context.boardInputs()).isSameAs(board);
      assertThat(context.inputSource("AVG|304/2B/1219")).isEqualTo("CUSTOMER_SUS");
    }
    assertThat(p0.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("90");
    assertThat(p1.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("100");
  }

  @Test
  void eachMissingGradeFallsBackIndependentlyAndFixedPricesAreAlreadyPerKg() {
    var p0 =
        financial(true).withBoardInputs(new BoardPriceInputs(null, new BigDecimal("40000"), true));
    assertThat(p0.requiredPrice("AVG|304/2B/1219", FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("15");
    assertThat(p0.requiredPrice("AVG|316L/2B", FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("40");
    var p1 =
        financial(false)
            .withBoardInputs(new BoardPriceInputs(new BigDecimal("23000"), null, false));
    assertThat(p1.requiredPrice("AVG|304/2B/1219", FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("23");
    assertThat(p1.requiredPrice("AVG|316L/2B", FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("32.5");
    assertThat(p1.inputSource("AVG|316L/2B")).isEqualTo("FINANCE_FACTOR");
    // 客户字段单位明确：20元/吨必须是0.02元/公斤，不能套固定表的数值猜测。
    assertThat(
            financial(false)
                .withBoardInputs(new BoardPriceInputs(new BigDecimal("20"), null, false))
                .requiredPrice("AVG|304/2B/1219", FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("0.02");
  }

  @Test
  void invalidConditionScopeCustomerValueAndTaxCannotSilentlyFallBack() {
    assertThatThrownBy(() -> new BoardPriceInputs(BigDecimal.ZERO, null, false))
        .hasMessageContaining("SUS304");
    assertThatThrownBy(() -> new BoardPriceInputs(null, BigDecimal.ONE.negate(), false))
        .hasMessageContaining("SUS316");
    assertThatThrownBy(
            () -> financial(false).withBoardInputs(new BoardPriceInputs(null, null, true)))
        .hasMessageContaining("招标条件");
    assertThatThrownBy(
            () ->
                PriceFactorContextTest.financial()
                    .withBoardInputs(new BoardPriceInputs(null, null, false)))
        .hasMessageContaining("组织");
    assertThatThrownBy(
            () ->
                financial(false)
                    .withBoardInputs(new BoardPriceInputs(BigDecimal.ONE, null, false))
                    .requiredPrice("AVG|304/2B/1219", FactorPriceUnit.CNY_KG, false))
        .hasMessageContaining("含税");
  }

  static PriceFactorContext financial(boolean tender) {
    var original = PriceFactorContextTest.financial().financial();
    var values = new LinkedHashMap<>(original.factors());
    for (String name : List.of("304/2B/1219", "316L/2B"))
      values.put(
          "AVG|" + name,
          new FactorPriceSnapshot.FactorValue(
              2L,
              3L,
              name,
              name,
              new BigDecimal(name.startsWith("304") ? tender ? "15" : "20" : "32.5"),
              "CNY/KG",
              true,
              List.of()));
    var snapshot =
        new FactorPriceSnapshot(
            new FactorPriceScope(
                "COMMERCIAL",
                "220",
                "2026-10",
                "BOARD",
                tender ? FactorPriceScope.Condition.TENDER : FactorPriceScope.Condition.NON_TENDER),
            42L,
            3,
            "board.xlsx",
            "sha",
            "hash",
            values,
            List.of("BOARD"),
            "test",
            null,
            null);
    return new PriceFactorContext(
        "BOARD-MATERIAL", new PriceMonthSelection("2026-10", "2026-10"), snapshot, null);
  }
}
