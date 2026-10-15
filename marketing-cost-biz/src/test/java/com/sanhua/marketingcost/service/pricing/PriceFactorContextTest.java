package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PriceFactorContextTest {
  @Test
  void formulaEvidenceKeepsUsedFactorSourcesAndPublicationWithoutChangingInputs() {
    var original = financial().withCustomerCopperPerTon(new BigDecimal("100000"));
    var evidence = original.evidenceForFactors(java.util.Set.of("AVG|cu", "AVG|PCU"));
    assertThat(evidence.financial().factors()).containsOnlyKeys("AVG|1#CU", "AVG|PCU");
    assertThat(evidence.financial().batchId()).isEqualTo(original.financial().batchId());
    assertThat(evidence.financial().revision()).isEqualTo(original.financial().revision());
    assertThat(evidence.financial().contentHash()).isEqualTo(original.financial().contentHash());
    assertThat(evidence.financial().scope()).isEqualTo(original.financial().scope());
    for (String key : evidence.financial().factors().keySet()) {
      assertThat(evidence.financial().required(key)).isSameAs(original.financial().required(key));
      assertThat(evidence.requiredPrice(key, FactorPriceUnit.CNY_KG, true))
          .isEqualByComparingTo(original.requiredPrice(key, FactorPriceUnit.CNY_KG, true));
    }
    assertThat(evidence.customerCopperPricePerTon())
        .isEqualTo(original.customerCopperPricePerTon());
    assertThat(original.financial().factors()).hasSize(6);
  }

  @Test
  void nonCopperFormulaEvidenceStillProvidesTheSharedQuoteCopperBasis() {
    var original = financial();
    var evidence = original.evidenceForFactors(java.util.Set.of("AVG|1#ZN"));
    assertThat(evidence.financial().factors())
        .containsOnlyKeys("AVG|1#ZN", PriceFactorContext.COPPER);
    assertThat(evidence.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("90");
    assertThat(evidence.financial().required(PriceFactorContext.COPPER))
        .isSameAs(original.financial().required(PriceFactorContext.COPPER));
  }

  @Test
  void customerCopperUsesSameFinancialSnapshotAndChangesOnlyCopper() {
    var financial = financial();
    var customer = financial.withCustomerCopperPerTon(new BigDecimal("100000"));
    assertThat(customer.financial()).isSameAs(financial.financial());
    assertThat(customer.months()).isSameAs(financial.months());
    assertThat(financial.requiredPrice("AVG|1#CU", FactorPriceUnit.CNY_TON, true))
        .isEqualByComparingTo("90000");
    assertThat(customer.requiredPrice("AVG|cu", FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("100");
    for (String key : List.of("AVG|PCU", "AVG|1#ZN", "AVG|A00", "AVG|1#AG", "AVG|黄金AU9999")) {
      assertThat(customer.requiredPrice(key, FactorPriceUnit.CNY_KG, true))
          .isEqualByComparingTo(financial.requiredPrice(key, FactorPriceUnit.CNY_KG, true));
      assertThat(customer.financial().required(key)).isSameAs(financial.financial().required(key));
    }
    assertThat(customer.requiredPrice("AVG|PCU", FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("115.979");
    assertThat(customer.financial().required("AVG|1#CU").price()).isEqualByComparingTo("90");
    assertThat(customer.financialOnly()).isEqualTo(financial);
  }

  @Test
  void identicalCopperHasNoDifferenceAndPrecisionIsNotRounded() {
    var p0 = financial();
    var p1 = p0.withCustomerCopperPerTon(new BigDecimal("90000"));
    assertThat(
            p1.requiredPrice("AVG|Cu", FactorPriceUnit.CNY_KG, true)
                .subtract(p0.requiredPrice("AVG|CU", FactorPriceUnit.CNY_KG, true)))
        .isZero();
    var precise = p0.withCustomerCopperPerTon(new BigDecimal("95000.1234567890123"));
    assertThat(precise.requiredPrice("AVG|CU", FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("95.0001234567890123");
  }

  @Test
  void allUnitConversionsAreExactAndGoldSilverAreNotConfused() {
    for (var from : FactorPriceUnit.values())
      for (var to : FactorPriceUnit.values()) {
        var value = new BigDecimal("15772.8612345678901234");
        assertThat(to.convert(from.convert(value, to), from)).isEqualByComparingTo(value);
      }
    assertThat(financial().requiredPrice("AVG|1#AG", FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("15772.86");
    assertThat(financial().requiredPrice("AVG|黄金AU9999", FactorPriceUnit.CNY_G, true))
        .isEqualByComparingTo("1000.1234567890123");
    assertThatThrownBy(() -> FactorPriceUnit.require("万元/吨"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位");
  }

  @Test
  void zeroIsAValueButMissingTaxConflictAndInvalidCopperFailWithMaterialAndFactor() {
    var context = financial();
    assertThat(context.requiredPrice("AVG|A00", FactorPriceUnit.CNY_KG, true)).isZero();
    assertThatThrownBy(() -> context.requiredPrice("AVG|MISSING", FactorPriceUnit.CNY_KG, true))
        .hasMessageContainingAll("MAT-01", "AVG|MISSING", "2026-10", "42");
    assertThatThrownBy(() -> context.requiredPrice("AVG|1#CU", FactorPriceUnit.CNY_KG, false))
        .hasMessageContainingAll("MAT-01", "AVG|1#CU", "税价");
    assertThatThrownBy(() -> context.withCustomerCopperPerTon(BigDecimal.ZERO))
        .hasMessageContaining("大于0");
    assertThatThrownBy(() -> context.withCustomerCopperPerTon(null)).hasMessageContaining("客户Cu缺失");
  }

  @Test
  void monthProvenanceRejectsFutureOrInconsistentSnapshotAndAllCollectionsAreImmutable() {
    assertThat(financial().months().carriedForward()).isTrue();
    assertThat(new PriceMonthSelection("2026-10", "2026-10").carriedForward()).isFalse();
    assertThatThrownBy(() -> new PriceMonthSelection("2026-10", "2026-11"))
        .hasMessageContaining("未来月份");
    assertThatThrownBy(() -> new PriceMonthSelection("2026-1", "2026-01"))
        .hasMessageContaining("yyyy-MM");
    assertThatThrownBy(
            () ->
                new PriceFactorContext(
                    "MAT-01",
                    new PriceMonthSelection("2026-11", "2026-09"),
                    financial().financial(),
                    null))
        .hasMessageContaining("来源月");
    assertThatThrownBy(() -> financial().financial().factors().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> financial().financial().required("AVG|1#CU").sources().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  public static PriceFactorContext financial() {
    Map<String, FactorPriceSnapshot.FactorValue> factors = new LinkedHashMap<>();
    add(factors, "AVG|1#CU", "90", "CNY/KG");
    add(factors, "AVG|PCU", "115.979", "CNY/KG");
    add(factors, "AVG|1#ZN", "21.636", "CNY/KG");
    add(factors, "AVG|A00", "0", "CNY/KG");
    add(factors, "AVG|1#AG", "15772.86", "CNY/KG");
    add(factors, "AVG|黄金AU9999", "1000.1234567890123", "CNY/G");
    var snapshot =
        new FactorPriceSnapshot(
            new FactorPriceScope(
                "COMMERCIAL", "210", "2026-10", "CU_90000", FactorPriceScope.Condition.GENERAL),
            42L,
            3,
            "financial.xlsx",
            "sha",
            "hash",
            factors,
            List.of("CU_90000", "CU_100000"),
            "finance",
            LocalDateTime.of(2026, 10, 1, 0, 0),
            null);
    return new PriceFactorContext(
        "MAT-01", new PriceMonthSelection("2026-11", "2026-10"), snapshot, null);
  }

  private static void add(
      Map<String, FactorPriceSnapshot.FactorValue> factors, String key, String price, String unit) {
    factors.put(
        key,
        new FactorPriceSnapshot.FactorValue(
            1L,
            2L,
            key,
            key,
            new BigDecimal(price),
            unit,
            true,
            List.of(
                new FactorPriceSnapshot.Source(
                    3L, "Sheet1", "G3", price, unit, null, "0.000", "SCHEME_ADJUSTMENT"))));
  }
}
