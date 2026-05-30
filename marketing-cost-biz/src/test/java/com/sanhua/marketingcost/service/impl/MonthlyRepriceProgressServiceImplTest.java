package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.CostRunTaskStatusCount;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import com.sanhua.marketingcost.service.MonthlyRepriceAuditService;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("T8 月度调价批次进度服务")
class MonthlyRepriceProgressServiceImplTest {

  private MonthlyRepriceBatchMapper batchMapper;
  private CostRunTaskMapper taskMapper;
  private MonthlyRepriceResultMapper resultMapper;
  private MonthlyRepriceAuditService auditService;
  private MonthlyRepriceProgressServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceBatch.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = mock(MonthlyRepriceBatchMapper.class);
    taskMapper = mock(CostRunTaskMapper.class);
    resultMapper = mock(MonthlyRepriceResultMapper.class);
    auditService = mock(MonthlyRepriceAuditService.class);
    service = new MonthlyRepriceProgressServiceImpl(
        batchMapper, taskMapper, resultMapper, auditService);
    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(batch("RUNNING", 2, 0));
    when(resultMapper.countByRepriceNo("MRP-001")).thenReturn(2L);
  }

  @Test
  @DisplayName("refreshProgress：全部成功时进入 WAIT_CONFIRM")
  void refreshProgressMarksWaitConfirmWhenAllSuccess() {
    when(taskMapper.selectMonthlyRepriceStatusCounts("MRP-001"))
        .thenReturn(List.of(count("SUCCESS", 2)));

    MonthlyRepriceProgressSnapshot snapshot = service.refreshProgress("MRP-001");

    assertThat(snapshot.getStatus()).isEqualTo("WAIT_CONFIRM");
    assertThat(snapshot.getSuccessCount()).isEqualTo(2);
    assertThat(snapshot.getProgressPercent()).isEqualTo(100);

    ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
    verify(batchMapper).updateProgress(
        eq(1001L), eq(2), eq(0), eq(0), statusCaptor.capture(), any(), any());
    assertThat(statusCaptor.getValue()).isEqualTo("WAIT_CONFIRM");
    verify(auditService).recordCalcCompleted(any(MonthlyRepriceBatch.class), any());
  }

  @Test
  @DisplayName("refreshProgress：完成后存在失败任务则进入 FAILED")
  void refreshProgressMarksFailedWhenAnyTaskFailed() {
    when(taskMapper.selectMonthlyRepriceStatusCounts("MRP-001"))
        .thenReturn(List.of(count("SUCCESS", 1), count("FAILED", 1)));

    MonthlyRepriceProgressSnapshot snapshot = service.refreshProgress("MRP-001");

    assertThat(snapshot.getStatus()).isEqualTo("FAILED");
    assertThat(snapshot.getFailedCount()).isEqualTo(1);
    verify(batchMapper).updateProgress(
        eq(1001L), eq(1), eq(1), eq(0), eq("FAILED"), any(), any());
    verify(auditService).recordCalcFailed(any(MonthlyRepriceBatch.class), any());
  }

  @Test
  @DisplayName("refreshProgress：已确认批次只读，不再回写进度")
  void refreshProgressDoesNotModifyConfirmedBatch() {
    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(batch("CONFIRMED", 2, 0));
    when(taskMapper.selectMonthlyRepriceStatusCounts("MRP-001"))
        .thenReturn(List.of(count("SUCCESS", 2)));

    MonthlyRepriceProgressSnapshot snapshot = service.refreshProgress("MRP-001");

    assertThat(snapshot.getStatus()).isEqualTo("CONFIRMED");
    verify(batchMapper, never()).updateProgress(
        any(), anyInt(), anyInt(), anyInt(), anyString(), any(), any());
    verify(auditService, never()).recordCalcCompleted(any(), any());
    verify(auditService, never()).recordCalcFailed(any(), any());
  }

  @Test
  @DisplayName("refreshProgress：已取消批次只读，不清空 finished_at")
  void refreshProgressDoesNotModifyCancelledBatch() {
    MonthlyRepriceBatch cancelled = batch("CANCELLED", 2, 0);
    cancelled.setFinishedAt(LocalDateTime.of(2026, 5, 26, 11, 0));
    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(cancelled);
    when(taskMapper.selectMonthlyRepriceStatusCounts("MRP-001"))
        .thenReturn(List.of(count("CANCELED", 2)));

    MonthlyRepriceProgressSnapshot snapshot = service.refreshProgress("MRP-001");

    assertThat(snapshot.getStatus()).isEqualTo("CANCELLED");
    assertThat(snapshot.getFinishedAt()).isEqualTo(LocalDateTime.of(2026, 5, 26, 11, 0));
    verify(batchMapper, never()).updateProgress(
        any(), anyInt(), anyInt(), anyInt(), anyString(), any(), any());
  }

  @Test
  @DisplayName("getLatestConfirmedBatch：同月份同业务单元默认取最新 CONFIRMED")
  void getLatestConfirmedBatchUsesMapperDefault() {
    MonthlyRepriceBatch latest = batch("CONFIRMED", 3, 0);
    latest.setRepriceNo("MRP-LATEST");
    when(batchMapper.selectLatestConfirmed("2026-05", "COMMERCIAL")).thenReturn(latest);

    MonthlyRepriceBatch result = service.getLatestConfirmedBatch("2026-05", "COMMERCIAL");

    assertThat(result.getRepriceNo()).isEqualTo("MRP-LATEST");
    verify(batchMapper).selectLatestConfirmed("2026-05", "COMMERCIAL");
  }

  private MonthlyRepriceBatch batch(String status, int totalCount, int skippedCount) {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setId(1001L);
    batch.setRepriceNo("MRP-001");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setStatus(status);
    batch.setTotalCount(totalCount);
    batch.setSuccessCount(0);
    batch.setFailedCount(0);
    batch.setSkippedCount(skippedCount);
    batch.setStartedAt(LocalDateTime.of(2026, 5, 26, 9, 0));
    return batch;
  }

  private CostRunTaskStatusCount count(String status, long count) {
    CostRunTaskStatusCount row = new CostRunTaskStatusCount();
    row.setStatus(status);
    row.setCount(count);
    return row;
  }
}
