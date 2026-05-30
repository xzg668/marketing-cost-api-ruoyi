package com.sanhua.marketingcost.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MarketingCostCoreModuleTest {

  @Test
  void moduleNameIdentifiesCoreModule() {
    assertEquals("marketing-cost-core", MarketingCostCoreModule.moduleName());
  }
}
