package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpMethod;

/** Tests the actual order selector and saved costs, including a manufactured blank below the base. */
@Tag("integration")
class SinteredOutsourceGoldIntegrationTest extends QuoteOrderMaterialShapeIntegrationTest {
  static final String GOLD="311034930";
  MaterialShapeSwitchTestData.Sample assembly() {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
        new org.springframework.core.io.ClassPathResource("db/V367__sintered_base_processing_fee.sql"))
        .execute(jdbc.getDataSource());
    jdbc.update("UPDATE lp_material_master_raw SET material_name='烧结基座' WHERE material_code=?",s.parent());
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET material_name='烧结基座' WHERE top_product_code=? AND material_code=?",s.product(),s.parent());
    jdbc.update("UPDATE lp_bom_u9_source SET child_material_name='烧结基座' WHERE import_batch_id=? AND child_material_no=?",s.product(),s.parent());
    jdbc.update("UPDATE lp_material_master_raw SET material_name='烧结基座毛坯',shape_attr='制造件' WHERE material_code=?",s.raw());
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET material_name='烧结基座毛坯',shape_attr='制造件',is_leaf=0 WHERE top_product_code=? AND material_code=?",s.product(),s.raw());
    jdbc.update("DELETE FROM lp_bom_settlement_rule WHERE rule_code=?",s.product());
    var date=YearMonth.parse(s.month()).atDay(1).atTime(12,0);
    String c=s.raw()+"-C",d=s.raw()+"-D";
    addChild(s,c,s.raw(),3,"1.2",31,"2",date);
    addChild(s,d,s.raw(),3,"0.4",32,"3.5",date);
    addChild(s,GOLD,s.parent(),2,"0.0036",41,"100",date);
    addChild(s,GOLD,s.product(),1,"0.001",42,"100",date);
    jdbc.update("INSERT INTO lp_material_quote_shape_policy(material_org_code,material_code,material_name,policy_mode,action_config_json,effective_from_month,enabled) VALUES('COMMERCIAL',?,'烧结基座','SUPPLIER_RATIO',?,?,1)",s.parent(),"{\"excludedDirectChildMaterialCodes\":[\""+GOLD+"\"]}",s.month());
    // The retained cost belongs to the manufactured blank under this same base, not another branch.
    String blank=s.parent()+"(×2.5)→"+s.raw()+"(×1)";
    jdbc.update("UPDATE cms_workshop_labor_raw SET path=REPLACE(path,'OTHER(×1)',?) WHERE parent_code=?",blank,s.product());
    jdbc.update("UPDATE cms_product_subject_cost_raw SET path=REPLACE(path,'OTHER(×1)',?) WHERE parent_code=?",blank,s.product());
    return s;
  }
  private void addChild(MaterialShapeSwitchTestData.Sample s,String code,String parent,int level,String quantity,int seq,String price,LocalDateTime date) {
    var raw=new BomRawHierarchy();raw.setTopProductCode(s.product());raw.setParentCode(parent);raw.setMaterialCode(code);
    raw.setMaterialName(code.equals(GOLD)?"氰化亚金钾(金盐)":code);raw.setLevel(level);raw.setSortSeq(seq);
    String path="/"+s.product()+"/"+(level>1?s.parent()+"/":"")+(level>2?s.raw()+"/":"")+code+"/";
    raw.setPath(path);raw.setQtyPerParent(new BigDecimal(quantity));raw.setQtyPerTop(new BigDecimal(quantity).multiply(level>1?new BigDecimal("2.5"):BigDecimal.ONE));
    raw.setShapeAttr("采购件");raw.setIsLeaf(1);raw.setU9IsCostFlag(1);raw.setBomPurpose("主制造");raw.setBomVersion("MPP12");
    raw.setEffectiveFrom(date.toLocalDate());raw.setEffectiveTo(LocalDate.of(2099,1,1));raw.setBomStatus("已核准");raw.setSourceType("U9");
    raw.setSourceImportBatchId(s.product());raw.setBuildBatchId(s.product());raw.setBuiltAt(date);raw.setPriceOrgCode("210");raw.setBusinessUnitType("COMMERCIAL");raw.setChildType("NORMAL");hierarchy.insert(raw);
    if(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_master_raw WHERE material_code=?",Integer.class,code)==0) {
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'只','COMMERCIAL',?,1,'15','采购件')",code,raw.getMaterialName(),s.product());
      new OrdinaryCostingTestSources(jdbc,hierarchy).comparisonFixedPrice(code,price,date);
    }
    jdbc.update("INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,child_material_name,stock_unit,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type) VALUES(?,'U9',NOW(),'210',?,?,?,'只','主制造','MPP12',?,?,1,?,'2099-01-01','NORMAL')",s.product(),parent,code,raw.getMaterialName(),seq,new BigDecimal(quantity),date.toLocalDate());
  }
  @Test void manufactureOutsourceRestoreKeepsBlankCostsAndOnlyConfiguredGoldChanges() throws Exception {
    var s=assembly();var first=calculate(s);
    assertThat(first.path("pipelineStatus").asText()).as(first.toString()).isEqualTo("SUCCESS");
    long manufactured=latest(s);var original=bom(s);
    var goldBefore=part(manufactured,GOLD);assertThat(goldBefore).isEqualByComparingTo("1");
    var blankC=part(manufactured,s.raw()+"-C");var blankD=part(manufactured,s.raw()+"-D");
    assertThat(blankC).isPositive();assertThat(blankD).isPositive();
    collect(s);classifyPurchase(s);choose(s,"OUTSOURCE");
    var switched=bom(s);
    assertThat(switched.path("nodes")).anySatisfy(n->{
      assertThat(n.path("materialCode").asText()).isEqualTo(s.raw());
      assertThat(n.path("effectiveMaterialShape").asText()).isEqualTo("MANUFACTURE");
    });
    assertThat(switched.path("nodes")).filteredOn(n->GOLD.equals(n.path("materialCode").asText())).hasSize(1);
    var second=calculate(s);assertThat(second.path("pipelineStatus").asText()).as(second.toString()).isEqualTo("SUCCESS");
    long outsourced=latest(s);assertThat(outsourced).isNotEqualTo(manufactured);
    assertThat(part(outsourced,s.parent())).as("rows=%s; nodes=%s; rule=%s",
        jdbc.queryForList("SELECT material_code,material_name,shape_attr,settlement_row_type,path FROM lp_bom_costing_row WHERE oa_form_item_id=?",s.itemId()),
        switched.path("nodes"),jdbc.queryForList("SELECT rule_code,enabled,deleted,match_condition_json FROM lp_bom_settlement_rule WHERE rule_code='SINTERED_BASE_PROCESS_FEE'"))
        .isEqualByComparingTo("7.5");
    assertThat(part(outsourced,GOLD)).isEqualByComparingTo("0.1");
    assertThat(part(outsourced,s.raw()+"-C")).isEqualByComparingTo(blankC);
    assertThat(part(outsourced,s.raw()+"-D")).isEqualByComparingTo(blankD);
    assertThat(cost(outsourced,"DIRECT_LABOR")).isEqualByComparingTo("1");
    assertThat(cost(outsourced,"INDIRECT_LABOR")).isEqualByComparingTo("1");
    assertThat(cost(outsourced,"AUX_0201")).isEqualByComparingTo("2.1");
    assertThat(calculate(s).path("reusedSuccess").asBoolean()).isTrue();
    assertThat(latest(s)).isEqualTo(outsourced);
    var saved=call(HttpMethod.GET,base(s)+"/cost-run?periodMonth="+s.month()+"&versionId="+outsourced,null);
    assertThat(saved.path("sheet").path("rows").toString()).contains(s.raw()+"-C",s.raw()+"-D","委外加工费");
    choose(s,"MANUFACTURE");var third=calculate(s);
    assertThat(third.path("pipelineStatus").asText()).as(third.toString()).isEqualTo("SUCCESS");
    long restored=latest(s);assertThat(part(restored,GOLD)).isEqualByComparingTo(goldBefore);
    assertThat(cost(restored,"DIRECT_LABOR")).isEqualByComparingTo("3");
    assertThat(cost(restored,"INDIRECT_LABOR")).isEqualByComparingTo("2");
    assertThat(part(manufactured,GOLD)).isEqualByComparingTo(goldBefore);
    assertThat(part(outsourced,GOLD)).isEqualByComparingTo("0.1");
    assertThat(original.path("nodes")).filteredOn(n->GOLD.equals(n.path("materialCode").asText())).hasSize(2);
  }
}
