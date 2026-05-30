package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.MonthlyRepriceProperties;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateRequest;
import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.mapper.MonthlyRepriceAuditLogMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("T2 月度调价批次服务")
class MonthlyRepriceBatchServiceImplTest {

  private MonthlyRepriceBatchMapper batchMapper;
  private MonthlyRepriceAuditLogMapper auditLogMapper;
  private MonthlyRepriceProperties monthlyRepriceProperties;
  private MonthlyRepriceBatchServiceImpl service;

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
    auditLogMapper = mock(MonthlyRepriceAuditLogMapper.class);
    monthlyRepriceProperties = new MonthlyRepriceProperties();
    service = new MonthlyRepriceBatchServiceImpl(
        batchMapper,
        auditLogMapper,
        new PermissionService(),
        monthlyRepriceProperties,
        new ObjectMapper());
    authenticate("COMMERCIAL", "ROLE_BU_DIRECTOR");
    when(batchMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
    doAnswer(invocation -> {
      MonthlyRepriceBatch batch = invocation.getArgument(0);
      batch.setId(10001L);
      return 1;
    }).when(batchMapper).insert(any(MonthlyRepriceBatch.class));
    doAnswer(invocation -> {
      MonthlyRepriceAuditLog log = invocation.getArgument(0);
      log.setId(20001L);
      return 1;
    }).when(auditLogMapper).insert(any(MonthlyRepriceAuditLog.class));
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("createBatch：创建批次但不触发核算，并写入审计日志")
  void createBatchCreatesControlBatchAndAuditLog() {
    MonthlyRepriceBatchCreateRequest request = request();

    var response = service.createBatch(request, "alice");

    assertThat(response.getId()).isEqualTo(10001L);
    assertThat(response.getRepriceNo()).startsWith("MRP");
    assertThat(response.getPricingMonth()).isEqualTo("2026-05");
    assertThat(response.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(response.getExecutionBackend()).isEqualTo("LOCAL_WORKER");
    assertThat(response.getStatus()).isEqualTo("CREATED");
    assertThat(response.getTotalCount()).isZero();

    ArgumentCaptor<MonthlyRepriceBatch> batchCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceBatch.class);
    verify(batchMapper).insert(batchCaptor.capture());
    MonthlyRepriceBatch batch = batchCaptor.getValue();
    assertThat(batch.getAdjustBatchId()).isNull();
    assertThat(batch.getPriceAsOfTime()).isNotNull();
    assertThat(batch.getBomSourcePolicy()).isEqualTo("HISTORICAL_OA_BOM");
    assertThat(batch.getCreatedBy()).isEqualTo("alice");
    assertThat(batch.getCreatedName()).isEqualTo("alice");
    assertThat(batch.getSuccessCount()).isZero();
    assertThat(batch.getFailedCount()).isZero();
    assertThat(batch.getSkippedCount()).isZero();

    ArgumentCaptor<MonthlyRepriceAuditLog> logCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceAuditLog.class);
    verify(auditLogMapper).insert(logCaptor.capture());
    MonthlyRepriceAuditLog log = logCaptor.getValue();
    assertThat(log.getOperationType()).isEqualTo("CREATE_BATCH");
    assertThat(log.getOperationName()).contains("发起月度调价");
    assertThat(log.getOperatorRole()).isEqualTo("BU_DIRECTOR");
    assertThat(log.getAfterJson()).contains(batch.getRepriceNo(), "\"priceAsOfTime\"");
    assertThat(log.getChangeSummary()).contains("固化取价时点", "全价格源重算");
  }

  @Test
  @DisplayName("createBatch：同业务单元已有未结束批次时拒绝")
  void createBatchRejectsWhenBusinessUnitAlreadyActive() {
    when(batchMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

    assertThatThrownBy(() -> service.createBatch(request(), "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("正在月度调价");

    verify(batchMapper, never()).insert(any(MonthlyRepriceBatch.class));
    verify(auditLogMapper, never()).insert(any(MonthlyRepriceAuditLog.class));
  }

  @Test
  @DisplayName("createBatch：唯一索引并发兜底异常转换为业务提示")
  void createBatchConvertsDuplicateKeyToBusinessMessage() {
    when(batchMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
    doThrow(new DuplicateKeyException("uk_monthly_reprice_active_bu"))
        .when(batchMapper).insert(any(MonthlyRepriceBatch.class));

    assertThatThrownBy(() -> service.createBatch(request(), "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("正在月度调价");

    verify(auditLogMapper, never()).insert(any(MonthlyRepriceAuditLog.class));
  }

  @Test
  @DisplayName("createBatch：同月份允许顺序多次调价，只要没有未结束批次")
  void createBatchAllowsSequentialSameMonthWhenNoActiveBatch() {
    when(batchMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

    var response = service.createBatch(request(), "alice");

    assertThat(response.getPricingMonth()).isEqualTo("2026-05");
    assertThat(response.getStatus()).isEqualTo("CREATED");
    verify(batchMapper).insert(any(MonthlyRepriceBatch.class));
  }

  @Test
  @DisplayName("createBatch：非业务总监不能发起")
  void createBatchRejectsNonDirector() {
    authenticate("COMMERCIAL", "ROLE_BU_STAFF");

    assertThatThrownBy(() -> service.createBatch(request(), "bob"))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("业务总监");

    verify(batchMapper, never()).insert(any(MonthlyRepriceBatch.class));
  }

  @Test
  @DisplayName("createBatch：灰度总开关关闭时拒绝新建批次")
  void createBatchRejectsWhenFeatureDisabled() {
    monthlyRepriceProperties.setEnabled(false);

    assertThatThrownBy(() -> service.createBatch(request(), "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("入口未开启");

    verify(batchMapper, never()).insert(any(MonthlyRepriceBatch.class));
    verify(auditLogMapper, never()).insert(any(MonthlyRepriceAuditLog.class));
  }

  @Test
  @DisplayName("createBatch：当前业务单元不在灰度白名单时拒绝")
  void createBatchRejectsWhenBusinessUnitNotAllowed() {
    monthlyRepriceProperties.setAllowedBusinessUnits(List.of("HOUSEHOLD"));

    assertThatThrownBy(() -> service.createBatch(request(), "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未纳入月度调价灰度范围");

    verify(batchMapper, never()).insert(any(MonthlyRepriceBatch.class));
    verify(auditLogMapper, never()).insert(any(MonthlyRepriceAuditLog.class));
  }

  @Test
  @DisplayName("createBatch：灰度白名单包含当前业务单元时允许新建批次")
  void createBatchAllowsConfiguredBusinessUnit() {
    monthlyRepriceProperties.setAllowedBusinessUnits(List.of(" COMMERCIAL "));

    var response = service.createBatch(request(), "alice");

    assertThat(response.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    verify(batchMapper).insert(any(MonthlyRepriceBatch.class));
  }

  @Test
  @DisplayName("createBatch：第一阶段只允许 LOCAL_WORKER 后端")
  void createBatchRejectsUnsupportedExecutionBackend() {
    monthlyRepriceProperties.setExecutionBackend("EASYDATA");

    assertThatThrownBy(() -> service.createBatch(request(), "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("LOCAL_WORKER");

    verify(batchMapper, never()).insert(any(MonthlyRepriceBatch.class));
  }

  @Test
  @DisplayName("createBatch：未知执行后端直接拒绝，避免落入不可控链路")
  void createBatchRejectsUnknownExecutionBackend() {
    monthlyRepriceProperties.setExecutionBackend("REMOTE_PLATFORM");

    assertThatThrownBy(() -> service.createBatch(request(), "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的月度调价执行后端");

    verify(batchMapper, never()).insert(any(MonthlyRepriceBatch.class));
  }

  @Test
  @DisplayName("createBatch：未选择业务单元时拒绝")
  void createBatchRejectsMissingBusinessUnitContext() {
    authenticate(null, "ROLE_BU_DIRECTOR");
    MonthlyRepriceBatchCreateRequest request = request();
    request.setBusinessUnitType(null);

    assertThatThrownBy(() -> service.createBatch(request, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("选择业务单元");
  }

  @Test
  @DisplayName("createBatch：非管理员请求业务单元必须与登录业务单元一致")
  void createBatchRejectsMismatchedBusinessUnitForNonAdmin() {
    MonthlyRepriceBatchCreateRequest request = request();
    request.setBusinessUnitType("HOUSEHOLD");

    assertThatThrownBy(() -> service.createBatch(request, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("业务单元不一致");
  }

  @Test
  @DisplayName("createBatch：不再要求影响因素调价批次，兼容旧字段但创建时忽略")
  void createBatchIgnoresLegacyAdjustBatchId() {
    MonthlyRepriceBatchCreateRequest request = request();
    request.setAdjustBatchId(9001L);

    service.createBatch(request, "alice");

    ArgumentCaptor<MonthlyRepriceBatch> batchCaptor =
        ArgumentCaptor.forClass(MonthlyRepriceBatch.class);
    verify(batchMapper).insert(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getAdjustBatchId()).isNull();
  }

  @Test
  @DisplayName("hasActiveBatch：锁定口径只按业务单元和未结束状态判断")
  void hasActiveBatchUsesBusinessUnitAndActiveStatuses() {
    when(batchMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

    assertThat(service.hasActiveBatch("COMMERCIAL")).isTrue();

    ArgumentCaptor<Wrapper<MonthlyRepriceBatch>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(batchMapper).selectCount(captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment())
        .contains("business_unit_type", "status")
        .doesNotContain("pricing_month");
  }

  private MonthlyRepriceBatchCreateRequest request() {
    MonthlyRepriceBatchCreateRequest request = new MonthlyRepriceBatchCreateRequest();
    request.setPricingMonth("2026-05");
    request.setBusinessUnitType("COMMERCIAL");
    request.setRemark("5月月度调价");
    return request;
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
