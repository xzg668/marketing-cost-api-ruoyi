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
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
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
class TechnicalDataNativeChainIntegrationTest extends BomMapperTestBase {
  private static final AtomicLong IDS=new AtomicLong(9_510_000);
  private static final TechnicalDataActor ADMIN=new TechnicalDataActor(1L,"报价员",Set.of("*:*:*"));
  private static final TechnicalDataActor DING=new TechnicalDataActor(101L,"丁云龙",Set.of("technical:data:task:list","technical:data:task:edit"));
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
  @Autowired TechnicalDataReadPolicy readPolicy;
  @Autowired OaIntegrationProperties properties;
  @MockBean OaWorkflowClient oa;
  @MockBean SysUserService users;
  @Autowired OaPersonDirectoryRepository directory;

  @Autowired TechnicalDataReturnService returns;
  @Autowired TechnicalDataOaSubmissionLifecycle lifecycle;
  @Autowired TechnicalDataNetLossApplicationService netLoss;
  @Autowired com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository people;
  @Autowired com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper submissionMapper;
  @Autowired com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy access;

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
    when(oa.submit(any())).thenAnswer(call -> {
      ObjectNode body=call.getArgument(0);
      return new OaWorkflowResult("test",OaWorkflowResult.Status.SUCCESS,200,"0","成功",body.path("requestId").asText(),null,1);
    });
  }

  private Fixture dispatched(int dingProducts,boolean panProduct) { return dispatched(dingProducts,panProduct,false); }
  private Fixture dispatched(int dingProducts,boolean panProduct,boolean extraModule) {
    long form=IDS.incrementAndGet(); String oaNo="FI-SC-005-CHAIN-"+form;
    jdbc.update("INSERT INTO oa_form(id,oa_no,process_code,business_unit_type,deleted) VALUES(?,?,'FI-SC-005','COMMERCIAL',0)",form,oaNo);
    jdbc.update("INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version) VALUES('HZ_KG_OA_TST','TEST',?,?,1)","FLOW-"+form,form);
    List<Long> ids=new ArrayList<>();
    for (int i=0;i<dingProducts+(panProduct?1:0);i++) {
      long item=IDS.incrementAndGet();
      jdbc.update("INSERT INTO oa_form_item(id,oa_form_id,seq,material_no,product_name,sunl_model,business_unit_type,deleted) VALUES(?,?,?,?,'测试产品','M','COMMERCIAL',0)",item,form,i+1,"P"+item);
      var task=new QuoteTechTask();task.setTaskNo("TD-CHAIN-"+item);task.setOaFormId(form);task.setOaFormItemId(item);task.setOaNo(oaNo);
      task.setAccountingMonth("2026-09");task.setBusinessUnitType("COMMERCIAL");task.setApplicableOrgCode("210");task.setAssigneeUserId(i<dingProducts?101L:102L);task.setAssigneeName("技术员");
      task=persistence.createTask(task); ids.add(task.getId());
      var product=new QuoteTechProduct();product.setTaskId(task.getId());product.setOaFormItemId(item);product.setMaterialNo("P"+item);product.setProductName("测试产品");product.setQuoteNo(oaNo);product.setAccountingMonth("2026-09");product.setContentSchemaVersion(2);
      product.setSourceSnapshotJson("{\"productModel\":\"M\",\"newProduct\":false}");product.setSourceFingerprint("b".repeat(64));product=persistence.createProduct(product);
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

  private void save(long taskId,TechnicalDataActor actor,String amount) {
    var product=repository.findProducts(taskId).getFirst();
    var request=new TechnicalDataProfileUpdateRequest();request.setExpectedVersion(product.getRowVersion());request.setProductProperty("非标品");
    request.setHasAdditionalFees(true);request.setUnitToolingFee(amount);request.setUnitMouldFee("/");request.setUnitCertificationFee("/");
    profiles.save(product.getId(),request,actor);
  }

  private TechnicalDataDocumentSubmissionService.Request request(long form,TechnicalDataActor actor) {
    return new TechnicalDataDocumentSubmissionService.Request(UUID.randomUUID().toString(),documents.workbench(form,actor).fingerprint());
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
        && body.at("/formData/dataDetails/0/content").asText().contains("submission="+result.batchId())));
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

  private void materialActor() {
    jdbc.update("UPDATE sys_user SET employee_no='11101516' WHERE user_id=1");
    String username=jdbc.queryForObject("SELECT user_name FROM sys_user WHERE user_id=1",String.class);
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(username,"unused",List.of()));
  }
  @AfterEach void clearActor() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

  private Fixture approvedFixture(int count,boolean extraModule) {
    var fixture=dispatched(count,false,extraModule);
    for(long id:fixture.taskIds()) { save(id,DING,"2.50"); if(extraModule) saveRate(id,"0.3"); }
    assertThat(documents.submit(fixture.formId(),request(fixture.formId(),DING),DING).status()).isEqualTo("SUCCESS");
    approveCurrent(fixture);
    jdbc.update("INSERT INTO lp_oa_workflow_state(source_system,environment,workflow_request_id,applied_version,form_version,observed_form_version,state,active_work_items_json) VALUES('HZ_KG_OA_TST','TEST',?,1,1,1,'MATERIAL_REVIEW',?)",
        "FLOW-"+fixture.formId(),"[{\"workItemId\":\"MATERIAL-1\",\"nodeRole\":\"MATERIAL\",\"employeeNo\":\"11101516\"}]");
    materialActor(); clearInvocations(oa);
    return fixture;
  }
  private void approveCurrent(Fixture fixture) {
    new TransactionTemplate(manager).executeWithoutResult(status -> {
      for(long id:fixture.taskIds()) for(var person:people.current(id)) if("SUBMITTED".equals(person.todoStatus())) {
        var submission=submissionMapper.selectById(person.latestSubmissionId());
        lifecycle.approve(taskMapper.selectByIdForUpdate(id),submission,person.messageId(),ADMIN);
      }
    });
  }
  private void saveRate(long taskId,String percent) {
    var product=repository.findProducts(taskId).getFirst();
    var req=new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataNetLossSaveRequest();
    req.setExpectedVersion(product.getRowVersion());req.setEntryMode("MANUAL");req.setPercent(percent);
    netLoss.save(product.getId(),req,DING);
  }
  private TechnicalDataReturnService.Request returnRequest(long taskId,String... modules) {
    return new TechnicalDataReturnService.Request(UUID.randomUUID().toString(),List.of(new TechnicalDataReturnService.Target(
        taskId,taskMapper.selectById(taskId).getTaskVersion(),List.of(modules),"请调整填写金额")));
  }

  @Test void returnOnlySelectedModuleThenSubmitOnlyReturnedProductAndPreserveApprovedHistory() {
    var f=approvedFixture(2,true);long task=f.taskIds().getFirst();
    var before=repository.findProducts(task).getFirst();
    long oldProfile=repository.findModules(before.getId()).stream().filter(m->"PROFILE".equals(m.getModuleType())).findFirst().orElseThrow().getCurrentVersionId();
    var req=returnRequest(task,"PROFILE");
    var result=returns.submit(req,ADMIN);
    assertThat(result.status()).isEqualTo("SUCCESS");
    assertThat(returns.submit(req,ADMIN).batchId()).isEqualTo(result.batchId());
    assertThat(returns.status(result.batchId(),ADMIN).status()).isEqualTo("SUCCESS");
    verify(oa,times(1)).submit(argThat(body->body.path("remark").asText().contains("退回修改")
        && body.path("userid").asText().equals("11101516")
        && body.at("/formData/dataDetails/0/dataOptions/0/optionId").asText().equals("12211470")));
    assertThat(people.current(task).getFirst().processingModules()).containsExactly("PROFILE");
    assertThat(repository.findProducts(task).getFirst().getCurrentEditVersionId()).isEqualTo(before.getCurrentEditVersionId());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version WHERE product_id=? AND version_status IN ('DRAFT','VOIDED')",Long.class,before.getId())).isEqualTo(1);

    assertThat(people.current(f.taskIds().getLast()).getFirst().todoStatus()).isEqualTo("DONE");
    assertThat(DING.canEditModule(taskMapper.selectById(task),repository.findModules(before.getId()).stream().filter(m->"PROFILE".equals(m.getModuleType())).findFirst().orElseThrow())).isTrue();
    assertThat(netLoss.get(before.getId(),null,DING).editable()).isFalse();
    assertThatThrownBy(()->saveRate(task,"0.9")).hasMessageContaining("未分派");
    assertThat(jdbc.queryForObject("SELECT version_status FROM lp_quote_tech_data_version WHERE id=?",String.class,oldProfile)).isEqualTo("APPROVED");
    save(task,DING,"9.99");
    assertThat(views.detail(task,ADMIN).products().getFirst().profile().unitToolingFee()).isEqualTo("2.5");
    assertThat(documents.workbench(f.formId(),DING).canSubmit()).isTrue();
    var resubmit=documents.submit(f.formId(),request(f.formId(),DING),DING);
    assertThat(resubmit.status()).isEqualTo("SUCCESS");
    assertThat(documents.submitted(f.formId(),resubmit.batchId(),ADMIN).tasks()).hasSize(1);
    var current=people.current(task).getFirst();
    assertThat(submissionMapper.selectById(current.latestSubmissionId()).getModuleTypesJson()).contains("PROFILE").doesNotContain("NET_LOSS");
    approveCurrent(f);
    // 下一轮可退回此人另一块仍指向旧批准版本的资料。
    assertThat(returns.submit(returnRequest(task,"NET_LOSS"),ADMIN).status()).isEqualTo("SUCCESS");
    assertThat(netLoss.get(before.getId(),null,DING).editable()).isTrue();
    assertThat(DING.canEditModule(taskMapper.selectById(task),repository.findModules(before.getId()).stream().filter(m->"PROFILE".equals(m.getModuleType())).findFirst().orElseThrow())).isFalse();
    saveRate(task,"0.8");
    assertThat(documents.submit(f.formId(),request(f.formId(),DING),DING).status()).isEqualTo("SUCCESS");
  }

  @Test void rejectedReturnPreservesApprovalAndOnlyNewExplicitRequestRetries() {
    var f=approvedFixture(1,false);long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("test",OaWorkflowResult.Status.REJECTED,200,"200031","没有查询到对应人员信息","FLOW-"+f.formId(),null,1)).when(oa).submit(any());
    var req=returnRequest(task,"PROFILE");
    assertThat(returns.submit(req,ADMIN).status()).isEqualTo("REJECTED");
    assertThat(returns.submit(req,ADMIN).status()).isEqualTo("REJECTED");
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("DONE");
    assertThat(taskMapper.selectById(task).getTaskStatus()).isEqualTo("APPROVED");
    assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
    verify(oa,times(1)).submit(any());
    success();assertThat(returns.submit(returnRequest(task,"PROFILE"),ADMIN).status()).isEqualTo("SUCCESS");
    verify(oa,times(2)).submit(any());
  }

  @Test void unknownReturnNeverReopensOrResendsEvenWithNewKey() {
    var f=approvedFixture(1,false);long task=f.taskIds().getFirst();
    doReturn(new OaWorkflowResult("test",OaWorkflowResult.Status.UNKNOWN,null,"TIMEOUT","结果未确认","FLOW-"+f.formId(),null,1)).when(oa).submit(any());
    var req=returnRequest(task,"PROFILE");
    var result=returns.submit(req,ADMIN);assertThat(result.status()).isEqualTo("UNKNOWN");
    assertThat(returns.submit(req,ADMIN).status()).isEqualTo("UNKNOWN");
    assertThat(returns.status(result.batchId(),ADMIN).status()).isEqualTo("UNKNOWN");
    assertThatThrownBy(()->returns.submit(returnRequest(task,"PROFILE"),ADMIN)).hasMessageContaining("退回等待");
    assertThat(views.detail(task,ADMIN).taskStatus()).isEqualTo("RETURN_PENDING");
    assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
    assertThatThrownBy(()->access.requireCostPublication(f.formId())).hasMessageContaining("尚未确认");
    verify(oa,times(1)).submit(any());
  }

  @Test void staleVersionCrossDocumentAndTechnicalActorCannotReturn() {
    var first=approvedFixture(1,false);var second=approvedFixture(1,false);
    var req=returnRequest(first.taskIds().getFirst(),"PROFILE");
    assertThatThrownBy(()->returns.submit(req,DING)).hasMessageContaining("无权");
    jdbc.update("UPDATE lp_quote_tech_task SET task_version=task_version+1 WHERE id=?",first.taskIds().getFirst());
    assertThatThrownBy(()->returns.submit(req,ADMIN)).hasMessageContaining("已变化");
    var a=returnRequest(first.taskIds().getFirst(),"PROFILE").targets().getFirst();
    var b=returnRequest(second.taskIds().getFirst(),"PROFILE").targets().getFirst();
    assertThatThrownBy(()->returns.submit(new TechnicalDataReturnService.Request("mixed",List.of(a,b)),ADMIN)).hasMessageContaining("同一张");
    assertThat(people.current(first.taskIds().getFirst()).getFirst().todoStatus()).isEqualTo("DONE");
    verifyNoInteractions(oa);
  }

  @Test void simultaneousReturnClicksKeepPendingReadonlyAndSendOnlyOnce() throws Exception {
    var f=approvedFixture(1,false);long task=f.taskIds().getFirst();var req=returnRequest(task,"PROFILE");
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
    var f=approvedFixture(1,false);long task=f.taskIds().getFirst();
    doAnswer(call->{jdbc.update("UPDATE lp_oa_workflow_state SET state='CANCELLED' WHERE workflow_request_id=?","FLOW-"+f.formId());
      return new OaWorkflowResult("test",OaWorkflowResult.Status.SUCCESS,200,"0","success","FLOW-"+f.formId(),null,1);
    }).when(oa).submit(any());
    assertThatThrownBy(()->returns.submit(returnRequest(task,"PROFILE"),ADMIN)).hasMessageContaining("尚未开放编辑");
    assertThat(people.current(task).getFirst().todoStatus()).isEqualTo("RETURN_PENDING");
    assertThatThrownBy(()->save(task,DING,"99")).hasMessageContaining("未分派");
  }
}
