package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.service.QuoteCostingInputFingerprintService.Input;
import com.sanhua.marketingcost.service.QuoteCostingInputFingerprintService.PriceReference;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteCostingInputFingerprintServiceImplTest {

  private final QuoteCostingInputFingerprintServiceImpl service =
      new QuoteCostingInputFingerprintServiceImpl();

  @Test
  void sameBusinessInputProducesSameFingerprintRegardlessOfCollectionOrder() {
    Input first = input("托盘", "BOM-FP-1", "RULE-FP-1", List.of("A=M1", "B=M2"),
        List.of(price(11L, 21L), price(12L, 22L)));
    Input reordered = input("托盘", "BOM-FP-1", "RULE-FP-1", List.of("B=M2", "A=M1"),
        List.of(price(12L, 22L), price(11L, 21L)));

    assertThat(service.calculate(first)).isEqualTo(service.calculate(reordered));
  }

  @Test
  void businessInputChangesProduceDifferentFingerprints() {
    Input baseline = input("托盘", "BOM-FP-1", "RULE-FP-1", List.of("A=M1"),
        List.of(price(11L, 21L)));

    assertThat(service.calculate(input("纸箱", "BOM-FP-1", "RULE-FP-1", List.of("A=M1"),
        List.of(price(11L, 21L))))).isNotEqualTo(service.calculate(baseline));
    assertThat(service.calculate(input("托盘", "BOM-FP-2", "RULE-FP-1", List.of("A=M1"),
        List.of(price(11L, 21L))))).isNotEqualTo(service.calculate(baseline));
    assertThat(service.calculate(input("托盘", "BOM-FP-1", "RULE-FP-2", List.of("A=M1"),
        List.of(price(11L, 21L))))).isNotEqualTo(service.calculate(baseline));
    assertThat(service.calculate(input("托盘", "BOM-FP-1", "RULE-FP-1", List.of("A=M2"),
        List.of(price(11L, 21L))))).isNotEqualTo(service.calculate(baseline));
    assertThat(service.calculate(input("托盘", "BOM-FP-1", "RULE-FP-1", List.of("A=M1"),
        List.of(price(11L, 99L))))).isNotEqualTo(service.calculate(baseline));
  }

  @Test
  void displayOnlyValuesAreExcludedFromFingerprintContract() {
    assertThat(
        Arrays.stream(Input.class.getRecordComponents())
            .map(component -> component.getName())
            .toList())
        .doesNotContain("productName", "productModel", "statusLabel", "operatorName");
  }

  private Input input(
      String packageMethod,
      String bomFingerprint,
      String ruleFingerprint,
      List<String> alternatives,
      List<PriceReference> prices) {
    return new Input(
        180L,
        "1001900001090",
        "2026-08",
        packageMethod,
        "PKG-1",
        "1",
        "量产",
        "COMMERCIAL",
        bomFingerprint,
        ruleFingerprint,
        alternatives,
        prices,
        List.of("MANUFACTURE_RATE:2026-08", "THREE_EXPENSE:2026-08"));
  }

  private PriceReference price(Long typeId, Long sourceId) {
    return new PriceReference(typeId, "FIXED", sourceId, "SUP-1", 31L);
  }
}
