package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MonthlyRepriceCalcObject;
import com.sanhua.marketingcost.dto.MonthlyRepriceObjectExpandResult;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.service.MonthlyRepriceTaskService;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("T3 月度调价核算对象展开服务")
class MonthlyRepriceObjectExpandServiceImplTest {

  private MonthlyRepriceBatchMapper batchMapper;
  private OaFormItemMapper oaFormItemMapper;
  private MonthlyRepriceTaskService taskService;
  private MonthlyRepriceAuditLogMapper auditLogMapper;
  private MonthlyRepriceObjectExpandServiceImpl service;

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
    oaFormItemMapper = mock(OaFormItemMapper.class);
    taskService = mock(MonthlyRepriceTaskService.class);
    auditLogMapper = mock(MonthlyRepriceAuditLogMapper.class);
    service = new MonthlyRepriceObjectExpandServiceImpl(
        batchMapper, oaFormItemMapper, taskService, auditLogMapper, new ObjectMapper());

    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(batch());
    List<MonthlyRepriceCalcObject> objects = List.of(calcObject("OA-1", "P-1"));
    when(oaFormItemMapper.selectMonthlyRepriceCalcObjects("COMMERCIAL", "已核算"))
        .thenReturn(objects);
    when(taskService.createTasks(any(MonthlyRepriceBatch.class), any()))
        .thenReturn(MonthlyRepriceObjectExpandResult.of("MRP202605260001", 1, 1, 0));
  }

  @Test
  @DisplayName("expand：按已核算 OA 明细查询并更新 batch.total_count")
  void expandQueriesCalculatedOaItemsAndUpdatesBatchCount() {
    var result = service.expand("MRP202605260001", "alice");

    assertThat(result.getTaskCount()).isEqualTo(1);
    verify(oaFormItemMapper).selectMonthlyRepriceCalcObjects("COMMERCIAL", "已核算");

    ArgumentCaptor<MonthlyRepriceBatch> batchCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceBatch.class);
    verify(batchMapper).updateById(batchCaptor.capture());
    MonthlyRepriceBatch update = batchCaptor.getValue();
    assertThat(update.getTotalCount()).isEqualTo(1);
    assertThat(update.getSuccessCount()).isZero();
    assertThat(update.getFailedCount()).isZero();
    assertThat(update.getSkippedCount()).isZero();
  }

  @Test
  @DisplayName("expand：记录生成任务审计日志，包含跳过数量")
  void expandWritesAuditLog() {
    when(taskService.createTasks(any(MonthlyRepriceBatch.class), any()))
        .thenReturn(MonthlyRepriceObjectExpandResult.of("MRP202605260001", 2, 1, 1));

    service.expand("MRP202605260001", "alice");

    ArgumentCaptor<MonthlyRepriceAuditLog> logCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceAuditLog.class);
    verify(auditLogMapper).insert(logCaptor.capture());
    MonthlyRepriceAuditLog log = logCaptor.getValue();
    assertThat(log.getOperationType()).isEqualTo("EXPAND_TASKS");
    assertThat(log.getOperationName()).contains("生成月度调价核算任务");
    assertThat(log.getChangeSummary()).contains("总对象 2", "任务 1", "跳过 1");
    assertThat(log.getAfterJson()).contains("\"skippedCount\":1");
  }

  private MonthlyRepriceBatch batch() {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setId(10001L);
    batch.setRepriceNo("MRP202605260001");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    return batch;
  }

  private MonthlyRepriceCalcObject calcObject(String oaNo, String productCode) {
    MonthlyRepriceCalcObject object = new MonthlyRepriceCalcObject();
    object.setOaNo(oaNo);
    object.setOaFormItemId(1L);
    object.setProductCode(productCode);
    object.setPackageMethod("箱装");
    object.setCustomerName("客户A");
    object.setSourceOaCalcStatus("已核算");
    return object;
  }
}
