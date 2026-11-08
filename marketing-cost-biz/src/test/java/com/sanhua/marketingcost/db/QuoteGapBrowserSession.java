package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowClient;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Actual API/MySQL and two real browser calculations; only outbound OA transport is controlled. */
@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15218",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "integration.oa-person-directory.source-system=HZ_KG_OA_TST", "integration.oa-person-directory.environment=TEST",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class QuoteGapBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper rawHierarchy;
  @Autowired com.sanhua.marketingcost.mapper.BomSettlementRuleMapper settlementRules;
  @MockBean OaWorkflowClient oa;

  @org.springframework.test.context.DynamicPropertySource
  static void pricingPeriod(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state=Path.of(System.getProperty("quote.gap.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    String product="SAVED-"+UUID.randomUUID().toString().substring(0,8), material=product+"-PART";
    jdbc.update("INSERT INTO oa_form(oa_no,process_code,business_unit_type,copper_price,accounting_period_month) VALUES(?,'FI-SC-006','COMMERCIAL',80000,?)",product,month);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,product);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,business_type,shipping_fee,business_unit_type) VALUES(?,1,?,?,'批量品',0,'COMMERCIAL')",form,product,"已保存成本提交验收");
    long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    jdbc.update("INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id,oa_form_id,source_version) VALUES('HZ_KG_OA_TST','TEST',?,?,1)",product,form);
    jdbc.update("INSERT INTO lp_oa_form_item_extra_field(oa_form_id,oa_form_item_id,field_code,field_name,field_value,business_unit_type) VALUES(?,?,'OA_ROW_ID','原明细行','SAVED-ROW','COMMERCIAL')",form,item);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    var sources=new OrdinaryCostingTestSources(jdbc,rawHierarchy);
    sources.ordinarySources(product,material,form,date);
    if(Boolean.getBoolean("quote.gap.browser.manufacturing")) manufacturing(product,material,date,month,sources);
    else sources.comparisonFixedPrice(material,"2",date);
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
    jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
    var gaps = new ArrayList<Map<String,Object>>();
    for (String scenario : List.of("RATIO", "PRICE")) {
      String gp="GAP-"+UUID.randomUUID().toString().substring(0,8), gm=gp+"-PART";
      jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')",gp);
      long gf=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,gp);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,business_type,shipping_fee,business_unit_type) VALUES(?,1,?,?,'批量品',0,'COMMERCIAL')",gf,gp,"缺口回归"+scenario);
      long gi=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,gf);
      sources.ordinarySources(gp,gm,gf,date);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",gp+"-BARE",gp);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",gp+"-BARE",gp+"-BARE",gp);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",gp+"-BARE",gp);
      if (scenario.equals("RATIO")) {
        manufacturing(gp,gm,date,month,sources);gm += "-RAW";
        jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('210',?,?,'KG',12,0,'GAP-S2',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",gm,gm,date.toLocalDate());
      }
      gaps.add(Map.of("scenario",scenario,"oaNo",gp,"itemId",gi,"material",gm));
    }
    // Current U9 contract requires explicit standard/alternative facts in new fixtures.
    for (String code : java.util.stream.Stream.concat(java.util.stream.Stream.of(product), gaps.stream().map(g -> g.get("oaNo").toString())).toList()) {
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",code);
      jdbc.update("UPDATE lp_bom_u9_source SET child_type='标准' WHERE parent_material_no=?",code+"-PART");
    }
    String username="saved_"+UUID.randomUUID().toString().substring(0,8), password="Saved-browser-test-2026";
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,employee_no,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'保存成本验收',?,'SAVED001','COMMERCIAL','0','0','SAVED_TEST',NOW(),'SAVED_TEST',NOW())",username,new BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    when(oa.submit(any())).thenReturn(new OaWorkflowResult("saved-test",OaWorkflowResult.Status.SUCCESS,200,"0","success",product,null,1));
    var json=new ObjectMapper();
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(Map.of("apiPort",port,"username",username,"password",password,"oaNo",product,"itemId",item,"material",material,"month",month,"gaps",gaps)));
    Path change=Path.of(state+".change-rates"),release=Path.of(state+".release");
    Map<String,Object> first=null;
    long deadline=System.nanoTime()+Duration.ofMinutes(15).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline) {
      if(first==null&&Files.exists(change)) {
        first=jdbc.queryForMap("SELECT id,cost_run_no,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'",item);
        jdbc.update("UPDATE lp_quality_loss_rate SET loss_rate=0.02 WHERE bare_product_code=? AND business_unit_type='COMMERCIAL'",product+"-BARE");
        Files.writeString(Path.of(state+".rates-changed"),json.writeValueAsString(first));
      }
      Thread.sleep(200);
    }
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    for (var g : gaps) {
      var row=jdbc.queryForMap("SELECT workspace_status,last_error_code,last_error_message FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",g.get("itemId"));
      assertThat(row.get("workspace_status")).isEqualTo("WAIT_PRICE");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,g.get("itemId"))).isZero();
      if (g.get("scenario").equals("RATIO")) assertThat(row.get("last_error_code")).isEqualTo("SUPPLIER_RATIO_REVIEW_REQUIRED");
    }
    assertThat(first).isNotNull();
    var latest=jdbc.queryForMap("SELECT id,cost_run_no,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'",item);
    assertThat(latest.get("id")).isNotEqualTo(first.get("id"));
    assertThat((BigDecimal)latest.get("final_quote_amount")).isNotEqualByComparingTo((BigDecimal)first.get("final_quote_amount"));
    assertThat(jdbc.queryForMap("SELECT id,cost_run_no,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE id=?",first.get("id"))).isEqualTo(first);
    var body=ArgumentCaptor.forClass(ObjectNode.class);verify(oa,times(1)).submit(body.capture());
    assertThat(new BigDecimal(body.getValue().at("/formData/dataDetails/0/content").asText())).isEqualByComparingTo((BigDecimal)latest.get("final_quote_amount"));
    assertThat(jdbc.queryForObject("SELECT status FROM lp_quote_final_submission WHERE oa_form_id=?",String.class,form)).isEqualTo("SUBMITTED");
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"first",first,"latest",latest,"oldCostPreserved",true,"oaTransport","controlled","oaCalls",1)));
  }

  private void manufacturing(String product,String parent,LocalDateTime date,String month,OrdinaryCostingTestSources sources) {
    String raw=parent+"-RAW",scrap=parent+"-SCRAP";
    jdbc.update("UPDATE lp_material_master_raw SET shape_attr='制造件',global_seg_3_theoretical_net_weight='100',global_seg_4_material='紫铜T2' WHERE material_code=? AND organization_code='COMMERCIAL'",parent);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET shape_attr='制造件',is_leaf=0 WHERE top_product_code=? AND material_code=?",product,parent);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,global_seg_4_material) VALUES(?,?,'KG','COMMERCIAL',?,1,'15','采购件','紫铜T2')",raw,raw,product);
    var node=new com.sanhua.marketingcost.entity.BomRawHierarchy();
    node.setTopProductCode(product);node.setParentCode(parent);node.setMaterialCode(raw);node.setMaterialName(raw);
    node.setPath("/"+product+"/"+parent+"/"+raw+"/");node.setLevel(2);node.setSortSeq(2);
    node.setQtyPerParent(new BigDecimal("0.12"));node.setQtyPerTop(new BigDecimal("0.30"));
    node.setShapeAttr("采购件");node.setBomPurpose("主制造");node.setBomVersion("MPP12");node.setIsLeaf(1);node.setU9IsCostFlag(1);
    node.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1));node.setEffectiveTo(LocalDate.of(2099,1,1));node.setBomStatus("已核准");
    node.setSourceType("U9");node.setSourceImportBatchId(product);node.setBuildBatchId(product);node.setBuiltAt(date);node.setPriceOrgCode("210");node.setBusinessUnitType("COMMERCIAL");rawHierarchy.insert(node);
    jdbc.update("INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to) VALUES(?,'U9',NOW(),'210',?,?,?,'KG','主制造','MPP12',1,0.12,1,?,'2099-01-01')",product,parent,raw,raw,date.toLocalDate().withDayOfMonth(1));
    sources.comparisonFixedPrice(raw,"10",date);jdbc.update("UPDATE lp_price_fixed_item SET unit='KG' WHERE material_code=?",raw);
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL',?,?,'铜废料','FIXED',2,'{}','IMPORT',?,?,'JJB_TEST',NOW(),'JJB_TEST',NOW())",month,scrap,month,"a".repeat(64));
    jdbc.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,scrap_unit,ratio,business_unit_type) VALUES(?,?,'KG',1,'COMMERCIAL')",raw,scrap);
    var rule=new com.sanhua.marketingcost.entity.BomSettlementRule();
    rule.setRuleCode(product);rule.setRuleName(product);rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");rule.setSubRefType("SPECIAL_ROLLUP_CHILD");
    rule.setMatchConditionJson("{\"nodeConditions\":[{\"field\":\"material_code\",\"op\":\"EQ\",\"value\":\""+raw+"\"}]}");
    rule.setMarkSubtreeCostRequired(1);rule.setPriority(1);rule.setEnabled(1);rule.setDeleted(0);rule.setBusinessUnitType("COMMERCIAL");
    settlementRules.insert(rule);
  }
}
