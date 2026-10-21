package com.sanhua.marketingcost.service.pricing.settlement;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SettlementContractTest {
  @Test void sharingTwoOrganizationsKeepsBusinessUnitAndAccountingMonthExplicit() {
    var commercial = new SettlementPriceRequest("commercial", " M1 ", "2026-11", "210", SettlementPriceType.SETTLE_LINKED);
    var board = new SettlementPriceRequest("COMMERCIAL", "M1", "2026-11", "220", SettlementPriceType.SETTLE_LINKED);
    assertThat(commercial.businessUnitType()).isEqualTo(board.businessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(commercial.materialCode()).isEqualTo("M1");
    assertThat(commercial.accountingMonth()).isEqualTo("2026-11");
    assertThatThrownBy(() -> new SettlementPriceRequest("COMMERCIAL", "M1", "2026-13", "210", SettlementPriceType.SETTLE_FIXED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SettlementPriceRequest("COMMERCIAL", "M1", "2026-11", "999", SettlementPriceType.SETTLE_FIXED))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SettlementPriceRequest(null, "M1", "2026-11", "210", SettlementPriceType.SETTLE_FIXED))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test void unconfirmedExampleIsPreservedWithoutBecomingACalculationInput() {
    var example = new SettlementWeights(new BigDecimal("7.8"), new BigDecimal("1.48"), null, null, false, null);
    assertThat(example.completeAndConfirmed()).isFalse();
    assertThat(example.originalCopperG()).isEqualByComparingTo("7.8");
    assertThat(example.copperG()).isNull();
    var zero = new SettlementWeights(null, null, BigDecimal.ZERO, BigDecimal.ZERO, true, "财务确认无铜锌");
    assertThat(zero.completeAndConfirmed()).isTrue();
    assertThatThrownBy(() -> new SettlementWeights(null, null, BigDecimal.ZERO, null, true, "确认"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SettlementWeights(null, null, BigDecimal.ONE.negate(), BigDecimal.ZERO, false, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
