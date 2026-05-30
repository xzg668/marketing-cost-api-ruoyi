package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.MonthlyRepriceResultWriter;
import com.sanhua.marketingcost.service.PricePrepareService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MonthlyRepriceCostRunAdapterImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), MonthlyRepriceBatch.class);
  }

  @Test
  void buildsMonthlyRepriceContextRunsEngineAndWritesMonthlyTables() {
    MonthlyRepriceBatchMapper batchMapper = mock(MonthlyRepriceBatchMapper.class);
    PricePrepareService pricePrepareService = mock(PricePrepareService.class);
    CostRunEngine costRunEngine = mock(CostRunEngine.class);
    MonthlyRepriceResultWriter writer = mock(MonthlyRepriceResultWriter.class);
    MonthlyRepriceCostRunAdapterImpl adapter =
        new MonthlyRepriceCostRunAdapterImpl(
            batchMapper, pricePrepareService, costRunEngine, writer);
    when(batchMapper.selectOne(any())).thenReturn(batch());
    when(pricePrepareService.generate(any(PricePrepareGenerateRequest.class))).thenReturn(prepareSuccess());
    CostRunObjectResult engineResult = engineResult();
    when(costRunEngine.run(any(CostRunContext.class))).thenReturn(engineResult);

    adapter.execute(task(), "worker-node-1");

    ArgumentCaptor<PricePrepareGenerateRequest> prepareCaptor =
        ArgumentCaptor.forClass(PricePrepareGenerateRequest.class);
    verify(pricePrepareService).generate(prepareCaptor.capture());
    assertThat(prepareCaptor.getValue().getOaNo()).isEqualTo("OA-001");
    assertThat(prepareCaptor.getValue().getTopProductCodes()).containsExactly("P-001");
    assertThat(prepareCaptor.getValue().getPeriodMonth()).isEqualTo("2026-05");
    assertThat(prepareCaptor.getValue().getPriceAsOfTime())
        .isEqualTo(LocalDateTime.of(2026, 5, 26, 10, 0));
    assertThat(prepareCaptor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    ArgumentCaptor<CostRunContext> contextCaptor = ArgumentCaptor.forClass(CostRunContext.class);
    verify(costRunEngine).run(contextCaptor.capture());
    CostRunContext context = contextCaptor.getValue();
    assertThat(context.getScene()).isEqualTo("MONTHLY_REPRICE");
    assertThat(context.getRepriceNo()).isEqualTo("MRP-001");
    assertThat(context.getPricingMonth()).isEqualTo("2026-05");
    assertThat(context.getAdjustBatchId()).isEqualTo(88L);
    assertThat(context.getPriceAsOfTime()).isEqualTo(LocalDateTime.of(2026, 5, 26, 10, 0));
    assertThat(context.getBomSourcePolicy()).isEqualTo(CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM);
    assertThat(context.getProductCode()).isEqualTo("P-001");
    assertThat(context.getCalcObjectKey()).isEqualTo("OBJ-001");
    verify(writer).write(engineResult);
  }

  @Test
  void stopsBeforeCostRunWhenPricePrepareFails() {
    MonthlyRepriceBatchMapper batchMapper = mock(MonthlyRepriceBatchMapper.class);
    PricePrepareService pricePrepareService = mock(PricePrepareService.class);
    CostRunEngine costRunEngine = mock(CostRunEngine.class);
    MonthlyRepriceResultWriter writer = mock(MonthlyRepriceResultWriter.class);
    MonthlyRepriceCostRunAdapterImpl adapter =
        new MonthlyRepriceCostRunAdapterImpl(
            batchMapper, pricePrepareService, costRunEngine, writer);
    when(batchMapper.selectOne(any())).thenReturn(batch());
    PricePrepareGenerateResult failed = new PricePrepareGenerateResult();
    failed.setStatus("FAILED");
    failed.setGapCount(2);
    failed.setMessage("读取BOM结算明细失败");
    when(pricePrepareService.generate(any(PricePrepareGenerateRequest.class))).thenReturn(failed);

    assertThatThrownBy(() -> adapter.execute(task(), "worker-node-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("价格准备未完成");

    verify(costRunEngine, never()).run(any());
    verify(writer, never()).write(any());
  }

  @Test
  void allowsPartialPricePrepareAndLegacyNoRouteItemsToMatchDailyQuote() {
    MonthlyRepriceBatchMapper batchMapper = mock(MonthlyRepriceBatchMapper.class);
    PricePrepareService pricePrepareService = mock(PricePrepareService.class);
    CostRunEngine costRunEngine = mock(CostRunEngine.class);
    MonthlyRepriceResultWriter writer = mock(MonthlyRepriceResultWriter.class);
    MonthlyRepriceCostRunAdapterImpl adapter =
        new MonthlyRepriceCostRunAdapterImpl(
            batchMapper, pricePrepareService, costRunEngine, writer);
    when(batchMapper.selectOne(any())).thenReturn(batch());
    PricePrepareGenerateResult partial = new PricePrepareGenerateResult();
    partial.setStatus("PARTIAL");
    partial.setGapCount(4);
    partial.setMessage("存在待补充缺口");
    when(pricePrepareService.generate(any(PricePrepareGenerateRequest.class))).thenReturn(partial);
    CostRunObjectResult result = engineResult();
    CostRunPartItemDto noRoute = new CostRunPartItemDto();
    noRoute.setPartCode("339953744");
    noRoute.setPartQty(BigDecimal.ONE);
    noRoute.setPriceSource("NO_ROUTE");
    noRoute.setRemark("未配价格类型路由");
    result.setPartItems(java.util.List.of(noRoute));
    when(costRunEngine.run(any(CostRunContext.class))).thenReturn(result);

    adapter.execute(task(), "worker-node-1");

    verify(writer).write(result);
  }

  @Test
  void allowsResolvedMonthlyCriticalSourcesWhenPriceAndAmountExist() {
    MonthlyRepriceBatchMapper batchMapper = mock(MonthlyRepriceBatchMapper.class);
    PricePrepareService pricePrepareService = mock(PricePrepareService.class);
    CostRunEngine costRunEngine = mock(CostRunEngine.class);
    MonthlyRepriceResultWriter writer = mock(MonthlyRepriceResultWriter.class);
    MonthlyRepriceCostRunAdapterImpl adapter =
        new MonthlyRepriceCostRunAdapterImpl(
            batchMapper, pricePrepareService, costRunEngine, writer);
    when(batchMapper.selectOne(any())).thenReturn(batch());
    when(pricePrepareService.generate(any(PricePrepareGenerateRequest.class))).thenReturn(prepareSuccess());
    CostRunObjectResult result = engineResult();
    CostRunPartItemDto make = pricedPart("203250582", "自制件", "取自制造件价格生成结果");
    CostRunPartItemDto linked = pricedPart("203250307", "月度调价联动价", "");
    result.setPartItems(java.util.List.of(make, linked));
    when(costRunEngine.run(any(CostRunContext.class))).thenReturn(result);

    adapter.execute(task(), "worker-node-1");

    verify(writer).write(result);
  }

  @Test
  void refusesToWriteSuccessWhenCostRunStillHasMissingPartPrice() {
    MonthlyRepriceBatchMapper batchMapper = mock(MonthlyRepriceBatchMapper.class);
    PricePrepareService pricePrepareService = mock(PricePrepareService.class);
    CostRunEngine costRunEngine = mock(CostRunEngine.class);
    MonthlyRepriceResultWriter writer = mock(MonthlyRepriceResultWriter.class);
    MonthlyRepriceCostRunAdapterImpl adapter =
        new MonthlyRepriceCostRunAdapterImpl(
            batchMapper, pricePrepareService, costRunEngine, writer);
    when(batchMapper.selectOne(any())).thenReturn(batch());
    when(pricePrepareService.generate(any(PricePrepareGenerateRequest.class))).thenReturn(prepareSuccess());
    when(costRunEngine.run(any(CostRunContext.class))).thenReturn(engineResultWithMissingPart());

    assertThatThrownBy(() -> adapter.execute(task(), "worker-node-1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("部品缺价");

    verify(writer, never()).write(any());
  }

  private PricePrepareGenerateResult prepareSuccess() {
    PricePrepareGenerateResult result = new PricePrepareGenerateResult();
    result.setStatus("SUCCESS");
    result.setGapCount(0);
    return result;
  }

  private CostRunObjectResult engineResult() {
    CostRunObjectResult result = new CostRunObjectResult();
    CostRunResultDto resultDto = new CostRunResultDto();
    resultDto.setTotalCost(new BigDecimal("123.45"));
    result.setResult(resultDto);
    return result;
  }

  private CostRunObjectResult engineResultWithMissingPart() {
    CostRunObjectResult result = engineResult();
    CostRunPartItemDto part = new CostRunPartItemDto();
    part.setPartCode("MAKE-001");
    part.setPartQty(BigDecimal.ONE);
    part.setPriceSource("ERROR");
    part.setRemark("缺制造件价格生成结果");
    result.setPartItems(java.util.List.of(part));
    return result;
  }

  private CostRunPartItemDto pricedPart(String code, String priceSource, String remark) {
    CostRunPartItemDto part = new CostRunPartItemDto();
    part.setPartCode(code);
    part.setPartQty(BigDecimal.ONE);
    part.setUnitPrice(new BigDecimal("12.34"));
    part.setAmount(new BigDecimal("12.34"));
    part.setPriceSource(priceSource);
    part.setRemark(remark);
    return part;
  }

  private MonthlyRepriceBatch batch() {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setRepriceNo("MRP-001");
    batch.setPricingMonth("2026-05");
    batch.setPriceAsOfTime(LocalDateTime.of(2026, 5, 26, 10, 0));
    batch.setBomSourcePolicy(CostRunContext.BOM_SOURCE_POLICY_HISTORICAL_OA_BOM);
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setAdjustBatchId(88L);
    return batch;
  }

  private CostRunTask task() {
    CostRunTask task = new CostRunTask();
    task.setId(11L);
    task.setBatchNo("CRM-001");
    task.setScene("MONTHLY_REPRICE");
    task.setSourceNo("MRP-001");
    task.setPricingMonth("2026-05");
    task.setBusinessUnitType("COMMERCIAL");
    task.setOaNo("OA-001");
    task.setOaFormItemId(7L);
    task.setProductCode("P-001");
    task.setPackageMethod("箱装");
    task.setCustomerName("客户A");
    task.setCalcObjectKey("OBJ-001");
    return task;
  }
}
