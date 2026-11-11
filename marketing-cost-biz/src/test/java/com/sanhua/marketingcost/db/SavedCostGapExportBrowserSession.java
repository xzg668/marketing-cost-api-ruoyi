package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.costing.QuoteCostingPreparationService;
import com.sanhua.marketingcost.service.costing.QuoteCostingGapExportService;
import com.sanhua.marketingcost.service.quotefinal.QuoteFinalSubmissionService;
import com.sanhua.marketingcost.service.impl.SupplierSupplyRatioWorkbookParserImpl;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("integration")
@EnabledIfSystemProperty(named="gap.browser.state",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15238",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class SavedCostGapExportBrowserSession extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;
  @Autowired QuoteCostingPreparationService costing;
  @Autowired QuoteFinalSubmissionService finalCosts;
  @Autowired QuoteCostingGapExportService exports;
  @Autowired javax.sql.DataSource dataSource;
  @LocalServerPort int port;
  @org.springframework.test.context.DynamicPropertySource static void period(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("monthly.reprice.go-live-months.COMMERCIAL",CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void actualSavedCostsAndDeduplicatedExports() throws Exception {
    var state=Path.of(System.getProperty("gap.browser.state"));assertThat(Files.exists(state)).isFalse();
    try(var connection=dataSource.getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V90__supplier_supply_ratio_menu.sql"));
    }
    String prefix="GAP-"+UUID.randomUUID().toString().substring(0,8),month=CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",prefix);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,prefix);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    var sources=new OrdinaryCostingTestSources(jdbc,hierarchy);var ids=new ArrayList<Long>();var products=new ArrayList<String>();
    for(int n=0;n<7;n++) {
      String product=prefix+"-"+n;products.add(product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,sunl_model,spec,business_unit_type,shipping_fee) VALUES(?,?,?,?,?,?,'COMMERCIAL',0)",form,n+1,product,"产品"+n,"型号"+n,"规格"+n);
      ids.add(jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=? AND material_no=?",Long.class,form,product));
      if(n>=2) continue;
      sources.ordinarySources(product,product+"-PART",form,date);
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",product);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
      sources.comparisonFixedPrice(product+"-PART","2",date);
    }
    jdbc.update("""
        INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,
          parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,
          child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type)
        SELECT source_import_batch_id,'U9',NOW(),price_org_code,parent_code,material_code,material_name,
          '件',bom_purpose,bom_version,sort_seq,qty_per_parent,1,effective_from,effective_to,'NORMAL'
        FROM lp_bom_raw_hierarchy WHERE top_product_code LIKE ? AND level>0
        """,prefix+"-%");
    String actor="gap_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'缺口导出验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",actor,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,actor);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var auth=new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("*:*:*")));
    auth.setDetails(Map.of("businessUnitType","COMMERCIAL"));SecurityContextHolder.getContext().setAuthentication(auth);
    var versions=new ArrayList<Long>();
    try {
      for(int n=0;n<2;n++) {
        var cost=costing.prepareQueuedProduct(new ProductCostingRequest(prefix,ids.get(n),month,actor,false));
        assertThat(cost.getPipelineStatus()).isEqualTo("SUCCESS");versions.add(cost.getCostVersionId());
      }
      String ratio="0000912345678",price="0000912345679";
      for(String code:List.of(ratio,price)) jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,material_model,material_spec,unit,organization_code,import_batch_id,active_flag,production_division,purchase_category) VALUES(?,?,'实际型号','实际规格','件','COMMERCIAL',?,1,'事业部A','部品联动')",code,"物料"+code,prefix);
      for(String supplier:List.of("A","A")) jdbc.update("INSERT INTO lp_price_fixed_item(org_code,source_name,supplier_code,supplier_name,material_code,material_name,fixed_price,effective_from,effective_to,business_unit_type,source_type,source_kind,created_at,updated_at) VALUES('210','供管处',?,?,?, ?,10,?,'2099-01-01','COMMERCIAL','PURCHASE_FIXED','PUBLIC',NOW(),NOW())",supplier,"供应商"+supplier,ratio,"比率物料",date.toLocalDate());
      jdbc.update("INSERT INTO lp_price_linked_item(material_code,material_name,supplier_code,supplier_name,business_unit_type,org_code,source_kind,rule_status,pricing_month,effective_from,created_at,updated_at) VALUES(?,'比率物料','B','供应商B','COMMERCIAL','210','PUBLIC','LEGACY',?,?,NOW(),NOW())",ratio,month,date.toLocalDate());
      jdbc.update("INSERT INTO lp_price_fixed_item(org_code,supplier_code,supplier_name,material_code,fixed_price,business_unit_type,created_at,updated_at) VALUES('220','FOREIGN','其他业务单元',?,10,'PLATE',NOW(),NOW())",ratio);
      jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,supplier_code,supplier_name,supply_ratio,is_active,deleted,source_type) VALUES('COMMERCIAL',?,'比率物料','C','不在候选中的零比率供应商',0,1,0,'EXCEL')",ratio);
      jdbc.update("INSERT INTO lp_price_linked_item(material_code,material_name,business_unit_type,org_code,source_kind,rule_status,pricing_month,effective_from,created_at,updated_at) VALUES('205700099','行情档位待核实材料','COMMERCIAL','210','PUBLIC','DRAFT',?,?,NOW(),NOW())",month,date.toLocalDate());
      jdbc.update("UPDATE oa_form_item SET annual_volume=0.01 WHERE id=?",ids.getFirst());
      String weightCode=prefix+"-WEIGHT";
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,material_model,material_spec,unit,organization_code,import_batch_id,active_flag) VALUES(?,'重量待补原料','WG-M','WG-S','千克','COMMERCIAL',?,1)",weightCode,prefix);
      jdbc.update("INSERT INTO lp_make_part_price_calc_row(calc_batch_id,oa_no,business_unit_type,pricing_month,parent_material_no,child_material_no,item_process_type,gross_weight_g,net_weight_g,status,price_complete,remark,price_as_of_time,created_at) VALUES(?,?,'COMMERCIAL',?,?,?,'原材料加工',0.701,NULL,'MISSING_WEIGHT',0,'副产品反算失败(parent_material_no=test)；缺父子材料净重',NOW(),NOW())",prefix,prefix,month,products.get(6),weightCode);
      String[] messages={"缺少原料价格："+price,null,"缺少供货比率："+ratio+"；供货比率请财务维护或确认取价方式","缺少供货比率："+ratio+"；缺少可用价格："+price+"；供货比率请财务维护或确认取价方式","CMS 已完成核实，本产品两项工资均不存在","物料=205700099，供应商=示例供应商，缺少行情档位：120000","计价资料待完善："+weightCode};
      jdbc.update("UPDATE lp_quote_costing_workspace SET workspace_status='WAIT_PRICE',input_fingerprint='CHANGED-INPUT',last_error_code='PRICE_MISSING',last_error_message=?,gap_count=1 WHERE oa_form_item_id=? AND period_month=?",messages[0],ids.getFirst(),month);
      for(int n=2;n<7;n++) jdbc.update("INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,workspace_status,current_step,gap_count,last_error_code,last_error_message) VALUES(?,?,?,?,'COMMERCIAL',?,'PRICE_PREPARE',1,'PRICE_MISSING',?)",prefix,ids.get(n),products.get(n),month,n==5?"SYSTEM_FAILED":"WAIT_PRICE",messages[n]);
      var status=finalCosts.status(prefix,month,new TechnicalDataActor(user,actor,Set.of("*:*:*")));
      assertThat(status.costs().getFirst().totalCost()).isNull();assertThat(status.costs().getFirst().status()).isEqualTo("STALE");
      assertThat(status.costs().get(1).status()).isEqualTo("READY");assertThat(status.readyProducts()).isEqualTo(1);
      assertThat(status.canConfirm()).isFalse();
      byte[] workbook=exports.export(prefix,QuoteCostingGapExportService.Category.RATIO);
      try(var book=new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
        var sheet=book.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(3);
        assertThat(sheet.getRow(0).getPhysicalNumberOfCells()).isEqualTo(10);
        assertThat(book.getSheetAt(1).getLastRowNum()).isEqualTo(1);
        for(int i=1;i<=3;i++) assertThat(sheet.getRow(i).getCell(6).getStringCellValue()).isEqualTo(ratio);
      }
      var parsed=new SupplierSupplyRatioWorkbookParserImpl().parse(new ByteArrayInputStream(workbook),"待补.xlsx",null);
      assertThat(parsed.getErrors()).isEmpty();assertThat(parsed.getRows()).hasSize(3);
      for(var category:List.of(QuoteCostingGapExportService.Category.PRICE,QuoteCostingGapExportService.Category.OTHER))
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(exports.export(prefix,category)))) { assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(category==QuoteCostingGapExportService.Category.PRICE?1:3); }
      var missing=exports.details(prefix,QuoteCostingGapExportService.Category.PRICE);
      assertThat(missing.records()).hasSize(1);assertThat(missing.records().getFirst().sourceChecks()).hasSize(12);
      assertThat(missing.records().getFirst().sourceChecks()).allSatisfy(source->assertThat(source.records()).isZero());
      assertThat(missing.records().getFirst().reason()).contains("各价格来源均无");
      assertThat(exports.details(prefix,QuoteCostingGapExportService.Category.OTHER).records()).anySatisfy(issue->{
        assertThat(issue.materialCode()).isEqualTo("205700099");assertThat(issue.reason()).contains("已有价格记录", "尚未发布", "120000");
      });
      assertThat(exports.details(prefix,QuoteCostingGapExportService.Category.OTHER).records()).anySatisfy(issue->{
        assertThat(issue.materialCode()).isEqualTo(weightCode);assertThat(issue.reason()).contains("缺少净重").doesNotContain("缺少毛重","parent_material_no","副产品反算失败");
      });
      var values=new LinkedHashMap<String,Object>();values.put("apiPort",port);values.put("username",actor);values.put("password",password);
      values.put("oaNo",prefix);values.put("month",month);values.put("items",ids);values.put("products",products);values.put("versions",versions);values.put("ratioCode",ratio);
      values.put("savedAmount",jdbc.queryForObject("SELECT final_quote_amount FROM lp_quote_cost_run_version WHERE id=?",java.math.BigDecimal.class,versions.getFirst()).toPlainString());
      if(Boolean.getBoolean("gap.browser.realMissingPrice")) {
        jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?",products.getFirst()+"-PART");
        jdbc.update("UPDATE lp_material_monthly_price SET disabled=1 WHERE material_code=?",products.getFirst()+"-PART");
      }
      values.put("weightCode",weightCode);values.put("priceCode",price);
      var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
      values.put("jdbcUrl",ds.getJdbcUrl());values.put("dbUsername",ds.getUsername());values.put("dbPassword",ds.getPassword());
      var json=new ObjectMapper().findAndRegisterModules();Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.writeString(state,json.writeValueAsString(values));
      var marker=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(8).toNanos();
      while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(200);
      assertThat(Files.exists(marker)).isTrue();assertThat(json.readTree(Files.readString(marker)).path("passed").asBoolean()).isTrue();
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_no=?",Integer.class,prefix)).isEqualTo(2);
      assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE material_code=? AND supplier_code='C' AND is_active=1",java.math.BigDecimal.class,ratio)).isEqualByComparingTo("0");
    } finally {SecurityContextHolder.clearContext();}
  }
}
