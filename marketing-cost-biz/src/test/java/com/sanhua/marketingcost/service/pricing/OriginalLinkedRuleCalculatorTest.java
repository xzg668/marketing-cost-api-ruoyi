package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.Address;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.CompiledFormula;
import com.sanhua.marketingcost.service.impl.OriginalLinkedRuleCalculator;
import com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser.FactorInput;
import com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser.Rule;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OriginalLinkedRuleCalculatorTest {
  private final OriginalLinkedRuleCalculator calculator = new OriginalLinkedRuleCalculator();

  @Test
  void financialAndCustomerUseOneRuleWithOnlyCopperChangedAndNoSecondTaxDivision() {
    var context = PriceFactorContextTest.financial();
    var rule = rule("ROUND([cu]*0.1+[zn],4)/1.13", false);
    var result = calculator.calculatePair(rule, context, new BigDecimal("100000"));
    assertThat(result.financial().priceExcludingTax())
        .isEqualByComparingTo(
            new BigDecimal("30.636").divide(new BigDecimal("1.13"), MathContext.DECIMAL128));
    assertThat(result.customer().priceExcludingTax())
        .isEqualByComparingTo(
            new BigDecimal("31.636").divide(new BigDecimal("1.13"), MathContext.DECIMAL128));
    assertThat(result.financial().inputs().get("zn"))
        .isEqualByComparingTo(result.customer().inputs().get("zn"));
    assertThat(result.financial().inputs().get("cu")).isEqualByComparingTo("90");
    assertThat(result.customer().inputs().get("cu")).isEqualByComparingTo("100");
    assertThat(context.financial().required("AVG|1#CU").price()).isEqualByComparingTo("90");
    assertThat(rule.compiled().sourceInputs().get("cu")).isEqualByComparingTo("109.114");
  }

  @Test
  void taxIncludedOutputIsConvertedOnceAndSameCopperHasZeroDifference() {
    var result =
        calculator.calculatePair(
            rule("ROUND([cu]*0.1+[zn],4)", true),
            PriceFactorContextTest.financial(),
            new BigDecimal("90000"));
    assertThat(result.financial().sourceResult()).isEqualByComparingTo("30.636");
    assertThat(result.financial().priceExcludingTax())
        .isEqualByComparingTo(result.customer().priceExcludingTax());
    assertThat(result.financial().priceExcludingTax())
        .isEqualByComparingTo(
            new BigDecimal("30.636").divide(new BigDecimal("1.13"), MathContext.DECIMAL128));
  }

  @Test
  void missingFactorAndMismatchedScopeFailWithMaterialAndCellRatherThanUsingOriginalInput() {
    var original = rule("[cu]+[zn]", false);
    var missing =
        new Rule(
            original.identity(),
            original.materialName(),
            original.sheet(),
            original.row(),
            original.cell(),
            original.sourceFormula(),
            original.sourceFormulaText(),
            original.numberFormat(),
            original.taxIncluded(),
            original.compiled(),
            Map.of(
                "cu",
                new FactorInput(
                    "AVG|MISSING",
                    new Address("因素", "E2"),
                    FactorPriceUnit.CNY_KG,
                    "公斤",
                    "AVG|MISSING",
                    "SAME_CANONICAL_FACTOR")),
            original.originalPrice());
    assertThatThrownBy(() -> calculator.calculate(missing, PriceFactorContextTest.financial()))
        .hasMessageContainingAll("MAT-01", "ImportData!O2", "AVG|MISSING");
    var financial = PriceFactorContextTest.financial();
    assertThatThrownBy(
            () ->
                calculator.calculate(
                    original,
                    new PriceFactorContext(
                        "OTHER", financial.months(), financial.financial(), null)))
        .hasMessageContaining("不一致");
  }

  private Rule rule(String expression, boolean taxIncluded) {
    return new Rule(
        new MaterialPriceRuleKey("COMMERCIAL", "210", "MAT-01", "SUP", "供应商", "SPEC", "只"),
        "试验部件",
        "ImportData",
        2,
        "O2",
        expression,
        "原稿中文说明",
        "0.0000",
        taxIncluded,
        new CompiledFormula(
            expression,
            List.of(),
            Map.of("cu", new BigDecimal("109.114"), "zn", new BigDecimal("26"))),
        Map.of(
            "cu",
            new FactorInput(
                "AVG|1#CU",
                new Address("因素", "E2"),
                FactorPriceUnit.CNY_KG,
                "公斤",
                "AVG|1#CU",
                "SAME_CANONICAL_FACTOR"),
            "zn",
            new FactorInput(
                "AVG|1#ZN",
                new Address("因素", "E3"),
                FactorPriceUnit.CNY_KG,
                "公斤",
                "AVG|1#ZN",
                "SAME_CANONICAL_FACTOR")),
        BigDecimal.ONE);
  }
}
