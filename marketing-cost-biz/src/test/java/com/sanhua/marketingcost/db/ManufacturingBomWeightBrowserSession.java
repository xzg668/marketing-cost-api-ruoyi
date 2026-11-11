package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Fresh local database only. The real browser submits every calculation and downloads each sheet. */
@Tag("integration")
@EnabledIfSystemProperty(named="manufacturing.bom.browser.state", matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15238",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class ManufacturingBomWeightBrowserSession extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;
  @Autowired com.sanhua.marketingcost.mapper.BomSettlementRuleMapper settlementRules;
  @LocalServerPort int port;

  @org.springframework.test.context.DynamicPropertySource
  static void period(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    var state=Path.of(System.getProperty("manufacturing.bom.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    String prefix="BOMW-"+UUID.randomUUID().toString().substring(0,8);
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",prefix);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,prefix);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    var sources=new OrdinaryCostingTestSources(jdbc,hierarchy);
    var items=new ArrayList<Long>();var products=new ArrayList<String>();
    for(int n=0;n<4;n++) {
      String product=prefix+"-"+n,parent=product+"-PART";products.add(product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,business_type,shipping_fee,business_unit_type) VALUES(?,?,?,?, '批量品',0,'COMMERCIAL')",
          form,n+1,product,List.of("毛坯件数反算毛重","BOM重量优先副产品","副产品缺失净重兜底","毛坯人工确认无废料").get(n));
      items.add(jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=? AND material_no=?",Long.class,form,product));
      sources.ordinarySources(product,parent,form,date);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
      manufacturing(product,parent,n,date,month,sources);
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",product);
    }
    jdbc.update("""
        INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,
          parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,
          child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type)
        SELECT r.source_import_batch_id,'U9',NOW(),r.price_org_code,r.parent_code,r.material_code,
          r.material_name,'件',r.bom_purpose,r.bom_version,r.sort_seq,r.qty_per_parent,1,
          r.effective_from,r.effective_to,'NORMAL' FROM lp_bom_raw_hierarchy r
        WHERE r.top_product_code LIKE ? AND r.level>0 AND NOT EXISTS (
          SELECT 1 FROM lp_bom_u9_source s WHERE s.price_org_code=r.price_org_code
            AND s.parent_material_no=r.parent_code AND s.child_material_no=r.material_code
            AND s.bom_version=r.bom_version)
        """,prefix+"-%");
    String username="weight_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'重量规则验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",username,new BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var json=new ObjectMapper();
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(Map.of("apiPort",port,"oaNo",prefix,"items",items,
        "products",products,"month",month,"username",username,"password",password,
        "expectedPrices",List.of("46.511","36.511","36.319","50"))));
    var release=Path.of(state+".release");
    long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline) Thread.sleep(200);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    var evidence=new ArrayList<Map<String,Object>>();
    for(int n=0;n<4;n++) {
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,items.get(n))).isEqualTo(1);
      var weights=jdbc.queryForList("SELECT gross_weight_g,net_weight_g,cost_price,remark FROM lp_make_part_price_calc_row WHERE parent_material_no=?",products.get(n)+"-PART");
      assertThat(weights).isNotEmpty();
      String gross=n==0?"780.8":n==3?"431.9":"800",net=n==1?"451.1":"431.9";
      for(var row:weights) {
        assertThat((BigDecimal)row.get("gross_weight_g")).isEqualByComparingTo(gross);
        assertThat((BigDecimal)row.get("net_weight_g")).isEqualByComparingTo(net);
        assertThat((BigDecimal)row.get("cost_price")).isEqualByComparingTo(List.of("46.511","36.511","36.319","50").get(n));
      }
      evidence.add(Map.of("product",products.get(n),"weights",weights));
    }
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"samples",evidence)));
  }

  private void manufacturing(String product,String parent,int scenario,LocalDateTime date,
      String month,OrdinaryCostingTestSources sources) {
    String raw=parent+"-RAW",scrap=parent+"-SCRAP";
    String unit=scenario==0||scenario==3?"只":"KG",quantity=scenario==0||scenario==3?"1":"0.8";
    jdbc.update("UPDATE lp_material_master_raw SET shape_attr='制造件',global_seg_3_theoretical_net_weight='431.9',global_seg_4_material='紫铜T2' WHERE material_code=? AND organization_code='COMMERCIAL'",parent);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET shape_attr='制造件',is_leaf=0 WHERE top_product_code=? AND material_code=?",product,parent);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,global_seg_4_material,global_seg_3_theoretical_net_weight) VALUES(?,?,?,'COMMERCIAL',?,1,'15','采购件','紫铜T2','999')",raw,raw,unit,product);
    var node=new com.sanhua.marketingcost.entity.BomRawHierarchy();
    node.setTopProductCode(product);node.setParentCode(parent);node.setMaterialCode(raw);node.setMaterialName(raw);
    node.setPath("/"+product+"/"+parent+"/"+raw+"/");node.setLevel(2);node.setSortSeq(2);
    node.setQtyPerParent(new BigDecimal(quantity));node.setQtyPerTop(new BigDecimal(quantity).multiply(new BigDecimal("2.5")));
    node.setShapeAttr("采购件");node.setBomPurpose("主制造");node.setBomVersion("MPP12");node.setIsLeaf(1);node.setU9IsCostFlag(1);
    node.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1));node.setEffectiveTo(LocalDate.of(2099,1,1));node.setBomStatus("已核准");
    node.setSourceType("U9");node.setSourceImportBatchId(product);node.setBuildBatchId(product);node.setBuiltAt(date);node.setPriceOrgCode("210");node.setBusinessUnitType("COMMERCIAL");hierarchy.insert(node);
    jdbc.update("INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type) VALUES(?,'U9',NOW(),'210',?,?,?,?,'主制造','MPP12',2,?,1,?,'2099-01-01','NORMAL')",product,parent,raw,raw,unit,new BigDecimal(quantity),date.toLocalDate().withDayOfMonth(1));
    sources.comparisonFixedPrice(raw,"50",date);jdbc.update("UPDATE lp_price_fixed_item SET unit=? WHERE material_code=?",unit,raw);
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL',?,?,'铜废料','FIXED',10,'{}','IMPORT',?,?,'WEIGHT_TEST',NOW(),'WEIGHT_TEST',NOW())",month,scrap,month,"a".repeat(64));
    if(scenario==3) {
      jdbc.update("INSERT INTO lp_make_part_no_scrap_confirmation(business_unit_type,material_no,effective_from_month,status,confirm_reason,confirmed_by,confirmed_at) VALUES('COMMERCIAL',?,?,'ACTIVE','原有人工确认无废料回归','TEST',NOW())",raw,month);
    } else {
      jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,scrap_unit,ratio,business_unit_type) VALUES(?,?,'KG',1,'COMMERCIAL')",raw,scrap);
      jdbc.update("INSERT INTO lp_u9_bom_byproduct_master(price_org_code,parent_material_no,bom_purpose,version_no,output_type,byproduct_material_no,output_qty,unit,status,effective_from,effective_to) VALUES('210',?,'主制造','MPP12','副产品',?,0.3489,'kg','已核准',?,'2099-01-01')",parent,scenario==2?scrap+"-OTHER":scrap,date.toLocalDate().withDayOfMonth(1));
    }
    var rule=new com.sanhua.marketingcost.entity.BomSettlementRule();
    rule.setRuleCode(product);rule.setRuleName(product);rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");rule.setSubRefType("SPECIAL_ROLLUP_CHILD");
    rule.setMatchConditionJson("{\"nodeConditions\":[{\"field\":\"material_code\",\"op\":\"EQ\",\"value\":\""+raw+"\"}]}");
    rule.setMarkSubtreeCostRequired(1);rule.setPriority(1);rule.setEnabled(1);rule.setDeleted(0);rule.setBusinessUnitType("COMMERCIAL");
    settlementRules.insert(rule);
  }
}
