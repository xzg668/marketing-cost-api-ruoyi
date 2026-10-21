package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScrapPriceCalculatorTest {
  final ScrapPriceRuleCodec rules =
      new ScrapPriceRuleCodec(new ObjectMapper().findAndRegisterModules());
  final ScrapPriceCalculator calculator = new ScrapPriceCalculator(rules);

  @Test
  void explicitPerTonConversionPreservesTheExcelVatFormula() {
    var row = linked("[Cu]*0.9845/1.13");
    var input = calculator.prepare(row, snapshot("210", "CNY/TON", true, "90000"));
    assertThat(input.variables().get("Cu")).isEqualByComparingTo("90");
    assertThat(calculator.calculate(input))
        .isEqualByComparingTo(
            new BigDecimal("90")
                .multiply(new BigDecimal("0.9845"))
                .divide(new BigDecimal("1.13"), 18, RoundingMode.HALF_UP));
    assertThat(input.evidenceJson()).contains("CNY/TON", "90000", "2026-09", "2026-10");
  }

  @Test
  void missingFactorWrongUnitTaxBasisAndConsumerOrganizationCannotBeGuessed() {
    var row = linked("[Cu]*0.982/1.13");
    assertThatThrownBy(() -> calculator.prepare(row, snapshot("210", "UNKNOWN", true, "90")))
        .hasMessageContaining("单位");
    assertThatThrownBy(() -> calculator.prepare(row, snapshot("210", "CNY/KG", false, "90")))
        .hasMessageContaining("税价口径");
    assertThatThrownBy(() -> calculator.prepare(row, snapshot("220", "CNY/KG", true, "90")))
        .hasMessageContaining("范围不一致");
    var complete = snapshot("210", "CNY/KG", true, "90");
    var missing =
        new FactorPriceSnapshot(
            complete.scope(),
            3L,
            1,
            "finance.xlsx",
            "hash",
            "content",
            Map.of(),
            List.of("DEFAULT"),
            "test",
            null,
            null);
    assertThatThrownBy(() -> calculator.prepare(row, missing)).hasMessageContaining("缺少财务因素");
  }

  @Test
  void negativeAndOverflowPricesAreRejectedInsteadOfSavedAsReady() {
    for (String expression : List.of("[Cu]*-1", "[Cu]*100000000000000000000")) {
      var input = calculator.prepare(linked(expression), snapshot("210", "CNY/KG", true, "90"));
      assertThatThrownBy(() -> calculator.calculate(input)).hasMessageContaining("不能为负或超出精度");
    }
  }

  @Test
  void fixedZeroIsARealReadyValueAndDoesNotRequireFinancialInputs() {
    var row = linked("[Cu]");
    rules.write(
        new ScrapPriceRule(
            "scrap", "废料", "", "KG", null, null, "FIXED", BigDecimal.ZERO, null, null, Map.of()),
        row);
    var input = calculator.prepare(row, (FactorPriceSnapshot) null);
    assertThat(calculator.calculate(input)).isEqualByComparingTo("0");
    assertThat(input.factors()).isNull();
  }

  @Test
  void inputFingerprintIncludesRuleRevisionAndSelectedFinancialRevision() {
    var row = linked("[Cu]*0.982/1.13");
    var first = snapshot("210", "CNY/KG", true, "90");
    String fingerprint = calculator.prepare(row, first).fingerprint();
    row.setRuleRevision(2);
    assertThat(calculator.prepare(row, first).fingerprint()).isNotEqualTo(fingerprint);
    row.setRuleRevision(1);
    var revised =
        new FactorPriceSnapshot(
            first.scope(),
            4L,
            2,
            first.fileName(),
            first.fileSha256(),
            first.contentHash(),
            first.factors(),
            first.schemeCodes(),
            first.publishedBy(),
            first.publishedAt(),
            first.publicationNote());
    assertThat(calculator.prepare(row, revised).fingerprint()).isNotEqualTo(fingerprint);
  }

  private ScrapPrice linked(String expression) {
    var row = new ScrapPrice();
    row.setId(1L);
    row.setBusinessUnitType("COMMERCIAL");
    row.setPriceMonth("2026-10");
    row.setRuleSourceMonth("2026-10");
    row.setRuleRevision(1);
    rules.write(
        new ScrapPriceRule(
            "scrap",
            "废料",
            "",
            "KG",
            null,
            null,
            "LINKED",
            null,
            expression,
            expression,
            Map.of("Cu", "AVG|1#CU")),
        row);
    return row;
  }

  private FactorPriceSnapshot snapshot(String org, String unit, boolean tax, String price) {
    var scope =
        new FactorPriceScope(
            "COMMERCIAL",
            org,
            "2026-09",
            "DEFAULT",
            org.equals("220")
                ? FactorPriceScope.Condition.NON_TENDER
                : FactorPriceScope.Condition.GENERAL);
    return new FactorPriceSnapshot(
        scope,
        3L,
        1,
        "finance.xlsx",
        "hash",
        "content",
        Map.of(
            "AVG|1#CU",
            new FactorPriceSnapshot.FactorValue(
                1L, 2L, "铜", "Cu", new BigDecimal(price), unit, tax, List.of())),
        List.of("DEFAULT"),
        "test",
        null,
        null);
  }
}
