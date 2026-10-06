package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.sanhua.marketingcost.dto.quotecosting.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.*;
import com.sanhua.marketingcost.integration.oa.workflow.*;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.costing.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

@Tag("integration")
@TestPropertySource(properties={"cms.sync-publish.enabled=false"})
class QuoteCostingPreparationIntegrationTest extends BomMapperTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired QuoteCostingPreparationService service;
  @Autowired OaWorkflowAccessPolicy access;
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
    oaNo="LOCAL-COST-"+UUID.randomUUID(); requestId="REQ-"+UUID.randomUUID(); workItem="MAT-"+UUID.randomUUID();
    jdbc.update("INSERT INTO oa_form(oa_no,source_type,business_unit_type) VALUES(?,'OA','COMMERCIAL')",oaNo);
    formId=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oaNo);
    for(int n=1;n<=2;n++) jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no) VALUES(?,?,?)",formId,n,"P"+n);
    items=jdbc.queryForList("SELECT id FROM oa_form_item WHERE oa_form_id=? ORDER BY id",Long.class,formId);
    jdbc.update("INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version) VALUES('LOCAL-COST','TEST',?,?,1)",requestId,formId);
    jdbc.update("INSERT INTO lp_oa_workflow_state(source_system,environment,workflow_request_id,applied_version,form_version,observed_form_version,state,active_work_items_json) VALUES('LOCAL-COST','TEST',?,1,1,1,'COSTING',?)",requestId,todo(workItem));
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
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  String todo(String id) {return "[{\"workItemId\":\""+id+"\",\"nodeRole\":\"COSTING\",\"employeeNo\":\"001001\"}]";}
  ProductCostingResult check(long id,String status){var r=new ProductCostingResult();r.setOaNo(oaNo);r.setOaFormItemId(id);r.setProductCode("P"+id);r.setPipelineStatus(status);r.setSourceRevision("revision");return r;}
  QuoteCostingPreparationService.Request request(Long item){return new QuoteCostingPreparationService.Request(item,month);}

  @Test void wholeOrderChecksAllProductsAndSingleProductChecksOnlyItsOwnInputs() {
    assertThat(service.state(oaNo).status()).isEqualTo("AVAILABLE");
    verifyNoInteractions(oa,pipeline);
    var first=service.prepareAndCost(oaNo,request(null),"admin");
    assertThat(first.batch()).isNotNull();
    var second=service.prepareAndCost(oaNo,request(items.getFirst()),"admin");
    assertThat(second.product().getPipelineStatus()).isEqualTo("SUCCESS");
    verifyNoInteractions(oa);
    verify(pipeline,times(3)).prepare(any());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_material_confirmation WHERE oa_form_id=?",Long.class,formId)).isZero();
    assertThat(jdbc.queryForObject("SELECT state FROM lp_oa_workflow_state WHERE workflow_request_id=?",String.class,requestId)).isEqualTo("COSTING");
  }
  @Test void anotherProductsMissingInputsDoNotBlockSingleProduct() {
    doReturn(check(items.getLast(),"BLOCKED")).when(pipeline).prepare(argThat(r->r != null && r.oaFormItemId().equals(items.getLast())));
    var single=service.prepareAndCost(oaNo,request(items.getFirst()),"admin");
    assertThat(single.product().getPipelineStatus()).isEqualTo("SUCCESS");
    assertThat(single.checks()).hasSize(1);
    var whole=service.prepareAndCost(oaNo,request(null),"admin");
    assertThat(whole.preparation().status()).isEqualTo("WAITING_INPUT");
    assertThat(whole.checks()).hasSize(2);
    verifyNoInteractions(oa,batches);
    verify(pipeline,times(1)).execute(any());
  }
  @Test void anotherProductsPendingTechnicalTaskDoesNotBlockSingleProduct() {
    long otherItem=items.getLast();
    jdbc.update("INSERT INTO lp_quote_tech_task(task_no,oa_form_id,oa_no,accounting_month,business_unit_type,applicable_org_code,assignee_user_id,assignee_name,task_status,oa_form_item_id,active_lock_key) VALUES(?,?,?,?,'COMMERCIAL','210',1,'技术员','SUBMITTED',?,?)",oaNo,formId,oaNo,month,otherItem,"ITEM:"+otherItem+":MONTH:"+month);
    assertThat(service.state(oaNo).canCost()).isFalse();
    assertThat(service.state(oaNo,items.getFirst()).canCost()).isTrue();
    var single=service.prepareAndCost(oaNo,request(items.getFirst()),"admin");
    assertThat(single.product().getPipelineStatus()).isEqualTo("SUCCESS");
    assertThatThrownBy(()->service.prepareAndCost(oaNo,request(null),"admin")).hasMessageContaining("尚未全部成功提交");
  }
  @Test void taskWithoutSuccessfulSubmissionBlocksEvenIfPublicSourcesBecameReady() {
    jdbc.update("INSERT INTO lp_quote_tech_task(task_no,oa_form_id,oa_no,accounting_month,business_unit_type,applicable_org_code,assignee_user_id,assignee_name,task_status,oa_form_item_id,active_lock_key) VALUES(?,?,?,?,'COMMERCIAL','210',1,'技术员','SUBMITTED',?,?)",oaNo,formId,oaNo,month,items.getFirst(),"ITEM:"+items.getFirst()+":MONTH:"+month);
    assertThat(service.state(oaNo).canCost()).isFalse();
    assertThatThrownBy(()->service.prepareAndCost(oaNo,request(null),"admin")).hasMessageContaining("尚未全部成功提交");
    assertThatThrownBy(()->access.requireCostPublication(formId)).hasMessageContaining("尚未全部成功提交");
    verifyNoInteractions(oa,batches);
  }
  @Test void materialChangeAfterPreparationDoesNotStartCosting() {
    when(contexts.resolveRevision(any())).thenAnswer(call->((ProductCostingContext)call.getArgument(0)).withRevision("changed"));
    assertThatThrownBy(()->service.prepareAndCost(oaNo,request(null),"admin")).hasMessageContaining("资料已变化");
    verifyNoInteractions(oa,batches);verify(pipeline,never()).execute(any());
  }
  @Test void currentActorAndSourceVersionAreRequired() {
    jdbc.update("UPDATE sys_user SET employee_no='other' WHERE user_id=1");
    assertThatThrownBy(()->service.prepareAndCost(oaNo,request(null),"admin")).hasMessageContaining("有效办理人");
    jdbc.update("UPDATE sys_user SET employee_no='001001' WHERE user_id=1");
    jdbc.update("UPDATE lp_oa_workflow_state SET observed_form_version=2 WHERE workflow_request_id=?",requestId);
    assertThatThrownBy(()->service.prepareAndCost(oaNo,request(null),"admin")).hasMessageContaining("未同步");
    verifyNoInteractions(oa,pipeline,batches);
  }
  @Test void costingFailureCanRetryLocallyWithoutAnyOaSubmission() {
    when(batches.submit(anyString(),any(),anyString())).thenThrow(new IllegalStateException("队列暂不可用"))
        .thenReturn(new QuoteBatchCostRunResponse());
    assertThatThrownBy(()->service.prepareAndCost(oaNo,request(null),"admin")).hasMessageContaining("队列暂不可用");
    assertThat(service.prepareAndCost(oaNo,request(null),"admin").batch()).isNotNull();
    verifyNoInteractions(oa);
  }
}
