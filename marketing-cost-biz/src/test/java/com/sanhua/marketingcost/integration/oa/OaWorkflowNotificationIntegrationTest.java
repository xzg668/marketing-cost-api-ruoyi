package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.sanhua.marketingcost.integration.oa.OaWorkflowNotificationTest.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@Tag("integration") @AutoConfigureMockMvc
@TestPropertySource(properties={"integration.oa.mode=MOCK","integration.oa.environment=I01_TEST", "integration.oa.clients.test.source-system=WEAVER","integration.oa.clients.test.environment=I01_TEST","integration.oa.clients.test.mode=MOCK",
 "integration.oa.clients.test.business-units=COMMERCIAL","integration.oa.clients.test.secret=i01-test-only-token-at-least-32-characters"})
class OaWorkflowNotificationIntegrationTest extends BomMapperTestBase {
 static final OaPeer PEER=new OaPeer("WEAVER","I01_TEST",Set.of("COMMERCIAL"));
 static final String URL="/integration/v1/workflow-events", TOKEN="Bearer i01-test-only-token-at-least-32-characters";
 @Autowired OaWorkflowNotificationService service;
 @Autowired OaQuotationService quotes;
 @Autowired JdbcTemplate jdbc;
 @Autowired MockMvc http;
 String flow(){return "WF-N-"+UUID.randomUUID();}
 void quote(String flow)throws Exception {
   var r=OaQuotationRequestMapperTest.sample(1).put("requestId",flow).put("formNo",flow);
   quotes.receive(PEER,r.toString());
 }
 String receive(ObjectNode e){return service.receive(PEER,e.toString()).path("data").path("status").asText();}
 @Autowired com.sanhua.marketingcost.service.technicaldata.QuoteTechnicalDataPersistenceService persistence;
 @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalDataParticipantVersions versions;
 @Autowired com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository people;
 @Autowired com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository technicalFlows;
 @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
 @Autowired com.sanhua.marketingcost.mapper.QuoteTechTaskMapper taskMapper;
 @Autowired com.sanhua.marketingcost.mapper.QuoteTechProductMapper productMapper;
 @Autowired OaMessageRepository messages;
 @Autowired OaMessageCodec codec;
 @Autowired com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaSubmissionLifecycle submissionLifecycle;
 @org.springframework.boot.test.mock.mockito.MockBean
 com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaGateway gateway;
 @org.springframework.boot.test.mock.mockito.MockBean
 com.sanhua.marketingcost.service.technicaldata.TechnicalDataAssigneeResolver assigneeResolver;
 record TechnicalFixture(String flow,String key,long taskId,long productId,long personId,long submissionId,String requestId,long frozenId,long draftId) {}
 private long outgoing(String requestId,String type) { return outgoing(requestId,type,"{}"); }
 private long outgoing(String requestId,String type,String raw) {
   jdbc.update("INSERT INTO lp_oa_integration_message(source_system,environment,direction,request_id,interface_type,schema_version,occurred_at,raw_payload,payload_hash,allowed_business_units) VALUES('WEAVER','I01_TEST','OUTBOUND',?,?,1,NOW(3),?,?,'COMMERCIAL')",requestId,type,raw,"a".repeat(64));
   return jdbc.queryForObject("SELECT id FROM lp_oa_integration_message WHERE request_id=?",Long.class,requestId);
 }
 private TechnicalFixture technical() throws Exception { return technical(null,1,"001001"); }
 private TechnicalFixture technical(String existing, long userId, String employee) throws Exception { return technical(existing,userId,employee,true); }
 private TechnicalFixture technical(String existing, long userId, String employee, boolean send) throws Exception {
   String f=existing==null?flow():existing;
   if(existing==null) quote(f);
   jdbc.update("INSERT IGNORE INTO sys_user(user_id,user_name,nick_name,employee_no,status,del_flag) VALUES(?,?,'回调验收技术员',?,'0','0')",userId,"callback-user-"+userId,employee);
   jdbc.update("UPDATE sys_user SET employee_no=? WHERE user_id=?",employee,userId);
   jdbc.update("INSERT IGNORE INTO lp_oa_user_mapping(source_system,environment,external_user_id,user_id,updated_by) VALUES('WEAVER','I01_TEST','001001',1,1)");
   return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx->{
     long form=jdbc.queryForObject("SELECT oa_form_id FROM lp_oa_quote_document WHERE external_document_id=?",Long.class,f);
     jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,business_unit_type,deleted) VALUES(?,99,?,'回调产品','COMMERCIAL',0)",form,"P-"+UUID.randomUUID());
     long item=jdbc.queryForObject("SELECT MAX(id) FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
     jdbc.update("INSERT INTO lp_oa_technical_batch(id,operation,oa_form_id,actor_user_id,request_key,input_fingerprint,status,request_json) VALUES(?,'I02',?,1,?,?,'SUCCESS','{}')",UUID.randomUUID().toString(),form,UUID.randomUUID().toString(),"a".repeat(64));
     var task=new com.sanhua.marketingcost.entity.QuoteTechTask();task.setTaskNo("T-"+UUID.randomUUID());task.setOaFormId(form);task.setOaFormItemId(item);task.setOaNo(f);task.setAccountingMonth("2026-09");task.setBusinessUnitType("COMMERCIAL");task.setApplicableOrgCode("210");task.setAssigneeUserId(userId);task.setAssigneeName("测试技术员");task=persistence.createTask(task);
     var flow=technicalFlows.bindFlow(task,PEER);
     jdbc.update("UPDATE lp_oa_technical_flow SET external_flow_id=? WHERE id=?",f,flow.id());
     jdbc.update("UPDATE lp_quote_tech_task SET oa_flow_id=?,external_task_status='PUBLISHED',oa_assignment_version=1 WHERE id=?",flow.id(),task.getId());
     var product=new com.sanhua.marketingcost.entity.QuoteTechProduct();product.setTaskId(task.getId());product.setOaFormItemId(item);product.setQuoteNo(f);product.setAccountingMonth("2026-09");product.setContentSchemaVersion(2);product.setSourceSnapshotJson("{}");product.setSourceFingerprint("b".repeat(64));product=persistence.createProduct(product);
     var draft=new com.sanhua.marketingcost.entity.QuoteTechDataVersion();draft.setProductId(product.getId());draft.setVersionNo(1);draft.setContentSchemaVersion(2);draft.setProductModel("TEST");draft.setProductProperty("标准品");draft.setNewProductFlag(0);
     draft.setProductFeesJson("{\"includesNewToolingMouldCertificationFee\":false,\"unitToolingFee\":0,\"unitMouldFee\":0,\"unitCertificationFee\":0,\"currency\":\"CNY\"}");draft=persistence.createVersion(draft);
     jdbc.update("UPDATE lp_quote_tech_product SET current_edit_version_id=? WHERE id=?",draft.getId(),product.getId());product.setCurrentEditVersionId(draft.getId());
     var module=new com.sanhua.marketingcost.entity.QuoteTechModule();module.setProductId(product.getId());module.setModuleType("PROFILE");module.setRequiredFlag(1);module.setRequirementReasonCode("TEST");module.setRequirementReason("测试补录");module.setSourceAvailability("MISSING");module.setSourceCheckedAt(java.time.LocalDateTime.now());module=persistence.createModule(module);
     jdbc.update("UPDATE lp_quote_tech_module SET current_version_id=?,module_status='READY',entry_mode='MANUAL',last_validation_code='VALID',assignee_user_id=?,assignee_name='测试技术员' WHERE id=?",draft.getId(),userId,module.getId());
     long dispatch=outgoing("DISPATCH-"+UUID.randomUUID(),"TASK_DISPATCH");String key="T-PERSON-"+UUID.randomUUID();
     jdbc.update("INSERT INTO lp_quote_tech_oa_recipient(task_id,assignment_version,assignee_user_id,assignee_name,external_user_id,module_types_json,outbound_message_id,external_task_id,dispatch_status,todo_status,active_flag,integration_task_id) VALUES(?,1,?,'测试技术员',?,'[\"PROFILE\"]',?,'WI-OLD','CONFIRMED','OPEN',1,?)",task.getId(),userId,employee,dispatch,key);
     long person=jdbc.queryForObject("SELECT id FROM lp_quote_tech_oa_recipient WHERE integration_task_id=?",Long.class,key);
     var frozen=versions.freeze(product,people.findById(person),product.getRowVersion(),1L);
     String requestId="TS-"+UUID.randomUUID();long sent=outgoing(requestId,"TECH_SUBMIT");
     jdbc.update("INSERT INTO lp_quote_tech_submission(task_id,product_id,technical_version_id,submission_round,request_id,expected_task_version,expected_product_version,content_schema_version,content_fingerprint,content_snapshot_json,summary_json,assignee_user_id,submitted_by,prepared_at,recipient_id,module_types_json,outbound_message_id) VALUES(?,?,?,1,?,0,0,2,?,'{}','{}',?,?,NOW(6),?,'[\"PROFILE\"]',?)",task.getId(),product.getId(),frozen.getId(),requestId,frozen.getContentFingerprint(),userId,userId,person,sent);
     long submission=jdbc.queryForObject("SELECT id FROM lp_quote_tech_submission WHERE outbound_message_id=?",Long.class,sent);
     people.prepared(person,submission,1);
     if(send) technicalFlows.markSending(submission);
     return new TechnicalFixture(f,key,task.getId(),product.getId(),person,submission,requestId,frozen.getId(),draft.getId());
   });
 }
 @org.springframework.boot.test.mock.mockito.MockBean com.sanhua.marketingcost.service.ProductCostingPipeline costing;
 @org.springframework.boot.test.mock.mockito.MockBean com.sanhua.marketingcost.service.costing.ProductCostingContextResolver costingContexts;
 @org.springframework.boot.test.mock.mockito.MockBean com.sanhua.marketingcost.service.QuoteBatchCostRunService costingBatches;
 @org.springframework.boot.test.mock.mockito.MockBean com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard repriceLock;
 @org.springframework.boot.test.mock.mockito.MockBean com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowClient nativeWorkflow;
 @Autowired com.sanhua.marketingcost.service.quoteconfirmation.QuoteMaterialConfirmationService materials;
 @Test void approvedTechnicalDataStillRequiresOneExplicitI06AndUsesSupplementRemark() throws Exception {
   var f=technical();
   var approved=event(f.flow(),"TECH_APPROVED","001001");receive(approved);
   jdbc.update("UPDATE sys_user SET employee_no='001001' WHERE user_id=1");
   var authentication=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin",null,List.of());
   authentication.setDetails(Map.of("businessUnitType","COMMERCIAL"));
   org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
   try {
     var form=jdbc.queryForMap("SELECT f.id,f.oa_no FROM oa_form f JOIN lp_quote_tech_task t ON t.oa_form_id=f.id WHERE t.id=?",f.taskId());
     long formId=((Number)form.get("id")).longValue();String oaNo=form.get("oa_no").toString();
     org.mockito.Mockito.when(costing.prepare(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
       com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest req=call.getArgument(0);
       var checked=new com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult();checked.setOaNo(oaNo);
       checked.setOaFormItemId(req.oaFormItemId());checked.setPipelineStatus("READY");checked.setSourceRevision("current-approved");return checked;
     });
     org.mockito.Mockito.when(costingContexts.resolve(org.mockito.ArgumentMatchers.any())).thenAnswer(call->{
       com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest req=call.getArgument(0);
       var parent=new com.sanhua.marketingcost.entity.OaForm();parent.setId(formId);parent.setOaNo(oaNo);
       var item=new com.sanhua.marketingcost.entity.OaFormItem();item.setId(req.oaFormItemId());
       return new com.sanhua.marketingcost.service.costing.ProductCostingContext(parent,item,"P",req.periodMonth(),"admin","current-approved");
     });
     org.mockito.Mockito.when(costingContexts.resolveRevision(org.mockito.ArgumentMatchers.any())).thenAnswer(call->call.getArgument(0));
     org.mockito.Mockito.when(nativeWorkflow.submit(org.mockito.ArgumentMatchers.any())).thenReturn(new com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult(
         "call",com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult.Status.SUCCESS,200,"0","success",f.flow(),null,1));
     org.mockito.Mockito.when(costingBatches.submit(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(new com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunResponse());
     assertThat(materials.state(oaNo).needsConfirmation()).isTrue();
     org.mockito.Mockito.verifyNoInteractions(nativeWorkflow);
     var outcome=materials.confirmAndCost(oaNo,new com.sanhua.marketingcost.service.quoteconfirmation.QuoteMaterialConfirmationService.Request("confirm",null,null,false),"admin");
     assertThat(outcome.confirmation().status()).isEqualTo("SUCCESS");
     var sent=org.mockito.ArgumentCaptor.forClass(ObjectNode.class);org.mockito.Mockito.verify(nativeWorkflow).submit(sent.capture());
     assertThat(sent.getValue().path("remark").asText()).isEqualTo("补录资料已确认，同意继续核算");
     assertThat(jdbc.queryForObject("SELECT finance_confirmed_fingerprint FROM lp_oa_technical_flow WHERE oa_form_id=?",String.class,formId)).isNotBlank();
   } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
 }
 record FinalFixture(String flow,long formId,String requestId,long submissionId,String snapshot) {}
 private FinalFixture finalSubmission() throws Exception {
   String f=flow();quote(f);
   long form=jdbc.queryForObject("SELECT oa_form_id FROM lp_oa_quote_document WHERE external_document_id=?",Long.class,f);
   String request="RS-"+f;long message=outgoing(request,"QUOTE_COST_SUBMIT");
   String snapshot="[{\"costVersionId\":123}]";
   jdbc.update("INSERT INTO lp_quote_final_submission(oa_form_id,oa_no,accounting_month,submission_round,source_system,environment,external_document_id,business_unit_type,status,outbound_message_id,content_fingerprint,cost_snapshot_json,operator_user_id,operator_external_id) VALUES(?,?,'2026-09',1,'WEAVER','I01_TEST',?,'COMMERCIAL','PENDING',?,?,?,1,'001001')",form,f,f,message,"a".repeat(64),snapshot);
   long id=jdbc.queryForObject("SELECT id FROM lp_quote_final_submission WHERE outbound_message_id=?",Long.class,message);
   String storedSnapshot=jdbc.queryForObject("SELECT cost_snapshot_json FROM lp_quote_final_submission WHERE id=?",String.class,id);
   return new FinalFixture(f,form,request,id,storedSnapshot);
 }


 @Test void authenticationAndUnknownFlowFailWithoutCreatingBusinessData() throws Exception {
   var notice=event(flow(),"TECH_APPROVED","001001");
   http.perform(post(URL).contentType("application/json").content(notice.toString())).andExpect(status().isUnauthorized());
   http.perform(post(URL).header("Authorization",TOKEN).contentType("application/json").content(notice.toString()))
     .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
   http.perform(post(URL).header("Authorization",TOKEN).contentType("application/json").content("{"))
     .andExpect(status().isBadRequest());
 }
 @Test void approvalTargetsAllOwnProductsAndWaitsForOtherTechnicians() throws Exception {
   var a=technical();var b=technical(a.flow(),1,"001001");var other=technical(a.flow(),9900002,"002002");
   var approved=event(a.flow(),"TECH_APPROVED","001001");
   assertThat(receive(approved)).isEqualTo("SUCCEEDED");
   assertThat(submissionState(a)).isEqualTo("APPROVED");assertThat(submissionState(b)).isEqualTo("APPROVED");
   assertThat(submissionState(other)).isEqualTo("SENDING");assertThat(flowState(a.flow())).isEqualTo("TECHNICAL");
   long version=flowVersion(a.flow());
   assertThat(receive(approved)).isEqualTo("SUCCEEDED");assertThat(flowVersion(a.flow())).isEqualTo(version);
   assertThat(receive(event(a.flow(),"TECH_APPROVED","002002"))).isEqualTo("SUCCEEDED");
   assertThat(flowState(a.flow())).isEqualTo("MATERIAL_REVIEW");
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_material_confirmation WHERE oa_form_id=(SELECT oa_form_id FROM lp_quote_tech_task WHERE id=?)",Long.class,a.taskId())).isZero();
 }
 @Test void invalidPersonRollsBackTheEntireNotification() throws Exception {
   var a=technical();
   assertThatThrownBy(()->receive(event(a.flow(),"TECH_APPROVED","001001","MISSING"))).hasMessageContaining("不属于本单");
   assertThat(submissionState(a)).isEqualTo("SENDING");
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_workflow_state WHERE workflow_request_id=?",Long.class,a.flow())).isZero();
 }
 @Test void returnedTechnicalDataRestoresOneDraftWithoutTouchingOthers() throws Exception {
   var a=technical();var other=technical(a.flow(),9900002,"002002");
   var notice=event(a.flow(),"TECHNICAL","001001");
   assertThat(receive(notice)).isEqualTo("SUCCEEDED");assertThat(submissionState(a)).isEqualTo("RETURNED");
   assertThat(submissionState(other)).isEqualTo("SENDING");
   assertThat(jdbc.queryForObject("SELECT current_edit_version_id FROM lp_quote_tech_product WHERE id=?",Long.class,a.productId())).isEqualTo(a.draftId());
   assertThat(jdbc.queryForObject("SELECT version_status FROM lp_quote_tech_data_version WHERE id=?",String.class,a.frozenId())).isEqualTo("RETURNED");
   assertThat(jdbc.queryForObject("SELECT oa_edit_allowed FROM lp_quote_tech_module WHERE product_id=?",Integer.class,a.productId())).isEqualTo(1);
   long version=flowVersion(a.flow());receive(notice);assertThat(flowVersion(a.flow())).isEqualTo(version);
   assertThatThrownBy(()->receive(event(a.flow(),"TECH_APPROVED","001001"))).hasMessageContaining("不是已发送待审批");
 }
 @Test void unknownDeliveryCanBeConfirmedByARealApprovalNotification() throws Exception {
   var a=technical();jdbc.update("UPDATE lp_quote_tech_submission SET submission_status='UNKNOWN' WHERE id=?",a.submissionId());
   receive(event(a.flow(),"TECH_APPROVED","001001"));assertThat(submissionState(a)).isEqualTo("APPROVED");
 }
 @Test void unsentDraftCannotBeApproved() throws Exception {
   var a=technical(null,1,"001001",false);
   assertThatThrownBy(()->receive(event(a.flow(),"TECH_APPROVED","001001"))).hasMessageContaining("不是已发送待审批");
   assertThat(submissionState(a)).isEqualTo("PREPARED");
 }
 @Test void resultReturnKeepsFrozenCostsAndCompletionNeedsAResubmission() throws Exception {
   var f=finalSubmission();jdbc.update("UPDATE sys_user SET employee_no='001001' WHERE user_id=1");
   var returned=event(f.flow(),"COSTING");
   assertThat(receive(returned)).isEqualTo("SUCCEEDED");assertThat(flowState(f.flow())).isEqualTo("RECOSTING");
   assertThat(jdbc.queryForObject("SELECT status FROM lp_quote_final_submission WHERE id=?",String.class,f.submissionId())).isEqualTo("RETURNED");
   assertThat(jdbc.queryForObject("SELECT cost_snapshot_json FROM lp_quote_final_submission WHERE id=?",String.class,f.submissionId())).isEqualTo(f.snapshot());
   long version=flowVersion(f.flow());receive(returned);assertThat(flowVersion(f.flow())).isEqualTo(version);
   assertThatThrownBy(()->receive(event(f.flow(),"COMPLETED"))).hasMessageContaining("须重新提交");
 }
 @Test void completionIsFinalAndDoesNotDeleteCostHistory() throws Exception {
   var f=finalSubmission();var notice=event(f.flow(),"COMPLETED");
   http.perform(post(URL).header("Authorization",TOKEN).contentType("application/json").content(notice.toString()))
     .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("0")).andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
   assertThat(flowState(f.flow())).isEqualTo("COMPLETED");
   assertThat(jdbc.queryForObject("SELECT status FROM lp_quote_final_submission WHERE id=?",String.class,f.submissionId())).isEqualTo("COMPLETED");
   assertThat(jdbc.queryForObject("SELECT cost_snapshot_json FROM lp_quote_final_submission WHERE id=?",String.class,f.submissionId())).isEqualTo(f.snapshot());
   receive(notice);
   assertThatThrownBy(()->receive(event(f.flow(),"COSTING"))).hasMessageContaining("已经结束");
 }

 @Autowired com.sanhua.marketingcost.integration.oa.workflow.OaTechnicalBatchRepository nativeBatches;
 @Test void approvalResolvesUnknownNativeBatchAndLateHttpCannotDowngradeIt() throws Exception {
   var a=technical();var b=technical(a.flow(),1,"001001");
   long form=jdbc.queryForObject("SELECT oa_form_id FROM lp_quote_tech_task WHERE id=?",Long.class,a.taskId());
   String batch=UUID.randomUUID().toString();
   jdbc.update("INSERT INTO lp_oa_technical_batch(id,operation,oa_form_id,actor_user_id,request_key,input_fingerprint,status,request_json) VALUES(?,'I03',?,1,?,?,'UNKNOWN',?)",batch,form,batch,"a".repeat(64),codec.write(Map.of("requestId",a.flow())));
   for(var f:List.of(a,b)) {
     jdbc.update("UPDATE lp_quote_tech_submission SET submission_status='UNKNOWN' WHERE id=?",f.submissionId());
     jdbc.update("UPDATE lp_oa_integration_message SET technical_batch_id=? WHERE id=(SELECT outbound_message_id FROM lp_quote_tech_submission WHERE id=?)",batch,f.submissionId());
   }
   receive(event(a.flow(),"TECH_APPROVED","001001"));
   assertThat(nativeBatches.find(batch,false).status()).isEqualTo("SUCCESS");
   assertThat(submissionState(a)).isEqualTo("APPROVED");assertThat(submissionState(b)).isEqualTo("APPROVED");
   new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx->nativeBatches.received(batch,
     new com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult("late",com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult.Status.UNKNOWN,null,"TIMEOUT","迟到超时",a.flow(),null,1)));
   assertThat(nativeBatches.find(batch,false).status()).isEqualTo("SUCCESS");
 }
 @Test void personnelNoticeCannotExpandAnAlreadyConfirmedI05ProductScope() throws Exception {
   var a=technical();var b=technical(a.flow(),1,"001001");receive(event(a.flow(),"TECH_APPROVED","001001"));
   long form=jdbc.queryForObject("SELECT oa_form_id FROM lp_quote_tech_task WHERE id=?",Long.class,a.taskId());
   String batch=UUID.randomUUID().toString();long message=outgoing("RETURN-"+batch,"TECH_RETURN",codec.write(Map.of("payload",Map.of("submissionId",a.submissionId()))));
   jdbc.update("INSERT INTO lp_oa_technical_batch(id,operation,oa_form_id,actor_user_id,request_key,input_fingerprint,status,request_json) VALUES(?,'I05',?,1,?,?,'SUCCESS','{}')",batch,form,batch,"a".repeat(64));
   jdbc.update("UPDATE lp_oa_integration_message SET technical_batch_id=? WHERE id=?",batch,message);
   new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx->{
     people.revisionScope(a.personId(),List.of("PROFILE"));people.requestReturn(a.personId(),a.submissionId(),message,1,"只退第一个产品");
     submissionLifecycle.financeReturned(taskMapper.selectByIdForUpdate(a.taskId()),submissions.selectById(a.submissionId()),1,"只退第一个产品");
   });
   receive(event(a.flow(),"TECHNICAL","001001"));
   assertThat(people.findById(a.personId()).todoStatus()).isEqualTo("OPEN");
   assertThat(people.findById(b.personId()).todoStatus()).isEqualTo("DONE");
   assertThat(submissionState(b)).isEqualTo("APPROVED");
   assertThat(flowState(a.flow())).isEqualTo("TECHNICAL");
 }
 @Autowired com.sanhua.marketingcost.mapper.QuoteTechSubmissionMapper submissions;
 @Test void simultaneousDuplicateNotificationsApplyOnlyOnce() throws Exception {
   var a=technical();var notice=event(a.flow(),"TECH_APPROVED","001001");
   try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
     var jobs=executor.invokeAll(List.of((java.util.concurrent.Callable<String>)()->receive(notice),()->receive(notice)));
     for(var job:jobs) assertThat(job.get()).isEqualTo("SUCCEEDED");
   }
   assertThat(flowVersion(a.flow())).isEqualTo(1);
   assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_oa_workflow_notification WHERE flow_id=(SELECT id FROM lp_oa_workflow_state WHERE workflow_request_id=?)",Long.class,a.flow())).isEqualTo(1L);
 }

 @Autowired OaWorkflowAccessPolicy access;
 @Test void firstMaterialReviewDoesNotRequireAnUnspecifiedInitialOaNotification() throws Exception {
   String f=flow();quote(f);
   long form=jdbc.queryForObject("SELECT oa_form_id FROM lp_oa_quote_document WHERE external_document_id=?",Long.class,f);
   jdbc.update("UPDATE sys_user SET employee_no='001001' WHERE user_id=1");
   var authentication=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin",null,
     List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ingest:quote:cost-run:execute")));
   org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
   try {
     assertThat(access.view(form).state()).isEqualTo("MATERIAL_REVIEW");
     assertThat(access.materialNode(form).workItemId()).startsWith("LOCAL-INITIAL:");
     assertThat(access.materialConfirmed(form)).isFalse();
   } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 }
 private String submissionState(TechnicalFixture f){return jdbc.queryForObject("SELECT submission_status FROM lp_quote_tech_submission WHERE id=?",String.class,f.submissionId());}
 private String flowState(String f){return jdbc.queryForObject("SELECT state FROM lp_oa_workflow_state WHERE workflow_request_id=?",String.class,f);}
 private long flowVersion(String f){return jdbc.queryForObject("SELECT applied_version FROM lp_oa_workflow_state WHERE workflow_request_id=?",Long.class,f);}
}
