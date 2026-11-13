package com.sanhua.marketingcost.db;
import java.math.BigDecimal;
final class QuoteOrderMaterialShapeTestData {
  static MaterialShapeSwitchTestData.Sample sharedConsumer(org.springframework.jdbc.core.JdbcTemplate jdbc, com.sanhua.marketingcost.mapper.BomRawHierarchyMapper hierarchy, com.sanhua.marketingcost.mapper.BomSettlementRuleMapper rules,MaterialShapeSwitchTestData.Sample shared,boolean sameOrder,String quantity) {
    var own=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    long form=jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?",Long.class,shared.itemId());
    if(sameOrder) jdbc.update("UPDATE oa_form_item SET oa_form_id=?,seq=id WHERE id=?",form,own.itemId());
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET material_code=CASE material_code WHEN ? THEN ? WHEN ? THEN ? ELSE material_code END,parent_code=CASE parent_code WHEN ? THEN ? ELSE parent_code END,path=REPLACE(REPLACE(path,?,?),?,?),qty_per_top=CASE WHEN level>0 THEN ? ELSE qty_per_top END,qty_per_parent=CASE WHEN level=1 THEN ? ELSE qty_per_parent END WHERE top_product_code=? AND material_code IN (?,?)",
      own.parent(),shared.parent(),own.raw(),shared.raw(),own.parent(),shared.parent(),own.parent(),shared.parent(),own.raw(),shared.raw(),new BigDecimal(quantity),new BigDecimal(quantity),own.product(),own.parent(),own.raw());
    jdbc.update("DELETE FROM lp_bom_u9_source WHERE import_batch_id=? AND parent_material_no=?",own.product(),own.parent());
    jdbc.update("UPDATE lp_bom_u9_source SET parent_material_no=CASE parent_material_no WHEN ? THEN ? ELSE parent_material_no END,child_material_no=CASE child_material_no WHEN ? THEN ? WHEN ? THEN ? ELSE child_material_no END WHERE import_batch_id=?",
      own.parent(),shared.parent(),own.parent(),shared.parent(),own.raw(),shared.raw(),own.product());
    jdbc.update("UPDATE cms_workshop_labor_raw SET path=REPLACE(path,?,?) WHERE parent_code=?",own.parent(),shared.parent(),own.product());
    jdbc.update("UPDATE cms_product_subject_cost_raw SET path=REPLACE(path,?,?) WHERE parent_code=?",own.parent(),shared.parent(),own.product());
    return new MaterialShapeSwitchTestData.Sample(sameOrder?shared.oaNo():own.oaNo(),own.itemId(),own.product(),shared.parent(),shared.raw(),shared.scrap(),own.month());
  }
}
