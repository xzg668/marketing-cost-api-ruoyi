package com.sanhua.marketingcost.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.ThreeExpenseRateRequest;
import com.sanhua.marketingcost.dto.ThreeExpenseRateImportRequest;
import com.sanhua.marketingcost.dto.ThreeExpenseRateImportResponse;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ThreeExpenseRateServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), ThreeExpenseRate.class);
  }

  @Test
  @DisplayName("list：支持按年度、标题拆分字段、申请部门和申请处室查询")
  void listSupportsTitleMatrixFilters() {
    ThreeExpenseRateMapper mapper = mock(ThreeExpenseRateMapper.class);
    when(mapper.selectList(any())).thenReturn(List.of());
    ThreeExpenseRateServiceImpl service = new ThreeExpenseRateServiceImpl(mapper);

    service.list("历史部门", 2026, "商用直销产品", "国内产线", "亚洲业务部", "一处");

    ArgumentCaptor<Wrapper<ThreeExpenseRate>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    Wrapper<ThreeExpenseRate> wrapper = captor.getValue();
    String sql = wrapper.getCustomSqlSegment();
    assertTrue(sql.contains("department"));
    assertTrue(sql.contains("period_year"));
    assertTrue(sql.contains("product_category"));
    assertTrue(sql.contains("product_line"));
    assertTrue(sql.contains("applicant_department"));
    assertTrue(sql.contains("applicant_office"));

    Map<String, Object> params =
        ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
    assertTrue(params.containsValue("2026") || params.containsValue(2026));
    assertTrue(params.containsValue("商用直销产品"));
    assertTrue(params.containsValue("国内产线"));
  }

  @Test
  @DisplayName("create：新口径字段能保存并回显，旧字段可为空")
  void createSupportsTitleMatrixFields() {
    ThreeExpenseRateMapper mapper = mock(ThreeExpenseRateMapper.class);
    ThreeExpenseRateServiceImpl service = new ThreeExpenseRateServiceImpl(mapper);

    ThreeExpenseRateRequest request = titleMatrixRequest();
    ThreeExpenseRate created = service.create(request);

    assertNotNull(created);
    assertEquals(2026, created.getPeriodYear());
    assertEquals("商用直销产品", created.getProductCategory());
    assertEquals("国内产线", created.getProductLine());
    assertEquals("亚洲业务部（直销）", created.getApplicantDepartment());
    assertEquals("", created.getApplicantOffice());
    assertEquals(new BigDecimal("0.060000"), created.getThreeExpenseTotalRate());
    assertEquals(new BigDecimal("0.005000"), created.getOemExpenseRate());
    assertEquals("EXCEL_IMPORT", created.getSourceType());
    assertEquals("COMMERCIAL", created.getBusinessUnitType());
    verify(mapper).insert(created);
  }

  @Test
  @DisplayName("update：编辑接口能保存并回显新字段")
  void updateSupportsTitleMatrixFields() {
    ThreeExpenseRateMapper mapper = mock(ThreeExpenseRateMapper.class);
    ThreeExpenseRate existing = new ThreeExpenseRate();
    existing.setId(10L);
    existing.setManagementExpenseRate(new BigDecimal("0.010000"));
    existing.setFinanceExpenseRate(new BigDecimal("0.020000"));
    existing.setSalesExpenseRate(new BigDecimal("0.030000"));
    when(mapper.selectById(10L)).thenReturn(existing);
    ThreeExpenseRateServiceImpl service = new ThreeExpenseRateServiceImpl(mapper);

    ThreeExpenseRateRequest request = titleMatrixRequest();
    request.setProductLine("墨西哥产线");
    ThreeExpenseRate updated = service.update(10L, request);

    assertNotNull(updated);
    assertEquals(2026, updated.getPeriodYear());
    assertEquals("商用直销产品", updated.getProductCategory());
    assertEquals("墨西哥产线", updated.getProductLine());
    assertEquals("亚洲业务部（直销）", updated.getApplicantDepartment());
    assertEquals("", updated.getApplicantOffice());
    verify(mapper).updateById(updated);
  }

  @Test
  @DisplayName("importItems：不同 productLine 的配置不会互相覆盖")
  void importKeepsDifferentProductLines() {
    ThreeExpenseRateMapper mapper = mock(ThreeExpenseRateMapper.class);
    when(mapper.selectOne(any())).thenReturn(null);
    ThreeExpenseRateServiceImpl service = new ThreeExpenseRateServiceImpl(mapper);
    ThreeExpenseRateImportRequest request = new ThreeExpenseRateImportRequest();
    request.setRows(List.of(importRow("国内产线", "0.010000"), importRow("墨西哥产线", "0.020000")));

    ThreeExpenseRateImportResponse response = service.importItems(request);

    assertEquals(2, response.getInsertedCount());
    assertEquals(0, response.getUpdatedCount());
    verify(mapper, times(2)).insert(any(ThreeExpenseRate.class));
  }

  @Test
  @DisplayName("importItems：同批重复只写最后一条并返回提示")
  void importSameBatchDuplicateUsesLastRow() {
    ThreeExpenseRateMapper mapper = mock(ThreeExpenseRateMapper.class);
    when(mapper.selectOne(any())).thenReturn(null);
    ThreeExpenseRateServiceImpl service = new ThreeExpenseRateServiceImpl(mapper);
    ThreeExpenseRateImportRequest request = new ThreeExpenseRateImportRequest();
    request.setRows(List.of(importRow("国内产线", "0.010000"), importRow("国内产线", "0.040000")));

    ThreeExpenseRateImportResponse response = service.importItems(request);

    assertEquals(1, response.getDuplicateOverrideCount());
    assertEquals(1, response.getInsertedCount());
    ArgumentCaptor<ThreeExpenseRate> captor = ArgumentCaptor.forClass(ThreeExpenseRate.class);
    verify(mapper).insert(captor.capture());
    assertEquals(new BigDecimal("0.040000"), captor.getValue().getManagementExpenseRate());
    assertEquals(1, response.getMessages().size());
  }

  @Test
  @DisplayName("importItems：重复导入同一唯一键时更新旧值")
  void importUpdatesExistingRow() {
    ThreeExpenseRateMapper mapper = mock(ThreeExpenseRateMapper.class);
    ThreeExpenseRate existing = new ThreeExpenseRate();
    existing.setId(20L);
    when(mapper.selectOne(any())).thenReturn(existing);
    ThreeExpenseRateServiceImpl service = new ThreeExpenseRateServiceImpl(mapper);
    ThreeExpenseRateImportRequest request = new ThreeExpenseRateImportRequest();
    request.setRows(List.of(importRow("国内产线", "0.040000")));

    ThreeExpenseRateImportResponse response = service.importItems(request);

    assertEquals(0, response.getInsertedCount());
    assertEquals(1, response.getUpdatedCount());
    assertEquals(new BigDecimal("0.040000"), existing.getManagementExpenseRate());
    verify(mapper).updateById(existing);
    verify(mapper, never()).insert(any(ThreeExpenseRate.class));
  }

  @Test
  @DisplayName("importItems：校验失败不写入半批数据")
  void importValidationFailureDoesNotWritePartialBatch() {
    ThreeExpenseRateMapper mapper = mock(ThreeExpenseRateMapper.class);
    ThreeExpenseRateServiceImpl service = new ThreeExpenseRateServiceImpl(mapper);
    ThreeExpenseRateImportRequest request = new ThreeExpenseRateImportRequest();
    ThreeExpenseRateImportRequest.ThreeExpenseRateRow invalid = importRow("国内产线", "0.010000");
    invalid.setApplicantDepartment("");
    request.setRows(List.of(importRow("墨西哥产线", "0.020000"), invalid));

    ThreeExpenseRateImportResponse response = service.importItems(request);

    assertEquals(1, response.getFailedCount());
    verify(mapper, never()).insert(any(ThreeExpenseRate.class));
    verify(mapper, never()).updateById(any(ThreeExpenseRate.class));
  }

  private static ThreeExpenseRateRequest titleMatrixRequest() {
    ThreeExpenseRateRequest request = new ThreeExpenseRateRequest();
    request.setPeriodYear(2026);
    request.setProductCategory("商用直销产品");
    request.setProductLine("国内产线");
    request.setApplicantDepartment("亚洲业务部（直销）");
    request.setApplicantOffice("/");
    request.setManagementExpenseRate(new BigDecimal("0.010000"));
    request.setFinanceExpenseRate(new BigDecimal("0.020000"));
    request.setSalesExpenseRate(new BigDecimal("0.030000"));
    request.setThreeExpenseTotalRate(new BigDecimal("0.060000"));
    request.setOemExpenseRate(new BigDecimal("0.005000"));
    return request;
  }

  private static ThreeExpenseRateImportRequest.ThreeExpenseRateRow importRow(
      String productLine, String managementRate) {
    ThreeExpenseRateImportRequest.ThreeExpenseRateRow row =
        new ThreeExpenseRateImportRequest.ThreeExpenseRateRow();
    row.setBusinessUnitType("COMMERCIAL");
    row.setPeriodYear(2026);
    row.setProductCategory("商用直销产品");
    row.setProductLine(productLine);
    row.setApplicantDepartment("亚洲业务部（直销）");
    row.setApplicantOffice("/");
    row.setManagementExpenseRate(new BigDecimal(managementRate));
    row.setFinanceExpenseRate(new BigDecimal("0.020000"));
    row.setSalesExpenseRate(new BigDecimal("0.030000"));
    row.setThreeExpenseTotalRate(
        row.getManagementExpenseRate().add(row.getFinanceExpenseRate()).add(row.getSalesExpenseRate()));
    row.setOemExpenseRate(new BigDecimal("0.005000"));
    row.setImportBatchNo("TEST-BATCH");
    return row;
  }
}
