package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.CostRunPreparedPartItemProvider;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CostRunObjectCalcServiceImplTest {

  @Test
  void quoteRecalculatesFromCurrentInputsAndFiltersOtherProducts() {
    CostRunPartItemService partService = mock(CostRunPartItemService.class);
    CostRunCostItemService costService = mock(CostRunCostItemService.class);
    CostRunObjectCalcServiceImpl service =
        new CostRunObjectCalcServiceImpl(partService, costService, List.of());
    CostRunContext context = quoteContext();
    context.setCostRunVersionId(99L);
    when(partService.listByOaNo(
            eq("OA-001"), any(LocalDate.class), eq(context), eq(false), any()))
        .thenReturn(List.of(part("P-001", "PART-1"), part("P-002", "PART-2")));
    when(costService.listByMaterialCodes(
            eq("OA-001"), eq("P-001"), eq(Set.of("P-001")), eq(context), any(), eq(false), any()))
        .thenReturn(List.of(cost("TOTAL", "120.000000")));

    var result = service.calculate(context);

    assertThat(result.getSourceCostVersionId()).isEqualTo(99L);
    assertThat(result.getResult().getTotalCost()).isEqualByComparingTo("120.000000");
    assertThat(result.getPartItems()).extracting(CostRunPartItemDto::getPartCode)
        .containsExactly("PART-1");
    verify(partService, never()).listStoredByOaNo(any());
    verify(costService, never()).listStoredByOaNo(any(), any(), any());
  }

  @Test
  void monthlyRepriceUsesPricingMonthAsPriceDate() {
    CostRunPartItemService partService = mock(CostRunPartItemService.class);
    CostRunCostItemService costService = mock(CostRunCostItemService.class);
    CostRunObjectCalcServiceImpl service =
        new CostRunObjectCalcServiceImpl(partService, costService, List.of());
    CostRunContext context =
        CostRunContext.monthlyReprice(
            "2026-05", 88L, "MRP-001", "COMMERCIAL",
            LocalDateTime.of(2026, 5, 1, 9, 30),
            CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM,
            "OA-001", 7L, "P-001", "箱装", "客户A", "OBJ-001");
    when(partService.listByOaNo(
            eq("OA-001"), eq(LocalDate.of(2026, 5, 1)), eq(context), eq(false), any()))
        .thenReturn(List.of(part("P-001", "PART-1")));
    when(costService.listByMaterialCodes(any(), any(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn(List.of(cost("TOTAL", "130.000000")));

    service.calculate(context);

    verify(partService)
        .listByOaNo(
            eq("OA-001"), eq(LocalDate.of(2026, 5, 1)), eq(context), eq(false), any());
  }

  @Test
  void quoteUsesPreparedFinalPricesWhenPrepareNoExists() {
    CostRunPartItemService partService = mock(CostRunPartItemService.class);
    CostRunCostItemService costService = mock(CostRunCostItemService.class);
    CostRunPreparedPartItemProvider provider = mock(CostRunPreparedPartItemProvider.class);
    CostRunObjectCalcServiceImpl service =
        new CostRunObjectCalcServiceImpl(partService, costService, List.of(provider));
    CostRunContext context = quoteContext();
    context.setPricePrepareNo("PPR-001");
    CostRunPartItemDto prepared = part("P-001", "PART-PREPARED");
    prepared.setUnitPrice(new BigDecimal("50.000000"));
    prepared.setPriceSource("联动价");
    when(provider.supports(context)).thenReturn(true);
    when(provider.listPreparedPartItems(context)).thenReturn(List.of(prepared));
    when(costService.listByMaterialCodes(any(), any(), any(), any(), any(), anyBoolean(), any()))
        .thenReturn(List.of(cost("TOTAL", "137.806000")));

    var result = service.calculate(context);

    assertThat(result.getPartItems().get(0).getPartCode()).isEqualTo("PART-PREPARED");
    assertThat(result.getPartItems().get(0).getUnitPrice()).isEqualByComparingTo("50.000000");
    assertThat(result.getPartItems().get(0).getPriceSource()).isEqualTo("联动价");
    verifyNoInteractions(partService);
  }

  @Test
  void quoteFailsWhenPreparedFinalPriceSnapshotHasNoReader() {
    CostRunPartItemService partService = mock(CostRunPartItemService.class);
    CostRunCostItemService costService = mock(CostRunCostItemService.class);
    CostRunObjectCalcServiceImpl service =
        new CostRunObjectCalcServiceImpl(partService, costService, List.of());
    CostRunContext context = quoteContext();
    context.setPricePrepareNo("PPR-MISSING");

    assertThatThrownBy(() -> service.calculate(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少价格源快照读取器")
        .hasMessageContaining("PPR-MISSING");
    verifyNoInteractions(partService, costService);
  }

  private CostRunContext quoteContext() {
    return CostRunContext.quote(
        "OA-001", 7L, "P-001", "箱装", "客户A", "COMMERCIAL", "2026-05", "OA-001:P-001");
  }

  private CostRunPartItemDto part(String productCode, String partCode) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setProductCode(productCode);
    item.setPartCode(partCode);
    item.setAmount(BigDecimal.ONE);
    return item;
  }

  private CostRunCostItemDto cost(String code, String amount) {
    CostRunCostItemDto item = new CostRunCostItemDto();
    item.setCostCode(code);
    item.setAmount(new BigDecimal(amount));
    return item;
  }
}
