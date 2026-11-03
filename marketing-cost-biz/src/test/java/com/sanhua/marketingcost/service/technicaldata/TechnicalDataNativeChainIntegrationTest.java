package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileUpdateRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaIntegrationProperties;
import com.sanhua.marketingcost.integration.oa.directory.*;
import com.sanhua.marketingcost.integration.oa.workflow.*;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.service.SysUserService;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class TechnicalDataNativeChainIntegrationTest extends OriginalPricingIntegrationTestBase {
  private static final AtomicLong IDS=new AtomicLong(9_510_000);
  protected static final TechnicalDataActor ADMIN=new TechnicalDataActor(1L,"报价员",Set.of("*:*:*"));
  protected static final TechnicalDataActor DING=new TechnicalDataActor(101L,"丁云龙",Set.of("technical:data:task:list","technical:data:task:edit"));
  private static final TechnicalDataActor PAN=new TechnicalDataActor(102L,"潘晓红",DING.authorities());
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired QuoteTechnicalDataPersistenceService persistence;
  @Autowired TechnicalDataTaskRepository repository;
  @Autowired QuoteTechTaskMapper taskMapper;
  @Autowired TechnicalDataOaDispatchService dispatch;
  @Autowired TechnicalDataDocumentSubmissionService documents;
  @Autowired TechnicalDataProfileApplicationService profiles;
  @Autowired TechnicalDataTaskApplicationService views;
  @Autowired TechnicalDataSubmissionValidationService validation;
  @Autowired TechnicalDataReadPolicy readPolicy;
  @Autowired com.sanhua.marketingcost.service.platematerial.PlateMaterialMappingService plateMappings;
  @Autowired OaIntegrationProperties properties;
  @MockBean OaWorkflowClient oa;
  @MockBean SysUserService users;
  @Autowired OaPersonDirectoryRepository directory;

  @Autowired TechnicalDataReturnService returns;
  @Autowired OaTechnicalBatchRepository batches;
  @Autowired TechnicalDataGapStatusQuery gapStatus;
  @Autowired TechnicalDataOaSubmissionLifecycle lifecycle;
  @Autowired TechnicalDataNetLossApplicationService netLoss;
  @Autowired TechnicalDataRequirementRefreshService requirementRefresh;
  @Autowired com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository people;
  @Autowired com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper submissionMapper;
  @Autowired com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy access;

  protected String accountingMonth() { return "2026-09"; }

  @Test void resolvedUnassignedAdditionalGapRestoresSubmittedTaskWithoutResubmittingSavedModules() {
    var fixture = submittedFixture(1, true);
    long taskId = fixture.taskIds().getFirst();
    var product = repository.findProducts(taskId).getFirst();
    var saved = jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version WHERE product_id=? ORDER BY id", product.getId());
    var submissions = jdbc.queryForList("SELECT * FROM lp_quote_tech_submission WHERE task_id=? ORDER BY id", taskId);
    var tx = new TransactionTemplate(manager);
    var missing = new TechnicalDataModuleRequirement("DRAWING_BOM", true, "DRAWING_SOURCE_PENDING", "待核实图库",
        TechnicalDataAvailability.MISSING, "previous-source", java.time.LocalDateTime.now());
    tx.executeWithoutResult(status -> requirementRefresh.reconcile(product.getOaFormItemId(), accountingMonth(), "210", List.of(missing)));
    assertThat(taskMapper.selectById(taskId).getTaskStatus()).isEqualTo("PENDING");
    var ready = new TechnicalDataModuleRequirement("DRAWING_BOM", false, "DRAWING_SOURCE_AVAILABLE", "图库已发布",
        TechnicalDataAvailability.AVAILABLE, "published-source", java.time.LocalDateTime.now());
    clearInvocations(oa);
    tx.executeWithoutResult(status -> requirementRefresh.reconcile(product.getOaFormItemId(), accountingMonth(), "210", List.of(ready)));
    assertThat(taskMapper.selectById(taskId).getTaskStatus()).isEqualTo("SUBMITTED");
    var drawing = repository.findModules(product.getId()).stream().filter(m -> "DRAWING_BOM".equals(m.getModuleType())).findFirst().orElseThrow();
    assertThat(drawing.getRequiredFlag()).isZero();
    assertThat(drawing.getModuleStatus()).isEqualTo("NOT_REQUIRED");
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version WHERE product_id=? ORDER BY id", product.getId())).isEqualTo(saved);
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_tech_submission WHERE task_id=? ORDER BY id", taskId)).isEqualTo(submissions);
    verifyNoInteractions(oa);
  }

  record Fixture(long formId,List<Long> taskIds,String dispatchId) {}

  @BeforeEach void setup() {
    properties.getOutbound().setFrontendBaseUrl("http://localhost:5173");
    for (long id:List.of(1L,101L,102L)) {
      jdbc.update("INSERT IGNORE INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES(?,?,?,'0','0')",id,"chain-"+id,"技术员"+id);
      var user=new SysUser(); user.setUserId(id); user.setUserName("chain-"+id); user.setNickName(id==101?"丁云龙":id==102?"潘晓红":"报价员");
      user.setStatus("0"); user.setDelFlag("0"); user.setEmployeeNo(id==1?"11101516":id==101?"12211470":"11101516");
      when(users.findIdentityById(id)).thenReturn(user); when(users.getById(id)).thenReturn(user);
      when(users.findPermissionsByUserId(id)).thenReturn(Set.of("technical:data:task:edit"));
      if (id != 1) {
        jdbc.update("""
            INSERT INTO lp_oa_person_directory(source_system,environment,oa_user_id,employee_no,person_name,
                target_department_paths,actual_department_paths,oa_department_ids,target_department_ids,
                match_types,system_user_id,sync_batch_id)
            VALUES('HZ_KG_OA_TST','TEST',?,?,?,'测试','测试','TEST','TEST','TEST',?,?)
            ON DUPLICATE KEY UPDATE system_user_id=VALUES(system_user_id),active_flag=1,selectable_flag=1
            """, "CHAIN-"+id,user.getEmployeeNo(),user.getNickName(),id,UUID.randomUUID().toString());
        assertThat(directory.findActiveBySystemUserId(id).employeeNo()).isEqualTo(user.getEmployeeNo());
        assertThat(directory.findActiveByEmployeeNo(user.getEmployeeNo()).userId()).isEqualTo(id);
      }
    }
    success();
  }

  private void success() {
    doAnswer(call -> {
      ObjectNode body=call.getArgument(0);
      return new OaWorkflowResult("test",OaWorkflowResult.Status.SUCCESS,200,"0","成功",body.path("requestId").asText(),null,1);
    }).when(oa).submit(any());
    doAnswer(call -> {
      ObjectNode body=call.getArgument(0);
      return new OaWorkflowResult("reject-test",OaWorkflowResult.Status.SUCCESS,200,"0","成功",body.path("requestId").asText(),null,1);
    }).when(oa).reject(any());
  }

  private Fixture dispatched(int dingProducts,boolean panProduct) { return dispatched(dingProducts,panProduct,false); }
  private Fixture dispatched(int dingProducts,boolean panProduct,boolean extraModule) {
    long form=IDS.incrementAndGet(); String oaNo="FI-SC-005-CHAIN-"+form;
    jdbc.update("INSERT INTO oa_form(id,oa_no,process_code,business_unit_type,deleted) VALUES(?,?,'FI-SC-005','COMMERCIAL',0)",form,oaNo);
    jdbc.update("INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version,reject_to_node_id) VALUES('HZ_KG_OA_TST','TEST',?,?,1,'00034567890123456789')","FLOW-"+form,form);
    List<Long> ids=new ArrayList<>();
    for (int i=0;i<dingProducts+(panProduct?1:0);i++) {
      long item=IDS.incrementAndGet();
      jdbc.update("INSERT INTO oa_form_item(id,oa_form_id,seq,material_no,product_name,sunl_model,business_unit_type,deleted) VALUES(?,?,?,?,'测试产品','M','COMMERCIAL',0)",item,form,i+1,"P"+item);
      var task=new QuoteTechTask();task.setTaskNo("TD-CHAIN-"+item);task.setOaFormId(form);task.setOaFormItemId(item);task.setOaNo(oaNo);
      task.setAccountingMonth(accountingMonth());task.setBusinessUnitType("COMMERCIAL");task.setApplicableOrgCode("210");task.setAssigneeUserId(i<dingProducts?101L:102L);task.setAssigneeName("技术员");
      task=persistence.createTask(task); ids.add(task.getId());
      var product=new QuoteTechProduct();product.setTaskId(task.getId());product.setOaFormItemId(item);product.setMaterialNo("P"+item);product.setProductName("测试产品");product.setQuoteNo(oaNo);product.setAccountingMonth(accountingMonth());product.setContentSchemaVersion(2);
      product.setSourceModel("PMM-NATIVE-" + item);
      product.setSourceSnapshotJson("{\"sourceModel\":\"PMM-NATIVE-" + item
          + "\",\"newProduct\":false,\"businessUnitType\":\"COMMERCIAL\",\"materialOrganizationCode\":\"COMMERCIAL\"}");
      product.setSourceFingerprint("b".repeat(64));product=persistence.createProduct(product);
      for (String type:TechnicalDataModuleType.codesForVersion(2)) {
        boolean required="PROFILE".equals(type) || extraModule && "NET_LOSS".equals(type);
        var module=new QuoteTechModule();module.setProductId(product.getId());module.setModuleType(type);module.setRequiredFlag(required?1:0);
        module.setRequirementReasonCode(required?"MISSING":"AVAILABLE");module.setRequirementReason("测试来源");module.setSourceAvailability(required?"MISSING":"AVAILABLE");module.setSourceCheckedAt(java.time.LocalDateTime.now());persistence.createModule(module);
      }
    }
    // 每次 I02 的模块默认人相同。丁的多个产品一起分派；潘随后独立分派同单据另一个产品。
    var tx=new TransactionTemplate(manager);
    String batch=tx.execute(status -> dispatch.prepare(ids.subList(0,dingProducts).stream().map(taskMapper::selectByIdForUpdate).toList(),101L,Map.of(),UUID.randomUUID().toString(),"a".repeat(64),ADMIN));
    assertThat(dispatch.deliver(batch).status()).isEqualTo("SUCCESS");
    if (panProduct) {
      String other=tx.execute(status -> dispatch.prepare(List.of(taskMapper.selectByIdForUpdate(ids.getLast())),102L,Map.of(),UUID.randomUUID().toString(),"c".repeat(64),ADMIN));
      assertThat(dispatch.deliver(other).status()).isEqualTo("SUCCESS");
    }
    return new Fixture(form,ids,batch);
  }

  protected void save(long taskId,TechnicalDataActor actor,String amount) {
    var product=repository.findProducts(taskId).getFirst();
    var request=new TechnicalDataProfileUpdateRequest();request.setExpectedVersion(product.getRowVersion());request.setProductProperty("非标品");
    request.setHasAdditionalFees(true);request.setUnitToolingFee(amount);request.setUnitMouldFee("/");request.setUnitCertificationFee("/");
    // Supply a genuine revision even for forged saves, so the save itself must reject the wrong actor.
    long owner = repository.findModules(product.getId()).stream().filter(m -> "PROFILE".equals(m.getModuleType()))
        .findFirst().orElseThrow().getAssigneeUserId();
    var plate = views.detail(taskId, owner == DING.userId() ? DING : PAN).products().getFirst().profile().plateMaterial();
    if (plate != null) request.setExpectedPlateMaterialRevision(plate.revision());
    profiles.save(product.getId(),request,actor);
  }

  protected TechnicalDataDocumentSubmissionService.Request request(long form,TechnicalDataActor actor) {
    return new TechnicalDataDocumentSubmissionService.Request(UUID.randomUUID().toString(),documents.workbench(form,actor).fingerprint());
  }

  @Test void plateMaterialIsRetainedAcrossDocumentSubmissionAndDirectedReturn() {
    var f = dispatched(1, false); long task = f.taskIds().getFirst();
    var product = repository.findProducts(task).getFirst();
    var mapping = plateMappings.create("COMMERCIAL", product.getSourceModel(), "水氟316L", 1);
    save(task, DING, "2.50");
    var first = documents.submit(f.formId(), request(f.formId(), DING), DING);
    assertThat(first.status()).isEqualTo("SUCCESS");
    var historical = jdbc.queryForList("SELECT * FROM lp_quote_tech_submission WHERE task_id=?", task);
    var frozen = views.detail(task, ADMIN).products().getFirst().profile().plateMaterial();
    assertThat(frozen.value().value()).isEqualTo("水氟316L");
    assertThat(frozen.historical()).isTrue();
    plateMappings.update("COMMERCIAL", mapping.getId(), product.getSourceModel(), "水水316L", 0, 1);
    assertThat(views.detail(task, ADMIN).products().getFirst().profile().plateMaterial()).isEqualTo(frozen);
    materialActor();
    assertThat(returns.submit(returnRequest(task, "PROFILE"), ADMIN).status()).isEqualTo("SUCCESS");
    assertThat(views.detail(task, DING).products().getFirst().profile().plateMaterial().sourceChanged()).isTrue();
    assertThat(documents.workbench(f.formId(), DING).canSubmit()).isFalse();
    save(task, DING, "2.50");
    assertThat(documents.submit(f.formId(), request(f.formId(), DING), DING).status()).isEqualTo("SUCCESS");
    assertThat(views.detail(task, ADMIN).products().getFirst().profile().plateMaterial().value().value())
        .isEqualTo("水水316L");
    for (var row : historical) {
      assertThat(jdbc.queryForObject("SELECT content_snapshot_json FROM lp_quote_tech_submission WHERE id=?",
          String.class, row.get("id"))).isEqualTo(row.get("content_snapshot_json"));
      assertThat(jdbc.queryForObject("SELECT content_fingerprint FROM lp_quote_tech_submission WHERE id=?",
          String.class, row.get("id"))).isEqualTo(row.get("content_fingerprint"));
    }
  }

  @Test void configurationChangedAfterWorkbenchBlocksSubmissionBeforeAnyOaRequest() {
    var f = dispatched(1, false); long task = f.taskIds().getFirst();
    var product = repository.findProducts(task).getFirst();
    var mapping = plateMappings.create("COMMERCIAL", product.getSourceModel(), "水氟316L", 1);
    save(task, DING, "2.50");
    assertThat(documents.workbench(f.formId(), DING).canSubmit()).isTrue();
    var request = request(f.formId(), DING);
    plateMappings.update("COMMERCIAL", mapping.getId(), product.getSourceModel(), "水水316L", 0, 1);
    assertThat(documents.workbench(f.formId(), DING).canSubmit()).isFalse();
    clearInvocations(oa);
    assertThatThrownBy(() -> documents.submit(f.formId(), request, DING)).hasMessageContaining("来源已变化");
    verifyNoInteractions(oa);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_submission WHERE task_id=?", Integer.class, task)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_technical_batch WHERE oa_form_id=? AND operation='I03'",
        Integer.class, f.formId())).isZero();
  }

  @Test void unchangedReturnedProfileDoesNotDisableResubmissionWhenPublicSourceChanges() {
    var f = submittedFixture(1, true);
    long task = f.taskIds().getFirst();
    var product = repository.findProducts(task).getFirst();
    var history = jdbc.queryForList("SELECT * FROM lp_quote_tech_submission WHERE task_id=? ORDER BY id", task);
    jdbc.update("""
        INSERT INTO lp_material_master_raw(organization_code,material_code,material_model,
          production_division,active_flag,import_batch_id)
        VALUES('COMMERCIAL',?,?,'其他事业部',1,'RETURN-RESUBMIT-TEST')
        """, product.getMaterialNo(), product.getSourceModel());
    assertThat(returns.submit(returnRequest(task, "PROFILE", "NET_LOSS"), ADMIN).status()).isEqualTo("SUCCESS");
    assertThat(documents.workbench(f.formId(), DING).canSubmit()).isFalse();
    save(task, DING, "2.50");
    saveRate(task, "1");
    assertThat(views.detail(task, DING).products().getFirst().profile().plateMaterial().sourceChanged()).isTrue();
    assertThat(validation.validate(task, DING.userId(), DING).valid()).isTrue();
    assertThat(repository.findModules(product.getId()).stream().filter(m -> "PROFILE".equals(m.getModuleType())))
        .singleElement().satisfies(m -> assertThat(m.getLastValidationCode()).isEqualTo("REUSED_SUBMITTED"));
    assertThat(documents.workbench(f.formId(), DING).canSubmit()).isTrue();
    assertThat(documents.submit(f.formId(), request(f.formId(), DING), DING).status()).isEqualTo("SUCCESS");
    for (var row : history) {
      assertThat(jdbc.queryForObject("SELECT content_snapshot_json FROM lp_quote_tech_submission WHERE id=?",
          String.class, row.get("id"))).isEqualTo(row.get("content_snapshot_json"));
    }
    assertThat(documents.workbench(f.formId(), DING).canSubmit()).isFalse();
  }

  @Test void twoProductsUseOneI03AndDraftIsPrivateAndOverwritten() {
    var f=dispatched(2,true);
    clearInvocations(oa);
    save(f.taskIds().get(0),DING,"1.25");save(f.taskIds().get(0),DING,"2.50");save(f.taskIds().get(1),DING,"3.50");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version WHERE product_id IN (SELECT id FROM lp_quote_tech_product WHERE task_id IN (?,?)) AND version_status='DRAFT'",Long.class,f.taskIds().get(0),f.taskIds().get(1))).isEqualTo(2);
    var before=views.detail(f.taskIds().getFirst(),ADMIN);
    assertThat(before.taskStatus()).isEqualTo("PENDING");
    assertThat(before.products().getFirst().profile().unitToolingFee()).isNull();
    assertThat(before.products().getFirst().currentEditVersionId()).isNull();
    assertThatThrownBy(() -> save(f.taskIds().getFirst(),ADMIN,"99")).hasMessageContaining("未分派给本人");
    var product=repository.findProducts(f.taskIds().getFirst()).getFirst();
    var module=repository.findModules(product.getId()).stream().filter(row -> "PROFILE".equals(row.getModuleType())).findFirst().orElseThrow();
    assertThatThrownBy(() -> readPolicy.version(product,module,ADMIN,product.getCurrentEditVersionId())).hasMessageContaining("只能查看");
    var req=request(f.formId(),DING);
    var result=documents.submit(f.formId(),req,DING);
    assertThat(result.status()).isEqualTo("SUCCESS");
    assertThat(documents.submit(f.formId(),req,DING).batchId()).isEqualTo(result.batchId());
    verify(oa,times(1)).submit(argThat(body -> body.path("userid").asText().equals("12211470")
        && body.path("remark").asText().contains("2.5") && body.path("remark").asText().contains("3.5")
        && body.at("/formData/dataDetails/0/content").asText().equals("http://localhost:5173/collaboration/technical-data/forms/"+f.formId())));
    assertThat(documents.workbench(f.formId(),PAN).tasks()).hasSize(1);
    assertThat(documents.workbench(f.formId(),PAN).canSubmit()).isFalse();
    assertThat(views.detail(f.taskIds().getFirst(),ADMIN).products().getFirst().profile().unitToolingFee()).isEqualTo("2.5");
    assertThat(documents.submitted(f.formId(),result.batchId(),ADMIN).tasks()).hasSize(2);
    assertThatThrownBy(() -> documents.submitted(f.formId(),result.batchId(),PAN)).hasMessageContaining("无权");
    save(f.taskIds().getLast(),PAN,"4.50");
    assertThat(documents.submit(f.formId(),request(f.formId(),PAN),PAN).status()).isEqualTo("SUCCESS");
    verify(oa,times(2)).submit(any());
    assertThat(documents.submitted(f.formId(),result.batchId(),ADMIN).tasks()).hasSize(2);
  }

  @Test void simultaneousClicksSendOnlyOneOaRequest() throws Exception {
    var fixture=dispatched(1,false);
    save(fixture.taskIds().getFirst(),DING,"2.50");
    clearInvocations(oa);
    var entered=new CountDownLatch(1);
    var release=new CountDownLatch(1);
    doAnswer(call -> {
      ObjectNode body=call.getArgument(0);
      entered.countDown();
      if (!release.await(10,TimeUnit.SECONDS)) throw new AssertionError("并发提交未完成");
      return new OaWorkflowResult("test",OaWorkflowResult.Status.SUCCESS,200,"0","成功",body.path("requestId").asText(),null,1);
    }).when(oa).submit(any());
    var request=request(fixture.formId(),DING);
    try (var executor=Executors.newFixedThreadPool(2)) {
      var first=executor.submit(() -> documents.submit(fixture.formId(),request,DING));
      try {
        assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        var repeated=executor.submit(() -> documents.submit(fixture.formId(),request,DING));
        assertThat(repeated.get(10,TimeUnit.SECONDS).status()).isEqualTo("SENDING");
      } finally {
        release.countDown();
      }
      assertThat(first.get(10,TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
    }
    verify(oa,times(1)).submit(any());
    assertThat(documents.submit(fixture.formId(),request,DING).status()).isEqualTo("SUCCESS");
    verify(oa,times(1)).submit(any());
  }

  @Test void incompleteProductPreventsWholeDocumentSubmission() {
    var f=dispatched(2,false);clearInvocations(oa);save(f.taskIds().getFirst(),DING,"1");
    assertThatThrownBy(() -> documents.submit(f.formId(),request(f.formId(),DING),DING)).hasMessageContaining("版本");
    verifyNoInteractions(oa);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_technical_batch WHERE oa_form_id=? AND operation='I03'",Long.class,f.formId())).isZero();
  }

  @Test void rejectedSubmissionKeepsOneDraftAndNoVisibleSubmission() {
    var f=dispatched(1,false);save(f.taskIds().getFirst(),DING,"1");clearInvocations(oa);
    doReturn(new OaWorkflowResult("test",OaWorkflowResult.Status.REJECTED,200,"1200302","requestId数据错误","FLOW-"+f.formId(),null,1)).when(oa).submit(any());
    var req=request(f.formId(),DING);
    assertThat(documents.submit(f.formId(),req,DING).status()).isEqualTo("REJECTED");
    assertThat(documents.submit(f.formId(),req,DING).status()).isEqualTo("REJECTED");
    verify(oa,times(1)).submit(any());
    assertThat(documents.workbench(f.formId(),DING).canSubmit()).isTrue();
    assertThat(views.detail(f.taskIds().getFirst(),ADMIN).taskStatus()).isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version WHERE product_id=(SELECT id FROM lp_quote_tech_product WHERE task_id=?) AND version_status='DRAFT'",Long.class,f.taskIds().getFirst())).isEqualTo(1);
  }

  @Test void unknownResultKeepsFrozenAndNeverResends() {
    var f=dispatched(1,false);save(f.taskIds().getFirst(),DING,"1");clearInvocations(oa);
    doReturn(new OaWorkflowResult("test",OaWorkflowResult.Status.UNKNOWN,null,"TIMEOUT","结果未确认","FLOW-"+f.formId(),null,1)).when(oa).submit(any());
    var req=request(f.formId(),DING);
    assertThat(documents.submit(f.formId(),req,DING).status()).isEqualTo("UNKNOWN");
    assertThat(documents.submit(f.formId(),req,DING).status()).isEqualTo("UNKNOWN");
    verify(oa,times(1)).submit(any());
    assertThat(documents.workbench(f.formId(),DING).canSubmit()).isFalse();
    assertThatThrownBy(() -> save(f.taskIds().getFirst(),DING,"99")).hasMessageContaining("未分派给本人");
    assertThat(views.detail(f.taskIds().getFirst(),ADMIN).taskStatus()).isEqualTo("PENDING");
  }

  protected void materialActor() {
    jdbc.update("UPDATE sys_user SET employee_no='11101516' WHERE user_id=1");
    String username=jdbc.queryForObject("SELECT user_name FROM sys_user WHERE user_id=1",String.class);
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(username,"unused",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("*:*:*"))));
  }
  @AfterEach void clearActor() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

  protected Fixture submittedFixture(int count,boolean extraModule) {
    var fixture=dispatched(count,false,extraModule);
    for(long id:fixture.taskIds()) { save(id,DING,"2.50"); if(extraModule) saveRate(id,"0.3"); }
    assertThat(documents.submit(fixture.formId(),request(fixture.formId(),DING),DING).status()).isEqualTo("SUCCESS");
    jdbc.update("INSERT INTO lp_oa_workflow_state(source_system,environment,workflow_request_id,applied_version,form_version,observed_form_version,state,active_work_items_json) VALUES('HZ_KG_OA_TST','TEST',?,1,1,1,'COSTING',?)",
        "FLOW-"+fixture.formId(),"[{\"workItemId\":\"MATERIAL-1\",\"nodeRole\":\"COSTING\",\"employeeNo\":\"11101516\"}]");
    materialActor(); clearInvocations(oa);
    return fixture;
  }
  protected void saveRate(long taskId,String percent) {
    var product=repository.findProducts(taskId).getFirst();
    var req=new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataNetLossSaveRequest();
    req.setExpectedVersion(product.getRowVersion());req.setEntryMode("MANUAL");req.setPercent(percent);
    netLoss.save(product.getId(),req,DING);
  }
  private TechnicalDataReturnService.Request returnRequest(long taskId,String... modules) {
    return new TechnicalDataReturnService.Request(UUID.randomUUID().toString(),List.of(new TechnicalDataReturnService.Target(
        taskId,taskMapper.selectById(taskId).getTaskVersion(),List.of(modules),"请调整填写金额")));
  }

  @Autowired com.sanhua.marketingcost.service.costing.QuoteCostingSubmissionService costingSubmissions;

  @Autowired com.sanhua.marketingcost.integration.oa.OaWorkflowNotificationService notices;

  @Test void returnedTechnicianCanResubmitAndOriginalQuoterCanCostWithoutApprovalCallback() {
    var f=dispatched(1,false,true);
    long task=f.taskIds().getFirst();save(task,DING,"2.50");saveRate(task,"0.3");
    assertThat(documents.submit(f.formId(),request(f.formId(),DING),DING).status()).isEqualTo("SUCCESS");
    materialActor();
    assertThat(access.quoterNode(f.formId()).workItemId()).startsWith("LOCAL-INITIAL:");
    new TransactionTemplate(manager).executeWithoutResult(tx->costingSubmissions.beginReview(f.formId(),1L));
    assertThat(jdbc.queryForObject("SELECT finance_user_id FROM lp_oa_technical_flow WHERE oa_form_id=?",Long.class,f.formId())).isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT finance_confirmed_fingerprint FROM lp_oa_technical_flow WHERE oa_form_id=?",String.class,f.formId())).isNull();
    assertThatThrownBy(()->access.requireCostPublication(f.formId())).hasMessageContaining("检查补录内容后点击核算");
    new TransactionTemplate(manager).executeWithoutResult(tx->costingSubmissions.accept(f.formId(),1L));
    access.requireCostPublication(f.formId());
    var notice=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
        .put("requestId","FLOW-"+f.formId()).put("eventType","TECHNICAL").put("reason","请核对单件费用");
    notice.putArray("employeeNos").add("12211470");
    notices.receive(new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST","TEST",Set.of("COMMERCIAL")),notice.toString());
    assertThatThrownBy(()->access.requireCostPublication(f.formId())).hasMessageContaining("尚未全部成功提交");
    long item = repository.findProducts(task).getFirst().getOaFormItemId();
    assertThat(gapStatus.forForm(f.formId(),item,"2026-09").getFirst().pendingModules()).containsExactly("PROFILE","NET_LOSS");
    save(task,DING,"4.50");
    assertThat(gapStatus.forForm(f.formId(),item,"2026-09").getFirst().pendingModules()).hasSize(2);
    assertThat(documents.submit(f.formId(),request(f.formId(),DING),DING).status()).isEqualTo("SUCCESS");
    assertThat(access.quoterNode(f.formId()).workItemId()).startsWith("LOCAL-TECHNICAL:");
    new TransactionTemplate(manager).executeWithoutResult(tx->costingSubmissions.accept(f.formId(),1L));
    access.requireCostPublication(f.formId());
    assertThat(taskMapper.selectById(task).getTaskStatus()).isEqualTo("SUBMITTED");
    assertThat(gapStatus.forForm(f.formId(),item,"2026-09").getFirst().pendingModules()).isEmpty();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_workflow_notification WHERE flow_id=(SELECT id FROM lp_oa_workflow_state WHERE workflow_request_id=?)",Long.class,"FLOW-"+f.formId())).isEqualTo(1);
  }

  @Test void failedReturnKeepsSubmittedMaterialsAndOriginalCostAcceptance() {
    var f=submittedFixture(1,true); long task=f.taskIds().getFirst();
    new TransactionTemplate(manager).executeWithoutResult(tx->costingSubmissions.accept(f.formId(),1L));
    var original=jdbc.queryForMap("SELECT finance_confirmed_fingerprint,finance_confirmed_by,finance_confirmed_at FROM lp_oa_technical_flow WHERE oa_form_id=?",f.formId());
    var versions=jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version ORDER BY id");
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.REJECTED,200,"1200308","节点不可选","FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var failed=returns.submit(returnRequest(task,"PROFILE","NET_LOSS"),ADMIN);
    assertThat(failed.status()).isEqualTo("RETURN_FAILED");
    assertThat(costingSubmissions.hasPendingSubmissions(f.formId())).isFalse();
    access.requireCostPublication(f.formId());
    var notice=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("requestId","FLOW-"+f.formId()).put("eventType","COSTING").put("reason","人工干预");
    notices.receive(new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST","TEST",Set.of("COMMERCIAL")),notice.toString());
    access.requireCostPublication(f.formId());
    assertThat(jdbc.queryForMap("SELECT finance_confirmed_fingerprint,finance_confirmed_by,finance_confirmed_at FROM lp_oa_technical_flow WHERE oa_form_id=?",f.formId())).isEqualTo(original);
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version ORDER BY id")).isEqualTo(versions);
    assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
    success();
    assertThat(returns.retryRejection(failed.batchId(),failed.attempt(),null,ADMIN).status()).isEqualTo("SUCCESS");
    assertThat(costingSubmissions.hasPendingSubmissions(f.formId())).isTrue();
    assertThatThrownBy(()->access.requireCostPublication(f.formId())).hasMessageContaining("尚未全部成功提交");
  }

  @Test void unknownReturnPreservesMaterialsButBlocksConcurrentCostPublication() {
    var f=submittedFixture(1,false);
    new TransactionTemplate(manager).executeWithoutResult(tx->costingSubmissions.accept(f.formId(),1L));
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.UNKNOWN,null,"TIMEOUT","待核实","FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    assertThat(returns.submit(returnRequest(f.taskIds().getFirst(),"PROFILE"),ADMIN).status()).isEqualTo("UNKNOWN");
    assertThat(costingSubmissions.hasPendingSubmissions(f.formId())).isFalse();
    assertThatThrownBy(()->access.requireCostPublication(f.formId())).hasMessageContaining("退回结果尚未确认");
    assertThat(jdbc.queryForObject("SELECT finance_confirmed_fingerprint FROM lp_oa_technical_flow WHERE oa_form_id=?",String.class,f.formId())).isNotBlank();
  }

  @Test void locallyAcceptedDataCanStillBeReturnedBeforeFinalCostSubmission() {
    var f=submittedFixture(1,false);
    new TransactionTemplate(manager).executeWithoutResult(tx->costingSubmissions.accept(f.formId(),1L));
    access.requireCostPublication(f.formId());
    verifyNoInteractions(oa);
    assertThat(returns.submit(returnRequest(f.taskIds().getFirst(),"PROFILE"),ADMIN).status()).isEqualTo("SUCCESS");
    assertThatThrownBy(()->access.requireCostPublication(f.formId())).hasMessageContaining("尚未全部成功提交");
    assertThat(jdbc.queryForObject("SELECT finance_confirmed_fingerprint FROM lp_oa_technical_flow WHERE oa_form_id=?",String.class,f.formId())).isNull();
    verify(oa,times(1)).submit(any());
  }

  @Test void returnEntireProductThenResubmitAndPreserveOtherProductAndHistory() {
    var f=submittedFixture(2,true);long task=f.taskIds().getFirst();
    var before=repository.findProducts(task).getFirst();
    long oldProfile=repository.findModules(before.getId()).stream().filter(m->"PROFILE".equals(m.getModuleType())).findFirst().orElseThrow().getCurrentVersionId();
    assertThat(gapStatus.forForm(f.formId(), before.getOaFormItemId(), "2026-09").getFirst().pendingModules()).isEmpty();
    assertThatThrownBy(() -> returns.submit(returnRequest(task,"PROFILE"), ADMIN)).hasMessageContaining("按产品退回全部");
    verifyNoInteractions(oa);
    var req=returnRequest(task,"PROFILE","NET_LOSS");
    var result=returns.submit(req,ADMIN);
    assertThat(result.status()).isEqualTo("SUCCESS");
    assertThat(returns.submit(req,ADMIN).batchId()).isEqualTo(result.batchId());
    assertThat(returns.status(result.batchId(),ADMIN).status()).isEqualTo("SUCCESS");
    verify(oa,times(1)).submit(argThat(body->body.path("remark").asText().contains("退回修改")
        && body.path("userid").asText().equals("11101516")
        && body.at("/formData/dataDetails/0/dataOptions/0/optionId").asText().equals("12211470")));
    assertThat(people.current(task).getFirst().processingModules()).containsExactly("PROFILE","NET_LOSS");
    assertThat(repository.findProducts(task).getFirst().getCurrentEditVersionId()).isEqualTo(before.getCurrentEditVersionId());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version WHERE product_id=? AND version_status IN ('DRAFT','VOIDED')",Long.class,before.getId())).isEqualTo(1);

    assertThat(people.current(f.taskIds().getLast()).getFirst().todoStatus()).isEqualTo("SUBMITTED");
    assertThat(DING.canEditModule(taskMapper.selectById(task),repository.findModules(before.getId()).stream().filter(m->"PROFILE".equals(m.getModuleType())).findFirst().orElseThrow())).isTrue();
    assertThat(netLoss.get(before.getId(),null,DING).editable()).isTrue();
    assertThat(gapStatus.forForm(f.formId(), before.getOaFormItemId(), "2026-09").getFirst().pendingModules()).containsExactly("PROFILE","NET_LOSS");
    var untouched = repository.findProducts(f.taskIds().getLast()).getFirst();
    assertThat(gapStatus.forForm(f.formId(), untouched.getOaFormItemId(), "2026-09").getFirst().pendingModules()).isEmpty();
    saveRate(task,"0.9");
    assertThat(jdbc.queryForObject("SELECT version_status FROM lp_quote_tech_data_version WHERE id=?",String.class,oldProfile)).isEqualTo("SUBMITTED");
    save(task,DING,"9.99");
    assertThat(views.detail(task,ADMIN).products().getFirst().profile().unitToolingFee()).isEqualTo("2.5");
    assertThat(documents.workbench(f.formId(),DING).canSubmit()).isTrue();
    var resubmit=documents.submit(f.formId(),request(f.formId(),DING),DING);
    assertThat(resubmit.status()).isEqualTo("SUCCESS");
    assertThat(documents.submitted(f.formId(),resubmit.batchId(),ADMIN).tasks()).hasSize(1);
    var current=people.current(task).getFirst();
    assertThat(submissionMapper.selectById(current.latestSubmissionId()).getModuleTypesJson()).contains("PROFILE","NET_LOSS");
    assertThat(gapStatus.forForm(f.formId(), before.getOaFormItemId(), "2026-09").getFirst().pendingModules()).isEmpty();
    assertThat(returns.submit(returnRequest(task,"PROFILE","NET_LOSS"),ADMIN).status()).isEqualTo("SUCCESS");
    assertThat(gapStatus.forForm(f.formId(), before.getOaFormItemId(), "2026-09").getFirst().pendingModules()).hasSize(2);
    saveRate(task,"0.8");
    assertThat(documents.submit(f.formId(),request(f.formId(),DING),DING).status()).isEqualTo("SUCCESS");
  }

  @Test void rejectedReturnPreservesSubmissionAndOnlyNewExplicitRequestRetries() {
    var f=submittedFixture(1,false);long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("test",OaWorkflowResult.Status.REJECTED,200,"200031","没有查询到对应人员信息","FLOW-"+f.formId(),null,1)).when(oa).submit(any());
    var req=returnRequest(task,"PROFILE");
    assertThat(returns.submit(req,ADMIN).status()).isEqualTo("REJECTED");
    assertThat(returns.submit(req,ADMIN).status()).isEqualTo("REJECTED");
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("SUBMITTED");
    assertThat(taskMapper.selectById(task).getTaskStatus()).isEqualTo("SUBMITTED");
    assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
    verify(oa,times(1)).submit(any());
    success();assertThat(returns.submit(returnRequest(task,"PROFILE"),ADMIN).status()).isEqualTo("SUCCESS");
    verify(oa,times(2)).submit(any());
  }

  @Test void unknownReturnNeverReopensOrResendsEvenWithNewKey() {
    var f=submittedFixture(1,false);long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("test",OaWorkflowResult.Status.UNKNOWN,null,"TIMEOUT","结果未确认","FLOW-"+f.formId(),null,1)).when(oa).submit(any());
    var req=returnRequest(task,"PROFILE");
    var result=returns.submit(req,ADMIN);assertThat(result.status()).isEqualTo("UNKNOWN");
    assertThat(returns.submit(req,ADMIN).status()).isEqualTo("UNKNOWN");
    assertThat(returns.status(result.batchId(),ADMIN).status()).isEqualTo("UNKNOWN");
    assertThatThrownBy(()->returns.submit(returnRequest(task,"PROFILE"),ADMIN)).hasMessageContaining("退回等待");
    assertThat(views.detail(task,ADMIN).taskStatus()).isEqualTo("RETURN_PENDING");
    assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
    assertThatThrownBy(()->access.requireCostPublication(f.formId())).hasMessageContaining("退回结果尚未确认");
    var notice=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
        .put("requestId","FLOW-"+f.formId()).put("eventType","TECHNICAL").put("reason","不能确认第一步未知结果");
    notice.putArray("employeeNos").add("12211470");
    var peer=new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST","TEST",Set.of("COMMERCIAL"));
    assertThatThrownBy(()->notices.receive(peer,notice.toString())).hasMessageContaining("退回人员尚未确认成功");
    assertThat(returns.status(result.batchId(),ADMIN).status()).isEqualTo("UNKNOWN");
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("RETURN_PENDING");
    verify(oa,times(1)).submit(any());
    verify(oa,never()).reject(any());
  }

  @Test void originalQuoterCanConfirmUnknownPeopleOnceAndOnlyDeliverTheSecondStep() {
    var f=submittedFixture(1,true); long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("people-unknown",OaWorkflowResult.Status.UNKNOWN,200,"MISSING_REQUEST_ID",
        "回执缺少requestId","FLOW-"+f.formId(),null,1)).when(oa).submit(any());
    var result=returns.submit(returnRequest(task,"PROFILE","NET_LOSS"),ADMIN);
    assertThatThrownBy(()->returns.confirmPeople(result.batchId(),result.attempt(),"已核实",DING)).hasMessageContaining("原报价员");
    assertThatThrownBy(()->returns.confirmPeople(result.batchId(),result.attempt()," ",ADMIN)).hasMessageContaining("依据");
    assertThatThrownBy(()->returns.confirmPeople(result.batchId(),result.attempt()+1,"已核实",ADMIN)).hasMessageContaining("请刷新");
    verify(oa,never()).reject(any());
    var confirmed=returns.confirmPeople(result.batchId(),result.attempt(),"在OA核对丁云龙工号更新成功",ADMIN);
    assertThat(confirmed.status()).isEqualTo("SUCCESS");
    assertThat(returns.confirmPeople(result.batchId(),result.attempt(),"重复点击",ADMIN).status()).isEqualTo("SUCCESS");
    assertThat(people.current(task).getFirst().processingModules()).containsExactly("PROFILE","NET_LOSS");
    verify(oa,times(1)).submit(any()); verify(oa,times(1)).reject(any());
    var saved=jdbc.queryForObject("SELECT people_result_json FROM lp_oa_technical_batch WHERE id=?",String.class,result.batchId());
    assertThat(saved).contains("MANUAL_CONFIRMED", "MISSING_REQUEST_ID", "manualConfirmation", "在OA核对丁云龙工号更新成功");
    save(task,DING,"9.99");
  }

  @Test void manualConfirmationStillRequiresAnUnchangedOriginalQuoterNode() {
    var f=submittedFixture(1,false); long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("unknown",OaWorkflowResult.Status.UNKNOWN,null,"TIMEOUT","未确认",
        "FLOW-"+f.formId(),null,1)).when(oa).submit(any());
    var result=returns.submit(returnRequest(task,"PROFILE"),ADMIN);
    jdbc.update("UPDATE lp_oa_quote_document SET source_version=source_version+1 WHERE oa_form_id=?",f.formId());
    assertThatThrownBy(()->returns.confirmPeople(result.batchId(),result.attempt(),"核实成功",ADMIN)).hasMessageContaining("暂停办理");
    verify(oa,never()).reject(any());
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("RETURN_PENDING");
  }

  @Test void staleVersionCrossDocumentAndTechnicalActorCannotReturn() {
    var first=submittedFixture(1,false);var second=submittedFixture(1,false);
    var req=returnRequest(first.taskIds().getFirst(),"PROFILE");
    assertThatThrownBy(()->returns.submit(req,DING)).hasMessageContaining("无权");
    jdbc.update("UPDATE lp_quote_tech_task SET task_version=task_version+1 WHERE id=?",first.taskIds().getFirst());
    assertThatThrownBy(()->returns.submit(req,ADMIN)).hasMessageContaining("已变化");
    var a=returnRequest(first.taskIds().getFirst(),"PROFILE").targets().getFirst();
    var b=returnRequest(second.taskIds().getFirst(),"PROFILE").targets().getFirst();
    assertThatThrownBy(()->returns.submit(new TechnicalDataReturnService.Request("mixed",List.of(a,b)),ADMIN)).hasMessageContaining("同一张");
    assertThat(people.current(first.taskIds().getFirst()).getFirst().todoStatus()).isEqualTo("SUBMITTED");
    verifyNoInteractions(oa);
  }

  @Test void simultaneousReturnClicksKeepPendingReadonlyAndSendOnlyOnce() throws Exception {
    var f=submittedFixture(1,false);long task=f.taskIds().getFirst();var req=returnRequest(task,"PROFILE");
    var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
    doAnswer(call->{entered.countDown();assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();
      return new OaWorkflowResult("test",OaWorkflowResult.Status.SUCCESS,200,"0","success","FLOW-"+f.formId(),null,1);
    }).when(oa).submit(any());
    try(var executor=Executors.newSingleThreadExecutor()) {
      var first=executor.submit(()->{materialActor();try{return returns.submit(req,ADMIN);}finally{clearActor();}});
      assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(returns.submit(req,ADMIN).status()).isEqualTo("SENDING");
        assertThat(views.detail(task,ADMIN).taskStatus()).isEqualTo("RETURN_PENDING");
        assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
      } finally {finish.countDown();}
      assertThat(first.get(10,TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
    }
    verify(oa,times(1)).submit(any());
  }

  @Test void successfulReceiptCannotReopenClosedWorkflow() {
    var f=submittedFixture(1,false);long task=f.taskIds().getFirst();
    doAnswer(call->{jdbc.update("UPDATE lp_oa_workflow_state SET state='CANCELLED' WHERE workflow_request_id=?","FLOW-"+f.formId());
      return new OaWorkflowResult("test",OaWorkflowResult.Status.SUCCESS,200,"0","success","FLOW-"+f.formId(),null,1);
    }).when(oa).reject(any());
    assertThatThrownBy(()->returns.submit(returnRequest(task,"PROFILE"),ADMIN)).hasMessageContaining("尚未开放编辑");
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("RETURN_PENDING");
    assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
  }

  @Test void missingSourceRejectionNodeStopsBeforeAnyOaCallOrTaskFreeze() {
    var f=submittedFixture(1,false); long task=f.taskIds().getFirst();
    jdbc.update("UPDATE lp_oa_quote_document SET reject_to_node_id=NULL WHERE oa_form_id=?",f.formId());
    assertThatThrownBy(()->returns.submit(returnRequest(task,"PROFILE"),ADMIN)).hasMessageContaining("rejectNodeid");
    verifyNoInteractions(oa);
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("SUBMITTED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_technical_batch WHERE oa_form_id=? AND operation='I05'",Long.class,f.formId())).isZero();
  }

  @Test void threeProductsAndTwoTechniciansAreReturnedByExactlyTwoCalls() {
    var f=dispatched(2,true);
    save(f.taskIds().get(0),DING,"1"); save(f.taskIds().get(1),DING,"2"); save(f.taskIds().get(2),PAN,"3");
    documents.submit(f.formId(),request(f.formId(),DING),DING);
    documents.submit(f.formId(),request(f.formId(),PAN),PAN);
    jdbc.update("INSERT INTO lp_oa_workflow_state(source_system,environment,workflow_request_id,applied_version,form_version,observed_form_version,state,active_work_items_json) VALUES('HZ_KG_OA_TST','TEST',?,1,1,1,'COSTING',?)",
        "FLOW-"+f.formId(),"[{\"workItemId\":\"MATERIAL-1\",\"nodeRole\":\"COSTING\",\"employeeNo\":\"11101516\"}]");
    materialActor(); clearInvocations(oa);
    var targets=f.taskIds().stream().map(id->returnRequest(id,"PROFILE").targets().getFirst()).toList();
    var req=new TechnicalDataReturnService.Request(UUID.randomUUID().toString(),targets);
    assertThat(returns.submit(req,ADMIN).status()).isEqualTo("SUCCESS");
    assertThat(returns.submit(req,ADMIN).status()).isEqualTo("SUCCESS");
    var order=inOrder(oa);
    order.verify(oa).submit(argThat(body->body.at("/formData/dataDetails/0/dataOptions").size()==2));
    order.verify(oa).reject(argThat(body->body.path("RejectToNodeid").asText().equals("00034567890123456789")
        && body.path("userid").asText().equals("11101516") && body.path("requestId").asText().equals("FLOW-"+f.formId())));
    verifyNoMoreInteractions(oa);
    assertThat(documents.workbench(f.formId(),DING).tasks()).hasSize(2);
    assertThat(documents.workbench(f.formId(),PAN).tasks()).hasSize(1);
    assertThatThrownBy(()->save(f.taskIds().getLast(),DING,"99")).hasMessageContaining("未分派");
  }

  @Test void failedSecondStepKeepsReadonlyAndRetryOnlySendsRejectionWithOriginalNode() {
    var f=submittedFixture(1,false); long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.REJECTED,200,"1200308","退回节点不在可选范围内","FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var req=returnRequest(task,"PROFILE");
    var failed=returns.submit(req,ADMIN);
    assertThat(failed.status()).isEqualTo("RETURN_FAILED");
    assertThat(failed.step()).isEqualTo("REJECT"); assertThat(failed.attempt()).isEqualTo(2);
    assertThat(failed.canRetry()).isTrue();
    var reopened=returns.candidates(List.of(task),ADMIN).getFirst();
    assertThat(reopened.modules()).extracting(TechnicalDataReturnService.Module::moduleType).containsExactly("PROFILE");
    assertThat(reopened.returnReason()).isEqualTo("请调整填写金额");
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("RETURN_PENDING");
    assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
    assertThatThrownBy(()->returns.submit(returnRequest(task,"PROFILE"),ADMIN)).hasMessageContaining("退回等待");
    assertThat(returns.submit(req,ADMIN).status()).isEqualTo("RETURN_FAILED");
    assertThat(returns.status(failed.batchId(),ADMIN).status()).isEqualTo("RETURN_FAILED");
    verify(oa,times(1)).submit(any()); verify(oa,times(1)).reject(any());
    assertThatThrownBy(()->returns.retryRejection(failed.batchId(),2,null,DING)).hasMessageContaining("原报价员");
    // 已上线的失败批次仍保存旧字段；重试转换报文，不能改写历史或重新读取其他节点。
    jdbc.update("UPDATE lp_oa_technical_batch SET reject_request_json=JSON_REMOVE(JSON_SET(reject_request_json,'$.rejectNodeid',JSON_EXTRACT(reject_request_json,'$.RejectToNodeid')),'$.RejectToNodeid') WHERE id=?",failed.batchId());
    var savedRejection=jdbc.queryForObject("SELECT reject_request_json FROM lp_oa_technical_batch WHERE id=?",String.class,failed.batchId());
    // 第二次调用只使用发送前固定的原需求节点，不能被发送期间的配置/来源更改替换。
    jdbc.update("UPDATE lp_oa_quote_document SET reject_to_node_id='CHANGED' WHERE oa_form_id=?",f.formId());
    success();
    var retried=returns.retryRejection(failed.batchId(),failed.attempt(),null,ADMIN);
    assertThat(retried.status()).isEqualTo("SUCCESS"); assertThat(retried.attempt()).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT reject_request_json FROM lp_oa_technical_batch WHERE id=?",String.class,failed.batchId())).isEqualTo(savedRejection);
    assertThat(returns.retryRejection(failed.batchId(),failed.attempt(),null,ADMIN).status()).isEqualTo("SUCCESS");
    verify(oa,times(1)).submit(any());
    verify(oa,times(2)).reject(argThat(body->body.path("RejectToNodeid").asText().equals("00034567890123456789")));
    assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(people_result_json->'$.status') FROM lp_oa_technical_batch WHERE id=?",String.class,failed.batchId())).isEqualTo("SUCCESS");
    save(task,DING,"9");
  }

  @Test void unknownSecondStepNeverReopensOrRetries() {
    var f=submittedFixture(1,false); long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.UNKNOWN,null,"TIMEOUT","结果未确认","FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var req=returnRequest(task,"PROFILE"); var result=returns.submit(req,ADMIN);
    assertThat(result.status()).isEqualTo("UNKNOWN"); assertThat(result.step()).isEqualTo("REJECT");
    assertThat(result.canRetry()).isFalse();
    assertThat(returns.submit(req,ADMIN).status()).isEqualTo("UNKNOWN");
    assertThat(returns.retryRejection(result.batchId(),result.attempt(),null,ADMIN).status()).isEqualTo("UNKNOWN");
    assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
    verify(oa,times(1)).submit(any()); verify(oa,times(1)).reject(any());
  }

  @Test void changedReasonReplacesRemarkAndSynchronizesBeforeRejectingAgain() {
    var f=submittedFixture(1,true); long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.REJECTED,200,"1200308","节点不可选",
        "FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var failed=returns.submit(returnRequest(task,"PROFILE","NET_LOSS"),ADMIN);
    String original=jdbc.queryForObject("SELECT raw_payload FROM lp_oa_integration_message WHERE technical_batch_id=?",String.class,failed.batchId());
    success(); clearInvocations(oa);
    String reason="请将净损失率修正为1%";
    var retry=returns.retryRejection(failed.batchId(),failed.attempt(),List.of(new TechnicalDataReturnService.Reason(task,reason)),ADMIN);
    assertThat(retry.status()).isEqualTo("SUCCESS");
    var order=inOrder(oa);
    order.verify(oa).submit(argThat(body->body.path("remark").asText().contains(reason)
        && !body.path("remark").asText().contains("请调整填写金额")
        && body.path("requestId").asText().equals("FLOW-"+f.formId())));
    order.verify(oa).reject(any());
    assertThat(people.current(task).getFirst().returnReason()).isEqualTo(reason);
    assertThat(jdbc.queryForObject("SELECT raw_payload FROM lp_oa_integration_message WHERE technical_batch_id=?",String.class,failed.batchId())).isEqualTo(original);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_business_change_log WHERE request_id=? AND field_name='RETURN_REASON_CHANGED' AND before_value=? AND after_value=?",Integer.class,failed.batchId(),"请调整填写金额",reason)).isEqualTo(1);
    assertThat(returns.retryRejection(failed.batchId(),failed.attempt(),List.of(new TechnicalDataReturnService.Reason(task,"过期页面的其他原因")),ADMIN).status()).isEqualTo("SUCCESS");
    assertThat(people.current(task).getFirst().returnReason()).isEqualTo(reason);
    verify(oa,times(1)).submit(any()); verify(oa,times(1)).reject(any());
  }

  @Test void identicalReasonOnlyRetriesRejectionAndInvalidReasonCannotChangeScope() {
    var f=submittedFixture(1,false); long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.REJECTED,200,"1200308","节点不可选",
        "FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var failed=returns.submit(returnRequest(task,"PROFILE"),ADMIN);clearInvocations(oa);
    for(var reasons:List.of(List.of(new TechnicalDataReturnService.Reason(task," ")),
        List.of(new TechnicalDataReturnService.Reason(task+100,"其他产品")),
        List.of(new TechnicalDataReturnService.Reason(task,"字".repeat(501))))) {
      assertThatThrownBy(()->returns.retryRejection(failed.batchId(),failed.attempt(),reasons,ADMIN)).isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(oa);
    success();
    assertThat(returns.retryRejection(failed.batchId(),failed.attempt(),List.of(new TechnicalDataReturnService.Reason(task,"请调整填写金额")),ADMIN).status()).isEqualTo("SUCCESS");
    verify(oa,never()).submit(any());verify(oa,times(1)).reject(any());
  }

  @Test void revisedReasonWaitsForPeopleReceiptBeforeAnyNewRejection() {
    var f=submittedFixture(1,false); long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.REJECTED,200,"1200308","节点不可选",
        "FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var failed=returns.submit(returnRequest(task,"PROFILE"),ADMIN);clearInvocations(oa);
    doReturn(new OaWorkflowResult("unknown",OaWorkflowResult.Status.UNKNOWN,null,"TIMEOUT","待核实",
        "FLOW-"+f.formId(),null,1)).when(oa).submit(any());
    var revised=returns.retryRejection(failed.batchId(),failed.attempt(),List.of(new TechnicalDataReturnService.Reason(task,"重新填写的原因")),ADMIN);
    assertThat(revised.status()).isEqualTo("UNKNOWN");assertThat(revised.step()).isEqualTo("PEOPLE");
    assertThat(returns.candidates(List.of(task),ADMIN).getFirst().returnReason()).isEqualTo("重新填写的原因");
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("RETURN_PENDING");
    verify(oa,times(1)).submit(any());verify(oa,never()).reject(any());
  }

  @Test void originalQuoterCanRetryAfterCostingNotificationRenewsLocalWorkItem() {
    var f=submittedFixture(1,true); long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.REJECTED,200,"1200504","已操作",
        "FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var failed=returns.submit(returnRequest(task,"PROFILE","NET_LOSS"),ADMIN);
    String originalNode=access.quoterNode(f.formId()).workItemId();
    var notice=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
        .put("requestId","FLOW-"+f.formId()).put("eventType","COSTING").put("reason","人工干预");
    var peer=new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST","TEST",Set.of("COMMERCIAL"));
    assertThat(notices.receive(peer,notice.toString()).path("code").asText()).isEqualTo("0");
    assertThat(access.quoterNode(f.formId()).workItemId()).isNotEqualTo(originalNode);
    success();
    var retried=returns.retryRejection(failed.batchId(),failed.attempt(),null,ADMIN);
    assertThat(retried.status()).isEqualTo("SUCCESS");
    assertThat(returns.retryRejection(failed.batchId(),failed.attempt(),null,ADMIN).status()).isEqualTo("SUCCESS");
    verify(oa,times(1)).submit(any());
    verify(oa,times(2)).reject(argThat(body->body.path("requestId").asText().equals("FLOW-"+f.formId())
        && body.path("userid").asText().equals("11101516")
        && body.path("RejectToNodeid").asText().equals("00034567890123456789")));
    assertThat(netLoss.get(repository.findProducts(task).getFirst().getId(),null,DING).editable()).isTrue();
    save(task,DING,"9.99");
  }

  @Test void renewedLocalWorkItemWhileOaRespondsDoesNotDiscardSuccessfulReturn() {
    var f=submittedFixture(1,false); long task=f.taskIds().getFirst();
    doAnswer(call->{
      jdbc.update("UPDATE lp_oa_workflow_state SET applied_version=applied_version+1,active_work_items_json=? WHERE workflow_request_id=?",
          "[{\"workItemId\":\"LOCAL-RENEWED\",\"nodeRole\":\"COSTING\",\"employeeNo\":\"11101516\"}]","FLOW-"+f.formId());
      return new OaWorkflowResult("reject",OaWorkflowResult.Status.SUCCESS,200,"0","success","FLOW-"+f.formId(),null,1);
    }).when(oa).reject(any());
    assertThat(returns.submit(returnRequest(task,"PROFILE"),ADMIN).status()).isEqualTo("SUCCESS");
    verify(oa,times(1)).submit(any()); verify(oa,times(1)).reject(any());
    save(task,DING,"9.99");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"REQUEST","EMPLOYEE","FORM_VERSION","SUBMISSION","CLOSED"})
  void retryStillRejectsChangedReturnIdentityOrData(String change) {
    var f=submittedFixture(1,false); long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.REJECTED,200,"1200504","已操作",
        "FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var failed=returns.submit(returnRequest(task,"PROFILE"),ADMIN);
    switch(change) {
      case "REQUEST" -> jdbc.update("UPDATE lp_oa_quote_document SET external_document_id=? WHERE oa_form_id=?","OTHER-"+f.formId(),f.formId());
      case "EMPLOYEE" -> {
        jdbc.update("UPDATE sys_user SET employee_no='OTHER' WHERE user_id=1");
        jdbc.update("UPDATE lp_oa_workflow_state SET active_work_items_json=? WHERE workflow_request_id=?",
            "[{\"workItemId\":\"MATERIAL-1\",\"nodeRole\":\"COSTING\",\"employeeNo\":\"OTHER\"}]","FLOW-"+f.formId());
      }
      case "FORM_VERSION" -> {
        jdbc.update("UPDATE lp_oa_quote_document SET source_version=2 WHERE oa_form_id=?",f.formId());
        jdbc.update("UPDATE lp_oa_workflow_state SET form_version=2,observed_form_version=2 WHERE workflow_request_id=?","FLOW-"+f.formId());
      }
      case "SUBMISSION" -> jdbc.update("UPDATE lp_quote_tech_oa_recipient SET latest_submission_id=NULL WHERE task_id=? AND active_flag=1",task);
      case "CLOSED" -> jdbc.update("UPDATE lp_oa_workflow_state SET state='CANCELLED' WHERE workflow_request_id=?","FLOW-"+f.formId());
    }
    var result=returns.retryRejection(failed.batchId(),failed.attempt(),null,ADMIN);
    assertThat(result.status()).isEqualTo("RETURN_FAILED");
    assertThat(result.oaResult().errorCode()).isEqualTo("BUSINESS_STATE_CHANGED");
    assertThat(result.oaResult().status()).isEqualTo(OaWorkflowResult.Status.NOT_SENT);
    verify(oa,times(1)).submit(any()); verify(oa,times(1)).reject(any());
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("RETURN_PENDING");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"missing","null","empty","blank"})
  void technicalNoticeWithEmptyReasonKeepsUnknownReturnReadonlyWithoutResending(String representation) {
    var f=submittedFixture(1,true); long task=f.taskIds().getFirst();
    var product=repository.findProducts(task).getFirst();
    long oldRate=repository.findModules(product.getId()).stream().filter(m->"NET_LOSS".equals(m.getModuleType()))
        .findFirst().orElseThrow().getCurrentVersionId();
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.UNKNOWN,null,"TIMEOUT","结果未确认",
        "FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var result=returns.submit(returnRequest(task,"PROFILE","NET_LOSS"),ADMIN);
    assertThat(result.status()).isEqualTo("UNKNOWN");
    var notice=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
        .put("requestId","FLOW-"+f.formId()).put("eventType","TECHNICAL");
    switch(representation) {
      case "null" -> notice.putNull("reason");
      case "empty" -> notice.put("reason","");
      case "blank" -> notice.put("reason"," \t\n");
    }
    notice.putArray("employeeNos").add("12211470");
    var peer=new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST","TEST",Set.of("COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
    assertThat(notices.receive(peer,notice.toString()).path("code").asText()).isEqualTo("0");
    assertThat(notices.receive(peer,notice.toString()).path("code").asText()).isEqualTo("0");
    assertThat(returns.status(result.batchId(),ADMIN).status()).isEqualTo("UNKNOWN");
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("RETURN_PENDING");
    assertThat(people.current(task).getFirst().returnReason()).isEqualTo("请调整填写金额");
    assertThat(repository.findModules(product.getId()).stream().filter(m->"NET_LOSS".equals(m.getModuleType()))
        .findFirst().orElseThrow().getCurrentVersionId()).isEqualTo(oldRate);
    assertThat(netLoss.get(product.getId(),null,DING).editable()).isFalse();
    assertThatThrownBy(()->save(task,DING,"9.99")).hasMessageContaining("未分派");
    verify(oa,times(1)).submit(any()); verify(oa,times(1)).reject(any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "false,SUCCESS", "false,REJECTED", "false,UNKNOWN", "false,EXCEPTION",
      "true,SUCCESS", "true,REJECTED", "true,UNKNOWN", "true,EXCEPTION"})
  void earlyTechnicalNoticeWaitsForExplicitRejectionResult(boolean retry, String receipt) throws Exception {
    var f=submittedFixture(1,true); long task=f.taskIds().getFirst();
    var req=returnRequest(task,"PROFILE","NET_LOSS");
    TechnicalDataReturnService.Result failed;
    if (retry) {
      doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.REJECTED,200,"1200390","执行失败",
          "FLOW-"+f.formId(),null,1)).when(oa).reject(any());
      failed=returns.submit(req,ADMIN);
      assertThat(failed.status()).isEqualTo("RETURN_FAILED");
    } else failed=null;
    var entered=new CountDownLatch(1); var finish=new CountDownLatch(1);
    doAnswer(call->{
      entered.countDown(); assertThat(finish.await(20,TimeUnit.SECONDS)).isTrue();
      if ("EXCEPTION".equals(receipt)) throw new IllegalStateException("simulated response lost");
      return new OaWorkflowResult("http-receipt",OaWorkflowResult.Status.valueOf(receipt),200,
          "SUCCESS".equals(receipt)?"0":"1200390","接口原始回执","FLOW-"+f.formId(),null,1);
    }).when(oa).reject(any());
    var notice=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
        .put("requestId","FLOW-"+f.formId()).put("eventType","TECHNICAL");
    var peer=new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST","TEST",Set.of("COMMERCIAL"));
    long productId=repository.findProducts(task).getFirst().getId();
    String originalFlow=jdbc.queryForObject("SELECT state FROM lp_oa_workflow_state WHERE workflow_request_id=?",String.class,"FLOW-"+f.formId());
    var versions=jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version WHERE product_id=? ORDER BY id",productId);
    try(var executor=Executors.newSingleThreadExecutor()) {
      var sending=executor.submit(()->{materialActor();try {
        return retry?returns.retryRejection(failed.batchId(),failed.attempt(),null,ADMIN):returns.submit(req,ADMIN);
      } finally {clearActor();}});
      assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();
      try {
        clearActor();
        assertThat(notices.receive(peer,notice.toString()).path("code").asText()).isEqualTo("0");
        assertThat(notices.receive(peer,notice.toString()).path("code").asText()).isEqualTo("0");
        assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("RETURN_PENDING");
        assertThat(netLoss.get(productId,null,DING).editable()).isFalse();
        assertThat(jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version WHERE product_id=? ORDER BY id",productId)).isEqualTo(versions);
        assertThat(jdbc.queryForObject("SELECT state FROM lp_oa_workflow_state WHERE workflow_request_id=?",String.class,"FLOW-"+f.formId())).isEqualTo(originalFlow);
      } finally {finish.countDown();}
      var result=sending.get(20,TimeUnit.SECONDS);
      boolean accepted="SUCCESS".equals(receipt);
      assertThat(result.status()).isEqualTo(accepted?"SUCCESS":"REJECTED".equals(receipt)?"RETURN_FAILED":"UNKNOWN");
      assertThat(netLoss.get(productId,null,DING).editable()).isEqualTo(accepted);
      assertThat(people.current(task).getFirst().returnReason()).isEqualTo("请调整填写金额");
      assertThat(jdbc.queryForObject("SELECT state FROM lp_oa_workflow_state WHERE workflow_request_id=?",String.class,"FLOW-"+f.formId())).isEqualTo(accepted?"TECHNICAL":originalFlow);
      assertThat(jdbc.queryForList("SELECT status FROM lp_oa_integration_message WHERE technical_batch_id=? AND direction='INBOUND'",String.class,result.batchId()))
          .containsExactly(accepted?"PROCESSED":"REJECTED".equals(receipt)?"REJECTED":"WAITING_HANDLER");
      var after=jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version WHERE product_id=? ORDER BY id",productId);
      assertThat(notices.receive(peer,notice.toString()).path("code").asText()).isEqualTo("0");
      assertThat(jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version WHERE product_id=? ORDER BY id",productId)).isEqualTo(after);
      assertThat(returns.status(result.batchId(),ADMIN).status()).isEqualTo(result.status());
      if (accepted) {
        assertThat(result.oaResult().callId()).isEqualTo("http-receipt");
        assertThat(result.oaResult().errorCode()).isEqualTo("0");
        assertThat(people.current(task).getFirst().processingModules()).containsExactly("PROFILE","NET_LOSS");
        save(task,DING,"9.99");
      } else {
        assertThat(after).isEqualTo(versions);
        assertThatThrownBy(()->save(task,DING,"9.99")).hasMessageContaining("未分派");
      }
    }
    verify(oa,times(1)).submit(any()); verify(oa,times(retry?2:1)).reject(any());
  }

  @Test void failedEarlyNoticeDoesNotPreventTheSameReturnFromSucceedingOnExplicitRetry() {
    var f=submittedFixture(1,true); long task=f.taskIds().getFirst();
    long productId=repository.findProducts(task).getFirst().getId();
    var notice=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
        .put("requestId","FLOW-"+f.formId()).put("eventType","TECHNICAL");
    var peer=new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST","TEST",Set.of("COMMERCIAL"));
    var attempts=new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call->{
      assertThat(notices.receive(peer,notice.toString()).path("code").asText()).isEqualTo("0");
      assertThat(netLoss.get(productId,null,DING).editable()).isFalse();
      boolean success=attempts.incrementAndGet()==2;
      return new OaWorkflowResult("actual-receipt",success?OaWorkflowResult.Status.SUCCESS:OaWorkflowResult.Status.REJECTED,
          200,success?"0":"1200390",success?"成功":"执行失败","FLOW-"+f.formId(),null,1);
    }).when(oa).reject(any());
    var first=returns.submit(returnRequest(task,"PROFILE","NET_LOSS"),ADMIN);
    assertThat(first.status()).isEqualTo("RETURN_FAILED");
    assertThat(netLoss.get(productId,null,DING).editable()).isFalse();
    var retried=returns.retryRejection(first.batchId(),first.attempt(),null,ADMIN);
    assertThat(retried.status()).isEqualTo("SUCCESS");
    assertThat(retried.oaResult().errorCode()).isEqualTo("0");
    assertThat(netLoss.get(productId,null,DING).editable()).isTrue();
    assertThat(jdbc.queryForList("SELECT status FROM lp_oa_integration_message WHERE technical_batch_id=? AND direction='INBOUND'",String.class,first.batchId()))
        .containsExactly("PROCESSED");
    verify(oa,times(1)).submit(any()); verify(oa,times(2)).reject(any());
  }

  @Test void notificationAfterSuccessfulReceiptBeforeCompletionUsesTheActualReceipt() {
    var f=submittedFixture(1,true); long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("unknown",OaWorkflowResult.Status.UNKNOWN,null,"TIMEOUT","结果未知",
        "FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var result=returns.submit(returnRequest(task,"PROFILE","NET_LOSS"),ADMIN);
    new TransactionTemplate(manager).executeWithoutResult(tx->{
      jdbc.update("UPDATE lp_oa_technical_batch SET status='SENDING' WHERE id=?",result.batchId());
      batches.receiveReturnStep(batches.find(result.batchId(),true),
          new OaWorkflowResult("actual-success",OaWorkflowResult.Status.SUCCESS,200,"0","成功","FLOW-"+f.formId(),null,1));
    });
    var notice=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
        .put("requestId","FLOW-"+f.formId()).put("eventType","TECHNICAL");
    clearActor();
    assertThat(notices.receive(new com.sanhua.marketingcost.integration.oa.OaPeer("HZ_KG_OA_TST","TEST",Set.of("COMMERCIAL")),notice.toString()).path("code").asText()).isEqualTo("0");
    var completed=returns.status(result.batchId(),ADMIN);
    assertThat(completed.status()).isEqualTo("SUCCESS");
    assertThat(completed.oaResult().callId()).isEqualTo("actual-success");
    assertThat(netLoss.get(repository.findProducts(task).getFirst().getId(),null,DING).editable()).isTrue();
    verify(oa,times(1)).reject(any());
  }

  @Test void repeatedRetryRequestCannotResendAfterAnotherExplicitFailure() {
    var f=submittedFixture(1,false);
    doReturn(new OaWorkflowResult("reject",OaWorkflowResult.Status.REJECTED,200,"1200308","退回节点不可选","FLOW-"+f.formId(),null,1)).when(oa).reject(any());
    var result=returns.submit(returnRequest(f.taskIds().getFirst(),"PROFILE"),ADMIN);
    assertThat(returns.retryRejection(result.batchId(),result.attempt(),null,ADMIN).attempt()).isEqualTo(3);
    assertThat(returns.retryRejection(result.batchId(),result.attempt(),null,ADMIN).attempt()).isEqualTo(3);
    verify(oa,times(1)).submit(any()); verify(oa,times(2)).reject(any());
  }

  @Test void whileSecondStepIsSendingAllProductsStayReadonlyAndDuplicateClickDoesNotSend() throws Exception {
    var f=submittedFixture(1,false); long task=f.taskIds().getFirst(); var req=returnRequest(task,"PROFILE");
    var entered=new CountDownLatch(1); var finish=new CountDownLatch(1);
    doAnswer(call->{entered.countDown();assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();
      return new OaWorkflowResult("reject",OaWorkflowResult.Status.SUCCESS,200,"0","success","FLOW-"+f.formId(),null,1);
    }).when(oa).reject(any());
    try(var executor=Executors.newSingleThreadExecutor()) {
      var first=executor.submit(()->{materialActor();try{return returns.submit(req,ADMIN);}finally{clearActor();}});
      assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(returns.submit(req,ADMIN).step()).isEqualTo("REJECT");
        assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("RETURN_PENDING");
        assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
      } finally {finish.countDown();}
      assertThat(first.get(10,TimeUnit.SECONDS).status()).isEqualTo("SUCCESS");
    }
    verify(oa,times(1)).submit(any()); verify(oa,times(1)).reject(any());
  }
}
