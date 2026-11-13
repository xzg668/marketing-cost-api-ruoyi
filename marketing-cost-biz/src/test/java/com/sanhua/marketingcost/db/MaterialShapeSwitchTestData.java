package com.sanhua.marketingcost.db;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceTestData;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

/** Owned data in disposable MySQL only. Prices: make 9, purchase 20, outsource 3+10-1=12. */
final class MaterialShapeSwitchTestData {
  record Sample(String oaNo,long itemId,String product,String parent,String raw,String scrap,String month) {}
  static Sample create(JdbcTemplate j,BomRawHierarchyMapper hierarchy,BomSettlementRuleMapper rules) {
    MaterialPriceSourceTestData.schema(j);
    new ResourceDatabasePopulator(new ClassPathResource("db/V380__quotation_material_shape_selection.sql")).execute(j.getDataSource());
    String p="SHAPE-"+UUID.randomUUID().toString().substring(0,8),a=p+"-A",b=p+"-B",s=p+"-S";
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    j.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",p);
    long form=j.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,p);
    j.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,business_type,shipping_fee,business_unit_type) VALUES(?,1,?,'多形态切换验收','批量品',0,'COMMERCIAL')",form,p);
    long item=j.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    var ordinary=new OrdinaryCostingTestSources(j,hierarchy);ordinary.ordinarySources(p,a,form,date);
    j.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    j.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",p+"-BARE",p);
    j.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",p+"-BARE",p+"-BARE",p);
    j.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",p+"-BARE",p);
    j.update("UPDATE lp_material_master_raw SET material_name='切换母件A',shape_attr='制造件',global_seg_3_theoretical_net_weight='250',global_seg_4_material='紫铜T2' WHERE material_code=?",a);
    j.update("UPDATE lp_bom_raw_hierarchy SET material_name='切换母件A',shape_attr='制造件',is_leaf=0 WHERE top_product_code=? AND material_code=?",p,a);
    j.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,global_seg_4_material) VALUES(?,'采购材料B','只','COMMERCIAL',?,1,'15','采购件','紫铜T2')",b,p);
    var raw=new BomRawHierarchy();raw.setTopProductCode(p);raw.setParentCode(a);raw.setMaterialCode(b);raw.setMaterialName("采购材料B");
    raw.setPath("/"+p+"/"+a+"/"+b+"/");raw.setLevel(2);raw.setSortSeq(2);raw.setQtyPerParent(BigDecimal.ONE);raw.setQtyPerTop(new BigDecimal("2.5"));
    raw.setShapeAttr("采购件");raw.setBomPurpose("主制造");raw.setBomVersion("MPP12");raw.setIsLeaf(1);raw.setU9IsCostFlag(1);
    raw.setEffectiveFrom(date.toLocalDate());raw.setEffectiveTo(LocalDate.of(2099,1,1));raw.setBomStatus("已核准");raw.setSourceType("U9");
    raw.setSourceImportBatchId(p);raw.setBuildBatchId(p);raw.setBuiltAt(date);raw.setPriceOrgCode("210");raw.setBusinessUnitType("COMMERCIAL");hierarchy.insert(raw);
    j.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",p);
    j.update("""
        INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,
          child_material_name,stock_unit,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type)
        SELECT r.source_import_batch_id,'U9',NOW(),r.price_org_code,r.parent_code,r.material_code,r.material_name,
          CASE WHEN r.material_code=? THEN '只' ELSE '件' END,r.bom_purpose,r.bom_version,r.sort_seq,r.qty_per_parent,1,
          r.effective_from,r.effective_to,'NORMAL' FROM lp_bom_raw_hierarchy r WHERE r.top_product_code=? AND r.level>0
        """,b,p);
    ordinary.comparisonFixedPrice(a,"20",date);
    for(var pair:List.of(new String[]{a,"3.39"},new String[]{b,"11.3"}))
      j.update("""
          INSERT INTO lp_price_linked_item(pricing_month,org_code,business_unit_type,material_code,material_name,unit,
            supplier_code,supplier_name,formula_expr,net_weight,tax_included,effective_from,effective_to,source_kind,
            rule_status,deleted,expression_version,created_at,updated_at)
          VALUES(?,'210','COMMERCIAL',?,?,?,'MRP11-S1','形态验收供应商',?,?,0,?,'2099-01-01','PUBLIC','LEGACY',0,'LEGACY',NOW(),NOW())
          """,month,pair[0],pair[0],pair[0].equals(b)?"只":"件",pair[1],pair[0].equals(b)?new BigDecimal("300"):null,date.toLocalDate());
    j.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) VALUES('COMMERCIAL',?,?,'铜废料','FIXED',20,'{}','IMPORT',?,?,'SHAPE_TEST',NOW(),'SHAPE_TEST',NOW())",month,s,month,"c".repeat(64));
    j.update("INSERT INTO lp_material_scrap_ref(material_code,scrap_code,scrap_unit,ratio,business_unit_type) VALUES(?,?,'KG',1,'COMMERCIAL')",b,s);
    j.update("INSERT INTO lp_u9_bom_byproduct_master(price_org_code,parent_material_no,bom_purpose,version_no,output_type,byproduct_material_no,output_qty,unit,status,effective_from,effective_to) VALUES('210',?,'主制造','MPP12','副产品',?,0.05,'kg','已核准',?,'2099-01-01')",a,s,date.toLocalDate());
    var rule=new BomSettlementRule();rule.setRuleCode(p);rule.setRuleName(p);rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");rule.setSubRefType("SPECIAL_ROLLUP_CHILD");
    rule.setMatchConditionJson("{\"nodeConditions\":[{\"field\":\"material_code\",\"op\":\"EQ\",\"value\":\""+b+"\"}]}");
    rule.setMarkSubtreeCostRequired(1);rule.setPriority(1);rule.setEnabled(1);rule.setDeleted(0);rule.setBusinessUnitType("COMMERCIAL");rules.insert(rule);
    cms(j,p,a,month,date.getYear());
    return new Sample(p,item,p,a,b,s,month);
  }
  private static void cms(JdbcTemplate j,String p,String a,String month,int year) {
    String own=p+"(×1)→"+a+"(×2.5)→WORK(自身)(20)",other=p+"(×1)→OTHER(×1)→WORK(自身)(10)";
    j.update("INSERT INTO cms_workshop_labor_raw(import_batch_id,row_no,period,parent_code,parent_name,working_cost_cent,working_cost_yuan,working_hours,path,source_row_id,business_unit_type) VALUES(1,1,?,?,?,300,3,30,?,UUID(),'COMMERCIAL')",month,p,p,own+";"+other);
    long direct=j.queryForObject("SELECT id FROM cms_workshop_labor_raw WHERE parent_code=?",Long.class,p);
    j.update("UPDATE cms_cost_source_effective SET source_table='cms_workshop_labor_raw',source_row_ids=?,amount_yuan=3 WHERE parent_code=? AND source_type='SALARY_DIRECT'",String.valueOf(direct),p);
    for(var pair:List.of(new String[]{"SALARY_INDIRECT","0205","100","100","2"},new String[]{"AUX_SUBJECT","0201","100","200","3"})) {
      String path=p+"(×1)→"+a+"(×2.5)→"+pair[1]+"(自身)("+pair[2]+");"+p+"(×1)→OTHER(×1)→"+pair[1]+"(自身)("+pair[3]+")";
      j.update("INSERT INTO cms_product_subject_cost_raw(import_batch_id,row_no,period,parent_code,parent_name,second_subject_code,material_price,material_price_yuan,path,source_row_id,business_unit_type) VALUES(1,1,?,?,?,?,?,?,?,UUID(),'COMMERCIAL')",month,p,p,pair[1],new BigDecimal(pair[4]).movePointRight(2),new BigDecimal(pair[4]),path);
      long id=j.queryForObject("SELECT id FROM cms_product_subject_cost_raw WHERE parent_code=? AND second_subject_code=?",Long.class,p,pair[1]);
      if(pair[0].equals("SALARY_INDIRECT")) j.update("UPDATE cms_cost_source_effective SET source_table='cms_product_subject_cost_raw',source_row_ids=?,amount_yuan=2 WHERE parent_code=? AND source_type='SALARY_INDIRECT'",String.valueOf(id),p);
      else j.update("INSERT INTO cms_cost_source_effective(cost_year,source_type,parent_code,period,subject_code,subject_name,source_table,source_row_ids,amount_yuan,business_unit_type) VALUES(?,'AUX_SUBJECT',?,?,'0201','工艺辅料','cms_product_subject_cost_raw',?,3,'COMMERCIAL')",year,p,month,String.valueOf(id));
    }
  }
}
