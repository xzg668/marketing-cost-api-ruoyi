package com.sanhua.marketingcost.service.effectivebom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuoteEffectiveBomFeatureSwitchTest {

  @Test
  void canBeClosedForSafeReleaseAndRollback() {
    assertThat(new QuoteEffectiveBomFeatureSwitch(false).isEnabled()).isFalse();
    assertThat(new QuoteEffectiveBomFeatureSwitch(true).isEnabled()).isTrue();
  }
}
