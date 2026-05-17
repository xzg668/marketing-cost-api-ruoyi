package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.FactorAdjustExcelParseResult;
import com.sanhua.marketingcost.dto.FactorAdjustExcelParseRow;
import com.sanhua.marketingcost.dto.FactorAdjustImportRequest;
import com.sanhua.marketingcost.dto.FactorAdjustImportResponse;
import com.sanhua.marketingcost.entity.FactorAdjustBatch;
import com.sanhua.marketingcost.entity.FactorAdjustPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import com.sanhua.marketingcost.mapper.FactorAdjustBatchMapper;
import com.sanhua.marketingcost.mapper.FactorAdjustPriceMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceChangeLogMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.FactorAdjustExcelParseService;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FactorAdjustImportServiceImplTest {

  private FactorAdjustExcelParseService parseService;
  private FactorAdjustBatchMapper batchMapper;
  private FactorAdjustPriceMapper priceMapper;
  private FactorMonthlyPriceMapper monthlyPriceMapper;
  private FactorMonthlyPriceChangeLogMapper changeLogMapper;
  private FactorAdjustImportServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorAdjustBatch.class);
    TableInfoHelper.initTableInfo(assistant, FactorAdjustPrice.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPrice.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPriceChangeLog.class);
  }

  @BeforeEach
  void setUp() {
    parseService = mock(FactorAdjustExcelParseService.class);
    batchMapper = mock(FactorAdjustBatchMapper.class);
    priceMapper = mock(FactorAdjustPriceMapper.class);
    monthlyPriceMapper = mock(FactorMonthlyPriceMapper.class);
    changeLogMapper = mock(FactorMonthlyPriceChangeLogMapper.class);
    service = new FactorAdjustImportServiceImpl(
        parseService, batchMapper, priceMapper, monthlyPriceMapper, changeLogMapper);

    doAnswer(invocation -> {
      FactorAdjustBatch batch = invocation.getArgument(0);
      batch.setId(9001L);
      return 1;
    }).when(batchMapper).insert(any(FactorAdjustBatch.class));
    doAnswer(invocation -> {
      FactorAdjustPrice price = invocation.getArgument(0);
      if (price.getId() == null) {
        price.setId(8001L);
      }
      return 1;
    }).when(priceMapper).insert(any(FactorAdjustPrice.class));
    doAnswer(invocation -> {
      FactorMonthlyPrice price = invocation.getArgument(0);
      price.setId(7001L);
      return 1;
    }).when(monthlyPriceMapper).insert(any(FactorMonthlyPrice.class));
  }

  @Test
  @DisplayName("REPRICE_ONLY：只写调价批次和明细，不更新日常报价价")
  void repriceOnlyDoesNotUpdateDailyMonthlyPrice() {
    FactorAdjustExcelParseResult parseResult = parseResult(matchedRow(191L, 501L, "19.10"));
    when(parseService.parse(any(), eq("adjust.xlsx"), eq("2026-05"), eq("COMMERCIAL")))
        .thenReturn(parseResult);
    when(monthlyPriceMapper.selectById(501L)).thenReturn(monthlyPrice(501L, 191L, "2026-05", "18.79"));

    FactorAdjustImportResponse response = service.importAdjustExcel(
        new ByteArrayInputStream("fake".getBytes()), "adjust.xlsx",
        request("REPRICE_ONLY"), "alice");

    assertThat(response.getAdjustBatchId()).isEqualTo(9001L);
    assertThat(response.getUsageScope()).isEqualTo("REPRICE_ONLY");
    assertThat(response.getChangedCount()).isEqualTo(1);
    assertThat(response.getFailedCount()).isZero();
    assertThat(response.getStatus()).isEqualTo("SUCCESS");
    assertThat(response.getRows()).hasSize(1);
    assertThat(response.getRows().getFirst().getApplyToDaily()).isZero();
    assertThat(response.getRows().getFirst().getOriginalPrice()).isEqualByComparingTo("18.79");
    assertThat(response.getRows().getFirst().getAdjustedPrice()).isEqualByComparingTo("19.10");

    verify(monthlyPriceMapper, never()).updateById(any(FactorMonthlyPrice.class));
    verify(monthlyPriceMapper, never()).insert(any(FactorMonthlyPrice.class));
    verify(changeLogMapper, never()).insert(any(FactorMonthlyPriceChangeLog.class));
  }

  @Test
  @DisplayName("REPRICE_AND_DAILY：同步更新已有日常报价价并写日志")
  void repriceAndDailyUpdatesExistingMonthlyPriceAndWritesLog() {
    FactorAdjustExcelParseResult parseResult = parseResult(matchedRow(191L, 501L, "19.10"));
    when(parseService.parse(any(), eq("adjust.xlsx"), eq("2026-05"), eq("COMMERCIAL")))
        .thenReturn(parseResult);
    when(monthlyPriceMapper.selectById(501L)).thenReturn(monthlyPrice(501L, 191L, "2026-05", "18.79"));

    FactorAdjustImportResponse response = service.importAdjustExcel(
        new ByteArrayInputStream("fake".getBytes()), "adjust.xlsx",
        request("REPRICE_AND_DAILY"), "alice");

    assertThat(response.getChangedCount()).isEqualTo(1);
    assertThat(response.getRows().getFirst().getApplyToDaily()).isEqualTo(1);
    assertThat(response.getRows().getFirst().getFactorMonthlyPriceId()).isEqualTo(501L);

    ArgumentCaptor<FactorMonthlyPrice> monthlyCaptor =
        ArgumentCaptor.forClass(FactorMonthlyPrice.class);
    verify(monthlyPriceMapper).updateById(monthlyCaptor.capture());
    FactorMonthlyPrice updated = monthlyCaptor.getValue();
    assertThat(updated.getId()).isEqualTo(501L);
    assertThat(updated.getPrice()).isEqualByComparingTo("19.10");
    assertThat(updated.getLatestAdjustBatchId()).isEqualTo(9001L);
    assertThat(updated.getLatestAdjustSourceType()).isEqualTo("ADJUST_EXCEL_IMPORT");
    assertThat(updated.getLatestAdjustedBy()).isEqualTo("alice");
    assertThat(updated.getSourceTag()).isEqualTo("ADJUST_IMPORT");

    ArgumentCaptor<FactorMonthlyPriceChangeLog> logCaptor =
        ArgumentCaptor.forClass(FactorMonthlyPriceChangeLog.class);
    verify(changeLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getChangeType()).isEqualTo("ADJUST_IMPORT");
    assertThat(logCaptor.getValue().getAdjustBatchId()).isEqualTo(9001L);
    assertThat(logCaptor.getValue().getOldPrice()).isEqualByComparingTo("18.79");
    assertThat(logCaptor.getValue().getNewPrice()).isEqualByComparingTo("19.10");
  }

  @Test
  @DisplayName("REPRICE_AND_DAILY：目标月份没有日常价时新增月度价格")
  void repriceAndDailyCreatesMonthlyPriceWhenMissing() {
    FactorAdjustExcelParseRow row = matchedRow(191L, null, "19.10");
    FactorAdjustExcelParseResult parseResult = parseResult(row);
    when(parseService.parse(any(), eq("adjust.xlsx"), eq("2026-05"), eq("COMMERCIAL")))
        .thenReturn(parseResult);
    when(monthlyPriceMapper.selectOne(any(Wrapper.class))).thenReturn(null);

    FactorAdjustImportResponse response = service.importAdjustExcel(
        new ByteArrayInputStream("fake".getBytes()), "adjust.xlsx",
        request("REPRICE_AND_DAILY"), "alice");

    assertThat(response.getRows().getFirst().getFactorMonthlyPriceId()).isEqualTo(7001L);
    ArgumentCaptor<FactorMonthlyPrice> monthlyCaptor =
        ArgumentCaptor.forClass(FactorMonthlyPrice.class);
    verify(monthlyPriceMapper).insert(monthlyCaptor.capture());
    assertThat(monthlyCaptor.getValue().getFactorIdentityId()).isEqualTo(191L);
    assertThat(monthlyCaptor.getValue().getPriceMonth()).isEqualTo("2026-05");
    assertThat(monthlyCaptor.getValue().getPrice()).isEqualByComparingTo("19.10");
    verify(changeLogMapper).insert(any(FactorMonthlyPriceChangeLog.class));
  }

  @Test
  @DisplayName("部分失败：成功和失败明细都落库，批次状态 PARTIAL_SUCCESS")
  void partialFailurePersistsFailedRows() {
    FactorAdjustExcelParseResult parseResult = parseResult(
        matchedRow(191L, 501L, "19.10"),
        failedRow("未匹配到已有影响因素身份"));
    when(parseService.parse(any(), eq("adjust.xlsx"), eq("2026-05"), eq("COMMERCIAL")))
        .thenReturn(parseResult);
    when(monthlyPriceMapper.selectById(501L)).thenReturn(monthlyPrice(501L, 191L, "2026-05", "18.79"));

    FactorAdjustImportResponse response = service.importAdjustExcel(
        new ByteArrayInputStream("fake".getBytes()), "adjust.xlsx",
        request("REPRICE_ONLY"), "alice");

    assertThat(response.getChangedCount()).isEqualTo(1);
    assertThat(response.getFailedCount()).isEqualTo(1);
    assertThat(response.getStatus()).isEqualTo("PARTIAL_SUCCESS");
    assertThat(response.getRows()).hasSize(2);
    assertThat(response.getRows().get(1).getStatus()).isEqualTo("FAILED");
    assertThat(response.getRows().get(1).getFailReason()).contains("未匹配");
  }

  @Test
  @DisplayName("非法用途直接拒绝")
  void rejectsInvalidUsageScope() {
    assertThatThrownBy(() -> service.importAdjustExcel(
        new ByteArrayInputStream("fake".getBytes()), "adjust.xlsx",
        request("BAD_SCOPE"), "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("usageScope");
  }

  private FactorAdjustImportRequest request(String usageScope) {
    FactorAdjustImportRequest request = new FactorAdjustImportRequest();
    request.setPricingMonth("2026-05");
    request.setBusinessUnitType("COMMERCIAL");
    request.setUsageScope(usageScope);
    request.setRemark("5月调价");
    return request;
  }

  private FactorAdjustExcelParseResult parseResult(FactorAdjustExcelParseRow... rows) {
    FactorAdjustExcelParseResult result = new FactorAdjustExcelParseResult();
    result.setSourceFileName("adjust.xlsx");
    result.setPricingMonth("2026-05");
    result.setBusinessUnitType("COMMERCIAL");
    for (FactorAdjustExcelParseRow row : rows) {
      result.addRow(row);
    }
    return result;
  }

  private FactorAdjustExcelParseRow matchedRow(Long factorIdentityId, Long monthlyPriceId, String price) {
    FactorAdjustExcelParseRow row = new FactorAdjustExcelParseRow();
    row.setSourceSheetName("影响因素");
    row.setSourceRowNumber(3);
    row.setFactorIdentityId(factorIdentityId);
    row.setFactorMonthlyPriceId(monthlyPriceId);
    row.setFactorSeqNo("15");
    row.setFactorName("上月16日-本月15日中华商务网长江现货市场1#锰平均价格");
    row.setShortName("1#Mn");
    row.setPriceSource("平均价");
    row.setPrice(new BigDecimal(price));
    row.setOriginalPrice(new BigDecimal("18.7929"));
    row.setUnit("公斤");
    row.setMatchMethod("SYSTEM_ID");
    row.setStatus("MATCHED");
    return row;
  }

  private FactorAdjustExcelParseRow failedRow(String reason) {
    FactorAdjustExcelParseRow row = new FactorAdjustExcelParseRow();
    row.setSourceSheetName("影响因素");
    row.setSourceRowNumber(4);
    row.setFactorSeqNo("999");
    row.setFactorName("不存在材料");
    row.setShortName("NO_MATCH");
    row.setPriceSource("平均价");
    row.setPrice(new BigDecimal("1.23"));
    row.setStatus("FAILED");
    row.setFailReason(reason);
    return row;
  }

  private FactorMonthlyPrice monthlyPrice(Long id, Long factorIdentityId, String priceMonth, String price) {
    FactorMonthlyPrice monthlyPrice = new FactorMonthlyPrice();
    monthlyPrice.setId(id);
    monthlyPrice.setFactorIdentityId(factorIdentityId);
    monthlyPrice.setPriceMonth(priceMonth);
    monthlyPrice.setPrice(new BigDecimal(price));
    monthlyPrice.setStatus("ACTIVE");
    return monthlyPrice;
  }
}
