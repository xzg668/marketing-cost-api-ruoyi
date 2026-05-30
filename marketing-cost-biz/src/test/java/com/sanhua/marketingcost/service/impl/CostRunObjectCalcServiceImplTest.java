package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CostRunObjectCalcServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), CostRunResult.class);
  }

  @Test
  void quoteObjectRecalculatesFromBomAndCostServicesWithoutCopyingStoredDetails() {
    CostRunResultMapper resultMapper = mock(CostRunResultMapper.class);
    CostRunPartItemService partItemService = mock(CostRunPartItemService.class);
    CostRunCostItemService costItemService = mock(CostRunCostItemService.class);
    CostRunObjectCalcServiceImpl service =
        new CostRunObjectCalcServiceImpl(resultMapper, partItemService, costItemService);
    when(resultMapper.selectOne(any())).thenReturn(sourceResult());
    when(partItemService.listByOaNo(
            eq("OA-001"), any(LocalDate.class), any(CostRunContext.class), eq(false), any()))
        .thenReturn(List.of(part("P-001", "PART-1"), part("P-002", "PART-2")));
    when(costItemService.listByMaterialCodes(
            eq("OA-001"),
            eq("P-001"),
            eq(Set.of("P-001")),
            any(CostRunContext.class),
            any(),
            eq(false),
            any()))
        .thenReturn(List.of(cost("TOTAL", "总成本", "120.000000")));

    var result = service.calculate(quoteContext());

    assertThat(result.getSourceCostResultId()).isEqualTo(99L);
    assertThat(result.getResult().getTotalCost()).isEqualByComparingTo("120.000000");
    assertThat(result.getPartItems()).hasSize(1);
    assertThat(result.getPartItems().get(0).getPartCode()).isEqualTo("PART-1");
    assertThat(result.getCostItems()).hasSize(1);
    verify(partItemService, never()).listStoredByOaNo(any());
    verify(costItemService, never()).listStoredByOaNo(any(), any(), any());
    verify(resultMapper, never()).insert(any(CostRunResult.class));
    verify(resultMapper, never()).updateById(any(CostRunResult.class));
  }

  @Test
  void monthlyObjectAlsoRecalculatesInsteadOfReadingHistoricalResultDetails() {
    CostRunResultMapper resultMapper = mock(CostRunResultMapper.class);
    CostRunPartItemService partItemService = mock(CostRunPartItemService.class);
    CostRunCostItemService costItemService = mock(CostRunCostItemService.class);
    CostRunObjectCalcServiceImpl service =
        new CostRunObjectCalcServiceImpl(resultMapper, partItemService, costItemService);
    when(partItemService.listByOaNo(
            eq("OA-001"), any(LocalDate.class), any(CostRunContext.class), eq(false), any()))
        .thenReturn(List.of(part("P-001", "PART-1")));
    when(costItemService.listByMaterialCodes(
            eq("OA-001"),
            eq("P-001"),
            eq(Set.of("P-001")),
            any(CostRunContext.class),
            any(),
            eq(false),
            any()))
        .thenReturn(List.of(cost("TOTAL", "总成本", "130.000000")));

    var result = service.calculate(monthlyContext());

    assertThat(result.getContext().getScene()).isEqualTo(CostRunContext.SCENE_MONTHLY_REPRICE);
    assertThat(result.getResult().getTotalCost()).isEqualByComparingTo("130.000000");
    verify(partItemService, never()).listStoredByOaNo(any());
    verify(costItemService, never()).listStoredByOaNo(any(), any(), any());
    verify(resultMapper, never()).selectOne(any());
  }

  @Test
  void monthlyObjectUsesPricingMonthAsPartPriceDate() {
    CostRunResultMapper resultMapper = mock(CostRunResultMapper.class);
    CostRunPartItemService partItemService = mock(CostRunPartItemService.class);
    CostRunCostItemService costItemService = mock(CostRunCostItemService.class);
    CostRunObjectCalcServiceImpl service =
        new CostRunObjectCalcServiceImpl(resultMapper, partItemService, costItemService);
    when(partItemService.listByOaNo(
            eq("OA-001"),
            eq(LocalDate.of(2026, 5, 1)),
            any(CostRunContext.class),
            eq(false),
            any()))
        .thenReturn(List.of(part("P-001", "PART-1")));
    when(costItemService.listByMaterialCodes(
            eq("OA-001"),
            eq("P-001"),
            eq(Set.of("P-001")),
            any(CostRunContext.class),
            any(),
            eq(false),
            any()))
        .thenReturn(List.of(cost("TOTAL", "总成本", "130.000000")));

    service.calculate(monthlyContext());

    verify(partItemService)
        .listByOaNo(
            eq("OA-001"),
            eq(LocalDate.of(2026, 5, 1)),
            any(CostRunContext.class),
            eq(false),
            any());
  }

  private CostRunContext monthlyContext() {
    return CostRunContext.monthlyReprice(
        "2026-05",
        88L,
        "MRP-001",
        "COMMERCIAL",
        LocalDateTime.of(2026, 5, 1, 9, 30),
        CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM,
        "OA-001",
        7L,
        "P-001",
        "箱装",
        "客户A",
        "OBJ-001");
  }

  private CostRunContext quoteContext() {
    return CostRunContext.quote(
        "OA-001",
        7L,
        "P-001",
        "箱装",
        "客户A",
        "COMMERCIAL",
        "2026-05",
        "OA-001:P-001");
  }

  private CostRunResult sourceResult() {
    CostRunResult result = new CostRunResult();
    result.setId(99L);
    result.setOaNo("OA-001");
    result.setProductCode("P-001");
    result.setTotalCost(new BigDecimal("120.000000"));
    result.setCalcStatus("已核算");
    return result;
  }

  private CostRunPartItemDto part(String productCode, String partCode) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setOaNo("OA-001");
    item.setProductCode(productCode);
    item.setPartCode(partCode);
    item.setAmount(BigDecimal.ONE);
    return item;
  }

  private CostRunCostItemDto cost(String code, String name, String amount) {
    CostRunCostItemDto item = new CostRunCostItemDto();
    item.setCostCode(code);
    item.setCostName(name);
    item.setAmount(new BigDecimal(amount));
    return item;
  }
}
