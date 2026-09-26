package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.ProductFees;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TechnicalDataProductFeeRulesTest {
  @Test void exactSingleUnitAmountsArePreservedForCostingWithoutVolumeDivisionOrFlagFiltering() {
    var stored = TechnicalDataProductFeeRules.parse(false, "0.10", "0.30", "0.05");
    var cost = TechnicalDataProductFeeRules.costingInput(stored);
    assertThat(cost.hasAdditionalFees()).isFalse();
    assertThat(cost.unitToolingFee().add(cost.unitMouldFee()).add(cost.unitCertificationFee())).isEqualByComparingTo("0.45");
    assertThat(cost.currency()).isEqualTo("CNY");
  }

  @Test void explicitNoExpenseAndMissingValueAreDifferent() {
    var stored = TechnicalDataProductFeeRules.parse(false, null, "/", " ");
    assertThat(TechnicalDataProductFeeRules.validate(stored)).isEmpty();
    assertThat(TechnicalDataProductFeeRules.display(stored.unitToolingFee())).isEqualTo("/");
    assertThat(TechnicalDataProductFeeRules.display(null)).isNull();
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.parse(true, null, "/", "/")).hasMessageContaining("工装费");
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.costingInput(new ProductFees(true, null, BigDecimal.ZERO, BigDecimal.ZERO, "CNY"))).hasMessageContaining("工装费");
  }

  @Test void corruptedCurrencyAndAmountsCannotBecomeCostingInput() {
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.costingInput(new ProductFees(true, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "USD"))).hasMessageContaining("人民币");
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.parse(true, "-1", "/", "/")).hasMessageContaining("工装费");
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.parse(true, "0", "/", "/")).hasMessageContaining("大于 0");
  }
}
