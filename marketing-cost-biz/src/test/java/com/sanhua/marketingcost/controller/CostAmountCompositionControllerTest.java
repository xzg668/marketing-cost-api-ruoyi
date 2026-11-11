package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.service.costing.CostAmountCompositionService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class CostAmountCompositionControllerTest {
  @Test void UsesTheExistingQuotationPermissionAndPassesTheExactSavedSelector() throws Exception {
    var service=mock(CostAmountCompositionService.class);
    new CostAmountCompositionController(service).read("RUN-101",2695L,null);
    verify(service).read("RUN-101",2695L,null);
    var method=CostAmountCompositionController.class.getMethod("read",String.class,Long.class,Long.class);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo("@ss.hasPermi('ingest:quote:list')");
  }
}
