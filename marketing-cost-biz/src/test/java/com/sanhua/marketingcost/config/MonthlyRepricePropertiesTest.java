package com.sanhua.marketingcost.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.MonthlyRepriceExecutionBackend;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonthlyRepricePropertiesTest {

  @Test
  void emptyAllowedBusinessUnitsMeansAllBusinessUnitsAllowed() {
    MonthlyRepriceProperties properties = new MonthlyRepriceProperties();

    assertThat(properties.isBusinessUnitAllowed("COMMERCIAL")).isTrue();
  }

  @Test
  void allowedBusinessUnitsAreTrimmedAndCaseInsensitive() {
    MonthlyRepriceProperties properties = new MonthlyRepriceProperties();
    properties.setAllowedBusinessUnits(List.of(" commercial ", "", "HOUSEHOLD"));

    assertThat(properties.isBusinessUnitAllowed("COMMERCIAL")).isTrue();
    assertThat(properties.isBusinessUnitAllowed("industrial")).isFalse();
  }

  @Test
  void blankExecutionBackendFallsBackToLocalWorker() {
    MonthlyRepriceProperties properties = new MonthlyRepriceProperties();
    properties.setExecutionBackend(" ");

    assertThat(properties.getExecutionBackend()).isEqualTo("LOCAL_WORKER");
    assertThat(properties.getExecutionBackendType())
        .isEqualTo(MonthlyRepriceExecutionBackend.LOCAL_WORKER);
  }
}
