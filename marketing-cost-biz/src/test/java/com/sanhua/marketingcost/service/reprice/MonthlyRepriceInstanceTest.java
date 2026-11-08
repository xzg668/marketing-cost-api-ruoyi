package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MonthlyRepriceInstanceTest {
  @Test void identicalDeploymentLabelsStillIdentifyDifferentApplicationInstances() {
    var first=new MonthlyRepriceInstance(" worker "); var second=new MonthlyRepriceInstance("worker");
    assertThat(first.value()).startsWith("worker-").isNotEqualTo(second.value());
    assertThat(first.value()).isEqualTo(first.value());
  }
  @Test void omittedLabelsHaveAValidUniqueIdentity() {
    var first=new MonthlyRepriceInstance(null); var second=new MonthlyRepriceInstance("");
    assertThat(first.value()).startsWith("monthly-reprice-").isNotEqualTo(second.value());
  }
  @Test void identityFitsTheExistingOwnerColumnAndRejectsOversizedLabels() {
    assertThat(new MonthlyRepriceInstance("x".repeat(90)).value().length()).isEqualTo(127);
    assertThatThrownBy(()->new MonthlyRepriceInstance("x".repeat(91))).isInstanceOf(IllegalArgumentException.class);
  }
}
