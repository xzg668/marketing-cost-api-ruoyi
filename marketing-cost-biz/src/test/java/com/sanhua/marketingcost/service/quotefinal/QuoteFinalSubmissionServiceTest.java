package com.sanhua.marketingcost.service.quotefinal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.*;
import com.sanhua.marketingcost.integration.oa.workflow.*;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.costing.*;
import com.sanhua.marketingcost.service.technicaldata.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("integration")
class QuoteFinalSubmissionServiceTest extends BomMapperTestBase {
  @Autowired QuoteFinalSubmissionService service;
  @Autowired QuoteFinalSubmissionRepository repository;
  @Autowired JdbcTemplate jdbc;
  @MockBean ProductCostingContextResolver contexts;
  @MockBean ProductCostingSuccessLookup successes;
  @MockBean TechnicalDataOaContext oaContext;
  @MockBean OaWorkflowClient oa;
  @MockBean OaWorkflowAccessPolicy access;
  String oaNo, requestId;
  long formId;
  String month = CostPricingPeriodUtils.currentPricingMonth();
  List<Long> items;
  Map<Long,QuoteCostRunVersion> versions = new ConcurrentHashMap<>();
  TechnicalDataActor actor = new TechnicalDataActor(1L,"报价员",Set.of("*:*:*"));

  void identity() {
    var auth = new UsernamePasswordAuthenticationToken("admin","unused",List.of());
    auth.setDetails(Map.of("businessUnitType","COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
  @BeforeEach void fixture() {
    identity(); oaNo="I07-"+UUID.randomUUID();requestId="ORIGINAL-"+UUID.randomUUID();
    jdbc.update("INSERT INTO oa_form(oa_no,process_code,business_unit_type) VALUES(?,'FI-SC-006','COMMERCIAL')",oaNo);
    formId=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oaNo);
    jdbc.update("INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version) VALUES('I07','TEST',?,?,1)",requestId,formId);
    for (int n=1;n<=2;n++) jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,business_type,business_unit_type) VALUES(?,?,?,'批量品','COMMERCIAL')",formId,n,"P"+n);
    items=jdbc.queryForList("SELECT id FROM oa_form_item WHERE oa_form_id=? ORDER BY id",Long.class,formId);
    for (long id : items) {
      jdbc.update("INSERT INTO lp_oa_form_item_extra_field(oa_form_id,oa_form_item_id,field_code,field_name,field_value,business_unit_type) VALUES(?,?,'OA_ROW_ID','原明细行',?,'COMMERCIAL')",formId,id,String.valueOf(1137224760702033920L+items.indexOf(id)));
      var v=new QuoteCostRunVersion();v.setId(id*100);v.setVersionNo("V1");v.setTotalCost(new BigDecimal("152.503400"));v.setSourceRevision("revision");versions.put(id,v);
    }
    when(contexts.resolve(any())).thenAnswer(call->{
      ProductCostingRequest req=call.getArgument(0);var form=new OaForm();form.setId(formId);form.setOaNo(oaNo);
      var item=new OaFormItem();item.setId(req.oaFormItemId());return new ProductCostingContext(form,item,"P",month,"admin","revision");
    });
    when(contexts.resolveRevision(any())).thenAnswer(call->call.getArgument(0));
    when(successes.find(any())).thenAnswer(call->{var v=versions.get(((ProductCostingContext)call.getArgument(0)).itemId());return v==null?Optional.empty():Optional.of(new ProductCostingSuccessLookup.ReusableCost(v,"prepare",0));});
    when(oaContext.document(formId)).thenReturn(new TechnicalDataOaContext.Document(formId,requestId,"FI-SC-006",new OaPeer("I07","TEST",Set.of("COMMERCIAL"))));
    when(oaContext.operatorEmployeeNo(1)).thenReturn("001001");
    when(oa.submit(any())).thenReturn(receipt(OaWorkflowResult.Status.SUCCESS));
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  OaWorkflowResult receipt(OaWorkflowResult.Status status){return new OaWorkflowResult("call",status,200,status==OaWorkflowResult.Status.SUCCESS?"0":"200031",status==OaWorkflowResult.Status.SUCCESS?"success":"OA测试错误",requestId,null,1);}
  QuoteFinalSubmissionService.Status read(){return service.status(oaNo,month,actor);}
  QuoteFinalSubmissionService.Status send(String hash,String key){return service.confirm(oaNo,month,hash,key,actor);}
  String key(){return UUID.randomUUID().toString();}

  @Test void wholeQuoteUsesDisplayedCostsAndOriginalIdsAndRepeatDoesNotSend() {
    var before=read();assertThat(before.canConfirm()).isTrue();assertThat(before.readyProducts()).isEqualTo(2);
    String key=key();var result=send(before.fingerprint(),key);assertThat(result.status()).isEqualTo("SUBMITTED");
    assertThat(result.costs()).isEqualTo(before.costs());send(before.fingerprint(),key);send(before.fingerprint(),key());
    var body=ArgumentCaptor.forClass(ObjectNode.class);verify(oa,times(1)).submit(body.capture());
    assertThat(body.getValue().path("requestId").asText()).isEqualTo(requestId);
    assertThat(body.getValue().path("userid").asText()).isEqualTo("001001");
    assertThat(body.getValue().at("/formData/dataDetails/0/content").asText()).isEqualTo("152.503400");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_final_submission WHERE oa_form_id=?",Integer.class,formId)).isEqualTo(1);
  }
  @Test void incompleteQuoteStillShowsSuccessfulProductButCannotSubmit() {
    versions.remove(items.get(1));var state=read();assertThat(state.readyProducts()).isEqualTo(1);assertThat(state.costs()).hasSize(2);assertThat(state.canConfirm()).isFalse();
    assertThatThrownBy(()->send(state.fingerprint(),key())).hasMessageContaining("尚未完成");verifyNoInteractions(oa);
  }
  @Test void changedCostOrPositionIsRejectedBeforeSending() {
    var before=read();versions.get(items.getFirst()).setTotalCost(new BigDecimal("100"));
    assertThatThrownBy(()->send(before.fingerprint(),key())).hasMessageContaining("已变化");verifyNoInteractions(oa);
  }
  @Test void sourceRowChangeAfterReadIsRejected() {
    var before=read();jdbc.update("UPDATE lp_oa_form_item_extra_field SET field_value='999' WHERE oa_form_item_id=? AND field_code='OA_ROW_ID'",items.getFirst());
    assertThatThrownBy(()->send(before.fingerprint(),key())).hasMessageContaining("已变化");verifyNoInteractions(oa);
  }
  @Test void missingEmployeeAndInvalidSourceIdCannotBeSubmitted() {
    when(oaContext.operatorEmployeeNo(1)).thenThrow(new IllegalArgumentException("当前账号未维护工号"));assertThat(read().canConfirm()).isFalse();
    doReturn("001001").when(oaContext).operatorEmployeeNo(1);jdbc.update("UPDATE lp_oa_form_item_extra_field SET field_value='ROW-X' WHERE oa_form_item_id=?",items.getFirst());
    var state=read();assertThat(state.error()).contains("subFormId");assertThatThrownBy(()->send(state.fingerprint(),key())).hasMessageContaining("subFormId");verifyNoInteractions(oa);
  }
  @Test void timeoutIsUnknownAndNeitherRefreshNorNewRequestResends() {
    when(oa.submit(any())).thenReturn(receipt(OaWorkflowResult.Status.UNKNOWN));var before=read();
    assertThat(send(before.fingerprint(),key()).status()).isEqualTo("UNKNOWN");assertThat(read().canConfirm()).isFalse();
    send(before.fingerprint(),key());verify(oa,times(1)).submit(any());
  }
  @Test void rejectionRequiresNewExplicitRequestAndDoesNotHideSavedAmounts() {
    when(oa.submit(any())).thenReturn(receipt(OaWorkflowResult.Status.REJECTED));var before=read();String request=key();
    assertThat(send(before.fingerprint(),request).status()).isEqualTo("REJECTED");send(before.fingerprint(),request);verify(oa,times(1)).submit(any());
    when(oa.submit(any())).thenReturn(receipt(OaWorkflowResult.Status.SUCCESS));assertThat(send(before.fingerprint(),key()).status()).isEqualTo("SUBMITTED");verify(oa,times(2)).submit(any());
  }
  @Test void doubleClickConcurrentRequestsOnlySendOnce() throws Exception {
    var ready=read();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    when(oa.submit(any())).thenAnswer(call->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return receipt(OaWorkflowResult.Status.SUCCESS);});
    try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
      var first=pool.submit(()->{identity();try{return send(ready.fingerprint(),key());}finally{clear();}});
      assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
      assertThat(send(ready.fingerprint(),key()).status()).isEqualTo("PENDING");release.countDown();assertThat(first.get(10,TimeUnit.SECONDS).status()).isEqualTo("SUBMITTED");
    } finally {release.countDown();}
    verify(oa,times(1)).submit(any());
  }
  @Test void returnRequiresNewCostVersionsAndOldSnapshotIsImmutable() {
    var first=send(read().fingerprint(),key());String frozen=repository.find(first.submissionId()).snapshotJson();repository.returned(first.submissionId(),1,"重新核算");
    assertThat(read().canConfirm()).isFalse();for(var v:versions.values()){v.setId(v.getId()+1);v.setVersionNo("V2");}
    var ready=read();assertThat(ready.canConfirm()).isTrue();assertThat(send(ready.fingerprint(),key()).status()).isEqualTo("SUBMITTED");
    assertThat(repository.find(first.submissionId()).snapshotJson()).isEqualTo(frozen);
  }
  @Test void lateSuccessDoesNotOverrideAnEarlierReturnNotification() {
    when(oa.submit(any())).thenAnswer(call->{var pending=repository.latest(formId);repository.returned(pending.id(),1,"先到的通知");return receipt(OaWorkflowResult.Status.SUCCESS);});
    var result=send(read().fingerprint(),key());assertThat(result.status()).isEqualTo("RETURNED");assertThat(result.returnReason()).isEqualTo("先到的通知");
  }
  @Test void materialGateMustPassBeforeAnyFinalSend() {
    var ready=read();doThrow(new IllegalArgumentException("整单资料尚未确认")).when(access).requireCostPublication(formId);
    assertThatThrownBy(()->send(ready.fingerprint(),key())).hasMessageContaining("资料尚未确认");verifyNoInteractions(oa);
  }
}
