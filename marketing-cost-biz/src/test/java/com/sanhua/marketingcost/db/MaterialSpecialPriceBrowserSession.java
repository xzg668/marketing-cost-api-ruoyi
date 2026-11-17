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
@EnabledIfSystemProperty(named="special.price.browser.state",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15239",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class MaterialSpecialPriceBrowserSession extends OriginalPricingIntegrationTestBase {
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

  @Test void specialMaterialsRequireMonthlyImportBeforeRecosting() throws Exception {
    var state=Path.of(System.getProperty("special.price.browser.state"));assertThat(Files.exists(state)).isFalse();
    try(var connection=dataSource.getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V90__supplier_supply_ratio_menu.sql"));
    }
    for(String script:List.of("V357__inquiry_price_menu.sql")) {
      try(var connection=dataSource.getConnection()) {
        org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,new org.springframework.core.io.ClassPathResource("db/"+script));
      }
    }
    String prefix="SPECIAL-"+UUID.randomUUID().toString().substring(0,8),month=CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",prefix);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,prefix);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    var sources=new OrdinaryCostingTestSources(jdbc,hierarchy);var ids=new ArrayList<Long>();var products=new ArrayList<String>();
    for(int n=0;n<3;n++) {
      String product=prefix+"-"+n;products.add(product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,sunl_model,spec,business_unit_type,shipping_fee) VALUES(?,?,?,?,?,?,'COMMERCIAL',0)",form,n+1,product,"产品"+n,"型号"+n,"规格"+n);
      ids.add(jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=? AND material_no=?",Long.class,form,product));
      String material=n==0?"201851022":n==1?"339950198":product+"-PART";
      sources.ordinarySources(product,material,form,date);
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",product);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
      sources.comparisonFixedPrice(material,"58.384779",date);
      jdbc.update("UPDATE lp_price_fixed_item SET supplier_code='S000052',supplier_name='浙江科宇金属材料有限公司' WHERE material_code=?",material);
      jdbc.update("UPDATE lp_material_master_raw SET material_name=?,material_spec=?,material_model=? WHERE material_code=?",n==0?"调理芯片":n==1?"信号调理芯片":"对照物料",n==0?"SNU529W":n==1?"SNU501":"",n==0?"SNU529W":"",material);

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
      for(int n=0;n<3;n++) {
        var cost=costing.prepareQueuedProduct(new ProductCostingRequest(prefix,ids.get(n),month,actor,false));
        assertThat(cost.getPipelineStatus()).isEqualTo("SUCCESS");versions.add(cost.getCostVersionId());
      }
      var before=jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version WHERE oa_no=? ORDER BY id",prefix);
      var values=new LinkedHashMap<String,Object>();values.put("apiPort",port);values.put("username",actor);values.put("password",password);
      values.put("oaNo",prefix);values.put("month",month);values.put("items",ids);values.put("products",products);values.put("versions",versions);
      values.put("savedAmounts",before.stream().map(v->v.get("final_quote_amount").toString()).toList());
      var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
      values.put("jdbcUrl",ds.getJdbcUrl());values.put("dbUsername",ds.getUsername());values.put("dbPassword",ds.getPassword());
      var json=new ObjectMapper().findAndRegisterModules();Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.writeString(state,json.writeValueAsString(values));
      awaitMarker(Path.of(state+".check-missing"));
      var waiting=new ArrayList<String>();
      for(int n=0;n<2;n++) {
        var cost=costing.prepareQueuedProduct(new ProductCostingRequest(prefix,ids.get(n),month,actor,false));
        assertThat(cost.getPipelineStatus()).isNotEqualTo("SUCCESS");waiting.add(cost.getPipelineStatus());
      }
      assertThat(jdbc.queryForList("SELECT * FROM lp_quote_cost_run_version WHERE oa_no=? ORDER BY id",prefix)).isEqualTo(before);
      Files.writeString(Path.of(state+".waiting"),json.writeValueAsString(waiting));
      awaitMarker(Path.of(state+".check-imported"));
      var newVersions=new ArrayList<Long>();
      for(int n=0;n<3;n++) {
        var cost=costing.prepareQueuedProduct(new ProductCostingRequest(prefix,ids.get(n),month,actor,false));
        assertThat(cost.getPipelineStatus()).as("product %s",n).isEqualTo("SUCCESS");
        newVersions.add(cost.getCostVersionId());
        if(n<2)assertThat(cost.getCostVersionId()).isNotEqualTo(versions.get(n));
        else assertThat(cost.getCostVersionId()).isEqualTo(versions.get(n));
        for(int repeat=0;repeat<3;repeat++) {
          var repeated=costing.prepareQueuedProduct(new ProductCostingRequest(prefix,ids.get(n),month,actor,false));
          assertThat(repeated.getPipelineStatus()).isEqualTo("SUCCESS");
          assertThat(repeated.getCostVersionId()).isEqualTo(cost.getCostVersionId());
        }
      }
      for(var old:before) {
        var current=new LinkedHashMap<>(jdbc.queryForMap("SELECT * FROM lp_quote_cost_run_version WHERE id=?",old.get("id")));
        var expected=new LinkedHashMap<>(old);current.remove("status");expected.remove("status");current.remove("updated_at");expected.remove("updated_at");
        assertThat(current).isEqualTo(expected);
      }
      values.put("newVersions",newVersions);
      values.put("newCosts",jdbc.queryForList("SELECT id,final_quote_amount FROM lp_quote_cost_run_version WHERE id IN (?,?,?) ORDER BY id",newVersions.toArray()));
      values.put("waiting",waiting);
      Files.writeString(Path.of(state+".complete"),json.writeValueAsString(values));
      awaitMarker(Path.of(state+".release"));
      assertThat(json.readTree(Files.readString(Path.of(state+".release"))).path("passed").asBoolean()).isTrue();
    } finally {SecurityContextHolder.clearContext();}
  }
  private static void awaitMarker(Path marker)throws Exception {
    long deadline=System.nanoTime()+Duration.ofMinutes(25).toNanos();
    while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(200);
    assertThat(Files.exists(marker)).as("marker %s",marker).isTrue();
  }
}
