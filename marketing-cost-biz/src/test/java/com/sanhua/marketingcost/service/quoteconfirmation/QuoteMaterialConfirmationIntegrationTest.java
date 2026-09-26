package com.sanhua.marketingcost.service.quoteconfirmation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.dto.quotecosting.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.*;
import com.sanhua.marketingcost.integration.oa.workflow.*;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.costing.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

@Tag("integration")
@TestPropertySource(properties={"cms.sync-publish.enabled=false"})
class QuoteMaterialConfirmationIntegrationTest extends BomMapperTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired QuoteMaterialConfirmationService service;
  @Autowired OaWorkflowAccessPolicy access;
  @Autowired QuoteMaterialConfirmationRepository confirmations;
  @MockBean ProductCostingPipeline pipeline;
  @MockBean ProductCostingContextResolver contexts;
  @MockBean QuoteBatchCostRunService batches;
  @MockBean OaWorkflowClient oa;
  @MockBean BusinessUnitRepriceLockGuard repriceLock;
  String oaNo, requestId, workItem;
  long formId;
  List<Long> items;
  String month = CostPricingPeriodUtils.currentPricingMonth();

  void actor() {
    var authentication = new UsernamePasswordAuthenticationToken("admin", "unused", List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }
  @BeforeEach void fixture() {
    actor();
    jdbc.update("UPDATE sys_user SET employee_no='001001' WHERE user_id=1");
    oaNo="I06-"+UUID.randomUUID(); requestId="REQ-"+UUID.randomUUID(); workItem="MAT-"+UUID.randomUUID();
    jdbc.update("INSERT INTO oa_form(oa_no,source_type,business_unit_type) VALUES(?,'OA','COMMERCIAL')",oaNo);
    formId=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oaNo);
    for(int n=1;n<=2;n++) jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no) VALUES(?,?,?)",formId,n,"P"+n);
    items=jdbc.queryForList("SELECT id FROM oa_form_item WHERE oa_form_id=? ORDER BY id",Long.class,formId);
    jdbc.update("INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version) VALUES('I06','TEST',?,?,1)",requestId,formId);
    jdbc.update("INSERT INTO lp_oa_workflow_state(source_system,environment,workflow_request_id,applied_version,form_version,observed_form_version,state,active_work_items_json) VALUES('I06','TEST',?,1,1,1,'MATERIAL_REVIEW',?)",requestId,todo(workItem));
    when(contexts.resolve(any())).thenAnswer(call->{
      ProductCostingRequest req=call.getArgument(0);
      var form=new OaForm();form.setId(formId);form.setOaNo(oaNo);
      var item=new OaFormItem();item.setId(req.oaFormItemId());item.setOaFormId(formId);
      return new ProductCostingContext(form,item,"P"+req.oaFormItemId(),month,"admin","revision");
    });
    when(contexts.resolveRevision(any())).thenAnswer(call->call.getArgument(0));
    when(pipeline.prepare(any())).thenAnswer(call->{
      ProductCostingRequest req=call.getArgument(0);return check(req.oaFormItemId(),"READY");
    });
    when(pipeline.execute(any())).thenAnswer(call->check(((ProductCostingRequest)call.getArgument(0)).oaFormItemId(),"SUCCESS"));
    when(batches.submit(anyString(),any(),anyString())).thenReturn(new QuoteBatchCostRunResponse());
    when(oa.submit(any())).thenReturn(result(OaWorkflowResult.Status.SUCCESS));
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  String todo(String id) {return "[{\"workItemId\":\""+id+"\",\"nodeRole\":\"MATERIAL\",\"employeeNo\":\"001001\"}]";}
  ProductCostingResult check(long id,String status){var r=new ProductCostingResult();r.setOaNo(oaNo);r.setOaFormItemId(id);r.setProductCode("P"+id);r.setPipelineStatus(status);r.setSourceRevision("revision");return r;}
  OaWorkflowResult result(OaWorkflowResult.Status status){return new OaWorkflowResult("call",status,200,status==OaWorkflowResult.Status.SUCCESS?"0":"TEST_REJECTED","测试回执",requestId,null,1);}
  QuoteMaterialConfirmationService.Request request(String key, Long item, boolean retry){return new QuoteMaterialConfirmationService.Request(key,item,month,retry);}

  @Test void allProductsCheckedThenOneNativeCallAcrossBatchAndProductClicks() {
    assertThat(service.state(oaNo).status()).isEqualTo("REQUIRES_CONFIRMATION");
    verifyNoInteractions(oa,pipeline);
    var first=service.confirmAndCost(oaNo,request("one",null,false),"admin");
    assertThat(first.confirmation().status()).isEqualTo("SUCCESS");assertThat(first.batch()).isNotNull();
    var second=service.confirmAndCost(oaNo,request("two",items.getFirst(),false),"admin");
    assertThat(second.product().getPipelineStatus()).isEqualTo("SUCCESS");
    var body=org.mockito.ArgumentCaptor.forClass(ObjectNode.class);
    verify(oa,times(1)).submit(body.capture());
    assertThat(body.getValue().path("userid").asText()).isEqualTo("001001");
    assertThat(body.getValue().path("requestId").asText()).isEqualTo(requestId);
    assertThat(body.getValue().path("remark").asText()).isEqualTo("无需技术员补录，直接提交");
    assertThat(body.getValue().path("otherParams").path("src").asText()).isEqualTo("submit");
    assertThat(body.getValue().path("formData").path("dataDetails").isEmpty()).isTrue();
    verify(pipeline,times(2)).prepare(any());
    assertThat(access.materialConfirmed(formId)).isTrue();
    assertThat(jdbc.queryForObject("SELECT state FROM lp_oa_workflow_state WHERE workflow_request_id=?",String.class,requestId)).isEqualTo("MATERIAL_REVIEW");
  }
  @Test void oneMissingProductBlocksWholeDocumentEvenWhenAnotherProductWasSelected() {
    doReturn(check(items.getLast(),"BLOCKED")).when(pipeline).prepare(argThat(r->r != null && r.oaFormItemId().equals(items.getLast())));
    var out=service.confirmAndCost(oaNo,request("gap",items.getFirst(),false),"admin");
    assertThat(out.confirmation().status()).isEqualTo("WAITING_INPUT");assertThat(out.checks()).hasSize(2);
    verifyNoInteractions(oa,batches);verify(pipeline,never()).execute(any());
    assertThatThrownBy(()->access.requireCostPublication(formId)).hasMessageContaining("整单资料尚未确认");
  }
  @Test void unknownNeverResendsOrStartsCosting() {
    when(oa.submit(any())).thenReturn(result(OaWorkflowResult.Status.UNKNOWN));
    var first=service.confirmAndCost(oaNo,request("lost",null,false),"admin");
    assertThat(first.confirmation().status()).isEqualTo("UNKNOWN");
    service.confirmAndCost(oaNo,request("new-click",null,true),"admin");
    verify(oa,times(1)).submit(any());verifyNoInteractions(batches);
  }
  @Test void rejectedOnlyRetriesOnExplicitNewAttempt() {
    when(oa.submit(any())).thenReturn(result(OaWorkflowResult.Status.REJECTED),result(OaWorkflowResult.Status.SUCCESS));
    service.confirmAndCost(oaNo,request("a",null,false),"admin");
    service.confirmAndCost(oaNo,request("b",null,false),"admin");
    service.confirmAndCost(oaNo,request("a",null,true),"admin");
    verify(oa,times(1)).submit(any());verifyNoInteractions(batches);
    assertThat(service.confirmAndCost(oaNo,request("c",null,true),"admin").confirmation().status()).isEqualTo("SUCCESS");
    verify(oa,times(2)).submit(any());
  }
  @Test void unapprovedTechnicalTaskBlocksEvenIfPublicSourcesBecameReady() {
    jdbc.update("INSERT INTO lp_quote_tech_task(task_no,oa_form_id,oa_no,accounting_month,business_unit_type,applicable_org_code,assignee_user_id,assignee_name,task_status,oa_form_item_id,active_lock_key) VALUES(?,?,?,?,'COMMERCIAL','210',1,'技术员','SUBMITTED',?,?)",oaNo,formId,oaNo,month,items.getFirst(),"ITEM:"+items.getFirst()+":MONTH:"+month);
    assertThatThrownBy(()->service.confirmAndCost(oaNo,request("approval",null,false),"admin"))
        .hasMessageContaining("未完成审批");
    verifyNoInteractions(oa,batches);
  }

  @Test void materialChangeAfterPreparationDoesNotSendOa() {
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    when(contexts.resolveRevision(any())).thenAnswer(call->{
      ProductCostingContext context=call.getArgument(0);
      return calls.incrementAndGet()>2 ? context.withRevision("changed") : context;
    });
    assertThat(service.confirmAndCost(oaNo,request("changed",null,false),"admin").confirmation().status())
        .isEqualTo("NOT_SENT");
    verifyNoInteractions(oa,batches);
  }

  @Test void currentActorAndSourceVersionAreRequired() {
    jdbc.update("UPDATE sys_user SET employee_no='other' WHERE user_id=1");
    assertThatThrownBy(()->service.confirmAndCost(oaNo,request("bad-actor",null,false),"admin")).hasMessageContaining("有效办理人");
    jdbc.update("UPDATE sys_user SET employee_no='001001' WHERE user_id=1");
    jdbc.update("UPDATE lp_oa_workflow_state SET observed_form_version=2 WHERE workflow_request_id=?",requestId);
    assertThatThrownBy(()->service.confirmAndCost(oaNo,request("bad-version",null,false),"admin")).hasMessageContaining("未同步");
    verifyNoInteractions(oa,pipeline,batches);
  }
  @Test void costingFailureAfterOaSuccessRetriesCostingWithoutResubmittingI06() {
    when(batches.submit(anyString(),any(),anyString())).thenThrow(new IllegalStateException("队列暂不可用"))
        .thenReturn(new QuoteBatchCostRunResponse());
    assertThatThrownBy(()->service.confirmAndCost(oaNo,request("first",null,false),"admin"))
        .hasMessageContaining("队列暂不可用");
    assertThat(service.state(oaNo).status()).isEqualTo("SUCCESS");
    assertThat(service.confirmAndCost(oaNo,request("retry-cost",null,false),"admin").batch()).isNotNull();
    verify(oa,times(1)).submit(any());
  }

  @Test void newMaterialWorkItemRequiresNewConfirmation() {
    service.confirmAndCost(oaNo,request("first",null,false),"admin");
    jdbc.update("UPDATE lp_oa_workflow_state SET active_work_items_json=?,applied_version=2 WHERE workflow_request_id=?",todo(workItem+"-return"),requestId);
    assertThat(access.materialConfirmed(formId)).isFalse();
    service.confirmAndCost(oaNo,request("second",null,false),"admin");verify(oa,times(2)).submit(any());
  }
  @Test void concurrentClicksClaimOneRequestWhileHttpIsInFlight() throws Exception {
    var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
    when(oa.submit(any())).thenAnswer(call->{entered.countDown();assertThat(finish.await(10,TimeUnit.SECONDS)).isTrue();return result(OaWorkflowResult.Status.SUCCESS);});
    try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
      var first=executor.submit(()->{actor();try{return service.confirmAndCost(oaNo,request("parallel-a",null,false),"admin");}finally{clear();}});
      assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      try {
        assertThat(service.confirmAndCost(oaNo,request("parallel-b",null,false),"admin").confirmation().status()).isEqualTo("SENDING");
        verifyNoInteractions(batches);
      } finally {finish.countDown();}
      assertThat(first.get(10,TimeUnit.SECONDS).confirmation().status()).isEqualTo("SUCCESS");
    }
    verify(oa,times(1)).submit(any());
  }
}
