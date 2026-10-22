package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.service.pricing.settlement.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SettlementPriceCalculatorTest {
  private final SettlementPriceCalculator calculator = new SettlementPriceCalculator();

  @Test void finalWorkbookRoundsOnlyAfterUsingCommercialBasesAndNeverFallsBackToHouseholdGivenPrice() {
    var row = rule();
    row.setFormulaVersion("SETTLEMENT_CU_ZN_ROUND4_V2");
    row.setExpression("ROUND(" + SettlementFormula.EXPRESSION + ", 4)");
    row.setGivenPrice(new BigDecimal("4.8648"));
    row.setWeightsConfirmed(true);
    row.setCopperWeightG(new BigDecimal("7.6384"));
    row.setZincWeightG(new BigDecimal("0.3306"));
    assertThat(calculator.calculate(row, base("90", "21.6362912421045")).unitPrice()).isEqualByComparingTo("4.7146");
    assertThat(calculator.calculate(row, base("108.352", "25.597")).unitPrice()).isEqualByComparingTo("4.8648");
    row.setWeightsConfirmed(false);
    assertCode(() -> calculator.calculate(row, base("90", "21.6362912421045")), "SETTLEMENT_WEIGHTS_MISSING");
  }

  @Test void suppliedPricesAreValidWithoutAnyBaseWhenFixedOrWeightsAreUnconfirmed() {
    var row = rule();
    var given = calculator.calculate(row, null);
    assertThat(given.mode()).isEqualTo(SettlementPriceMode.LINKED_GIVEN);
    assertThat(given.unitPrice()).isEqualByComparingTo("4.8410");
    row.setPriceType(SettlementPriceType.SETTLE_FIXED);
    row.setGivenPrice(new BigDecimal("4.1658"));
    assertThat(calculator.calculate(row, null).mode()).isEqualTo(SettlementPriceMode.FIXED_GIVEN);
    assertThat(calculator.calculate(row, null).unitPrice()).isEqualByComparingTo("4.1658");
  }

  @Test void confirmedWeightsUseExactFormulaWithOnlyFinalPriceRounding() {
    var row = rule();
    row.setWeightsConfirmed(true);
    row.setCopperWeightG(new BigDecimal("7.8"));
    row.setZincWeightG(new BigDecimal("1.48"));
    var value = calculator.calculate(row, base("104.526", "24.51"));
    assertThat(value.mode()).isEqualTo(SettlementPriceMode.LINKED_FORMULA);
    assertThat(value.unitPrice()).isEqualByComparingTo("4.841024707965");
  }

  @Test void eitherMissingWeightOrAnUnconfirmedCompletePairUsesTheGivenPrice() {
    for (int state = 0; state < 4; state++) {
      var row = rule();
      row.setWeightsConfirmed(state < 3);
      row.setCopperWeightG(state == 0 || state == 2 ? null : new BigDecimal("7.8"));
      row.setZincWeightG(state == 1 || state == 2 ? null : new BigDecimal("1.48"));
      assertThat(calculator.calculate(row, null).mode()).isEqualTo(SettlementPriceMode.LINKED_GIVEN);
      assertThat(calculator.calculate(row, base("999", "999")).unitPrice()).isEqualByComparingTo("4.8410");
    }
  }

  @Test void confirmedZeroWeightsAreRealInputsAndDoNotGetReplacedByOriginalSampleWeights() {
    var row = rule();
    row.setWeightsConfirmed(true);
    row.setOriginalCopperWeightG(new BigDecimal("7.8"));
    row.setOriginalZincWeightG(new BigDecimal("1.48"));
    row.setCopperWeightG(BigDecimal.ZERO);
    row.setZincWeightG(BigDecimal.ZERO);
    assertThat(calculator.calculate(row, base("999", "999")).unitPrice()).isEqualByComparingTo("4.617360000000");
    assertThat(calculator.calculate(row, base("999", "999")).mode()).isEqualTo(SettlementPriceMode.LINKED_FORMULA);
  }

  @Test void missingGivenMissingBaseAndMalformedFormulaAreDifferentBlockingFailures() {
    var row = rule();
    row.setGivenPrice(null);
    assertCode(() -> calculator.calculate(row, null), "SETTLEMENT_GIVEN_PRICE_MISSING");
    row.setGivenPrice(new BigDecimal("4.841"));
    row.setWeightsConfirmed(true);
    row.setCopperWeightG(new BigDecimal("7.8"));
    row.setZincWeightG(new BigDecimal("1.48"));
    assertCode(() -> calculator.calculate(row, null), "SETTLEMENT_BASE_MISSING");
    row.setExpression("givenPrice * 2");
    assertCode(() -> calculator.calculate(row, base("104.526", "24.51")), "SETTLEMENT_FORMULA_INVALID");
    row.setExpression(SettlementFormula.EXPRESSION);
    row.setTaxDivisor(BigDecimal.ZERO);
    assertCode(() -> calculator.calculate(row, base("104.526", "24.51")), "SETTLEMENT_INPUT_INVALID");
  }

  @Test void currentBaseDoesNotMutateBudgetsAndNegativeResultsAreRejected() {
    var row = rule();
    row.setWeightsConfirmed(true);
    row.setCopperWeightG(new BigDecimal("7.8"));
    row.setZincWeightG(new BigDecimal("1.48"));
    assertThat(calculator.calculate(row, base("110", "26")).unitPrice()).isEqualByComparingTo("4.888708672566");
    assertThat(row.getBudgetCu()).isEqualByComparingTo("78");
    assertThat(row.getBudgetZn()).isEqualByComparingTo("22");
    row.setPlannedPrice(BigDecimal.ZERO);
    assertCode(() -> calculator.calculate(row, base("0", "0")), "SETTLEMENT_INPUT_INVALID");
  }

  private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
    assertThatThrownBy(action).isInstanceOfSatisfying(SettlementPriceCalculator.CalculationException.class,
        error -> assertThat(error.code()).isEqualTo(code));
  }

  static SettlementPriceRecord rule() {
    var row = new SettlementPriceRecord();
    row.setBusinessUnitType("COMMERCIAL");
    row.setMaterialCode("203240646");
    row.setPriceType(SettlementPriceType.SETTLE_LINKED);
    row.setGivenPrice(new BigDecimal("4.8410"));
    row.setPlannedPrice(new BigDecimal("3.8478"));
    row.setMarkupRatio(new BigDecimal("1.2"));
    row.setBudgetCu(new BigDecimal("78"));
    row.setBudgetZn(new BigDecimal("22"));
    row.setTaxDivisor(new BigDecimal("1.13"));
    row.setExpression(SettlementFormula.EXPRESSION);
    row.setFormulaVersion(SettlementFormula.VERSION);
    return row;
  }

  static SettlementBasePrice base(String cu, String zn) {
    return new SettlementBasePrice(1, "COMMERCIAL", "2026-10", 1, true, new BigDecimal(cu), new BigDecimal(zn),
        "CNY/KG", "BEFORE_FORMULA_TAX", "FINANCE", null, "test", "finance", java.time.LocalDateTime.of(2026, 10, 1, 0, 0));
  }
}
