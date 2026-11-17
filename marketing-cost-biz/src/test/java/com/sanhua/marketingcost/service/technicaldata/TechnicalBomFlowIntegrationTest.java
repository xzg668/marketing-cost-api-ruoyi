package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalBomSaveRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.quotebom.*;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@org.springframework.test.context.TestPropertySource(properties="quote.technical-data.attachments-root=${java.io.tmpdir}/technical-bom-flow-${random.uuid}")
@Tag("integration")
class TechnicalBomFlowIntegrationTest extends BomMapperTestBase {
  private static final TechnicalDataActor ADMIN=new TechnicalDataActor(1L,"管理员",Set.of("*:*:*"));
  @Autowired TechnicalBomApplicationService service;
  @Autowired TechnicalBomWorkbook workbooks;
  @Autowired QuoteTechnicalDataPersistenceService persistence;
  @Autowired QuoteTechnicalDataRepository repository;
  @Autowired TechnicalDataVersionContentCodec codec;
  @Autowired TechnicalDataParticipantVersions versions;
  @Autowired TechnicalBomPreparationSource preparation;
  @Autowired TechnicalBomCostingInputs costingInputs;
  @Autowired TechnicalDataTaskRepository taskRepository;
  @Autowired TechnicalDataDependencies dependencies;
  @Autowired TechnicalBomModuleCoverage coverage;
  @Autowired MonthlyBomSnapshotDetailService details;
  @Autowired TechnicalDataPublicSourceCheck publicSources;
  @Autowired TechnicalBomContextQuery contexts;
  @Autowired QuoteBomPreparationRecordMapper preparations;
  @Autowired PlatformTransactionManager transactions;
  @Autowired JdbcTemplate jdbc;
  @MockBean LiveU9BomGateway u9;
  @MockBean CurrentU9BomGateway monthlyU9;
  @MockBean TechnicalBomReferenceQuery references;
  Long taskId,productId,itemId; String key,model,raw,make;
  @BeforeEach void fixture() {
    key="TB-"+UUID.randomUUID(); model=key+"-MODEL"; raw=key+"-RAW"; make=key+"-MAKE";
    jdbc.update("INSERT INTO oa_form(oa_no,accounting_period_month,business_unit_type) VALUES(?,'2026-11','COMMERCIAL')",key);
    Long formId=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,sunl_model,business_unit_type) VALUES(?,'','新产品',?,'COMMERCIAL')",formId,model);
    itemId=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,formId);
    for(String code:List.of(raw,make)) jdbc.update("INSERT INTO lp_material_master_raw(material_code,organization_code,material_name,unit,shape_attr,main_category_code,main_category_name,active_flag,source_type,import_batch_id) VALUES(?,'COMMERCIAL',?,?,?,?,?,1,'U9_API',?)",
        code,code,code.equals(raw)?"kg":"只",code.equals(raw)?"采购件":"制造件",code.equals(raw)?"30101":"20101",code.equals(raw)?"原材料":"制造件",key);
    var p=new QuoteBomPreparationRecord(); p.setOaFormId(formId); p.setOaFormItemId(itemId); p.setOaNo(key);
    p.setQuoteProductCode(QuoteProductIdentityUtils.resolveCostingCode(null,model,null)); p.setProductType("NON_BARE"); p.setCostPeriodMonth("2026-11");
    p.setPriceOrgCode("210"); p.setMaterialOrganizationCode("COMMERCIAL"); p.setPreparationStatus("NEED_TECH"); p.setActiveFlag(1); p.setElectronicWorkflowVersion(0); preparations.insert(p);
    var task=new QuoteTechTask(); task.setTaskNo(key); task.setOaFormId(formId); task.setOaFormItemId(itemId); task.setOaNo(key);
    task.setAccountingMonth("2026-11"); task.setBusinessUnitType("COMMERCIAL"); task.setApplicableOrgCode("210"); task.setTaskStatus("UNASSIGNED");
    task.setReviewStatus("NOT_STARTED"); task.setTaskVersion(0); task.setReviewRound(0); task.setActiveFlag(1);
    task.setActiveLockKey(QuoteTechnicalDataPersistenceServiceImpl.taskActiveLockKey(itemId,"2026-11")); taskId=repository.insertTask(task).getId();
    var product=new QuoteTechProduct(); product.setTaskId(taskId); product.setOaFormItemId(itemId); product.setQuoteNo(key); product.setAccountingMonth("2026-11");
    product.setContentSchemaVersion(2); product.setMaterialNo(""); product.setProductName("新产品"); product.setSourceFingerprint("a".repeat(64)); product.setSourceSnapshotJson("{\"sourceModel\":\""+model+"\"}"); productId=persistence.createProduct(product).getId();
    for(String type:TechnicalDataModuleType.orderedCodes()) {
      boolean required=type.equals("DRAWING_BOM"); var module=new QuoteTechModule(); module.setProductId(productId); module.setModuleType(type);
      module.setRequiredFlag(required?1:0); module.setModuleStatus(required?"PENDING":"NOT_REQUIRED"); module.setSourceAvailability(required?"MISSING":"AVAILABLE");
      module.setSourceReference("EXPLICIT_ABSENCE"); module.setSourceCheckedAt(LocalDateTime.now()); module.setRequirementReasonCode("TEST_CHECK"); module.setRequirementReason("隔离完整BOM测试"); module.setEntryMode(required?"MANUAL":"NONE"); persistence.createModule(module);
    }
    when(u9.readLive(any())).thenReturn(CurrentU9BomResult.notFound("explicit absence"));
    when(monthlyU9.read(any())).thenReturn(CurrentU9BomResult.notFound("explicit absence"));
  }
  private List<TechnicalBomRows.Row> rows(String quantity) {
    return List.of(new TechnicalBomRows.Row(2,"1",make,"制造件",null,null,new BigDecimal("2"),"只","制造件","铜",new BigDecimal("100"),"g"),
        new TechnicalBomRows.Row(3,"1.10",raw,"原材料",null,null,new BigDecimal(quantity),"g","采购件","铜",null,""),
        new TechnicalBomRows.Row(4,"2",raw,"原材料",null,null,new BigDecimal("3"),"kg","采购件","铜",BigDecimal.ZERO,"g"));
  }
  private byte[] workbook(String quantity,String targetModel) throws Exception {
    var target=new TechnicalBomRows.Product("","新产品",targetModel,"");
    byte[] template=workbooks.template(new TechnicalBomRows.Product("SAMPLE","示例","SAMPLE-MODEL",""),rows(quantity));
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(template)); var out=new ByteArrayOutputStream()) {
      var sheet=book.getSheet(TechnicalBomWorkbook.ENTRY);
      for(int index=0;index<rows(quantity).size();index++) { var row=sheet.createRow(index+1); var input=rows(quantity).get(index);
        var values=List.of("",target.name(),target.model(),"",input.hierarchy(),input.materialCode(),input.name(),"","",input.quantity().toPlainString(),input.unit(),input.attribute(),input.material(),input.netWeight()==null?"":input.netWeight().toPlainString(),input.netWeightUnit());
        for(int col=0;col<values.size();col++) row.createCell(col).setCellValue(values.get(col)); }
      book.write(out); return out.toByteArray();
    }
  }
  private TechnicalBomSaveRequest upload(String hash,int expected) {
    var request=new TechnicalBomSaveRequest(); request.setEntryMode("UPLOAD"); request.setExpectedVersion(expected); request.setFileSha256(hash); return request;
  }
  @Test void modelOnlyUploadRetainsBranchesUnitsAndOriginalFileAndFreezesWithoutGalleryForm() throws Exception {
    var preview=service.preview(productId,"自编BOM.xlsx",workbook("250",model),ADMIN);
    assertThat(preview.issues()).isEmpty(); assertThat(preview.rows()).hasSize(3);
    var saved=service.save(productId,upload(preview.upload().sha256(),0),ADMIN);
    assertThat(saved.bom().product().code()).isEmpty(); assertThat(saved.bom().scope().oaFormItemId()).isEqualTo(itemId);
    assertThat(saved.bom().nodes().get(1).quantityPerParent()).isEqualByComparingTo("0.25");
    assertThat(saved.bom().nodes().get(1).quantityPerTop()).isEqualByComparingTo("0.5");
    assertThat(saved.bom().nodes().get(2).quantityPerTop()).isEqualByComparingTo("3");
    assertThat(codec.drawingBom(repository.findVersion(saved.versionId()).orElseThrow())).isNull();
    assertThat(service.file(productId,null,ADMIN).sha256()).isEqualTo(preview.upload().sha256());
    var snapshot=preparation.snapshot(itemId,"2026-11","COMMERCIAL","210","","DEFAULT");
    assertThat(snapshot.getSyncStatus()).isEqualTo("DRAFT"); assertThat(snapshot.getBomSource()).isEqualTo("TECH_UPLOAD");
    assertThat(details.load(snapshot.getId())).hasSize(4);
    assertThat(details.load(snapshot.getId()).get(2).getQtyPerTop()).isEqualByComparingTo("0.5");
    assertThat(preparation.snapshot(itemId,"2026-11","COMMERCIAL","210","","DEFAULT").getId()).isEqualTo(snapshot.getId());
    assertThat(service.validateCurrent(repository.findProduct(productId).orElseThrow(),repository.findVersion(saved.versionId()).orElseThrow())).isEmpty();
    var frozen=new TransactionTemplate(transactions).execute(status->versions.freezeAdmin(repository.lockProduct(productId).orElseThrow(),saved.rowVersion(),ADMIN));
    assertThat(codec.technicalBom(frozen).nodes()).hasSize(3);
    assertThat(service.read(productId,frozen.getId(),ADMIN).editable()).isFalse();
    assertThat(service.read(productId,frozen.getId(),ADMIN).bom().nodes()).hasSize(3);
    assertThatThrownBy(()->service.save(productId,upload(preview.upload().sha256(),saved.rowVersion()),ADMIN)).isInstanceOf(TechnicalDataTaskException.class);
  }
  @Test void invalidTargetAndStaleVersionCannotReplaceSavedTree() throws Exception {
    var parsed=service.preview(productId,"BOM.xlsx",workbook("250",model),ADMIN);
    var first=service.save(productId,upload(parsed.upload().sha256(),0),ADMIN);
    var wrong=service.preview(productId,"wrong.xlsx",workbook("500","OTHER-MODEL"),ADMIN);
    assertThat(wrong.issues()).anyMatch(issue->issue.message().contains("产品型号")); assertThat(wrong.upload()).isNull();
    var next=service.preview(productId,"next.xlsx",workbook("500",model),ADMIN);
    assertThatThrownBy(()->service.save(productId,upload(next.upload().sha256(),0),ADMIN)).hasMessageContaining("其他会话");
    assertThat(service.read(productId,null,ADMIN).bom().nodes().get(1).quantityPerTop()).isEqualByComparingTo("0.5");
    var second=service.save(productId,upload(next.upload().sha256(),first.rowVersion()),ADMIN);
    assertThat(second.bom().nodes().get(1).quantityPerTop()).isEqualByComparingTo("1");
    var unauthorized=new TechnicalDataActor(102L,"其他技术员",Set.of("technical:data:task:edit"));
    assertThatThrownBy(()->service.read(productId,null,unauthorized)).hasMessageContaining("无权");
  }
  @Test void referenceEditsOnlyQuantitiesAndRejectsChangedSource() {
    var c=contexts.read(repository.findTask(taskId).orElseThrow(),repository.findProduct(productId).orElseThrow()).context();
    var evidence=new TechnicalBomContent.Reference("REAL-FINISHED","已有成品","REF-MODEL","","COMMERCIAL","210","主制造",List.of("F001"),c.bomEffectiveDate(),"f".repeat(64));
    var sourceRows=rows("250"); var lines=new LinkedHashMap<String,TechnicalBomContent.ReferenceLine>();
    sourceRows.forEach(row->lines.put(row.hierarchy(),new TechnicalBomContent.ReferenceLine((long)row.lineNo(),null,"/REAL/"+row.hierarchy()+"/","F001",row.quantity(),BigDecimal.ONE,row.quantity())));
    when(references.read(any(),eq("REAL-FINISHED"))).thenReturn(new TechnicalBomReferenceQuery.Result(new TechnicalBomRows.Product("REAL-FINISHED","已有成品","REF-MODEL",""),sourceRows,evidence,lines));
    var preview=service.reference(productId,"REAL-FINISHED",ADMIN); assertThat(preview.product().code()).isEmpty();
    var request=new TechnicalBomSaveRequest(); request.setEntryMode("REFERENCE"); request.setExpectedVersion(0); request.setReferenceCode("REAL-FINISHED"); request.setReferenceFingerprint(evidence.fingerprint());
    request.setQuantities(Map.of("1",new BigDecimal("4"),"1.10",new BigDecimal("250"),"2",new BigDecimal("3")));
    var saved=service.save(productId,request,ADMIN); assertThat(saved.bom().nodes().get(1).quantityPerTop()).isEqualByComparingTo("1");
    assertThat(saved.bom().nodes().get(0).reference().originalQuantityPerParent()).isEqualByComparingTo("2");
    request.setExpectedVersion(saved.rowVersion()); request.setReferenceFingerprint("changed");
    assertThatThrownBy(()->service.save(productId,request,ADMIN)).hasMessageContaining("参考BOM已变化");
    assertThat(service.read(productId,null,ADMIN).bom().scope().costingProductCode()).isEqualTo(QuoteProductIdentityUtils.resolveCostingCode(null,model,null));
  }
  @Test void savedBomCoversOnlyMaterialsAndStableSaveReusesSnapshot() throws Exception {
    var preview=service.preview(productId,"BOM.xlsx",workbook("250",model),ADMIN);
    var first=service.save(productId,upload(preview.upload().sha256(),0),ADMIN);
    var before=preparation.snapshot(itemId,"2026-11","COMMERCIAL","210","","DEFAULT");
    var second=service.save(productId,upload(preview.upload().sha256(),first.rowVersion()),ADMIN);
    assertThat(second.bom().checkedAt()).isEqualTo(first.bom().checkedAt());
    assertThat(preparation.snapshot(itemId,"2026-11","COMMERCIAL","210","","DEFAULT").getId()).isEqualTo(before.getId());
    var modules=taskRepository.findModules(productId);
    assertThat(modules.stream().filter(module->TechnicalBomModuleCoverage.TYPES.contains(module.getModuleType())))
        .allSatisfy(module->{assertThat(module.getRequiredFlag()).isZero();assertThat(module.getRequirementReasonCode()).isEqualTo("TECH_BOM_MATERIALS_AVAILABLE");});
    assertThat(modules.stream().filter(module->Set.of("PACKAGE","SALARY","NET_LOSS").contains(module.getModuleType())))
        .allSatisfy(module->assertThat(module.getRequirementReasonCode()).isEqualTo("TEST_CHECK"));
    var source=new TechnicalDataCostingSources.Source("DRAWING_BOM",repository.findTask(taskId).orElseThrow(),
        repository.findProduct(productId).orElseThrow(),repository.findVersion(second.versionId()).orElseThrow(),second.versionId());
    var inputs=costingInputs.read(source);
    assertThat(inputs).hasSize(1);
    var input=inputs.values().iterator().next();
    assertThat(input.material().grossWeightKg()).isEqualByComparingTo("0.25");
    assertThat(input.material().netWeightKg()).isEqualByComparingTo("0.1");
    assertThat(costingInputs.children(source,input.parentPath())).singleElement().satisfies(row->{
      assertThat(row.getChildMaterialNo()).isEqualTo(raw);assertThat(row.getQtyPerParent()).isEqualByComparingTo("0.25");
    });
  }

  @Test void assignedMaterialsKeepTheirOwnerAndFreezeOnlyBomReference() throws Exception {
    // Existing recipients keep their original scopes; no OA send or cancellation is involved.
    jdbc.update("UPDATE lp_quote_tech_task SET task_status='IN_PROGRESS',assignee_user_id=11,assignee_name='BOM技术员' WHERE id=?",taskId);
    jdbc.update("UPDATE lp_quote_tech_module SET assignee_user_id=11,assignee_name='BOM技术员',oa_edit_allowed=1 WHERE product_id=? AND module_type='DRAWING_BOM'",productId);
    jdbc.update("UPDATE lp_quote_tech_module SET required_flag=1,source_availability='MISSING',module_status='PENDING',assignee_user_id=12,assignee_name='原材料技术员',oa_edit_allowed=1 WHERE product_id=? AND module_type IN ('MANUFACTURING','AUXILIARY','SOLDER')",productId);
    var actor=new TechnicalDataActor(11L,"BOM技术员",Set.of("technical:data:task:edit"));
    var preview=service.preview(productId,"BOM.xlsx",workbook("250",model),actor);
    var saved=service.save(productId,upload(preview.upload().sha256(),0),actor);
    var all=taskRepository.findModules(productId);
    var covered=all.stream().filter(TechnicalBomModuleCoverage::covered).toList();
    assertThat(covered).hasSize(3).allSatisfy(module->{
      assertThat(module.getAssigneeUserId()).isEqualTo(12L);assertThat(module.getRequiredFlag()).isOne();
      assertThat(module.getModuleStatus()).isEqualTo("READY");assertThat(coverage.issues(module,all)).isEmpty();
    });
    var person=new com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient(
        1,taskId,1,12,"原材料技术员","12","ASSIGN",List.of("MANUFACTURING","AUXILIARY","SOLDER"),1,
        "TEST-TODO","CONFIRMED","OPEN",null,null,null,null,null,0,0,null,true,null,null,"TEST-INTEGRATION",null);
    var frozen=new TransactionTemplate(transactions).execute(status->versions.freeze(repository.lockProduct(productId).orElseThrow(),person,saved.rowVersion(),12));
    assertThat(codec.technicalBom(frozen)).isNull();assertThat(codec.manufacturing(frozen)).isNull();assertThat(codec.solder(frozen)).isNull();
    assertThat(repository.findAuxItems(frozen.getId())).isEmpty();
    assertThat(codec.supplementContent(frozen).sources().dependencies()).hasSize(3);
    var next=service.preview(productId,"new.xlsx",workbook("500",model),actor);
    service.save(productId,upload(next.upload().sha256(),repository.findProduct(productId).orElseThrow().getRowVersion()),actor);
    var after=taskRepository.findModules(productId);
    assertThat(after.stream().filter(TechnicalBomModuleCoverage::covered)).allSatisfy(module->{
      assertThat(module.getModuleStatus()).isEqualTo("FROZEN");assertThat(module.getCurrentVersionId()).isEqualTo(frozen.getId());
    });
    assertThat(dependencies.stale(frozen,after)).hasSize(3);
  }

}
