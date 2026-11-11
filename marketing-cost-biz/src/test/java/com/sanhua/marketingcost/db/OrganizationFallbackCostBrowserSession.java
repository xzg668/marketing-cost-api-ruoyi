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
@EnabledIfSystemProperty(named="organization.fallback.browser.state",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15538",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class OrganizationFallbackCostBrowserSession extends OriginalPricingIntegrationTestBase {
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

  @Test void otherOrganizationFallbackAndLocalPriorityReachSavedCosts() throws Exception {
    var state=Path.of(System.getProperty("organization.fallback.browser.state"));assertThat(Files.exists(state)).isFalse();
    try(var connection=dataSource.getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V90__supplier_supply_ratio_menu.sql"));
    }
    String prefix="ORG-"+UUID.randomUUID().toString().substring(0,8),month=CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",prefix);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,prefix);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    var sources=new OrdinaryCostingTestSources(jdbc,hierarchy);var ids=new ArrayList<Long>();var products=new ArrayList<String>();
    for(int n=0;n<2;n++) {
      String product=prefix+"-"+n;products.add(product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,sunl_model,spec,business_unit_type,shipping_fee) VALUES(?,?,?,?,?,?,'COMMERCIAL',0)",form,n+1,product,"产品"+n,"型号"+n,"规格"+n);
      ids.add(jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=? AND material_no=?",Long.class,form,product));
      if(n>=2) continue;
      sources.ordinarySources(product,product+"-PART",form,date);
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",product);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
      sources.comparisonFixedPrice(product+"-PART","58.384779",date);
      jdbc.update("UPDATE lp_price_fixed_item SET org_code='220',supplier_code='S000052',supplier_name='浙江科宇金属材料有限公司' WHERE material_code=?",product+"-PART");
      jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,supplier_code,supplier_name,supply_ratio,is_active,deleted,source_type) VALUES('COMMERCIAL',?,?,'S001315','丰山（连云港）新材料有限公司',1,1,0,'EXCEL')",product+"-PART",product+"-PART");
      if(n==1) {
        sources.comparisonFixedPrice(product+"-PART","50",date);
        jdbc.update("UPDATE lp_price_fixed_item SET supplier_code='S001315',supplier_name='丰山（连云港）新材料有限公司' WHERE material_code=? AND supplier_code='MRP11-S1'",product+"-PART");
      }
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
      var before=jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version WHERE oa_no=? ORDER BY id",prefix);
      var monthly=jdbc.queryForList("SELECT * FROM lp_material_monthly_price WHERE material_code IN (?,?) ORDER BY id",products.get(0)+"-PART",products.get(1)+"-PART");
      assertThat(monthly).hasSize(2);
      for(int n=0;n<2;n++) {
        var fact=jdbc.queryForMap("SELECT unit_price FROM lp_material_monthly_price WHERE material_code=?",products.get(n)+"-PART");
        assertThat(new java.math.BigDecimal(fact.get("unit_price").toString())).isEqualByComparingTo(n==0?"58.384779":"50");
      }
      var values=new LinkedHashMap<String,Object>();values.put("apiPort",port);values.put("username",actor);values.put("password",password);
      values.put("oaNo",prefix);values.put("month",month);values.put("items",ids);values.put("products",products);values.put("versions",versions);
      values.put("savedAmounts",before.stream().map(v->v.get("final_quote_amount").toString()).toList());
      var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
      values.put("jdbcUrl",ds.getJdbcUrl());values.put("dbUsername",ds.getUsername());values.put("dbPassword",ds.getPassword());
      var json=new ObjectMapper().findAndRegisterModules();Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.writeString(state,json.writeValueAsString(values));
      var marker=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(8).toNanos();
      while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(200);
      assertThat(Files.exists(marker)).isTrue();assertThat(json.readTree(Files.readString(marker)).path("passed").asBoolean()).isTrue();
      assertThat(jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version WHERE oa_no=? ORDER BY id",prefix)).isEqualTo(before);
      assertThat(jdbc.queryForList("SELECT * FROM lp_material_monthly_price WHERE material_code IN (?,?) ORDER BY id",products.get(0)+"-PART",products.get(1)+"-PART")).isEqualTo(monthly);
      assertThat(jdbc.queryForList("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE material_code IN (?,?)",products.get(0)+"-PART",products.get(1)+"-PART")).allSatisfy(v->assertThat(new java.math.BigDecimal(v.get("supply_ratio").toString())).isEqualByComparingTo("1"));
    } finally {SecurityContextHolder.clearContext();}
  }
}
