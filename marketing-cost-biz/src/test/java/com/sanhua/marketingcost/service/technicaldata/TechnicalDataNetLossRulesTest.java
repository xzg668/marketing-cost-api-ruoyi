package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.NetLoss;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalDataNetLossRulesTest {
  @Test void percentBecomesRatioExactlyOnceAndZeroIsComplete() {
    assertThat(TechnicalDataNetLossRules.ratio("0.475")).isEqualByComparingTo("0.00475");
    assertThat(TechnicalDataNetLossRules.ratio("99.999")).isEqualByComparingTo("0.99999");
    assertThat(TechnicalDataNetLossRules.validate(new NetLoss(null, BigDecimal.ZERO, "MANUAL", null, null))).isEmpty();
  }
  @Test void emptyDraftDoesNotBecomeZeroOrReady() {
    assertThat(TechnicalDataNetLossRules.ratio(null)).isNull();
    assertThat(TechnicalDataNetLossRules.ratio(" ")).isNull();
    assertThat(TechnicalDataNetLossRules.validate(new NetLoss(null, null, "MANUAL", null, null))).isNotEmpty();
  }
  @Test void invalidRangeAndPrecisionAreRejectedInsteadOfRounded() {
    for (String value : List.of("-1", "100", "100.001", "0.0001", "NaN", "1e1", "0.1%")) {
      assertThatThrownBy(() -> TechnicalDataNetLossRules.ratio(value)).isInstanceOf(IllegalArgumentException.class);
    }
  }
  @Test void manualCannotCarryReferenceAndIncompleteReferenceCannotSubmit() {
    assertThat(TechnicalDataNetLossRules.validate(new NetLoss("BARE", BigDecimal.ZERO, "MANUAL", null, null))).isNotEmpty();
    assertThat(TechnicalDataNetLossRules.validate(new NetLoss("BARE", new BigDecimal("0.00475"), "REFERENCE", "fake", null))).isNotEmpty();
    assertThat(TechnicalDataNetLossRules.validate(new NetLoss(null, new BigDecimal("0.000001"), "MANUAL", null, null))).isNotEmpty();
  }
}
