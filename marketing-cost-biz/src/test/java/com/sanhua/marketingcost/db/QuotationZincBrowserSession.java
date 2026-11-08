package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Actual API, disposable MySQL and real browser; only owned fixtures are changed. */
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15192",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class QuotationZincBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper rawHierarchy;
  @Autowired PriceLinkedItemMapper rules;
  @Autowired OriginalLinkedRuleSnapshot snapshots;
  @Autowired FinanceEffectivePriceService effective;
  @Autowired PlatformTransactionManager transactions;

  @org.springframework.test.context.DynamicPropertySource
  static void pricingPeriod(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state=Path.of(System.getProperty("quote.zinc.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    long publication=finance(month);
    var samples=new ArrayList<Map<String,Object>>();
    var sources=new OrdinaryCostingTestSources(jdbc,rawHierarchy);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    for(String scenario:List.of("BOTH","NO_ZINC","ZINC_ONLY","NEGATIVE")) {
      String product="ZN-"+scenario+"-"+UUID.randomUUID().toString().substring(0,6),material=product+"-PART";
      String copper=scenario.equals("NEGATIVE")?"80000":"100000";
      String zinc=scenario.equals("NO_ZINC")?null:scenario.equals("NEGATIVE")?"20000":"24000";
      jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,zinc_price) VALUES(?,'COMMERCIAL',?,?)",product,new BigDecimal(copper),zinc==null?null:new BigDecimal(zinc));
      long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,product,"铜锌差异验收 "+scenario);
      long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
      sources.ordinarySources(product,material,form,date);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
      String expression=scenario.equals("ZINC_ONLY")?"zn*0.2+2":"cu*0.1+zn*0.2+2";
      long rule=rule(material,month,expression);
      String base=scenario.equals("ZINC_ONLY")?"6.4":"15.4";
      String quote=switch(scenario){case "BOTH"->"16.8";case "NO_ZINC"->"16.4";case "ZINC_ONLY"->"6.8";default->"14";};
      var sample=new LinkedHashMap<String,Object>();sample.put("scenario",scenario);sample.put("oaNo",product);sample.put("product",product);sample.put("material",material);sample.put("itemId",item);sample.put("ruleId",rule);
      sample.put("base",base);sample.put("quote",quote);sample.put("difference",new BigDecimal(quote).subtract(new BigDecimal(base)).multiply(new BigDecimal("2.5")));samples.add(sample);
    }
    new TransactionTemplate(transactions).executeWithoutResult(tx->effective.generateScope("COMMERCIAL","210"));
    var before=jdbc.queryForList("SELECT * FROM lp_finance_effective_price WHERE finance_batch_id=? ORDER BY id",publication);
    assertThat(before.size()).isGreaterThanOrEqualTo(samples.size());
    String actor="zinc_"+UUID.randomUUID().toString().substring(0,8), password="ZincBrowser-test-2026";
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'铜锌验收',?,'COMMERCIAL','0','0','ZN_TEST',NOW(),'ZN_TEST',NOW())",actor,new BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,actor);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var json=new ObjectMapper().findAndRegisterModules();
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(Map.of("apiPort",port,"username",actor,"password",password,"month",month,"samples",samples)));
    Path release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(35).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline)Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    var checked=new ArrayList<Map<String,Object>>();
    for(var sample:samples) {
      var version=jdbc.queryForMap("SELECT id,cu_material_adjustment,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'",sample.get("itemId"));
      assertThat((BigDecimal)version.get("cu_material_adjustment")).isEqualByComparingTo((BigDecimal)sample.get("difference"));
      assertThat((BigDecimal)version.get("final_quote_amount")).isEqualByComparingTo(((BigDecimal)version.get("total_cost")).add((BigDecimal)sample.get("difference")));
      assertThat(jdbc.queryForObject("SELECT unit_price FROM lp_finance_effective_price WHERE original_id=? AND finance_batch_id=?",BigDecimal.class,sample.get("ruleId"),publication)).isEqualByComparingTo(sample.get("base").toString());
      checked.add(Map.of("scenario",sample.get("scenario"),"version",version));
    }
    assertThat(jdbc.queryForList("SELECT * FROM lp_finance_effective_price WHERE finance_batch_id=? ORDER BY id",publication)).isEqualTo(before);
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"publishedPricesUnchanged",true,"cases",checked)));
  }

  long finance(String month) {
    String batch="ZN-"+UUID.randomUUID();
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,'COMMERCIAL','zinc-test.xlsx',?,?,'zinc-test','PUBLISHED','210',999,'DEFAULT',NOW())",batch,month,"a".repeat(64),"b".repeat(64));
    long id=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,batch);
    for(String factor:List.of(PriceFactorContext.COPPER,PriceFactorContext.ZINC)) {
      String seq=UUID.randomUUID().toString();int value=factor.equals(PriceFactorContext.COPPER)?90:22;
      jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES('COMMERCIAL',?,?,?,'AVG',?)",seq,factor,factor,factor);
      long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,seq);
      jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,?,1,?,'PUBLISHED','210','DEFAULT','GENERAL',999,'CNY/KG',NOW())",identity,month,value,id);
      long price=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=?",Long.class,identity);
      jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,source_number_format,conversion_basis) VALUES(?,'财务基价',?,?,?,4,?,'DEFAULT','GENERAL',?,'CNY/KG','0.000000','IDENTITY')",id,value==90?3:4,identity,price,value==90?"D3":"D4",Integer.toString(value));
    }return id;
  }
  long rule(String code,String month,String expression) {
    var key=new MaterialPriceRuleKey("COMMERCIAL","210",code,"ZN-SUPPLIER","验收供应商",null,"件");
    var factors=new LinkedHashMap<String,OriginalLinkedWorkbookParser.FactorInput>();
    for(String name:List.of("cu","zn"))if(expression.contains(name)) {
      String keyName=name.equals("cu")?PriceFactorContext.COPPER:PriceFactorContext.ZINC;
      factors.put(name,new OriginalLinkedWorkbookParser.FactorInput(keyName,new Address("财务基价",name.equals("cu")?"D3":"D4"),FactorPriceUnit.CNY_KG,"CNY/KG",keyName,"SAME_CANONICAL_FACTOR"));
    }
    var source=new OriginalLinkedWorkbookParser.Rule(key,"铜锌物料","原稿",8,"H8",expression,expression,"0.000000",false,new CompiledFormula(expression,List.of(),Map.of()),factors,BigDecimal.ZERO);
    var row=new PriceLinkedItem();row.setRuleIdentityKey(key.identityKey());row.setRuleRevision(1);row.setRuleStatus("PUBLISHED");row.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);row.setSourceKind("PUBLIC");row.setBusinessUnitType("COMMERCIAL");row.setOrgCode("210");row.setPricingMonth(month);row.setMaterialCode(code);row.setMaterialName("铜锌物料");row.setUnit("件");row.setSupplierCode("ZN-SUPPLIER");row.setSupplierName("验收供应商");row.setTaxIncluded(0);row.setFormulaExpr(expression);row.setSourceInputSnapshotJson(snapshots.write(List.of(source),"zinc-browser-source"));row.setSourceSheetName("原稿");row.setSourceRowNumber(8);row.setSourceFormulaCellRef("H8");row.setSourceFormulaExpr(expression);row.setEffectiveFrom(YearMonth.parse(month).atDay(1));row.setEffectiveTo(LocalDate.of(2099,1,1));row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(LocalDateTime.now());row.setDeleted(0);row.setTechnicalRevision(0);rules.insert(row);return row.getId();
  }
}
