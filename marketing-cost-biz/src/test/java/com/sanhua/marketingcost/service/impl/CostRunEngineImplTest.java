package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.service.CostRunObjectCalcService;
import org.junit.jupiter.api.Test;

class CostRunEngineImplTest {

  @Test
  void quoteSceneIsSupportedAndDelegatesToObjectCalculator() {
    CostRunObjectCalcService calcService = mock(CostRunObjectCalcService.class);
    CostRunEngineImpl engine = new CostRunEngineImpl(calcService);
    CostRunObjectResult expected = new CostRunObjectResult();
    when(calcService.calculate(any())).thenReturn(expected);

    engine.run(CostRunContext.quote("OA-001", 1L, "P-001", "箱装", "客户A", "COMMERCIAL", "2026-05", "OBJ-1"));

    verify(calcService).calculate(any(CostRunContext.class));
  }

  @Test
  void quoteSceneRequiresOaNoAndProductCode() {
    CostRunObjectCalcService calcService = mock(CostRunObjectCalcService.class);
    CostRunEngineImpl engine = new CostRunEngineImpl(calcService);

    assertThatThrownBy(() -> engine.run(CostRunContext.quote("OA-001", 1L, " ", null, null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("普通 OA 核算上下文缺少必要字段");
  }
}
