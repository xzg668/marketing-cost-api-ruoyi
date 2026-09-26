package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileUpdateRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishRequest;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("integration")
@DisplayName("TW-01 批量分派的产品隔离与并发唯一性（真实 MySQL）")
class TechnicalDataTaskApplicationIntegrationTest extends BomMapperTestBase {
  private static final AtomicLong IDS = new AtomicLong(8_100_000);
  private static final TechnicalDataActor ADMIN = new TechnicalDataActor(1L, "管理员", Set.of("*:*:*"));
  @Autowired private TechnicalDataTaskApplicationService service;
  @Autowired private TechnicalDataProfileApplicationService profiles;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private TechnicalDataQuoteSourceReader sources;
  @Autowired private com.sanhua.marketingcost.mapper.QuoteTechModuleMapper modules;
  @Autowired private TechnicalDataRequirementRefreshService refresh;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
  @MockBean private SysUserService users;
  @MockBean private com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy access;
  @MockBean private com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowClient oa;
  @MockBean private com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryRepository directory;
  @Autowired private com.sanhua.marketingcost.integration.oa.OaIntegrationProperties oaProperties;
  @MockBean private com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway u9;

  @MockBean private com.sanhua.marketingcost.service.NetLossRateQuery netLoss;
  @MockBean private TechnicalDataPackageSourceCheck packaging;

  @BeforeEach void prepareUsers() {
    // 任务身份测试明确提供相邻模块的公共来源；实际包装和取率由各模块集成测试覆盖。
    when(netLoss.lookup(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new com.sanhua.marketingcost.service.NetLossRateQuery.Source("AVAILABLE", "PUBLIC", "公共费率可用",
            1L, "PUBLIC", "公共成品", "MODEL", "BARE", "210", 2026, "COMMERCIAL", 1L, new java.math.BigDecimal("0.00475")));
    when(packaging.check(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
      var availability = call.getArgument(1) == TechnicalDataAvailability.AVAILABLE
          ? TechnicalDataAvailability.AVAILABLE : TechnicalDataAvailability.UNCONFIRMED;
      return new TechnicalDataSourceFact(TechnicalDataModuleType.PACKAGE, availability, "PACKAGE_FIXTURE",
          "任务测试包装来源", null, java.time.LocalDateTime.now());
    });
    when(u9.read(org.mockito.ArgumentMatchers.any())).thenReturn(
        com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult.notFound("无原始 BOM").withMonthlySnapshot(11L,false));
    for (long id : List.of(101L, 102L)) {
      SysUser user = new SysUser();
      user.setUserId(id); user.setUserName("tech-" + id); user.setNickName("技术员" + id);
      user.setStatus("0"); user.setDelFlag("0");
      when(users.getById(id)).thenReturn(user);
      when(users.findPermissionsByUserId(id)).thenReturn(Set.of("technical:data:task:edit"));
    }
    var operator=new SysUser();operator.setUserId(1L);operator.setStatus("0");operator.setDelFlag("0");operator.setEmployeeNo("11101516");
    when(users.findIdentityById(1L)).thenReturn(operator);
    for (long id:List.of(101L,102L)) when(directory.findActiveBySystemUserId(id)).thenReturn(
        new com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryIdentity(id,"TEST"+id));
    oaProperties.getOutbound().setFrontendBaseUrl("http://localhost:5173");
    when(oa.submit(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
      com.fasterxml.jackson.databind.node.ObjectNode body=call.getArgument(0);
      return new com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult("test",
          com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult.Status.SUCCESS,200,"0","成功",body.path("requestId").asText(),null,1);
    });
    authenticate(true, "COMMERCIAL");
  }
  @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

  @Test void productSnapshotReadsAnnualVolumeUnitFromCurrentItemExtensionTable() {
    var ids = products(1);
    Long itemId = ids.getFirst();
    jdbc.update("UPDATE oa_form_item SET annual_volume=0.4 WHERE id=?", itemId);
    jdbc.update("""
        INSERT INTO lp_oa_form_item_extra_field(oa_form_id,oa_form_item_id,business_unit_type,field_code,field_name,field_value,value_type)
        SELECT oa_form_id,id,business_unit_type,'ANNUAL_VOLUME_UNIT','预计年用量单位','TEN_THOUSAND_PIECES','TEXT'
          FROM oa_form_item WHERE id=?
        """, itemId);
    var product = service.publish(request(ids, 101L), ADMIN).items().getFirst().task().products().getFirst();
    assertThat(product.annualVolume()).isEqualByComparingTo("0.4");
    assertThat(product.annualVolumeUnit()).isEqualTo("TEN_THOUSAND_PIECES");
    assertThat(product.sourceSnapshotJson()).contains("TEN_THOUSAND_PIECES");
  }
  @Test void sameProductInAnotherMonthCannotStartAnotherSupplement() {
    List<Long> ids = products(2);
    var september = service.publish(request(ids, 101L), ADMIN);
    jdbc.update("INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,workspace_status,gap_count,last_checked_at,last_error_code) "
        + "SELECT oa_no,oa_form_item_id,product_code,'2026-10',business_unit_type,workspace_status,gap_count,last_checked_at,last_error_code "
        + "FROM lp_quote_costing_workspace WHERE oa_form_item_id=? AND period_month='2026-09'", ids.get(0));
    var next = request(List.of(ids.get(0)), 101L); next.setAccountingMonth("2026-10");
    next.setCheckFingerprints(Map.of(ids.getFirst(), sources.lockAndRead(ids.getFirst(), "2026-10").check().fingerprint()));
    assertThatThrownBy(() -> service.publish(next, ADMIN)).hasMessageContaining("不能重复补录");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_task WHERE oa_form_item_id=?", Integer.class, ids.getFirst())).isEqualTo(1);
  }
  @Test void uncheckedNoGapForgedFieldsAndCrossBusinessUnitAreRejected() {
    List<Long> ids = products(1);
    var request = request(ids, 101L);
    request.captureUnknownField("validPackageSource", true);
    assertThatThrownBy(() -> service.publish(request, ADMIN)).hasMessageContaining("未知字段");
    when(u9.read(org.mockito.ArgumentMatchers.any())).thenReturn(
        com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult.available("U9","V1","B1",2));
    assertThatThrownBy(() -> service.publish(request(ids, 101L), ADMIN)).hasMessageContaining("无需分派");
    jdbc.update("UPDATE lp_quote_costing_workspace SET gap_count=1,last_checked_at=NULL WHERE oa_form_item_id=?", ids.getFirst());
    assertThatThrownBy(() -> service.publish(request(ids, 101L), ADMIN)).hasMessageContaining("尚未执行");
    authenticate(false, "HOUSEHOLD");
    assertThatThrownBy(() -> service.publish(request(ids, 101L), ADMIN)).hasMessageContaining("业务单元");
  }
  @Test void taskReadDoesNotLeakAnotherTechniciansProduct() {
    var first = service.publish(request(products(1), 101L), ADMIN).items().getFirst();
    var unrelated = new TechnicalDataActor(102L, "其他技术员", Set.of("technical:data:task:list"));
    assertThatThrownBy(() -> service.detail(first.task().id(), unrelated))
        .isInstanceOf(TechnicalDataTaskException.class).hasMessageContaining("只能查看");
    assertThat(service.detail(first.task().id(), ADMIN).id()).isEqualTo(first.task().id());
  }
  @Test void sourceChangedAfterPreviewRejectsStaleBatchWithoutCreatingAnyTask() {
    var ids = products(2);
    var preview = request(ids,101L);
    when(u9.read(org.mockito.ArgumentMatchers.any())).thenReturn(
        com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult.available("U9","V2","B2",3));
    assertThatThrownBy(() -> service.publish(preview,ADMIN)).hasMessageContaining("来源或缺口已变化");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_task WHERE oa_form_item_id IN (?,?)",
        Integer.class, ids.get(0),ids.get(1))).isZero();
  }
  @Test void sameDispatchRequestIsReplayedWithoutCallingOaAgain() {
    var ids=products(2);var command=request(ids,101L);
    var first=service.publish(command,ADMIN);var replay=service.publish(command,ADMIN);
    assertThat(first.oaResult().status().name()).isEqualTo("SUCCESS");
    assertThat(replay.batchId()).isEqualTo(first.batchId());
    assertThat(replay.items()).hasSize(2).allSatisfy(item -> assertThat(item.action()).isEqualTo("REPLAY"));
    org.mockito.Mockito.verify(oa,org.mockito.Mockito.times(1)).submit(org.mockito.ArgumentMatchers.any());
    command.setAssigneeUserId(102L);
    assertThatThrownBy(() -> service.publish(command,ADMIN)).hasMessageContaining("不能更换");
  }

  @Test void recheckNeverChangesAnAlreadyDispatchedScope() {
    var task=service.publish(request(products(1),101L),ADMIN).items().getFirst().task();
    var before=modules.selectByTaskId(task.id()).stream().filter(m -> m.getRequiredFlag()==1).map(m -> m.getModuleType()).toList();
    new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
      var loaded=new com.sanhua.marketingcost.entity.QuoteTechTask();loaded.setId(task.id());loaded.setOaAssignmentVersion(1);
      refresh.refresh(loaded,List.of(new TechnicalDataModuleRequirement("PROFILE",false,"AVAILABLE","新来源",
          TechnicalDataAvailability.AVAILABLE,"new",java.time.LocalDateTime.now())));
    });
    assertThat(modules.selectByTaskId(task.id()).stream().filter(m -> m.getRequiredFlag()==1).map(m -> m.getModuleType()).toList()).isEqualTo(before);
    org.mockito.Mockito.verify(oa,org.mockito.Mockito.times(1)).submit(org.mockito.ArgumentMatchers.any());
  }

  @Test void rejectedDispatchReturnsToUnassignedAndCanBeExplicitlyAssignedAgain() {
    var ids=products(1);
    var command=request(ids,101L);
    org.mockito.Mockito.doReturn(new com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult("test",
        com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult.Status.REJECTED,200,"1200302",
        "requestId数据错误",null,null,1)).when(oa).submit(org.mockito.ArgumentMatchers.any());
    var rejected=service.publish(command,ADMIN);
    var task=service.detail(rejected.items().getFirst().task().id(),ADMIN);
    assertThat(task.taskStatus()).isEqualTo("UNASSIGNED");
    assertThat(task.assigneeUserId()).isNull();
    assertThat(service.publish(command,ADMIN).batchId()).isEqualTo(rejected.batchId());
    org.mockito.Mockito.verify(oa,org.mockito.Mockito.times(1)).submit(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.doAnswer(call -> {
      com.fasterxml.jackson.databind.node.ObjectNode body=call.getArgument(0);
      return new com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult("test",
          com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult.Status.SUCCESS,200,"0","成功",
          body.path("requestId").asText(),null,1);
    }).when(oa).submit(org.mockito.ArgumentMatchers.any());
    var accepted=service.publish(request(ids,102L),ADMIN);
    assertThat(accepted.oaResult().status().name()).isEqualTo("SUCCESS");
    assertThat(accepted.items().getFirst().task().assigneeUserId()).isEqualTo(102L);
    assertThat(accepted.items().getFirst().task().id()).isEqualTo(task.id());
    org.mockito.Mockito.verify(oa,org.mockito.Mockito.times(2)).submit(org.mockito.ArgumentMatchers.any());
  }

  private List<Long> products(int count) {
    long formId = IDS.incrementAndGet(); String oaNo = "FI-SC-005-20260925-TW01-" + formId;
    jdbc.update("INSERT INTO oa_form(id,oa_no,process_code,business_unit_type,deleted) VALUES(?,?,'FI-SC-005','COMMERCIAL',0)", formId, oaNo);
    jdbc.update("INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version) VALUES('HZ_KG_OA_TST','TEST',?,?,1)","FLOW-"+formId,formId);
    return java.util.stream.IntStream.range(0, count).mapToObj(i -> {
      long itemId = IDS.incrementAndGet();
      jdbc.update("INSERT INTO oa_form_item(id,oa_form_id,seq,material_no,product_name,sunl_model,spec,product_attr,business_unit_type,deleted) "
          + "VALUES(?,?,?,?,'演练产品','TW01-MODEL','SPEC','标准品','COMMERCIAL',0)", itemId, formId, i + 1, "TW01-MATERIAL-" + itemId);
      jdbc.update("INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,workspace_status,gap_count,last_checked_at,last_error_code,last_error_message) "
          + "VALUES(?,?,?,'2026-09','COMMERCIAL','WAIT_BOM',1,NOW(),'BOM_MISSING','目标组织未找到有效BOM')", oaNo, itemId, "TW01-MATERIAL-" + itemId);
      return itemId;
    }).toList();
  }
  private TechnicalDataTaskPublishRequest request(List<Long> ids, long assignee) {
    var request = new TechnicalDataTaskPublishRequest(); request.setRequestId(UUID.randomUUID().toString());
    request.setOaFormItemIds(ids); request.setAccountingMonth("2026-09"); request.setAssigneeUserId(assignee);
    request.setCheckFingerprints(ids.stream().collect(java.util.stream.Collectors.toMap(
        id -> id, id -> sources.lockAndRead(id,"2026-09").check().fingerprint())));
    return request;
  }
  private static void authenticate(boolean admin, String businessUnit) {
    var auth = new UsernamePasswordAuthenticationToken("tester", "unused", List.of(new SimpleGrantedAuthority(
        admin ? "*:*:*" : "ingest:quote:cost-run:execute")));
    auth.setDetails(Map.of("businessUnitType", businessUnit));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
