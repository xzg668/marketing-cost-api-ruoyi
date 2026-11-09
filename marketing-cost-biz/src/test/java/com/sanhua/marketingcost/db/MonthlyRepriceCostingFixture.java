package com.sanhua.marketingcost.db;
import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** Real upstream fixtures shared by monthly transition and full customer-coverage acceptance. */
abstract class MonthlyRepriceCostingFixture extends OriginalPricingIntegrationTestBase {
 @Autowired JdbcTemplate jdbc;
 @Autowired com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway monthlyU9;
 @Autowired BomRawHierarchyMapper rawHierarchy;
 @Autowired QuoteEffectiveBomNodeMapper effectiveNodes;
 @Autowired QuoteBomPreparationRecordMapper preparationRecords;
 @Autowired QuoteProductBomCostingBuildService costingBoms;
 @Autowired BomCostingRowMapper bomRows;
 @Autowired ProductCostingPipeline pipeline;
 @Autowired OaFormMapper oaForms;
 @Autowired OaFormItemMapper oaItems;
 @Autowired QuotePricingPeriod pricingPeriods;
 @Autowired com.sanhua.marketingcost.config.MonthlyRepriceProperties repriceProperties;
 @Autowired com.sanhua.marketingcost.service.reprice.MonthlyRepriceContractService contracts;
 @Autowired MonthlyRepriceContractMapper repriceMapper;
 @Autowired com.sanhua.marketingcost.service.reprice.MonthlyRepriceCostingService repricing;
 @Autowired com.sanhua.marketingcost.service.reprice.MonthlyRepriceCheckService checks;
 @Autowired com.sanhua.marketingcost.service.reprice.MonthlyRepriceWorkflowService workflow;
 @Autowired com.sanhua.marketingcost.service.reprice.MonthlyRepriceDispatchService dispatch;
 String unit,priceOrg,materialOrg;
 @org.junit.jupiter.api.BeforeEach void existingAuditSchema() {
   new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
       new org.springframework.core.io.ClassPathResource("monthly-reprice/audit-schema.sql")).execute(jdbc.getDataSource());
 }
 protected MonthlyRepriceBatch retryFailed(String no) {
   var b=repriceMapper.readBatch(no);
   var ids=repriceMapper.tasks(no).stream().filter(t->"FAILED".equals(t.getStatus())).map(CostRunTask::getId).toList();
   var sources=jdbc.queryForList("SELECT id FROM lp_monthly_reprice_source WHERE batch_id=? AND object_id IS NULL",Long.class,b.getId());
   return ids.isEmpty() && sources.isEmpty()?contracts.recheck(no):contracts.retry(no,new com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.RetrySelection(ids,sources));
 }
 protected CostRunTask claimTask(String no, long id) {
   var task=dispatch.claim("reprice-fixture-"+UUID.randomUUID());
   assertThat(task).isNotNull();
   assertThat(task.getSourceNo()).isEqualTo(no); assertThat(task.getId()).isEqualTo(id);
   return task;
 }

  protected void comparisonFixedPrice(String material,String price,LocalDateTime date) {
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('"+priceOrg+"',?,?,'件',?,0,'MRP11-S1',?,'2099-01-01','"+unit+"','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",material,material,new BigDecimal(price),date.toLocalDate().withDayOfMonth(1));
  }

  protected void comparisonAdditionalPart(String product,String original,String material,String quantity,LocalDateTime date) {
    jdbc.update("INSERT IGNORE INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'件','"+materialOrg+"',?,1,'15','采购件')",material,material,product);
    var raw=rawHierarchy.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.sanhua.marketingcost.entity.BomRawHierarchy>lambdaQuery().eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getBuildBatchId,product).eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getMaterialCode,original));
    var copy=new com.sanhua.marketingcost.entity.BomRawHierarchy();org.springframework.beans.BeanUtils.copyProperties(raw,copy);
    copy.setId(null);copy.setSourceLineKey("MRP08-"+material);copy.setMaterialCode(material);copy.setMaterialName(material);copy.setPath("/"+product+"/"+material+"/");
    copy.setQtyPerParent(new BigDecimal(quantity));copy.setQtyPerTop(new BigDecimal(quantity));copy.setSortSeq(10);rawHierarchy.insert(copy);
  }

  protected BomCostingRow buildOriginalFixture(long form, long item, String key, String month,
      LocalDateTime date, String pricedMaterial, Map<String, String> units) {
    String oaNo = oaForms.selectById(form).getOaNo();
    var original = monthlyU9.read(new com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext(
        form, item, oaNo, month, unit, key, key, null, null, priceOrg, materialOrg, date.toLocalDate(), date));
    assertThat(original.status()).as(original.message())
        .isEqualTo(com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult.Status.AVAILABLE);
    var sources = rawHierarchy.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
        .lambdaQuery(com.sanhua.marketingcost.entity.BomRawHierarchy.class)
        .eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getBuildBatchId, key));
    var keys = new HashMap<String, String>(); sources.forEach(raw -> keys.put(raw.getPath(), "NODE-" + raw.getId()));
    String build = key + "-EFFECTIVE-" + item;
    for (var raw : sources) {
      var node = new com.sanhua.marketingcost.entity.QuoteEffectiveBomNode();
      node.setOriginMonthlySnapshotId(original.monthlySnapshotId()); node.setEffectiveVariantHash(original.structureFingerprint());
      node.setBuildBatchId(build); node.setTopProductCode(key); node.setCostPeriodMonth(month); node.setPriceOrgCode(priceOrg);
      node.setNodeKey(keys.get(raw.getPath()));
      int slash = raw.getPath().lastIndexOf('/', raw.getPath().length() - 2);
      node.setParentNodeKey(raw.getLevel() == 0 ? null : keys.get(raw.getPath().substring(0, slash + 1)));
      node.setNodeLevel(raw.getLevel()); node.setSortSeq(raw.getSortSeq()); node.setNodePath(raw.getPath());
      node.setMaterialCode(raw.getMaterialCode()); node.setMaterialName(raw.getMaterialName());
      node.setMaterialSpec(raw.getMaterialSpec());
      node.setMaterialUnit(units.getOrDefault(raw.getMaterialCode(), "件"));
      node.setQtyPerParent(raw.getQtyPerParent()); node.setQtyPerTop(raw.getQtyPerTop());
      node.setEffectiveMaterialShape(com.sanhua.marketingcost.enums.QuoteMaterialShape.fromU9(raw.getShapeAttr()).name());
      node.setSourceMaterialShape(node.getEffectiveMaterialShape()); node.setShapeResolutionSource("U9");
      node.setSourceBomType("U9"); node.setSourceBomBatchId(key); node.setSourceHierarchyId(raw.getId());
      node.setSourceNodePath(raw.getPath()); effectiveNodes.insert(node);
    }
    var record = new com.sanhua.marketingcost.entity.QuoteBomPreparationRecord();
    record.setOaFormId(form); record.setOaFormItemId(item); record.setOaNo(oaNo); record.setQuoteProductCode(key);
    record.setPriceOrgCode(priceOrg); record.setMaterialOrganizationCode(materialOrg); record.setProductType("NON_BARE");
    record.setCostPeriodMonth(month); record.setPreparationStatus("READY"); record.setActiveFlag(1);
    preparationRecords.insert(record);
    costingBoms.buildFromEffectiveBom(item, build);
    return bomRows.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(BomCostingRow.class)
        .eq(BomCostingRow::getOaFormItemId, item).eq(BomCostingRow::getMaterialCode, pricedMaterial));
  }

  protected void ordinarySources(String product, String material, long formId, LocalDateTime date) {
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
      raw.setChildType("NORMAL"); // These synthetic fixtures have no standard/alternative groups.
      raw.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1)); raw.setEffectiveTo(java.time.LocalDate.of(2099, 1, 1)); raw.setBomStatus("已核准");
      raw.setIsLeaf(level); raw.setU9IsCostFlag(1); raw.setSourceType("U9"); raw.setSourceImportBatchId(product);
      raw.setBuildBatchId(product); raw.setBuiltAt(date); raw.setPriceOrgCode(priceOrg); raw.setBusinessUnitType(unit); rawHierarchy.insert(raw);
    }
    zeroPackaging(product, date);
    ordinaryExpenseSources(product, formId, date);
  }

  protected void zeroPackaging(String product, LocalDateTime date) {
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
      raw.setChildType("NORMAL");
      raw.setBomStatus("已核准"); raw.setIsLeaf(index); raw.setU9IsCostFlag(1); raw.setSourceType("U9");
      raw.setSourceImportBatchId(product); raw.setBuildBatchId(product); raw.setBuiltAt(date);
      raw.setPriceOrgCode(priceOrg); raw.setBusinessUnitType(unit); rawHierarchy.insert(raw);
    }
  }

  protected void ordinaryExpenseSources(String product, long formId, LocalDateTime date) {
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
