package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MonthlyRepriceAuditLogQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchQueryRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceResultQueryRequest;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.entity.MonthlyRepriceCostItem;
import com.sanhua.marketingcost.entity.MonthlyRepricePartItem;
import com.sanhua.marketingcost.entity.MonthlyRepriceResult;
import com.sanhua.marketingcost.mapper.CostRunTaskMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceCostItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepricePartItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import java.math.BigDecimal;
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

@DisplayName("T9 月度调价查询服务")
class MonthlyRepriceQueryServiceImplTest {

  private MonthlyRepriceBatchMapper batchMapper;
  private CostRunTaskMapper taskMapper;
  private MonthlyRepriceResultMapper resultMapper;
  private MonthlyRepricePartItemMapper partItemMapper;
  private MonthlyRepriceCostItemMapper costItemMapper;
  private MonthlyRepriceAuditLogMapper auditLogMapper;
  private MonthlyRepriceQueryServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceBatch.class);
    TableInfoHelper.initTableInfo(assistant, CostRunTask.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceResult.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepricePartItem.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceCostItem.class);
    TableInfoHelper.initTableInfo(assistant, MonthlyRepriceAuditLog.class);
  }

  @BeforeEach
  void setUp() {
    batchMapper = mock(MonthlyRepriceBatchMapper.class);
    taskMapper = mock(CostRunTaskMapper.class);
    resultMapper = mock(MonthlyRepriceResultMapper.class);
    partItemMapper = mock(MonthlyRepricePartItemMapper.class);
    costItemMapper = mock(MonthlyRepriceCostItemMapper.class);
    auditLogMapper = mock(MonthlyRepriceAuditLogMapper.class);
    service = new MonthlyRepriceQueryServiceImpl(
        batchMapper,
        taskMapper,
        resultMapper,
        partItemMapper,
        costItemMapper,
        auditLogMapper,
        new PermissionService());
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("普通用户查批次：强制限定当前业务单元和已确认状态，分页排序透传")
  void normalUserBatchQueryOnlyConfirmedInCurrentBusinessUnit() {
    authenticate("COMMERCIAL", "cost:run:list", "ROLE_BU_STAFF");
    when(batchMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
      Page<MonthlyRepriceBatch> page = invocation.getArgument(0);
      page.setTotal(0);
      page.setRecords(List.of());
      return page;
    });
    MonthlyRepriceBatchQueryRequest request = new MonthlyRepriceBatchQueryRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setStatus("WAIT_CONFIRM");
    request.setPage(2);
    request.setPageSize(50);
    request.setSortBy("confirmedAt");
    request.setSortDirection("asc");

    service.pageBatches(request);

    ArgumentCaptor<Page<MonthlyRepriceBatch>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    ArgumentCaptor<Wrapper<MonthlyRepriceBatch>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
    org.mockito.Mockito.verify(batchMapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(50);
    assertThat(wrapperCaptor.getValue().getCustomSqlSegment())
        .contains("business_unit_type", "status", "confirmed_at ASC", "id DESC");
    assertThat(((AbstractWrapper<?, ?, ?>) wrapperCaptor.getValue()).getParamNameValuePairs().values())
        .contains("COMMERCIAL", "__NO_VISIBLE_STATUS__");
  }

  @Test
  @DisplayName("普通用户不能查看 WAIT_CONFIRM 结果")
  void normalUserCannotViewUnconfirmedResults() {
    authenticate("COMMERCIAL", "cost:run:list", "ROLE_BU_STAFF");
    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(batch("WAIT_CONFIRM"));

    assertThatThrownBy(() -> service.pageResults("MRP-001", new MonthlyRepriceResultQueryRequest()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("未确认");
  }

  @Test
  @DisplayName("普通用户可以按现有成本权限查看 CONFIRMED 结果")
  void normalUserCanViewConfirmedResults() {
    authenticate("COMMERCIAL", "cost:run:list", "ROLE_BU_STAFF");
    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(batch("CONFIRMED"));
    when(resultMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
      Page<MonthlyRepriceResult> page = invocation.getArgument(0);
      MonthlyRepriceResult row = new MonthlyRepriceResult();
      row.setRepriceNo("MRP-001");
      row.setProductCode("P-001");
      page.setTotal(1);
      page.setRecords(List.of(row));
      return page;
    });

    var response = service.pageResults("MRP-001", new MonthlyRepriceResultQueryRequest());

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getList()).extracting("productCode").containsExactly("P-001");
  }

  @Test
  @DisplayName("业务总监可以查看 WAIT_CONFIRM 结果")
  void directorCanViewUnconfirmedResults() {
    authenticate("COMMERCIAL", "ROLE_bu_director");
    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(batch("WAIT_CONFIRM"));
    when(resultMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
      Page<MonthlyRepriceResult> page = invocation.getArgument(0);
      MonthlyRepriceResult row = new MonthlyRepriceResult();
      row.setRepriceNo("MRP-001");
      row.setProductCode("P-001");
      row.setTotalCost(new BigDecimal("12.34"));
      page.setTotal(1);
      page.setRecords(List.of(row));
      return page;
    });

    var response = service.pageResults("MRP-001", new MonthlyRepriceResultQueryRequest());

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getList()).extracting("productCode").containsExactly("P-001");
  }

  @Test
  @DisplayName("普通用户查审计：未指定批次时只返回已确认批次关联日志")
  void normalUserAuditQueryOnlyConfirmedBatches() {
    authenticate("COMMERCIAL", "cost:run:list", "ROLE_BU_STAFF");
    when(auditLogMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
      Page<MonthlyRepriceAuditLog> page = invocation.getArgument(0);
      page.setTotal(0);
      page.setRecords(List.of());
      return page;
    });

    service.pageAuditLogs(new MonthlyRepriceAuditLogQueryRequest());

    ArgumentCaptor<Wrapper<MonthlyRepriceAuditLog>> captor = ArgumentCaptor.forClass(Wrapper.class);
    org.mockito.Mockito.verify(auditLogMapper).selectPage(any(Page.class), captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("SELECT reprice_no FROM lp_monthly_reprice_batch WHERE status = 'CONFIRMED'")
        .contains("business_unit_type");
  }

  @Test
  @DisplayName("结果下钻：按结果行 calcObjectKey 查询部品和成本项明细，避免一个 OA 多产品串数据")
  void resultDrillDownUsesCalcObjectKey() {
    authenticate("COMMERCIAL", "price:monthly-reprice:review", "ROLE_BU_DIRECTOR");
    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(batch("WAIT_CONFIRM"));
    MonthlyRepriceResult result = new MonthlyRepriceResult();
    result.setId(11L);
    result.setRepriceNo("MRP-001");
    result.setCalcObjectKey("OA-001|1001|P-001|BOX|客户A");
    when(resultMapper.selectOne(any(Wrapper.class))).thenReturn(result);
    MonthlyRepricePartItem partItem = new MonthlyRepricePartItem();
    partItem.setPartCode("PART-1");
    when(partItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(partItem));
    MonthlyRepriceCostItem costItem = new MonthlyRepriceCostItem();
    costItem.setCostItemCode("MATERIAL");
    when(costItemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(costItem));

    var parts = service.listPartItems("MRP-001", 11L);
    var costs = service.listCostItems("MRP-001", 11L);

    assertThat(parts).extracting("partCode").containsExactly("PART-1");
    assertThat(costs).extracting("costItemCode").containsExactly("MATERIAL");
    ArgumentCaptor<Wrapper<MonthlyRepricePartItem>> partCaptor = ArgumentCaptor.forClass(Wrapper.class);
    org.mockito.Mockito.verify(partItemMapper).selectList(partCaptor.capture());
    assertThat(partCaptor.getValue().getCustomSqlSegment())
        .contains("reprice_no", "calc_object_key");
  }

  @Test
  @DisplayName("active-lock：普通成本页可读取当前业务单元是否被月度调价锁定")
  void activeLockReturnsCurrentBusinessUnitLock() {
    authenticate("COMMERCIAL", "cost:run:list", "ROLE_BU_STAFF");
    MonthlyRepriceBatch batch = batch("CREATED");
    batch.setRepriceNo("MRP-CREATED");
    when(batchMapper.selectOne(any(Wrapper.class))).thenReturn(batch);

    var lock = service.getActiveLock();

    assertThat(lock.isLocked()).isTrue();
    assertThat(lock.getRepriceNo()).isEqualTo("MRP-CREATED");
    assertThat(lock.getMessage()).contains("正在月度调价");
  }

  private MonthlyRepriceBatch batch(String status) {
    MonthlyRepriceBatch batch = new MonthlyRepriceBatch();
    batch.setId(1001L);
    batch.setRepriceNo("MRP-001");
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setStatus(status);
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
