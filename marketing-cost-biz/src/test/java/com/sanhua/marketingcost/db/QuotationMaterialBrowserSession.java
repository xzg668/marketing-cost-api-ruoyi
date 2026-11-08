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
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15194",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class QuotationMaterialBrowserSession extends OriginalPricingIntegrationTestBase {
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
    Path state=Path.of(System.getProperty("quote.material.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    finance(month,"210"); finance(month,"220");
    var samples=new ArrayList<Map<String,Object>>();
    var sources=new OrdinaryCostingTestSources(jdbc,rawHierarchy);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    record Case(String name, String org, String copper, String zinc, String silver, String sus304, String sus316, String quote) {}
    for(var scenario:List.of(
        new Case("COMMON","210","100000","24000","15000000",null,null,"85.8"),
        new Case("SILVER_ONLY","210",null,null,"15000000",null,null,"84.4"),
        new Case("NONE","210",null,null,null,null,null,"82.4"),
        new Case("NEGATIVE","210","80000","20000","13000000",null,null,"79"),
        new Case("BOARD_BOTH","220","100000","24000","15000000","25000","35000","107.3"),
        new Case("BOARD_PARTIAL","220",null,null,null,null,"35000","102.4"),
        new Case("BOARD_NONE","220",null,null,null,null,null,"101.4"))) {
      String product="MAT-"+UUID.randomUUID().toString().substring(0,8),material=product+"-PART";
      jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,zinc_price,silver_price,sus304_price,sus316l_price) VALUES(?,'COMMERCIAL',?,?,?,?,?)",
          product,decimal(scenario.copper()),decimal(scenario.zinc()),decimal(scenario.silver()),decimal(scenario.sus304()),decimal(scenario.sus316()));
      long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,product,"材料差异验收 "+scenario.name());
      long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
      sources.ordinarySources(product,material,form,date);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
      boolean board = scenario.org().equals("220");
      if (board) {
        jdbc.update("UPDATE oa_form SET process_code='FI-SC-020' WHERE id=?",form);
        jdbc.update("UPDATE lp_material_master_raw SET organization_code='PLATE' WHERE import_batch_id=?",product);
        jdbc.update("UPDATE lp_bom_raw_hierarchy SET price_org_code='220' WHERE top_product_code=?",product);
      }
      String expression="cu*0.1+zn*0.2+ag*0.002+sn*0.1+2"+(board?"+s304*0.3+s316*0.4":"");
      long rule=rule(material,month,scenario.org(),expression);
      String base=board?"101.4":"82.4", quote=scenario.quote();
      var sample=new LinkedHashMap<String,Object>();sample.put("scenario",scenario.name());sample.put("oaNo",product);sample.put("product",product);sample.put("material",material);sample.put("itemId",item);sample.put("ruleId",rule);
      sample.put("base",base);sample.put("quote",quote);sample.put("difference",new BigDecimal(quote).subtract(new BigDecimal(base)).multiply(new BigDecimal("2.5")));samples.add(sample);
    }
    new TransactionTemplate(transactions).executeWithoutResult(tx->{effective.generateScope("COMMERCIAL","210");effective.generateScope("COMMERCIAL","220");});
    var before=jdbc.queryForList("SELECT * FROM lp_finance_effective_price ORDER BY id");
    assertThat(before.size()).isGreaterThanOrEqualTo(samples.size());
    String actor="material_"+UUID.randomUUID().toString().substring(0,8), password="MaterialBrowser-test-2026";
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'材料验收',?,'COMMERCIAL','0','0','MATERIAL_TEST',NOW(),'MATERIAL_TEST',NOW())",actor,new BCryptPasswordEncoder().encode(password));
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
      assertThat(jdbc.queryForObject("SELECT unit_price FROM lp_finance_effective_price WHERE original_id=? AND condition_code IN ('GENERAL','NON_TENDER')",BigDecimal.class,sample.get("ruleId"))).isEqualByComparingTo(sample.get("base").toString());
      checked.add(Map.of("scenario",sample.get("scenario"),"version",version));
    }
    assertThat(jdbc.queryForList("SELECT * FROM lp_finance_effective_price ORDER BY id")).isEqualTo(before);
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"publishedPricesUnchanged",true,"cases",checked)));
  }

  private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
  private static final Map<String,String> FACTORS=Map.of("cu","AVG|1#CU","zn","AVG|1#ZN","ag","AVG|1#AG","sn","AVG|1#SN","s304","AVG|304/2B/1219","s316","AVG|316L/2B");
  long finance(String month,String org) {
    String batch="MAT-"+UUID.randomUUID();
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,'COMMERCIAL','material-test.xlsx',?,?,'material-test','PUBLISHED',?,999,'DEFAULT',NOW())",batch,month,"a".repeat(64),"b".repeat(64),org);
    long id=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,batch);
    var values=Map.of("cu","90","zn","22","ag","14000","sn","390","s304","20","s316","32.5");
    int index=0;
    for(String name:org.equals("220")?List.of("cu","zn","ag","sn","s304","s316"):List.of("cu","zn","ag","sn")) {
      String factor=FACTORS.get(name),seq=UUID.randomUUID().toString();int row=++index+2;
      jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES('COMMERCIAL',?,?,?,'AVG',?)",seq,factor,factor,factor);
      long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,seq);
      for(String condition:org.equals("220")?List.of("NON_TENDER","TENDER"):List.of("GENERAL")) {
        jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,?,1,?,'PUBLISHED',?,'DEFAULT',?,999,'CNY/KG',NOW())",identity,month,decimal(values.get(name)),id,org,condition);
        long price=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=? AND condition_code=?",Long.class,identity,condition);
        jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,source_number_format,conversion_basis) VALUES(?,'财务基价',?,?,?,4,?,'DEFAULT',?,?,'CNY/KG','0.000000','IDENTITY')",id,row,identity,price,"D"+row,condition,values.get(name));
      }
    }return id;
  }
  long rule(String code,String month,String org,String expression) {
    var key=new MaterialPriceRuleKey("COMMERCIAL",org,code,"ZN-SUPPLIER","验收供应商",null,"件");
    var factors=new LinkedHashMap<String,OriginalLinkedWorkbookParser.FactorInput>();
    for(String name:List.of("cu","zn","ag","sn","s304","s316"))if(expression.contains(name)) {
      String keyName=FACTORS.get(name);
      factors.put(name,new OriginalLinkedWorkbookParser.FactorInput(keyName,new Address("财务基价",name.equals("cu")?"D3":"D4"),FactorPriceUnit.CNY_KG,"CNY/KG",keyName,"SAME_CANONICAL_FACTOR"));
    }
    var source=new OriginalLinkedWorkbookParser.Rule(key,"材料物料","原稿",8,"H8",expression,expression,"0.000000",false,new CompiledFormula(expression,List.of(),Map.of()),factors,BigDecimal.ZERO);
    var row=new PriceLinkedItem();row.setRuleIdentityKey(key.identityKey());row.setRuleRevision(1);row.setRuleStatus("PUBLISHED");row.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);row.setSourceKind("PUBLIC");row.setBusinessUnitType("COMMERCIAL");row.setOrgCode(org);row.setPricingMonth(month);row.setMaterialCode(code);row.setMaterialName("材料物料");row.setUnit("件");row.setSupplierCode("ZN-SUPPLIER");row.setSupplierName("验收供应商");row.setTaxIncluded(0);row.setFormulaExpr(expression);row.setSourceInputSnapshotJson(snapshots.write(List.of(source),"zinc-browser-source"));row.setSourceSheetName("原稿");row.setSourceRowNumber(8);row.setSourceFormulaCellRef("H8");row.setSourceFormulaExpr(expression);row.setEffectiveFrom(YearMonth.parse(month).atDay(1));row.setEffectiveTo(LocalDate.of(2099,1,1));row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(LocalDateTime.now());row.setDeleted(0);row.setTechnicalRevision(0);rules.insert(row);return row.getId();
  }
}
