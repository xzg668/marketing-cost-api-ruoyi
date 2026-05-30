package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("T12 月度调价审计服务")
class MonthlyRepriceAuditServiceImplTest {

  private MonthlyRepriceAuditLogMapper auditLogMapper;
  private MonthlyRepriceAuditServiceImpl service;

  @BeforeEach
  void setUp() {
    auditLogMapper = mock(MonthlyRepriceAuditLogMapper.class);
    service = new MonthlyRepriceAuditServiceImpl(auditLogMapper, new ObjectMapper());
  }

  @Test
  @DisplayName("开始计算：按批次记录 RUNNING 快照")
  void recordStartCalcWritesReadableSnapshot() {
    service.recordStartCalc(batch("CREATED"), "alice", "准备完成");

    MonthlyRepriceAuditLog log = capturedLog();
    assertThat(log.getOperationType()).isEqualTo("START_CALC");
    assertThat(log.getOperationName()).isEqualTo("开始月度调价成本核算");
    assertThat(log.getOperatorName()).isEqualTo("alice");
    assertThat(log.getTargetType()).isEqualTo("MONTHLY_REPRICE_BATCH");
    assertThat(log.getBeforeJson()).contains("\"status\":\"CREATED\"");
    assertThat(log.getAfterJson()).contains("\"status\":\"RUNNING\"");
    assertThat(log.getChangeSummary()).isEqualTo("准备完成");
  }

  @Test
  @DisplayName("计算完成：记录成功/失败/结果数")
  void recordCalcCompletedWritesProgressSnapshot() {
    service.recordCalcCompleted(batch("RUNNING"), progress("WAIT_CONFIRM", 2, 0, 2));

    MonthlyRepriceAuditLog log = capturedLog();
    assertThat(log.getOperationType()).isEqualTo("CALC_COMPLETED");
    assertThat(log.getOperatorName()).isEqualTo("system");
    assertThat(log.getAfterJson()).contains("\"status\":\"WAIT_CONFIRM\"");
    assertThat(log.getAfterJson()).contains("\"resultCount\":2");
    assertThat(log.getChangeSummary()).contains("成功 2").contains("失败 0").contains("结果 2");
  }

  @Test
  @DisplayName("计算失败：记录失败收口信息")
  void recordCalcFailedWritesProgressSnapshot() {
    service.recordCalcFailed(batch("RUNNING"), progress("FAILED", 1, 1, 1));

    MonthlyRepriceAuditLog log = capturedLog();
    assertThat(log.getOperationType()).isEqualTo("CALC_FAILED");
    assertThat(log.getOperationName()).isEqualTo("月度调价成本核算失败");
    assertThat(log.getAfterJson()).contains("\"status\":\"FAILED\"");
    assertThat(log.getChangeSummary()).contains("失败 1");
  }

  @Test
  @DisplayName("普通 OA 拦截：记录 OA 与锁定批次")
  void recordOaCostRunBlockedWritesOaTarget() {
    service.recordOaCostRunBlocked(batch("WAIT_CONFIRM"), " OA-001 ", "bob");

    MonthlyRepriceAuditLog log = capturedLog();
    assertThat(log.getOperationType()).isEqualTo("BLOCK_OA_COST_RUN");
    assertThat(log.getTargetType()).isEqualTo("OA_COST_RUN");
    assertThat(log.getTargetKey()).isEqualTo("OA-001");
    assertThat(log.getAfterJson()).contains("\"blocked\":true");
    assertThat(log.getAfterJson()).contains("\"reason\":\"MONTHLY_REPRICE_LOCK\"");
  }

  private MonthlyRepriceAuditLog capturedLog() {
    ArgumentCaptor<MonthlyRepriceAuditLog> captor =
        ArgumentCaptor.forClass(MonthlyRepriceAuditLog.class);
    verify(auditLogMapper).insert(captor.capture());
    return captor.getValue();
  }

  private MonthlyRepriceBatch batch(String status) {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setId(1001L);
    batch.setRepriceNo("MRP-001");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setStatus(status);
    batch.setTotalCount(2);
    batch.setSuccessCount(0);
    batch.setFailedCount(0);
    batch.setSkippedCount(0);
    batch.setStartedAt(LocalDateTime.of(2026, 5, 26, 9, 0));
    return batch;
  }

  private MonthlyRepriceProgressSnapshot progress(
      String status, int successCount, int failedCount, int resultCount) {
    MonthlyRepriceProgressSnapshot progress = new MonthlyRepriceProgressSnapshot();
    progress.setRepriceNo("MRP-001");
    progress.setPricingMonth("2026-05");
    progress.setBusinessUnitType("COMMERCIAL");
    progress.setStatus(status);
    progress.setTotalCount(2);
    progress.setSuccessCount(successCount);
    progress.setFailedCount(failedCount);
    progress.setSkippedCount(0);
    progress.setResultCount(resultCount);
    progress.setProgressPercent(100);
    progress.setFinishedAt(LocalDateTime.of(2026, 5, 26, 10, 0));
    return progress;
  }
}
