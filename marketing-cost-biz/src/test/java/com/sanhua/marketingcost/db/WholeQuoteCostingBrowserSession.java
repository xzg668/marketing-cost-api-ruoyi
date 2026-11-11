package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.costing.QuoteCostingPreparationService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Real 107-product browser/API/worker acceptance against one disposable local database. */
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15238",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class WholeQuoteCostingBrowserSession extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;
  @Autowired QuoteCostingPreparationService costing;
  @Autowired javax.sql.DataSource dataSource;
  @LocalServerPort int port;
  @org.springframework.test.context.DynamicPropertySource static void period(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("monthly.reprice.go-live-months.COMMERCIAL",CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    var state=Path.of(System.getProperty("whole.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    try(var connection=dataSource.getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V90__supplier_supply_ratio_menu.sql"));
    }
    String prefix="WHOLE-"+UUID.randomUUID().toString().substring(0,8),month=CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",prefix);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,prefix);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    var sources=new OrdinaryCostingTestSources(jdbc,hierarchy);var ids=new ArrayList<Long>();var products=new ArrayList<String>();
    for(int n=0;n<107;n++) {
      String product=prefix+"-"+n,material=product+"-PART";products.add(product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,business_unit_type,shipping_fee) VALUES(?,?,?,?,'COMMERCIAL',0)",form,n,product,product);
      ids.add(jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=? AND material_no=?",Long.class,form,product));
      sources.ordinarySources(product,material,form,date);
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",product);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
      if(n<105) sources.comparisonFixedPrice(material,"2",date);
      else for(String supplier:List.of("A","B")) jdbc.update("INSERT INTO lp_price_fixed_item(org_code,supplier_code,supplier_name,material_code,material_name,unit,fixed_price,tax_included,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('210',?,?,?,?,'件',?,0,?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",supplier,"供应商"+supplier,material,material,new BigDecimal(supplier.equals("A")?"10":"30"),date.toLocalDate());
    }
    jdbc.update("""
        INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,
          parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,
          child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type)
        SELECT source_import_batch_id,'U9',NOW(),price_org_code,parent_code,material_code,material_name,
          '件',bom_purpose,bom_version,sort_seq,qty_per_parent,1,effective_from,effective_to,'NORMAL'
        FROM lp_bom_raw_hierarchy WHERE top_product_code LIKE ? AND level>0
        """,prefix+"-%");
    String actor="whole_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'整单107产品验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",actor,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,actor);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var auth=new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("*:*:*")));
    auth.setDetails(Map.of("businessUnitType","COMMERCIAL"));SecurityContextHolder.getContext().setAuthentication(auth);
    Long firstVersion;
    try {
      var first=costing.prepareQueuedProduct(new ProductCostingRequest(prefix,ids.get(0),month,actor,false));
      assertThat(first.getPipelineStatus()).isEqualTo("SUCCESS");firstVersion=first.getCostVersionId();
      assertThat(costing.prepareQueuedProduct(new ProductCostingRequest(prefix,ids.get(1),month,actor,false)).getPipelineStatus()).isEqualTo("SUCCESS");
      jdbc.update("UPDATE oa_form_item SET calc_status='待核算' WHERE id=?",ids.get(0));
      var reused=costing.prepareQueuedProduct(new ProductCostingRequest(prefix,ids.get(0),month,actor,false));
      assertThat(reused.isReusedSuccess()).isTrue();assertThat(reused.getCostVersionId()).isEqualTo(firstVersion);
    } finally {SecurityContextHolder.clearContext();}
    jdbc.update("UPDATE oa_form_item SET shipping_fee=1 WHERE id=?",ids.get(1));
    var ds=(com.zaxxer.hikari.HikariDataSource)dataSource;
    var values=new LinkedHashMap<String,Object>();values.put("apiPort",port);values.put("username",actor);values.put("password",password);
    values.put("oaNo",prefix);values.put("month",month);values.put("items",ids);values.put("products",products);values.put("firstVersionId",firstVersion);
    values.put("jdbcUrl",ds.getJdbcUrl());values.put("dbUsername",ds.getUsername());values.put("dbPassword",ds.getPassword());
    var json=new ObjectMapper().findAndRegisterModules();Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(values));
    var release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline)Thread.sleep(200);
    assertThat(Files.exists(release)).isTrue();assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,ids.get(0))).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,ids.get(1))).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,ids.get(105))).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,ids.get(106))).isZero();
    var versions=jdbc.queryForList("SELECT oa_form_item_id,COUNT(*) n FROM lp_quote_cost_run_version WHERE oa_form_item_id IN (SELECT id FROM oa_form_item WHERE oa_form_id=?) GROUP BY oa_form_item_id",form);
    assertThat(versions).hasSize(106);
    var counts=jdbc.queryForList("SELECT v.oa_form_item_id,COUNT(DISTINCT v.id) version_count,COUNT(DISTINCT p.id) part_count,COUNT(DISTINCT c.id) cost_count FROM lp_quote_cost_run_version v LEFT JOIN lp_cost_run_part_item p ON p.cost_run_version_id=v.id LEFT JOIN lp_cost_run_cost_item c ON c.cost_run_version_id=v.id WHERE v.oa_no=? GROUP BY v.oa_form_item_id",prefix);
    Files.writeString(Path.of(state+".cost-counts"),json.writeValueAsString(counts));
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"unchangedVersionPreserved",true,"changedProductRecalculatedOnce",true,"repairedRatioProductCalculated",true,"remainingGapHasNoCost",true,"productsWithCost",106)));
  }
}
