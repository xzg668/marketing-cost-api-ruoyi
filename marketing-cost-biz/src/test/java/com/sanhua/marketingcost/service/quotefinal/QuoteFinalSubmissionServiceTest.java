package com.sanhua.marketingcost.service.quotefinal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.*;
import com.sanhua.marketingcost.integration.oa.workflow.*;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
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
class QuoteFinalSubmissionServiceTest extends OriginalPricingIntegrationTestBase {
  @org.springframework.test.context.DynamicPropertySource
  static void pricingPeriod(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL", CostPricingPeriodUtils::currentPricingMonth);
  }

  @Autowired QuoteFinalSubmissionService service;
  @Autowired QuoteFinalSubmissionRepository repository;
  @Autowired JdbcTemplate jdbc;
  @Autowired OaWorkflowProperties workflowProperties;
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
      var v=new QuoteCostRunVersion();v.setId(id*100);v.setVersionNo("V1");v.setTotalCost(new BigDecimal("152.503400"));v.setFinalQuoteAmount(new BigDecimal("152.503400"));v.setSourceRevision("revision");versions.put(id,v);
    }
    when(contexts.resolve(any())).thenAnswer(call->{
      ProductCostingRequest req=call.getArgument(0);var form=new OaForm();form.setId(formId);form.setOaNo(oaNo);
      var item=new OaFormItem();item.setId(req.oaFormItemId());return new ProductCostingContext(form,item,"P",month,"admin","revision");
    });
    when(successes.findSaved(any())).thenAnswer(call->{var v=versions.get(((ProductCostingContext)call.getArgument(0)).itemId());return v==null?Optional.empty():Optional.of(new ProductCostingSuccessLookup.ReusableCost(v,"prepare",0));});
    when(oaContext.document(formId)).thenReturn(new TechnicalDataOaContext.Document(formId,requestId,"FI-SC-006",new OaPeer("I07","TEST",Set.of("COMMERCIAL")),null));
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
    assertThat(body.getValue().at("/formData/dataDetails/0/subFormId").asText()).isEqualTo("1317497487388790985");
    assertThat(body.getValue().at("/formData/dataDetails/1/subFormId").asText()).isEqualTo("1317497487388790985");
    assertThat(body.getValue().at("/formData/dataDetails/0/dataIndex").asInt()).isEqualTo(1);
    assertThat(body.getValue().at("/formData/dataDetails/2/dataIndex").asInt()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_final_submission WHERE oa_form_id=?",Integer.class,formId)).isEqualTo(1);
  }
  @Test void incompleteQuoteStillShowsSuccessfulProductButCannotSubmit() {
    versions.remove(items.get(1));var state=read();assertThat(state.readyProducts()).isEqualTo(1);assertThat(state.costs()).hasSize(2);assertThat(state.canConfirm()).isFalse();
    assertThatThrownBy(()->send(state.fingerprint(),key())).hasMessageContaining("尚未完成");verifyNoInteractions(oa);
  }
  @Test void sendsFrozenFinalCostsIncludingPositiveAndNegativeMaterialDifferences() {
    var first = versions.get(items.getFirst());
    first.setCuMaterialAdjustment(new BigDecimal("139.65634237"));
    first.setFinalQuoteAmount(new BigDecimal("292.15974237"));
    var second = versions.get(items.getLast());
    second.setCuMaterialAdjustment(new BigDecimal("-0.92920427"));
    second.setFinalQuoteAmount(new BigDecimal("151.57419573"));
    var ready = read();
    assertThat(ready.costs()).extracting(QuoteFinalSubmissionService.CostLine::totalCost)
        .containsExactly("292.15974237", "151.57419573");
    var submitted = send(ready.fingerprint(), key());
    var body = ArgumentCaptor.forClass(ObjectNode.class);
    verify(oa).submit(body.capture());
    assertThat(body.getValue().at("/formData/dataDetails/0/content").asText()).isEqualTo("292.15974237");
    assertThat(body.getValue().at("/formData/dataDetails/2/content").asText()).isEqualTo("151.57419573");
    first.setFinalQuoteAmount(new BigDecimal("999"));
    assertThat(read().costs()).isEqualTo(submitted.costs());
  }
  @Test void missingFinalCostCannotSubmitTheFinancialBaseAsAFallback() {
    versions.get(items.getFirst()).setFinalQuoteAmount(null);
    var state = read();
    assertThat(state.canConfirm()).isFalse();
    assertThat(state.costs().getFirst().message()).contains("最终成本缺失");
    assertThatThrownBy(() -> send(state.fingerprint(), key())).hasMessageContaining("尚未完成");
    verifyNoInteractions(oa);
  }
  @Test void negativeFinalCostCannotBeSubmitted() {
    versions.get(items.getFirst()).setFinalQuoteAmount(new BigDecimal("-0.01"));
    assertThat(read().canConfirm()).isFalse();
    verifyNoInteractions(oa);
  }
  @Test void changedCostOrPositionIsRejectedBeforeSending() {
    var before=read();versions.get(items.getFirst()).setFinalQuoteAmount(new BigDecimal("100"));
    assertThatThrownBy(()->send(before.fingerprint(),key())).hasMessageContaining("已变化");verifyNoInteractions(oa);
  }
  @Test void changedPricesAndRatesDoNotBlockSavedCostsOrSubmission() {
    when(contexts.resolveRevision(any())).thenThrow(new IllegalStateException("价格费率已变化"));
    when(contexts.resolveRevisions(anyList())).thenThrow(new IllegalStateException("价格费率已变化"));
    var ready = read();
    assertThat(ready.canConfirm()).isTrue();
    assertThat(send(ready.fingerprint(), key()).status()).isEqualTo("SUBMITTED");
    verify(contexts, never()).resolveRevision(any());
    verify(contexts, never()).resolveRevisions(anyList());
    verify(successes, never()).find(any());
    verify(oa).submit(any());
  }
  @Test void regenerationReplacesOnlyTheNextSubmissionAndPreservesTheOldVersion() {
    var before = read();
    var original = versions.get(items.getFirst());
    var latest = new QuoteCostRunVersion();
    latest.setId(original.getId() + 1); latest.setVersionNo("V2");
    latest.setFinalQuoteAmount(new BigDecimal("32.93162700")); latest.setSourceRevision("new-rates");
    versions.put(items.getFirst(), latest);
    assertThatThrownBy(() -> send(before.fingerprint(), key())).hasMessageContaining("已变化");
    verifyNoInteractions(oa);
    var regenerated = read();
    assertThat(regenerated.canConfirm()).isTrue();
    assertThat(regenerated.costs().getFirst().costVersionId()).isEqualTo(latest.getId());
    assertThat(regenerated.costs().getFirst().totalCost()).isEqualTo("32.93162700");
    assertThat(send(regenerated.fingerprint(), key()).status()).isEqualTo("SUBMITTED");
    assertThat(original.getFinalQuoteAmount()).isEqualByComparingTo("152.503400");
    var body = ArgumentCaptor.forClass(ObjectNode.class); verify(oa).submit(body.capture());
    assertThat(body.getValue().at("/formData/dataDetails/0/content").asText()).isEqualTo("32.93162700");
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "FI-SC-005,新品,zcbbhyf,hyfzcbbhs,1315730937451335132",
      "FI-SC-006,批量品,zcb1bhs,zcbbhs,1317497487388790985",
      "FI-SC-020,批量品,bhysfzcbbhs,hysfzcbbhs,1315726685492431674",
      "FI-SR-005,新品,zcbbhs,hysfzcbbhs,1328371833677696778",
      "FI-SR-005,衍生品,cb1,zcbbhs,1328371833677696778",
      "FI-SR-005,批量品,cb1,zcbbhs,1328371833677696777"})
  void finalWritebackAddsEachOriginalRowsFreightOnce(String process, String businessType,
      String without, String with, String tableId) {
    jdbc.update("UPDATE oa_form SET process_code=? WHERE id=?", process, formId);
    jdbc.update("UPDATE oa_form_item SET business_type=? WHERE oa_form_id=?", businessType, formId);
    when(oaContext.document(formId)).thenReturn(new TechnicalDataOaContext.Document(formId,requestId,process,
        new OaPeer("I07","TEST",Set.of("COMMERCIAL")),null));
    jdbc.update("UPDATE oa_form_item SET shipping_fee=1.2345,seq=7 WHERE id=?", items.getFirst());
    jdbc.update("UPDATE oa_form_item SET shipping_fee=2.500000,seq=3 WHERE id=?", items.getLast());
    versions.get(items.getFirst()).setFinalQuoteAmount(new BigDecimal("292.15974237"));
    versions.get(items.getLast()).setFinalQuoteAmount(new BigDecimal("151.57419573"));
    var before = read();
    var submitted = send(before.fingerprint(), key());
    assertThat(submitted.status()).isEqualTo("SUBMITTED");
    var captured = ArgumentCaptor.forClass(ObjectNode.class); verify(oa).submit(captured.capture());
    var details = captured.getValue().at("/formData/dataDetails");
    assertThat(details.size()).isEqualTo(4);
    details.forEach(field -> assertThat(field.path("subFormId").asText()).isEqualTo(tableId));
    assertThat(submitted.costs()).allSatisfy(cost -> assertThat(cost.businessType()).isEqualTo(businessType));
    var actual = new HashMap<String, String>();
    details.forEach(field -> actual.put(field.path("dataIndex").asText()+":"+field.path("dataKey").asText(), field.path("content").asText()));
    assertThat(actual).containsExactlyInAnyOrderEntriesOf(Map.of(
        "7:"+without, "292.15974237", "7:"+with, "293.39424237",
        "3:"+without, "151.57419573", "3:"+with, "154.07419573"));
    jdbc.update("UPDATE oa_form_item SET shipping_fee=999 WHERE oa_form_id=?", formId);
    assertThat(read().costs()).isEqualTo(submitted.costs());
    send(before.fingerprint(), key()); verify(oa, times(1)).submit(any());
  }

  @Test void householdBusinessTypeChangeInvalidatesPreviewBeforeFreezingOrSending() {
    household("新品");
    var before = read();
    assertThat(before.canConfirm()).isTrue();
    jdbc.update("UPDATE oa_form_item SET business_type='批量品' WHERE oa_form_id=?", formId);
    assertThatThrownBy(() -> send(before.fingerprint(), key())).hasMessageContaining("已变化");
    assertThat(repository.latest(formId)).isNull();
    verifyNoInteractions(oa);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullSource
  @org.junit.jupiter.params.provider.ValueSource(strings = {"UNKNOWN", "批量品"})
  void householdInvalidOrMixedTypesCannotFreezeOrSend(String secondType) {
    household("新品");
    jdbc.update("UPDATE oa_form_item SET business_type=? WHERE id=?", secondType, items.getLast());
    var state = read();
    assertThat(state.canConfirm()).isFalse();
    assertThat(state.error()).contains("业务类型");
    assertThatThrownBy(() -> send(state.fingerprint(), key())).hasMessageContaining("业务类型");
    assertThat(repository.latest(formId)).isNull();
    verifyNoInteractions(oa);
  }

  private void household(String businessType) {
    jdbc.update("UPDATE oa_form SET process_code='FI-SR-005' WHERE id=?", formId);
    jdbc.update("UPDATE oa_form_item SET business_type=? WHERE oa_form_id=?", businessType, formId);
    when(oaContext.document(formId)).thenReturn(new TechnicalDataOaContext.Document(formId, requestId,
        "FI-SR-005", new OaPeer("I07", "TEST", Set.of("COMMERCIAL")), null));
  }

  @Test void changedFreightInvalidatesTheConfirmedPreviewBeforeSending() {
    jdbc.update("UPDATE oa_form_item SET shipping_fee=1 WHERE oa_form_id=?", formId);
    var before = read();
    jdbc.update("UPDATE oa_form_item SET shipping_fee=2 WHERE id=?", items.getFirst());
    assertThat(read().fingerprint()).isNotEqualTo(before.fingerprint());
    assertThatThrownBy(() -> send(before.fingerprint(), key())).hasMessageContaining("已变化");
    verifyNoInteractions(oa);
  }

  @Test void negativeFreightBlocksTheProductBeforeAnySubmissionIsFrozen() {
    jdbc.update("UPDATE oa_form_item SET shipping_fee=-1 WHERE id=?", items.getFirst());
    var state = read();
    assertThat(state.canConfirm()).isFalse();
    assertThat(state.costs().getFirst().message()).contains("运费不能为负数");
    assertThatThrownBy(() -> send(state.fingerprint(), key())).hasMessageContaining("尚未完成");
    assertThat(repository.latest(formId)).isNull(); verifyNoInteractions(oa);
  }

  @Test void historicalSubmissionWithoutFreightKeepsItsOriginalSnapshot() throws Exception {
    var submitted = send(read().fingerprint(), key());
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var rows = json.readTree(repository.find(submitted.submissionId()).snapshotJson());
    rows.forEach(row -> ((ObjectNode) row).remove("shippingFee"));
    jdbc.update("UPDATE lp_quote_final_submission SET cost_snapshot_json=? WHERE id=?", json.writeValueAsString(rows), submitted.submissionId());
    jdbc.update("UPDATE oa_form_item SET shipping_fee=999 WHERE oa_form_id=?", formId);
    assertThat(read().costs()).allSatisfy(row -> assertThat(row.shippingFee()).isNull());
    assertThat(repository.find(submitted.submissionId()).snapshotJson()).doesNotContain("shippingFee");
    verify(oa, times(1)).submit(any());
  }
  @Test void sourceRowChangeAfterReadIsRejected() {
    var before=read();jdbc.update("UPDATE lp_oa_form_item_extra_field SET field_value='999' WHERE oa_form_item_id=? AND field_code='OA_ROW_ID'",items.getFirst());
    assertThatThrownBy(()->send(before.fingerprint(),key())).hasMessageContaining("已变化");verifyNoInteractions(oa);
  }
  @Test void missingEmployeeCannotSubmitButSourceRowIdDoesNotDetermineTableId() {
    when(oaContext.operatorEmployeeNo(1)).thenThrow(new IllegalArgumentException("当前账号未维护工号"));
    assertThat(read().canConfirm()).isFalse();
    assertThatThrownBy(()->send(read().fingerprint(),key())).hasMessageContaining("工号");
    verifyNoInteractions(oa);
    doReturn("001001").when(oaContext).operatorEmployeeNo(1);
    jdbc.update("UPDATE lp_oa_form_item_extra_field SET field_value='ROW-X' WHERE oa_form_item_id=?",items.getFirst());
    var state=read();assertThat(state.canConfirm()).isTrue();
    assertThat(send(state.fingerprint(),key()).status()).isEqualTo("SUBMITTED");
    var body=ArgumentCaptor.forClass(ObjectNode.class);verify(oa).submit(body.capture());
    assertThat(body.getValue().at("/formData/dataDetails/0/subFormId").asText()).isEqualTo("1317497487388790985");
  }
  @Test void missingConfiguredTableBlocksBeforeFreezingOrSending() {
    String previous=workflowProperties.getFinalCostSubFormIds().remove("FI-SC-006");
    try {
      var state=read();assertThat(state.canConfirm()).isFalse();assertThat(state.error()).contains("subFormId");
      assertThatThrownBy(()->send(state.fingerprint(),key())).hasMessageContaining("subFormId");
      assertThat(repository.latest(formId)).isNull();verifyNoInteractions(oa);
    } finally {workflowProperties.getFinalCostSubFormIds().put("FI-SC-006",previous);}
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
    var ready=read();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);
    when(oa.submit(any())).thenAnswer(call->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return receipt(OaWorkflowResult.Status.SUCCESS);});
    try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
      var first=pool.submit(()->{identity();try{return send(ready.fingerprint(),key());}finally{clear();}});
      try {
        assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
        var second=pool.submit(()->{identity();secondStarted.countDown();try{return send(ready.fingerprint(),key());}finally{clear();}});
        assertThat(secondStarted.await(10,TimeUnit.SECONDS)).isTrue();
        release.countDown();
        var submitted=first.get(10,TimeUnit.SECONDS);
        var repeated=second.get(10,TimeUnit.SECONDS);
        assertThat(submitted.status()).isEqualTo("SUBMITTED");
        assertThat(repeated.submissionId()).isEqualTo(submitted.submissionId());
        assertThat(repeated.status()).isIn("PENDING", "SUBMITTED");
      } finally {release.countDown();}
    }
    verify(oa,times(1)).submit(any());
  }
  @Test void returnedQuoteCanResubmitSavedCostsWithoutRecalculation() {
    var first=send(read().fingerprint(),key());
    String frozen=repository.find(first.submissionId()).snapshotJson();
    repository.returned(first.submissionId(),1,"成本结果没有回写成功");
    jdbc.update("UPDATE oa_form_item SET calc_status='待核算' WHERE oa_form_id=?",formId);
    var ready=read();
    assertThat(ready.canConfirm()).isTrue();
    assertThat(ready.readyProducts()).isEqualTo(2);
    assertThat(ready.costs()).isEqualTo(first.costs());
    String request=key();
    var second=send(ready.fingerprint(),request);
    assertThat(second.status()).isEqualTo("SUBMITTED");
    assertThat(second.submissionId()).isNotEqualTo(first.submissionId());
    assertThat(repository.find(second.submissionId()).round()).isEqualTo(2);
    send(ready.fingerprint(),request);send(ready.fingerprint(),key());
    verify(oa,times(2)).submit(any());
    assertThat(repository.find(first.submissionId()).snapshotJson()).isEqualTo(frozen);
  }
  @Test void returnedQuoteCanCombineRecalculatedAndExistingSavedCosts() {
    var first=send(read().fingerprint(),key());
    String frozen=repository.find(first.submissionId()).snapshotJson();
    repository.returned(first.submissionId(),1,"重新确认");
    var changed=versions.get(items.getFirst());changed.setId(changed.getId()+1);changed.setVersionNo("V2");
    var ready=read();
    assertThat(ready.canConfirm()).isTrue();
    assertThat(ready.costs().getFirst().costVersionId()).isNotEqualTo(first.costs().getFirst().costVersionId());
    assertThat(ready.costs().getLast().costVersionId()).isEqualTo(first.costs().getLast().costVersionId());
    assertThat(send(ready.fingerprint(),key()).status()).isEqualTo("SUBMITTED");
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
