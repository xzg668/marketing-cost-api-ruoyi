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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.MonthlyRepriceProgressService;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("T8 月度调价确认服务")
class MonthlyRepriceConfirmServiceImplTest {

  private MonthlyRepriceBatchMapper batchMapper;
  private CostRunTaskMapper taskMapper;
  private MonthlyRepriceResultMapper resultMapper;
  private MonthlyRepriceAuditLogMapper auditLogMapper;
  private MonthlyRepriceProgressService progressService;
  private MonthlyRepriceConfirmServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceBatch.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceAuditLog.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = mock(MonthlyRepriceBatchMapper.class);
    taskMapper = mock(CostRunTaskMapper.class);
    resultMapper = mock(MonthlyRepriceResultMapper.class);
    auditLogMapper = mock(MonthlyRepriceAuditLogMapper.class);
    progressService = mock(MonthlyRepriceProgressService.class);
    service = new MonthlyRepriceConfirmServiceImpl(
        batchMapper,
        taskMapper,
        resultMapper,
        auditLogMapper,
        progressService,
        new PermissionService(),
        new ObjectMapper());
    authenticate("COMMERCIAL", "ROLE_BU_DIRECTOR");
    when(batchMapper.selectByRepriceNoForUpdate("MRP-001")).thenReturn(batch("WAIT_CONFIRM"));
    when(progressService.refreshProgress("MRP-001")).thenReturn(progress(2, 2, 0, "WAIT_CONFIRM"));
    when(resultMapper.countByRepriceNo("MRP-001")).thenReturn(2L);
    when(batchMapper.confirmBatch(any(), any(), any(), any())).thenReturn(1);
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("confirm：满足所有校验后确认并写审计日志")
  void confirmUpdatesBatchAndWritesAuditLog() {
    MonthlyRepriceProgressSnapshot result = service.confirm("MRP-001", "alice");

    assertThat(result.getStatus()).isEqualTo("CONFIRMED");
    assertThat(result.getConfirmedAt()).isNotNull();
    verify(batchMapper).confirmBatch(any(), any(), any(), any());

    ArgumentCaptor<MonthlyRepriceAuditLog> auditCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceAuditLog.class);
    verify(auditLogMapper).insert(auditCaptor.capture());
    MonthlyRepriceAuditLog log = auditCaptor.getValue();
    assertThat(log.getOperationType()).isEqualTo("CONFIRM_BATCH");
    assertThat(log.getAfterJson()).contains("\"status\":\"CONFIRMED\"");
  }

  @Test
  @DisplayName("confirm：failed_count > 0 不能确认")
  void failedCountCannotConfirm() {
    when(progressService.refreshProgress("MRP-001")).thenReturn(progress(2, 1, 1, "WAIT_CONFIRM"));

    assertThatThrownBy(() -> service.confirm("MRP-001", "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("失败数");

    verify(batchMapper, never()).confirmBatch(any(), any(), any(), any());
  }

  @Test
  @DisplayName("confirm：result 数量不等于 success_count 不能确认")
  void resultCountMismatchCannotConfirm() {
    when(resultMapper.countByRepriceNo("MRP-001")).thenReturn(1L);

    assertThatThrownBy(() -> service.confirm("MRP-001", "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("结果数量");

    verify(batchMapper, never()).confirmBatch(any(), any(), any(), any());
  }

  @Test
  @DisplayName("confirm：缺少部品明细不能确认")
  void missingPartItemsCannotConfirm() {
    when(resultMapper.countResultsMissingPartItems("MRP-001")).thenReturn(1L);

    assertThatThrownBy(() -> service.confirm("MRP-001", "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("部品明细");

    verify(batchMapper, never()).confirmBatch(any(), any(), any(), any());
  }

  @Test
  @DisplayName("confirm：缺少成本项明细不能确认")
  void missingCostItemsCannotConfirm() {
    when(resultMapper.countResultsMissingCostItems("MRP-001")).thenReturn(1L);

    assertThatThrownBy(() -> service.confirm("MRP-001", "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("成本项明细");

    verify(batchMapper, never()).confirmBatch(any(), any(), any(), any());
  }

  @Test
  @DisplayName("confirm：重复 result 不能确认")
  void duplicateResultCannotConfirm() {
    when(resultMapper.countDuplicateCalcObjectKeys("MRP-001")).thenReturn(1L);

    assertThatThrownBy(() -> service.confirm("MRP-001", "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复");

    verify(batchMapper, never()).confirmBatch(any(), any(), any(), any());
  }

  @Test
  @DisplayName("confirm：CONFIRMED 批次不能修改")
  void confirmedBatchCannotBeModified() {
    when(batchMapper.selectByRepriceNoForUpdate("MRP-001")).thenReturn(batch("CONFIRMED"));

    assertThatThrownBy(() -> service.confirm("MRP-001", "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能再次修改");

    verify(progressService, never()).refreshProgress(any());
    verify(batchMapper, never()).confirmBatch(any(), any(), any(), any());
  }

  @Test
  @DisplayName("confirm：非业务总监不能确认")
  void nonDirectorCannotConfirm() {
    authenticate("COMMERCIAL", "ROLE_BU_STAFF");

    assertThatThrownBy(() -> service.confirm("MRP-001", "bob"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("业务总监");

    verify(batchMapper, never()).confirmBatch(any(), any(), any(), any());
  }

  private MonthlyRepriceBatch batch(String status) {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setId(1001L);
    batch.setRepriceNo("MRP-001");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setStatus(status);
    batch.setTotalCount(2);
    batch.setSuccessCount(2);
    batch.setFailedCount(0);
    batch.setSkippedCount(0);
    return batch;
  }

  private MonthlyRepriceProgressSnapshot progress(
      int totalCount, int successCount, int failedCount, String status) {
    MonthlyRepriceProgressSnapshot snapshot = new MonthlyRepriceProgressSnapshot();
    snapshot.setId(1001L);
    snapshot.setRepriceNo("MRP-001");
    snapshot.setPricingMonth("2026-05");
    snapshot.setBusinessUnitType("COMMERCIAL");
    snapshot.setStatus(status);
    snapshot.setTotalCount(totalCount);
    snapshot.setSuccessCount(successCount);
    snapshot.setFailedCount(failedCount);
    snapshot.setSkippedCount(0);
    snapshot.setResultCount(successCount);
    return snapshot;
  }

  private void authenticate(String businessUnitType, String... authorities) {
    List<SimpleGrantedAuthority> auths = java.util.Arrays.stream(authorities)
        .map(SimpleGrantedAuthority::new)
        .toList();
    UsernamePasswordAuthenticationToken token =
        new UsernamePasswordAuthenticationToken("u", "N/A", auths);
    if (businessUnitType != null) {
      token.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    }
    SecurityContextHolder.getContext().setAuthentication(token);
  }
}
