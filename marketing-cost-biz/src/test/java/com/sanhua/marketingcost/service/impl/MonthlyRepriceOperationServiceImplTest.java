package com.sanhua.marketingcost.service.impl;

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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("T9 月度调价取消和重试服务")
class MonthlyRepriceOperationServiceImplTest {

  private MonthlyRepriceBatchMapper batchMapper;
  private CostRunTaskMapper taskMapper;
  private MonthlyRepriceAuditLogMapper auditLogMapper;
  private MonthlyRepriceProgressService progressService;
  private MonthlyRepriceOperationServiceImpl service;

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
    auditLogMapper = mock(MonthlyRepriceAuditLogMapper.class);
    progressService = mock(MonthlyRepriceProgressService.class);
    service = new MonthlyRepriceOperationServiceImpl(
        batchMapper,
        taskMapper,
        auditLogMapper,
        progressService,
        new PermissionService(),
        new ObjectMapper());
    authenticate("COMMERCIAL", "ROLE_BU_DIRECTOR");
    when(progressService.getProgress("MRP-001")).thenReturn(new MonthlyRepriceProgressSnapshot());
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("cancel：未确认批次可取消，并停止未完成任务")
  void cancelOpenBatch() {
    when(batchMapper.selectByRepriceNoForUpdate("MRP-001")).thenReturn(batch("RUNNING"));
    when(taskMapper.cancelMonthlyRepriceOpenTasks(any(), any())).thenReturn(3);
    when(batchMapper.cancelBatch(any(), any())).thenReturn(1);

    service.cancel("MRP-001", "alice");

    verify(taskMapper)
        .cancelMonthlyRepriceOpenTasks(org.mockito.ArgumentMatchers.eq("MRP-001"), any());
    verify(batchMapper).cancelBatch(org.mockito.ArgumentMatchers.eq("MRP-001"), any());
    verify(auditLogMapper).insert(any(MonthlyRepriceAuditLog.class));
  }

  @Test
  @DisplayName("cancel：已确认批次不能取消")
  void confirmedBatchCannotCancel() {
    when(batchMapper.selectByRepriceNoForUpdate("MRP-001")).thenReturn(batch("CONFIRMED"));

    assertThatThrownBy(() -> service.cancel("MRP-001", "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能取消");

    verify(taskMapper, never()).cancelMonthlyRepriceOpenTasks(any(), any());
  }

  @Test
  @DisplayName("retryFailed：FAILED 批次的失败任务重置为 PENDING")
  void retryFailedTasks() {
    when(batchMapper.selectByRepriceNoForUpdate("MRP-001")).thenReturn(batch("FAILED"));
    when(taskMapper.retryMonthlyRepriceFailedTasks(any(), any())).thenReturn(2);
    when(batchMapper.retryFailedBatch(any(), any())).thenReturn(1);

    service.retryFailed("MRP-001", "alice");

    verify(taskMapper)
        .retryMonthlyRepriceFailedTasks(org.mockito.ArgumentMatchers.eq("MRP-001"), any());
    verify(batchMapper).retryFailedBatch(org.mockito.ArgumentMatchers.eq("MRP-001"), any());
    verify(auditLogMapper).insert(any(MonthlyRepriceAuditLog.class));
  }

  @Test
  @DisplayName("retryFailed：非业务总监不能重试")
  void nonDirectorCannotRetry() {
    authenticate("COMMERCIAL", "ROLE_BU_STAFF");

    assertThatThrownBy(() -> service.retryFailed("MRP-001", "bob"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("业务总监");
  }

  private MonthlyRepriceBatch batch(String status) {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setId(1001L);
    batch.setRepriceNo("MRP-001");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setStatus(status);
    batch.setTotalCount(2);
    batch.setSuccessCount(1);
    batch.setFailedCount(1);
    batch.setSkippedCount(0);
    return batch;
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
