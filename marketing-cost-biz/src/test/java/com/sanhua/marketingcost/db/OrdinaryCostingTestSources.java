package com.sanhua.marketingcost.db;

import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/** Disposable ordinary-cost sources shared by browser and performance acceptance. */
final class OrdinaryCostingTestSources {
  private final JdbcTemplate jdbc;
  private final BomRawHierarchyMapper rawHierarchy;
  private final String unit="COMMERCIAL",priceOrg="210",materialOrg="COMMERCIAL";
  OrdinaryCostingTestSources(JdbcTemplate jdbc,BomRawHierarchyMapper rawHierarchy) {
    this.jdbc=jdbc;this.rawHierarchy=rawHierarchy;
  }
  void comparisonFixedPrice(String material,String price,LocalDateTime date) {
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('"+priceOrg+"',?,?,'件',?,0,'MRP11-S1',?,'2099-01-01','"+unit+"','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",material,material,new BigDecimal(price),date.toLocalDate().withDayOfMonth(1));
  }

  void ordinarySources(String product, String material, long formId, LocalDateTime date) {
    String division = "MPP12-" + product;
    jdbc.update("UPDATE oa_form SET process_code='FI-SC-005',accounting_period_month=?,source_business_division=?,applicant_dept=?,expense_product_category='商用直销产品' WHERE id=?",
        YearMonth.from(date).toString(), division, division, formId);
    for (String code : List.of(product, material))
      jdbc.update("INSERT IGNORE INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,production_division,product_attr) VALUES(?,?,'件','"+materialOrg+"',?,1,?, ?,?,'标准品')",
          code, code, product, code.equals(product) ? "11" : "15", code.equals(product) ? "制造件" : "采购件", division);
    for (int level = 0; level < 2; level++) {
      var raw = new com.sanhua.marketingcost.entity.BomRawHierarchy();
      String code = level == 0 ? product : material;
      raw.setTopProductCode(product); raw.setParentCode(product); raw.setMaterialCode(code); raw.setMaterialName(code);
      raw.setPath("/" + product + "/" + (level == 0 ? "" : material + "/")); raw.setLevel(level); raw.setSortSeq(level);
      raw.setQtyPerParent(new BigDecimal(level == 0 ? "1" : "2.5")); raw.setQtyPerTop(raw.getQtyPerParent());
      raw.setShapeAttr(level == 0 ? "制造件" : "采购件"); raw.setBomPurpose("主制造"); raw.setBomVersion("MPP12");
      raw.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1)); raw.setEffectiveTo(java.time.LocalDate.of(2099, 1, 1)); raw.setBomStatus("已核准");
      raw.setIsLeaf(level); raw.setU9IsCostFlag(1); raw.setSourceType("U9"); raw.setSourceImportBatchId(product);
      raw.setBuildBatchId(product); raw.setBuiltAt(date); raw.setPriceOrgCode(priceOrg); raw.setBusinessUnitType(unit); rawHierarchy.insert(raw);
    }
    zeroPackaging(product, date);
    ordinaryExpenseSources(product, formId, date);
  }

  private void zeroPackaging(String product, LocalDateTime date) {
    // The public BOM explicitly supplies a packaging relation with zero consumption.
    // It confirms the structure without adopting unused packaging material prices.
    String packageCode = product + "-ZERO-PACK", packageChild = product + "-ZERO-CHILD";
    for (int index = 0; index < 2; index++) {
      String code = index == 0 ? packageCode : packageChild;
      jdbc.update("INSERT IGNORE INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,main_category_name,shape_attr) VALUES(?,?,'件','"+materialOrg+"',?,1,?,?,?)",
          code, code, product, index == 0 ? "15155" : "15", index == 0 ? "包装组件" : "普通物料", index == 0 ? "虚拟" : "采购件");
      var raw = new com.sanhua.marketingcost.entity.BomRawHierarchy();
      raw.setTopProductCode(product); raw.setParentCode(index == 0 ? product : packageCode);
      raw.setMaterialCode(code); raw.setMaterialName(code);
      raw.setPath("/" + product + "/" + packageCode + "/" + (index == 0 ? "" : packageChild + "/"));
      raw.setLevel(index + 1); raw.setSortSeq(index + 2); raw.setQtyPerParent(index == 0 ? BigDecimal.ZERO : BigDecimal.ONE);
      raw.setQtyPerTop(BigDecimal.ZERO); raw.setShapeAttr(index == 0 ? "虚拟" : "采购件");
      raw.setBomPurpose("主制造"); raw.setBomVersion("MPP12"); raw.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1)); raw.setEffectiveTo(java.time.LocalDate.of(2099, 1, 1));
      raw.setBomStatus("已核准"); raw.setIsLeaf(index); raw.setU9IsCostFlag(1); raw.setSourceType("U9");
      raw.setSourceImportBatchId(product); raw.setBuildBatchId(product); raw.setBuiltAt(date);
      raw.setPriceOrgCode(priceOrg); raw.setBusinessUnitType(unit); rawHierarchy.insert(raw);
    }
  }

  private void ordinaryExpenseSources(String product, long formId, LocalDateTime date) {
    String division = "MPP12-" + product;
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='11',production_division=?,product_attr='标准品' WHERE material_code=?", division, product);
    jdbc.update("UPDATE oa_form SET process_code='FI-SC-005',accounting_period_month=?,source_business_division=?,applicant_dept=?,expense_product_category='商用直销产品' WHERE id=?", YearMonth.from(date).toString(), division, division, formId);
    int year = date.getYear();
    for(String subject:List.of("水电费用","工装零星费用","大修费用"))
      jdbc.update("INSERT INTO lp_department_fund_rate(business_unit,business_unit_type,rate_year,business_division,expense_subject,quote_ratio,uplift_ratio,manhour_rate,rate_calculation_mode) VALUES(?,?,?,?,?,0.01,1,1,'FINAL_QUOTE')",division,unit,year,division,subject);
    for (String type : List.of("SALARY_DIRECT", "SALARY_INDIRECT"))
      jdbc.update("INSERT INTO cms_cost_source_effective(cost_year,source_type,parent_code,period,subject_code,source_table,source_row_ids,amount_yuan,business_unit_type) VALUES(?,?,?,?,?,'MPP12-controlled','[]',?,'"+unit+"')",
          year, type, product, YearMonth.from(date).toString(), type, type.equals("SALARY_DIRECT") ? 1 : 2);
    jdbc.update("INSERT INTO lp_quality_loss_rate(bare_product_code,rate_year,business_unit_type,loss_rate) VALUES(?,?,'"+unit+"',0.01)", product, year);
    jdbc.update("INSERT INTO lp_manufacture_rate(company,business_unit,product_category,product_subcategory,fee_rate,period,rate_year,business_unit_type,product_code,match_level,match_key) VALUES(?,'"+unit+"',?,?,0.1,?,?,'"+unit+"',?,'MATERIAL_CODE',?)",
        product, product, product, YearMonth.from(date).toString(), year, product, product);
    jdbc.update("INSERT INTO lp_three_expense_rate(company,business_unit,department,management_expense_rate,finance_expense_rate,sales_expense_rate,three_expense_rate_2025,three_expense_rate_2026,period,period_year,business_unit_type,product_category,product_line,applicant_department) VALUES(?,'"+unit+"',?,0.1,0.03,0.2,0.33,0.33,?,?,'"+unit+"','商用直销产品','国内产线',?)",
        product, division, YearMonth.from(date).toString(), year, division);
  }

}
