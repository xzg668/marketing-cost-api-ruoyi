package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

class QuoteEffectiveBomCostingApiContractTest {

  @Test
  void exposesOnlyAutomaticPrepareEndpoint() throws Exception {
    Method method =
        QuoteEffectiveBomCostingController.class.getMethod(
            "prepareCostingBom", String.class, Long.class);

    assertThat(method.getAnnotation(PostMapping.class).value())
        .containsExactly("/effective-bom/prepare-costing");
    assertThat(method.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasAnyPermi('ingest:quote:list')");
    assertThat(QuoteEffectiveBomCostingController.class.getDeclaredMethods())
        .extracting(Method::getName)
        .doesNotContain("confirm", "rebuildFromEffective");
  }
}
