package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.technicaldata.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real disposable DB, source readers, technical submission, tree and costing; no mocked application ports. */
@Tag("integration")
@org.springframework.test.context.TestPropertySource(properties="quote.technical-data.attachments-root=${java.io.tmpdir}/technical-bom-cost-${random.uuid}")
class TechnicalCompleteBomCostingIntegrationTest extends OriginalPricingIntegrationTestBase {
  @org.springframework.test.context.DynamicPropertySource
  static void pricingMonth(org.springframework.test.context.DynamicPropertyRegistry properties) {
    properties.add("monthly.reprice.go-live-months.COMMERCIAL",CostPricingPeriodUtils::currentPricingMonth);
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;
  @Autowired BomSettlementRuleMapper settlementRules;
  @Autowired QuoteBomPreparationRecordMapper preparations;
  @Autowired TechnicalDataTaskApplicationService tasks;
  @Autowired QuoteTechnicalDataRepository repository;
  @Autowired TechnicalDataProfileApplicationService profiles;
  @Autowired TechnicalDataPackageApplicationService packages;
  @Autowired TechnicalBomApplicationService boms;
  @Autowired TechnicalBomWorkbook workbook;
  @Autowired TechnicalDataAdminDirectSave submit;
  @Autowired TechnicalDataBomPriceCheck priceCheck;
  @Autowired ProductCostingPipeline pipeline;
  @Autowired QuoteEffectiveBomApplicationService trees;
  static final TechnicalDataActor ADMIN=new TechnicalDataActor(1L,"管理员",Set.of("*:*:*"));
  String code, sample, material, month, costingCode; boolean missingCode; long item,form,task,product;

  @BeforeEach void fixture() { createFixture(false); }
  void createFixture(boolean missing) {
    missingCode=missing;
    code=missing?"N"+UUID.randomUUID().toString().substring(0,4):"TCB-"+UUID.randomUUID().toString().substring(0,8);sample=code+"-REF"; material=code+"-MAT";
    costingCode=missing?"MODEL:"+code.toUpperCase(java.util.Locale.ROOT)+"-MODEL":code;
    month=CostPricingPeriodUtils.currentPricingMonth();var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    var auth=new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("ROLE_admin")));
    auth.setDetails(Map.of("userId",1L,BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE,"COMMERCIAL"));SecurityContextHolder.getContext().setAuthentication(auth);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",code);
    form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,code);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,sunl_model,business_unit_type,shipping_fee) VALUES(?,?,?,?,'COMMERCIAL',0)",form,missing?null:code,code,code+"-MODEL");
    item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    var sources=new OrdinaryCostingTestSources(jdbc,hierarchy);
    sources.ordinarySources(costingCode,material,form,date);
    jdbc.update("DELETE FROM lp_bom_raw_hierarchy WHERE top_product_code=?",costingCode);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')",sample);
    long sampleForm=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,sample);
    sources.ordinarySources(sample,material,sampleForm,date);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=?",sample);
    sources.comparisonFixedPrice(material,"2",date);
    sources.comparisonFixedPrice(sample+"-ZERO-CHILD","3",date);
    var prep=new QuoteBomPreparationRecord();prep.setOaFormId(form);prep.setOaFormItemId(item);prep.setOaNo(code);prep.setQuoteProductCode(costingCode);
    prep.setProductType("NON_BARE");prep.setCostPeriodMonth(month);prep.setPriceOrgCode("210");prep.setMaterialOrganizationCode("COMMERCIAL");
    prep.setPreparationStatus("NEED_TECH");prep.setActiveFlag(1);prep.setElectronicWorkflowVersion(0);preparations.insert(prep);
    jdbc.update("INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,workspace_status,gap_count,last_checked_at) VALUES(?,?,?,?,'COMMERCIAL','WAIT_TECH_DATA',1,NOW())",code,item,costingCode,month);
    var entry=tasks.startAdminEntry(item,month,ADMIN);task=entry.id();product=entry.products().getFirst().id();
    var profile=new TechnicalDataProfileUpdateRequest();profile.setExpectedVersion(version());profile.setProductProperty("标准品");profile.setHasAdditionalFees(false);profile.setExpectedPlateMaterialRevision(entry.products().getFirst().profile().plateMaterial().revision());
    profile.setUnitMouldFee("/");profile.setUnitCertificationFee("/");profile.setUnitDepreciationFee("/");profile.setUnitToolingFee("/");profiles.save(product,profile,ADMIN);
    var source=packages.references(product,sample,ADMIN).components().getFirst().sources().getFirst();
    var child=source.children().getFirst();var pack=new TechnicalDataPackageSaveRequest();pack.setExpectedVersion(version());pack.setEntryMode("MANUAL");pack.setParentBaseQuantity(BigDecimal.ONE);
    var row=new TechnicalDataPackageItemRequest();row.setSourceParentNodeId(source.evidence().parentNodeId());row.setSourceNodeId(child.sourceNodeId());
    row.setSourceFingerprint(source.evidence().fingerprint());row.setQuantity(BigDecimal.ONE);row.setUnit(child.unit());pack.setItems(List.of(row));packages.save(product,pack,ADMIN);
  }
  int version(){return repository.findProduct(product).orElseThrow().getRowVersion();}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}

  byte[] upload(String quantity) throws Exception {
    var data=new TechnicalBomRows.Row(2,"1",material,material,"","",new BigDecimal(quantity),"件","采购件","",null,"");
    byte[] template=workbook.template(new TechnicalBomRows.Product(sample,sample,"",""),List.of(data));
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(template));var out=new ByteArrayOutputStream()){
      var row=book.getSheet(TechnicalBomWorkbook.ENTRY).createRow(1);
      var values=List.of(missingCode?"":code,code,code+"-MODEL","","1",material,material,"","",quantity,"件","采购件","","","");
      for(int i=0;i<values.size();i++)row.createCell(i).setCellValue(values.get(i));book.write(out);return out.toByteArray();
    }
  }
  @Test void uploadedQuantityIsPublishedCostedAndReusedWithoutIndependentMaterialFees() throws Exception {
    var preview=boms.preview(product,"成本BOM.xlsx",upload("4"),ADMIN);assertThat(preview.issues()).isEmpty();
    var request=new TechnicalBomSaveRequest();request.setEntryMode("UPLOAD");request.setFileSha256(preview.upload().sha256());request.setExpectedVersion(version());
    boms.save(product,request,ADMIN);
    var checked=priceCheck.check(product,ADMIN);assertThat(checked.issues()).isEmpty();assertThat(checked.missing()).isZero();
    var submitted=submit.save(task,new TechnicalDataAdminDirectSave.Request(version()),ADMIN);
    assertThat(submitted.validation().issues()).isEmpty();assertThat(submitted.effectiveVersionId()).isNotNull();
    var result=pipeline.execute(new ProductCostingRequest(code,item,month,"complete-bom-upload-test",true));
    assertThat(result.getPipelineStatus()).as(result.getMessage()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,result.getCostVersionId(),material)).isEqualByComparingTo("8");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",Integer.class,result.getCostVersionId(),material)).isEqualTo(1);
    assertThat(trees.getEffectiveBom(code,item).nodes().stream().filter(node->material.equals(node.materialCode()))).singleElement().satisfies(node->assertThat(node.qtyPerTop()).isEqualByComparingTo("4"));
    var again=pipeline.execute(new ProductCostingRequest(code,item,month,"complete-bom-upload-repeat",false));
    assertThat(again.getCostVersionId()).isEqualTo(result.getCostVersionId());
  }
  @Test void referencedQuantityChangesOnlyThisQuoteAndPreservesSourceBom() {
    var preview=boms.reference(product,sample,ADMIN);assertThat(preview.issues()).isEmpty();
    var request=new TechnicalBomSaveRequest();request.setEntryMode("REFERENCE");request.setExpectedVersion(version());
    request.setReferenceCode(sample);request.setReferenceFingerprint(preview.reference().fingerprint());
    var quantities=new LinkedHashMap<String,BigDecimal>();
    preview.rows().forEach(row->quantities.put(row.hierarchy(),material.equals(row.materialCode())?new BigDecimal("6"):row.quantity()));
    request.setQuantities(quantities);boms.save(product,request,ADMIN);
    assertThat(priceCheck.check(product,ADMIN).issues()).isEmpty();
    var submitted=submit.save(task,new TechnicalDataAdminDirectSave.Request(version()),ADMIN);
    assertThat(submitted.validation().issues()).isEmpty();assertThat(submitted.effectiveVersionId()).isNotNull();
    var result=pipeline.execute(new ProductCostingRequest(code,item,month,"complete-bom-reference-test",true));
    assertThat(result.getPipelineStatus()).as(result.getMessage()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,result.getCostVersionId(),material)).isEqualByComparingTo("12");
    assertThat(jdbc.queryForObject("SELECT qty_per_parent FROM lp_bom_raw_hierarchy WHERE top_product_code=? AND material_code=?",BigDecimal.class,sample,material)).isEqualByComparingTo("2.5");
    assertThat(pipeline.execute(new ProductCostingRequest(code,item,month,"complete-reference-repeat",false)).getCostVersionId()).isEqualTo(result.getCostVersionId());
  }

  @Test void manufacturingUsesUploadedNetWeightAndRawQuantityInsteadOfExistingU9ChildQuantity() throws Exception {
    String raw=material+"-RAW",scrap=material+"-SCRAP";var date=YearMonth.parse(month).atDay(1);
    jdbc.update("UPDATE lp_material_master_raw SET shape_attr='制造件',global_seg_3_theoretical_net_weight='999',global_seg_4_material='紫铜T2' WHERE material_code=?",material);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,main_category_name,shape_attr,global_seg_4_material) VALUES(?,?,'KG','COMMERCIAL',?,1,'15','原材料','采购件','紫铜T2')",raw,raw,code);
    var sources=new OrdinaryCostingTestSources(jdbc,hierarchy);sources.comparisonFixedPrice(raw,"50",date.atTime(12,0));
    jdbc.update("UPDATE lp_price_fixed_item SET unit='KG' WHERE material_code=?",raw);
    jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,scrap_unit,ratio,business_unit_type) VALUES(?,?,'KG',1,'COMMERCIAL')",raw,scrap);
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL',?,?,'铜废料','FIXED',10,'{}','IMPORT',?,?,'TEST',NOW(),'TEST',NOW())",month,scrap,month,"b".repeat(64));
    jdbc.update("INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type) VALUES(?,'U9',NOW(),'210',?,?,?,'KG','主制造','F001',1,0.8,1,?,'2099-01-01','NORMAL')",code,material,raw,raw,date);
    var rule=new BomSettlementRule();rule.setRuleCode(code);rule.setRuleName(code);rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");rule.setSubRefType("SPECIAL_ROLLUP_CHILD");
    rule.setMatchConditionJson("{\"nodeConditions\":[{\"field\":\"material_code\",\"op\":\"EQ\",\"value\":\""+raw+"\"}]}");
    rule.setMarkSubtreeCostRequired(1);rule.setPriority(1);rule.setEnabled(1);rule.setDeleted(0);rule.setBusinessUnitType("COMMERCIAL");
    settlementRules.insert(rule);
    byte[] bytes;
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(upload("2")));var out=new ByteArrayOutputStream()) {
      var sheet=book.getSheet(TechnicalBomWorkbook.ENTRY);sheet.getRow(1).getCell(11).setCellValue("制造件");
      sheet.getRow(1).getCell(13).setCellValue("100");sheet.getRow(1).getCell(14).setCellValue("g");
      var row=sheet.createRow(2);var values=List.of(code,code,code+"-MODEL","","1.1",raw,raw,"","","250","g","采购件","紫铜T2","","");
      for(int col=0;col<values.size();col++)row.createCell(col).setCellValue(values.get(col));book.write(out);bytes=out.toByteArray();
    }
    var preview=boms.preview(product,"制造BOM.xlsx",bytes,ADMIN);assertThat(preview.issues()).isEmpty();
    var request=new TechnicalBomSaveRequest();request.setEntryMode("UPLOAD");request.setFileSha256(preview.upload().sha256());request.setExpectedVersion(version());
    boms.save(product,request,ADMIN);assertThat(priceCheck.check(product,ADMIN).issues()).isEmpty();
    var submitted=submit.save(task,new TechnicalDataAdminDirectSave.Request(version()),ADMIN);assertThat(submitted.validation().issues()).isEmpty();
    var result=pipeline.execute(new ProductCostingRequest(code,item,month,"complete-manufacturing-test",true));
    assertThat(result.getPipelineStatus()).as(result.getMessage()).isEqualTo("SUCCESS");
    var calculated=jdbc.queryForList("SELECT gross_weight_g,net_weight_g,cost_price FROM lp_make_part_price_calc_row WHERE parent_material_no=?",material);
    assertThat(calculated).isNotEmpty().allSatisfy(row->{
      assertThat((BigDecimal)row.get("gross_weight_g")).isEqualByComparingTo("250");
      assertThat((BigDecimal)row.get("net_weight_g")).isEqualByComparingTo("100");
      assertThat((BigDecimal)row.get("cost_price")).isEqualByComparingTo("11");
    });
    assertThat(jdbc.queryForObject("SELECT qty_per_parent FROM lp_bom_u9_source WHERE parent_material_no=? AND child_material_no=?",BigDecimal.class,material,raw)).isEqualByComparingTo("0.8");
  }


  @Test void uploadWithOnlyProductModelCostsWithoutInventingAnOaMaterialNumber() throws Exception {
    createFixture(true);
    var preview=boms.preview(product,"无料号BOM.xlsx",upload("4"),ADMIN);assertThat(preview.issues()).isEmpty();
    var request=new TechnicalBomSaveRequest();request.setEntryMode("UPLOAD");request.setFileSha256(preview.upload().sha256());request.setExpectedVersion(version());
    boms.save(product,request,ADMIN);assertThat(priceCheck.check(product,ADMIN).issues()).isEmpty();
    assertThat(submit.save(task,new TechnicalDataAdminDirectSave.Request(version()),ADMIN).validation().issues()).isEmpty();
    var result=pipeline.execute(new ProductCostingRequest(code,item,month,"complete-model-only",true));
    assertThat(result.getPipelineStatus()).as(result.getMessage()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,result.getCostVersionId(),material)).isEqualByComparingTo("8");
    assertThat(jdbc.queryForObject("SELECT material_no FROM oa_form_item WHERE id=?",String.class,item)).isNull();
  }

  @Test void auxiliaryAndSolderInCompleteBomAreCostedExactlyOnce() throws Exception {
    var sources=new OrdinaryCostingTestSources(jdbc,hierarchy);var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    String auxiliary=code+"-AUX",solder=code+"-SOLDER";
    for(String child:List.of(auxiliary,solder)) {
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,main_category_name,shape_attr,cost_element) VALUES(?,?,'KG','COMMERCIAL',?,1,?,?,'采购件',?)",child,child,code,child.equals(solder)?"18181001":"18182001",child.equals(solder)?"焊料":"辅料",child.equals(solder)?"主要材料-焊料":"辅助材料");
      sources.comparisonFixedPrice(child,child.equals(solder)?"10":"5",date);jdbc.update("UPDATE lp_price_fixed_item SET unit='KG' WHERE material_code=?",child);
    }
    byte[] bytes;
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(upload("4")));var out=new ByteArrayOutputStream()) {
      var sheet=book.getSheet(TechnicalBomWorkbook.ENTRY);
      for(int n=0;n<2;n++) {
        String child=n==0?auxiliary:solder;var row=sheet.createRow(n+2);
        var values=List.of(code,code,code+"-MODEL","",String.valueOf(n+2),child,child,"","",n==0?"0.2":"0.3","kg","采购件","","","");
        for(int col=0;col<values.size();col++)row.createCell(col).setCellValue(values.get(col));
      }
      book.write(out);bytes=out.toByteArray();
    }
    var preview=boms.preview(product,"含辅料焊料BOM.xlsx",bytes,ADMIN);assertThat(preview.issues()).isEmpty();
    var request=new TechnicalBomSaveRequest();request.setEntryMode("UPLOAD");request.setFileSha256(preview.upload().sha256());request.setExpectedVersion(version());
    boms.save(product,request,ADMIN);assertThat(priceCheck.check(product,ADMIN).issues()).isEmpty();
    assertThat(submit.save(task,new TechnicalDataAdminDirectSave.Request(version()),ADMIN).validation().issues()).isEmpty();
    var result=pipeline.execute(new ProductCostingRequest(code,item,month,"complete-auxiliary-solder",true));
    assertThat(result.getPipelineStatus()).as(result.getMessage()).isEqualTo("SUCCESS");
    for(String child:List.of(auxiliary,solder)) {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",Integer.class,result.getCostVersionId(),child)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,result.getCostVersionId(),child)).isEqualByComparingTo(child.equals(solder)?"3":"1");
    }
    assertThat(trees.getEffectiveBom(code,item).nodes().stream().filter(node->solder.equals(node.materialCode()))).hasSize(1);
  }

  @Test void outsourceUsesExistingPricingWithoutGalleryManufacturingDetails() throws Exception {
    jdbc.update("UPDATE lp_material_master_raw SET shape_attr='委外加工件' WHERE material_code=?",material);
    String raw=code+"-OUT-RAW";
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'件','COMMERCIAL',?,1,'15','采购件')",raw,raw,code);
    new OrdinaryCostingTestSources(jdbc,hierarchy).comparisonFixedPrice(raw,"3",YearMonth.parse(month).atDay(1).atTime(12,0));
    byte[] bytes;
    try(var book=new XSSFWorkbook(new ByteArrayInputStream(upload("4")));var out=new ByteArrayOutputStream()) {
      book.getSheet(TechnicalBomWorkbook.ENTRY).getRow(1).getCell(11).setCellValue("委外加工件");
      book.write(out);
      assertThat(boms.preview(product,"不完整委外BOM.xlsx",out.toByteArray(),ADMIN).issues()).anySatisfy(issue->assertThat(issue.message()).contains("缺少下级材料"));
      out.reset();
      var row=book.getSheet(TechnicalBomWorkbook.ENTRY).createRow(2);
      var values=List.of(code,code,code+"-MODEL","","1.1",raw,raw,"","","0.5","件","采购件","","","");
      for(int col=0;col<values.size();col++)row.createCell(col).setCellValue(values.get(col));
      book.write(out);bytes=out.toByteArray();
    }
    var preview=boms.preview(product,"委外BOM.xlsx",bytes,ADMIN);assertThat(preview.issues()).isEmpty();
    var request=new TechnicalBomSaveRequest();request.setEntryMode("UPLOAD");request.setFileSha256(preview.upload().sha256());request.setExpectedVersion(version());
    boms.save(product,request,ADMIN);assertThat(priceCheck.check(product,ADMIN).issues()).isEmpty();
    assertThat(submit.save(task,new TechnicalDataAdminDirectSave.Request(version()),ADMIN).validation().issues()).isEmpty();
    var result=pipeline.execute(new ProductCostingRequest(code,item,month,"complete-outsourcing",true));
    assertThat(result.getPipelineStatus()).as(result.getMessage()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,result.getCostVersionId(),material)).isEqualByComparingTo("8");
    assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,result.getCostVersionId(),raw)).isEqualByComparingTo("6");
  }

}
