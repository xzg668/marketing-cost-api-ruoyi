package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.service.CostRunObjectCalcService;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
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

  @Test
  void resolvesExactItemAndMonthBeforeCalculation() {
    CostRunObjectCalcService calcService = mock(CostRunObjectCalcService.class);
    EffectiveTechnicalDataQueryService technicalData =
        mock(EffectiveTechnicalDataQueryService.class);
    EffectiveTechnicalDataInput v2 = effectiveV2();
    when(technicalData.resolve(1053100052030L, "2026-08")).thenReturn(v2);
    when(calcService.calculate(any())).thenAnswer(invocation -> {
      CostRunContext context = invocation.getArgument(0);
      assertThat(context.getEffectiveTechnicalData()).isSameAs(v2);
      return new CostRunObjectResult();
    });
    CostRunEngineImpl engine = new CostRunEngineImpl(calcService, technicalData);

    engine.run(CostRunContext.quote(
        "E2E-EDRAW-J40AH-20260830-T8-002",
        1053100052030L,
        "205686641",
        null,
        null,
        "COMMERCIAL",
        "2026-08",
        "ITEM:1053100052030:MONTH:2026-08"));

    verify(technicalData).resolve(1053100052030L, "2026-08");
  }

  @Test
  void productWithoutTechnicalWorkspaceKeepsExistingCostRules() {
    CostRunObjectCalcService calcService = mock(CostRunObjectCalcService.class);
    EffectiveTechnicalDataQueryService technicalData =
        mock(EffectiveTechnicalDataQueryService.class);
    when(calcService.calculate(any())).thenReturn(new CostRunObjectResult());
    CostRunEngineImpl engine = new CostRunEngineImpl(calcService, technicalData);
    CostRunContext context = CostRunContext.quote(
        "OA-NO-TECH", 99L, "P-NO-TECH", null, null, "COMMERCIAL", "2026-09", "OBJ");

    engine.run(context);

    verify(technicalData).resolve(99L, "2026-09");
    assertThat(context.getEffectiveTechnicalData()).isNull();
  }

  private EffectiveTechnicalDataInput effectiveV2() {
    return new EffectiveTechnicalDataInput(
        9001L,
        9002L,
        2,
        "2026-08",
        EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION,
        "f".repeat(64),
        LocalDateTime.of(2026, 8, 31, 8, 0),
        true,
        true,
        true,
        BigDecimal.ONE,
        new BigDecimal("0.704"),
        new BigDecimal("18.90"),
        List.of(),
        List.of(),
        List.of(), null, null);
  }
}
