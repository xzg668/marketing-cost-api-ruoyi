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
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15419",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class CuBandMaterialBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper rawHierarchy;
  @Autowired PriceLinkedItemMapper rules;
  @Autowired OriginalLinkedRuleSnapshot snapshots;
  @Autowired FinanceEffectivePriceService effective;
  @Autowired PlatformTransactionManager transactions;
  @Autowired com.sanhua.marketingcost.service.financefactor.FinanceFactorPublicationService publications;

  @org.springframework.test.context.DynamicPropertySource
  static void pricingPeriod(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state=Path.of(System.getProperty("quote.cu.band.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    finance(month,"210");
    var samples=new ArrayList<Map<String,Object>>();
    var sources=new OrdinaryCostingTestSources(jdbc,rawHierarchy);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    record Case(String name, String org, String copper, String zinc, String silver, String sus304, String sus316, String quote, String base, String expression) {}
    for(var scenario:List.of(
        new Case("BASE","210","90000",null,null,null,null,"52.6232791440377","52.6232791440377","brass"),
        new Case("BRASS_MIDDLE","210","105000",null,null,null,null,"58.4703101600419","52.6232791440377","brass"),
        new Case("H65_MIDDLE","210","105000",null,null,null,null,"60.996337260704","54.8967035346336","h65"),
        new Case("ABOVE_LAST","210","125000",null,null,null,null,"70.1643721920502","52.6232791440377","brass"),
        new Case("EXPLICIT_ZINC","210","125000","24000",null,null,null,"160.6599769048950","120.68619526189488","brass+h65+cu*0.1+zn*0.2"),
        new Case("BLANK_ZINC_RETAINS_BASE","210","125000",null,null,null,null,"160.02618948811858","120.68619526189488","brass+h65+cu*0.1+zn*0.2"),
        new Case("BELOW_FIRST","210","80000",null,null,null,null,"119.68619526189488","120.68619526189488","brass+h65+cu*0.1+zn*0.2"),
        new Case("NO_COPPER","210",null,null,null,null,null,"52.6232791440377","52.6232791440377","brass"))) {
      String product="MAT-"+UUID.randomUUID().toString().substring(0,8),material=product+"-PART";
      jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price,zinc_price,silver_price,sus304_price,sus316l_price) VALUES(?,'COMMERCIAL',?,?,?,?,?)",
          product,decimal(scenario.copper()),decimal(scenario.zinc()),decimal(scenario.silver()),decimal(scenario.sus304()),decimal(scenario.sus316()));
      long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,product,"材料差异验收 "+scenario.name());
      long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
      sources.ordinarySources(product,material,form,date);
      jdbc.update("""
          INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,
            parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,
            child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type)
          SELECT r.source_import_batch_id,'U9',NOW(),r.price_org_code,r.parent_code,r.material_code,
            r.material_name,'件',r.bom_purpose,r.bom_version,r.sort_seq,r.qty_per_parent,1,
            r.effective_from,r.effective_to,'NORMAL' FROM lp_bom_raw_hierarchy r
          WHERE r.top_product_code=? AND r.level>0
          """,product);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
      boolean board = scenario.org().equals("220");
      if (board) {
        jdbc.update("UPDATE oa_form SET process_code='FI-SC-020' WHERE id=?",form);
        jdbc.update("UPDATE lp_material_master_raw SET organization_code='PLATE' WHERE import_batch_id=?",product);
        jdbc.update("UPDATE lp_bom_raw_hierarchy SET price_org_code='220' WHERE top_product_code=?",product);
      }
      String expression=scenario.expression();
      long rule=rule(material,month,scenario.org(),expression);
      String base=scenario.base(), quote=scenario.quote();
      var sample=new LinkedHashMap<String,Object>();sample.put("scenario",scenario.name());sample.put("oaNo",product);sample.put("product",product);sample.put("material",material);sample.put("itemId",item);sample.put("ruleId",rule);
      sample.put("base",base);sample.put("copper",scenario.copper());sample.put("quote",quote);sample.put("difference",new BigDecimal(quote).subtract(new BigDecimal(base)).multiply(new BigDecimal("2.5")).setScale(6,java.math.RoundingMode.HALF_UP));samples.add(sample);
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
  private static final Map<String,String> FACTORS=Map.of("cu","AVG|1#CU","zn","AVG|1#ZN","brass","AVG|美国柜装黄铜","h65","AVG|H65黄铜边料","ag","AVG|1#AG","sn","AVG|1#SN","s304","AVG|304/2B/1219","s316","AVG|316L/2B");
  long finance(String month,String org) throws Exception {
    byte[] bytes;
    try(var in=getClass().getResourceAsStream("/finance-factor/finance-base-1109.xls")){bytes=in.readAllBytes();}
    var scope=new com.sanhua.marketingcost.dto.FinanceFactorImportContext("COMMERCIAL","210",month,"2026.11.2（9万报价）","CU_90000");
    var preview=publications.preview(bytes,"1109.xls",scope);
    assertThat(preview.workbook().problems()).isEmpty();
    return publications.publish(bytes,"1109.xls",scope,preview.previewFingerprint(),"CU_BAND_ISOLATED_TEST").batchId();
  }
  long rule(String code,String month,String org,String expression) {
    var key=new MaterialPriceRuleKey("COMMERCIAL",org,code,"ZN-SUPPLIER","验收供应商",null,"件");
    var factors=new LinkedHashMap<String,OriginalLinkedWorkbookParser.FactorInput>();
    for(String name:FACTORS.keySet())if(expression.contains(name)) {
      String keyName=FACTORS.get(name);
      factors.put(name,new OriginalLinkedWorkbookParser.FactorInput(keyName,new Address("财务基价",name.equals("cu")?"D3":"D4"),FactorPriceUnit.CNY_KG,"CNY/KG",keyName,"SAME_CANONICAL_FACTOR"));
    }
    var source=new OriginalLinkedWorkbookParser.Rule(key,"材料物料","原稿",8,"H8",expression,expression,"0.000000",false,new CompiledFormula(expression,List.of(),Map.of()),factors,BigDecimal.ZERO);
    var row=new PriceLinkedItem();row.setRuleIdentityKey(key.identityKey());row.setRuleRevision(1);row.setRuleStatus("PUBLISHED");row.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);row.setSourceKind("PUBLIC");row.setBusinessUnitType("COMMERCIAL");row.setOrgCode(org);row.setPricingMonth(month);row.setMaterialCode(code);row.setMaterialName("材料物料");row.setUnit("件");row.setSupplierCode("ZN-SUPPLIER");row.setSupplierName("验收供应商");row.setTaxIncluded(0);row.setFormulaExpr(expression);row.setSourceInputSnapshotJson(snapshots.write(List.of(source),"zinc-browser-source"));row.setSourceSheetName("原稿");row.setSourceRowNumber(8);row.setSourceFormulaCellRef("H8");row.setSourceFormulaExpr(expression);row.setEffectiveFrom(YearMonth.parse(month).atDay(1));row.setEffectiveTo(LocalDate.of(2099,1,1));row.setCreatedAt(LocalDateTime.now());row.setUpdatedAt(LocalDateTime.now());row.setDeleted(0);row.setTechnicalRevision(0);rules.insert(row);return row.getId();
  }
}
