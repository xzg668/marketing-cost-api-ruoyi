package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MonthlyRepriceCapacityTest {
  @Test void totalActiveAndFutureConnectionGrowthBothLimitAdmission() {
    var rules = new MonthlyRepriceCapacity.Rules(8,100,32);
    assertThat(rules.allowance(8,3,151,20)).isEqualTo(5);
    assertThat(rules.allowance(8,3,151,100)).isEqualTo(1);
    assertThat(rules.allowance(8,3,151,108)).isZero();
    assertThat(rules.allowance(8,3,151,200)).isZero();
    assertThat(rules.allowance(8,9,151,20)).isZero();
    assertThat(rules.allowance(1,0,151,20)).isEqualTo(1);
  }

  @Test void burstNeverExceedsOneSecondOfTheSharedRate() {
    var rules = new MonthlyRepriceCapacity.Rules(64,2,0);
    assertThat(rules.allowance(64,0,1000,1)).isEqualTo(2);
    assertThat(rules.identity()).isNotEqualTo(new MonthlyRepriceCapacity.Rules(64,3,0).identity());
  }

  @Test void invalidPolicyCannotSilentlyRemoveCapacityProtection() {
    assertThatThrownBy(()->new MonthlyRepriceCapacity.Rules(0,100,32)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new MonthlyRepriceCapacity.Rules(65,100,32)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new MonthlyRepriceCapacity.Rules(4,0,32)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new MonthlyRepriceCapacity.Rules(4,10001,32)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->new MonthlyRepriceCapacity.Rules(4,100,-1)).isInstanceOf(IllegalArgumentException.class);
    assertThat(new MonthlyRepriceCapacity.Rules(4,100,Integer.MAX_VALUE).allowance(4,0,151,1)).isZero();
  }
}
