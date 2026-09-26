package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.AuxiliaryClassificationResponse.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.util.*;
import java.time.LocalDateTime;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration") @Transactional
class TechnicalDataAuxiliaryClassificationIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor ADMIN=new TechnicalDataActor(1L,"管理员",Set.of("*:*:*"));
  private static final TechnicalDataActor WANG=new TechnicalDataActor(101L,"王工",Set.of("technical:data:task:edit"));
  @Autowired TechnicalDataAuxiliaryClassificationService service;
  @Autowired TechnicalDataAuxiliaryClassificationWorkbook workbook;
  @Autowired TechnicalDataAuxiliaryApplicationService auxiliary;
  @Autowired QuoteTechnicalDataPersistenceService persistence;
  @Autowired QuoteTechnicalDataRepository repository;
  @Autowired TechnicalDataParticipantVersions versions;
  @Autowired TechnicalDataVersionContentCodec codec;
  @Autowired EffectiveTechnicalDataQueryServiceImpl effective;
  @Autowired OaMessageCodec json;
  @Autowired JdbcTemplate jdbc;
  @Autowired OaFormMapper forms;
  @Autowired OaFormItemMapper items;
  @MockBean TechnicalDataOaWorkflowRepository workflow;
  private Long itemId,productId,taskId,flowId;
  private String key,subjectName,subjectCode;
  private QuoteTechDataVersion approved;

  @BeforeEach void fixture() throws Exception {
    key="TW16-"+UUID.randomUUID().toString().substring(0,8); subjectName=key+"科目";subjectCode=key;
    var form=new OaForm();form.setOaNo(key);form.setBusinessUnitType("COMMERCIAL");forms.insert(form);
    var item=new OaFormItem();item.setOaFormId(form.getId());item.setMaterialNo(key);item.setBusinessUnitType("COMMERCIAL");items.insert(item);itemId=item.getId();
    jdbc.update("INSERT INTO lp_oa_technical_flow(source_system,environment,oa_form_id,accounting_month,external_document_id) VALUES('TEST','TEST',?,'2026-09',?)",form.getId(),key);
    flowId=jdbc.queryForObject("SELECT id FROM lp_oa_technical_flow WHERE oa_form_id=?",Long.class,form.getId());
    var task=new QuoteTechTask();task.setTaskNo(key);task.setOaNo(key);task.setOaFormId(form.getId());task.setOaFormItemId(itemId);
    task.setAccountingMonth("2026-09");task.setBusinessUnitType("COMMERCIAL");task.setApplicableOrgCode("210");task.setAssigneeUserId(101L);task.setAssigneeName("王工");task.setOaFlowId(flowId);
    taskId=persistence.createTask(task).getId();
    var product=new QuoteTechProduct();product.setTaskId(taskId);product.setOaFormItemId(itemId);product.setQuoteNo(key);product.setAccountingMonth("2026-09");
    product.setContentSchemaVersion(2);product.setMaterialNo(key);product.setSourceFingerprint("a".repeat(64));product.setSourceSnapshotJson("{}");
    productId=persistence.createProduct(product).getId();
    for(String type:TechnicalDataModuleType.orderedCodes()) {
      boolean required=type.equals("AUXILIARY");var module=new QuoteTechModule();module.setProductId(productId);module.setModuleType(type);
      module.setRequiredFlag(required?1:0);module.setModuleStatus(required?"PENDING":"NOT_REQUIRED");module.setSourceAvailability(required?"MISSING":"AVAILABLE");
      module.setSourceReference("TW16-CONTROLLED");module.setSourceCheckedAt(LocalDateTime.now());module.setRequirementReasonCode("TEST_CHECK");module.setRequirementReason("控制审批辅料来源");
      module.setEntryMode(required?"UPLOAD":"NONE");if(required){module.setAssigneeUserId(101L);module.setAssigneeName("王工");}persistence.createModule(module);
    }
    jdbc.update("INSERT INTO cms_subject_setting_raw(import_batch_id,row_no,first_subject_code,first_subject_name,second_subject_code,second_subject_name,third_subject_code,business_unit_type) VALUES(1,1,'02','辅助材料',?,?,?,'COMMERCIAL')",subjectCode,subjectName,key+"3");
    byte[] file=new org.springframework.core.io.ClassPathResource("templates/technical-data/auxiliary.xlsx").getContentAsByteArray();
    var parsed=auxiliary.preview(productId,"原辅料.xlsx",file,WANG);
    var request=new TechnicalDataAuxiliarySaveRequest();request.setExpectedVersion(0);request.setEntryMode("UPLOAD");request.setFileSha256(parsed.fileSha256());
    request.setItems(parsed.items().stream().map(row->{var value=new TechnicalDataAuxiliaryItemRequest();value.setItemKey(row.itemKey());value.setAmount(row.amountPerProduct());return value;}).toList());
    var saved=auxiliary.save(productId,request,WANG);
    var person=new Recipient(1L,taskId,1,101L,"王工","wang","FILL",List.of("AUXILIARY"),1L,"todo","CONFIRMED","OPEN",null,"工程部","leader","王总",null,0,0,null,true,null,null,"T-TEST", null);
    var frozen=versions.freeze(repository.lockProduct(productId).orElseThrow(),person,saved.expectedVersion(),101L);
    var personal=versions.transition(versions.transition(frozen,"SUBMITTED",101L),"APPROVED",101L);
    versions.state(repository.lockProduct(productId).orElseThrow(),person,personal.getId(),"APPROVED");
    approved=versions.activate(repository.lockProduct(productId).orElseThrow(),101L);
    jdbc.update("UPDATE lp_quote_tech_task SET task_status='APPROVED',review_status='PASSED' WHERE id=?",taskId);
    when(workflow.approvalBasis(flowId)).thenReturn("approved-basis");
    when(workflow.findFlow(flowId)).thenReturn(new TechnicalDataOaWorkflowRepository.Flow(flowId,"TEST","TEST",form.getId(),"2026-09",key,key,3,true,1L,json.canonicalHash("approved-basis")));
  }

  @Test void workbookImportPreservesApprovalAndCostingUsesValidatedCodes() {
    var before=snapshot();var view=get();assertThat(view.status()).isEqualTo("PENDING");
    assertThatThrownBy(()->effective.resolve(itemId,"2026-09")).hasMessageContaining("辅料待财务归类");
    var rows=filled(view);Collections.reverse(rows);
    var checked=service.preview(key,itemId,"2026-09",rows,ADMIN);assertThat(checked.valid()).isTrue();assertThat(checked.totals()).hasSize(1);
    var result=service.confirm(key,itemId,"2026-09",rows,checked.fingerprint(),ADMIN);
    assertThat(result.status()).isEqualTo("CLASSIFIED");assertThat(result.totals().getFirst().amount()).isEqualTo("0.33830169");
    assertThat(snapshot()).isEqualTo(before);
    var input=effective.resolve(itemId,"2026-09");assertThat(input.auxiliaryItems()).allSatisfy(line -> assertThat(line.subjectCode()).isEqualTo(subjectCode));
    assertThat(input.contentFingerprint()).isNotEqualTo(approved.getContentFingerprint());
    assertThat(service.get(key,itemId,"2026-10",ADMIN).status()).isEqualTo("CLASSIFIED");
  }

  @Test void wrongScopeTamperingDuplicatesOmissionsAndInvalidSubjectsNeverWrite() {
    var view=get();var original=filled(view);
    for(int column:List.of(0,1,2,3,4,5,9,17,18)) {
      var changed=change(original,0,column,"changed");
      assertThat(service.preview(key,itemId,"2026-09",changed,ADMIN).valid()).as("column %s",column).isFalse();
    }
    assertThat(service.preview(key,itemId,"2026-10",original,ADMIN).valid()).isFalse();
    assertThat(service.preview(key,itemId,"2026-09",original.subList(1,original.size()),ADMIN).valid()).isFalse();
    var duplicate=new ArrayList<>(original);duplicate.add(original.getFirst());
    assertThat(service.preview(key,itemId,"2026-09",duplicate,ADMIN).valid()).isFalse();
    for(String name:List.of("","不存在的科目","包装辅料")) assertThat(service.preview(key,itemId,"2026-09",change(original,0,21,name),ADMIN).valid()).isFalse();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_aux_classification WHERE technical_version_id=?",Integer.class,approved.getId())).isZero();
  }

  @Test void financeNodeAndActorAreRequiredEvenForAdministrators() {
    assertThatThrownBy(()->service.preview(key,itemId,"2026-09",filled(get()),WANG)).hasMessageContaining("无权读取");
    when(workflow.findFlow(flowId)).thenReturn(null);
    assertThat(get().status()).isEqualTo("WAIT_FINANCE");
    assertThatThrownBy(()->service.preview(key,itemId,"2026-09",List.of(),ADMIN)).hasMessageContaining("财务节点");
  }

  @Test void onlyTheAssignedFinanceUserInTheCurrentBusinessUnitMayConfirm() {
    var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
        "finance", "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ingest:quote:cost-run:execute")));
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      var finance = new TechnicalDataActor(1L, "当前报价员", Set.of("ingest:quote:cost-run:execute"));
      var other = new TechnicalDataActor(2L, "其他报价员", Set.of("ingest:quote:cost-run:execute"));
      var view = service.get(key, itemId, "2026-09", finance);
      assertThat(view.canClassify()).isTrue();
      assertThat(service.get(key, itemId, "2026-09", other).canClassify()).isFalse();
      assertThatThrownBy(() -> service.preview(key, itemId, "2026-09", filled(view), other)).hasMessageContaining("当前 OA 财务节点");
      auth.setDetails(Map.of("businessUnitType", "HOUSEHOLD"));
      assertThatThrownBy(() -> service.get(key, itemId, "2026-09", finance)).isInstanceOf(TechnicalDataTaskException.class);
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
  }

  @Test void changedDictionaryAndConcurrentClassificationInvalidatePreview() {
    var rows=filled(get());var checked=service.preview(key,itemId,"2026-09",rows,ADMIN);
    jdbc.update("UPDATE cms_subject_setting_raw SET second_subject_name=? WHERE second_subject_code=?",subjectName+"新",subjectCode);
    assertThatThrownBy(()->service.confirm(key,itemId,"2026-09",rows,checked.fingerprint(),ADMIN)).hasMessageContaining("校验未通过");
    jdbc.update("UPDATE cms_subject_setting_raw SET second_subject_name=? WHERE second_subject_code=?",subjectName,subjectCode);
    service.confirm(key,itemId,"2026-09",rows,checked.fingerprint(),ADMIN);
    assertThatThrownBy(()->service.confirm(key,itemId,"2026-09",rows,checked.fingerprint(),ADMIN)).hasMessageContaining("重新预检");
    jdbc.update("DELETE FROM cms_subject_setting_raw WHERE second_subject_code=?",subjectCode);
    assertThat(get().status()).isEqualTo("PENDING");
    assertThatThrownBy(()->effective.resolve(itemId,"2026-09")).hasMessageContaining("辅料待财务归类");
  }

  @Test void duplicateNamesCannotBeSelectedAndNewApprovedVersionDoesNotInheritClassification() {
    var rows=filled(get());var checked=service.preview(key,itemId,"2026-09",rows,ADMIN);
    service.confirm(key,itemId,"2026-09",rows,checked.fingerprint(),ADMIN);
    var next=versions.activate(repository.lockProduct(productId).orElseThrow(),101L);
    assertThat(next.getId()).isNotEqualTo(approved.getId());assertThat(get().status()).isEqualTo("PENDING");
    assertThat(service.preview(key,itemId,"2026-09",rows,ADMIN).valid()).isFalse();
    jdbc.update("INSERT INTO cms_subject_setting_raw(import_batch_id,row_no,first_subject_code,first_subject_name,second_subject_code,second_subject_name,third_subject_code,business_unit_type) VALUES(1,2,'02','辅助材料',?,?,?,'COMMERCIAL')",subjectCode+"X",subjectName,key+"4");
    assertThat(service.preview(key,itemId,"2026-09",filled(get()),ADMIN).issues()).anySatisfy(issue -> assertThat(issue.message()).contains("不唯一"));
  }

  @Test void workbookRejectsFormulasAndForeignHeaders() throws Exception {
    byte[] exported=workbook.export(get());
    try(var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(exported));var bytes=new java.io.ByteArrayOutputStream()) {
      book.getSheet("辅料归类").getRow(1).getCell(17).setCellFormula("1+2");book.write(bytes);
      assertThatThrownBy(()->workbook.parse(bytes.toByteArray())).hasMessageContaining("不要填公式");
    }
  }
  private AuxiliaryClassificationResponse get(){return service.get(key,itemId,"2026-09",ADMIN);}
  private String snapshot(){return codec.versionContentJson(approved,codec.readReferenceSnapshot(approved.getReferenceSnapshotJson()),List.of(),repository.findAuxItems(approved.getId()),List.of());}
  private List<Row> filled(AuxiliaryClassificationResponse view){return changeAll(workbook.parse(workbook.export(view)));}
  private List<Row> changeAll(List<Row> values){var rows=new ArrayList<Row>();for(var row:values){var columns=new ArrayList<>(row.columns());columns.set(21,subjectName);rows.add(new Row(row.sheetRow(),columns));}return rows;}
  private List<Row> change(List<Row> values,int index,int column,String value){var rows=new ArrayList<>(values);var columns=new ArrayList<>(rows.get(index).columns());columns.set(column,value);rows.set(index,new Row(rows.get(index).sheetRow(),columns));return rows;}
}
