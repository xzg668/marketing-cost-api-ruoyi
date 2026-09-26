package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalDataModuleRequirementEvaluatorTest {
  private final TechnicalDataModuleRequirementEvaluator evaluator = new TechnicalDataModuleRequirementEvaluator();
  private final LocalDateTime checkedAt = LocalDateTime.of(2026, 9, 14, 9, 0);

  @Test void missingAvailableUnknownAndFailureRemainDistinct() {
    var result = evaluator.evaluate(List.of(
        fact(TechnicalDataModuleType.PACKAGE, TechnicalDataAvailability.MISSING),
        fact(TechnicalDataModuleType.AUXILIARY, TechnicalDataAvailability.AVAILABLE),
        fact(TechnicalDataModuleType.SALARY, TechnicalDataAvailability.ERROR)));
    assertThat(result).extracting(TechnicalDataModuleRequirement::moduleType).containsExactly(
        "PROFILE", "DRAWING_BOM", "MANUFACTURING", "PACKAGE", "AUXILIARY", "SOLDER", "SALARY", "NET_LOSS", "PRICE");
    assertThat(result.get(3).required()).isTrue();
    assertThat(result.get(4).availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
    assertThat(result.get(6).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
    assertThat(result.getFirst().availability()).isEqualTo(TechnicalDataAvailability.UNCONFIRMED);
    assertThat(result.getFirst().checkedAt()).isNull();
    assertThat(result.get(3).sourceReference()).isEqualTo("source:123");
    assertThat(result.get(3).checkedAt()).isEqualTo(checkedAt);
  }

  @Test void conflictingChecksAreRejectedInsteadOfLastWriteWinning() {
    assertThatThrownBy(() -> evaluator.evaluate(List.of(
        fact(TechnicalDataModuleType.PACKAGE, TechnicalDataAvailability.MISSING),
        fact(TechnicalDataModuleType.PACKAGE, TechnicalDataAvailability.AVAILABLE))))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
  }

  @Test void confirmedFactRequiresActualCheckTime() {
    assertThatThrownBy(() -> new TechnicalDataSourceFact(TechnicalDataModuleType.PRICE,
        TechnicalDataAvailability.AVAILABLE, "PRICE_FOUND", "已查到", "price:123", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private TechnicalDataSourceFact fact(TechnicalDataModuleType type, TechnicalDataAvailability availability) {
    return new TechnicalDataSourceFact(type, availability, "CHECK_" + availability, "来源结论", "source:123", checkedAt);
  }
}
