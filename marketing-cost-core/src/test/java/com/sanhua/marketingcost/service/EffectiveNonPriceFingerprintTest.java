package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EffectiveNonPriceFingerprintTest {
  @Test void priceResubmissionDoesNotChangeProductInputButEffectiveProfileAndNullAmountsDo() {
    var input = new AtomicReference<>(value(10L, 20L, null));
    EffectiveTechnicalDataQueryService query = (item, month) -> input.get();
    var initial = query.effectiveNonPriceFingerprint(1L, "2026-10");
    input.set(value(11L, 20L, null));
    assertThat(query.effectiveNonPriceFingerprint(1L, "2026-10")).isEqualTo(initial);
    input.set(value(11L, 21L, null));
    assertThat(query.effectiveNonPriceFingerprint(1L, "2026-10")).isNotEqualTo(initial);
    input.set(value(11L, 20L, BigDecimal.ZERO));
    assertThat(query.effectiveNonPriceFingerprint(1L, "2026-10")).isNotEqualTo(initial);
  }

  private static EffectiveTechnicalDataInput value(long priceVersion, long profileModule, BigDecimal fee) {
    return new EffectiveTechnicalDataInput(1L, priceVersion, 1, "2026-10", "QUOTE_TECH_EFFECTIVE_VERSION",
        "whole-version-" + priceVersion, LocalDateTime.now(), false, false, false,
        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(), List.of(),
        new EffectiveTechnicalDataInput.ProductFees(null, null, null, null, "CNY", fee, 2), null, null,
        List.of(new EffectiveTechnicalDataInput.ModuleSource("PRICE", 1L, priceVersion, priceVersion, "price-" + priceVersion),
            new EffectiveTechnicalDataInput.ModuleSource("PROFILE", 1L, priceVersion, profileModule, "whole-" + priceVersion)),
        List.of(), List.of(new EffectiveTechnicalDataInput.PriceSource("A", "FIXED", priceVersion, 1L, priceVersion, priceVersion, "price-" + priceVersion)), null);
  }
}
