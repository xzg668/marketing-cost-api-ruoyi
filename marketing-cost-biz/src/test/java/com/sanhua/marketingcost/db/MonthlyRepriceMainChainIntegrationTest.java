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

/** Fresh upstream fixtures; every successful cost is calculated by the production ordinary engine. */
@Tag("integration")
@TestPropertySource(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false","oa.person-directory.run-at-startup=false","monthly.reprice.enabled=false","cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class MonthlyRepriceMainChainIntegrationTest extends OriginalPricingIntegrationTestBase {
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
 String unit,priceOrg,materialOrg;
 @Test void twoUnitsCrossYearSavedSourcesAndRealMissingRatioRepair() throws Exception {
   String current=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
   String baseline="2025-12";var oldDate=YearMonth.parse(baseline).atDay(25).atTime(12,0);
   var targetDate=YearMonth.parse(current).atDay(25).atTime(12,0);
   var samples=new ArrayList<Map<String,Object>>();
   for(String scope:List.of("COMMERCIAL","HOUSEHOLD"))for(int year:List.of(2025,2026))
     jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES(?,?,'标准品',0)",scope,year);
   for(String scope:List.of("COMMERCIAL","HOUSEHOLD")) {
     unit=scope;priceOrg=scope.equals("COMMERCIAL")?"210":"220";materialOrg=scope.equals("COMMERCIAL")?"COMMERCIAL":"PLATE";
     String actor="mrp11_source_"+UUID.randomUUID().toString().substring(0,8);
     jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,?,?,'0','0','MRP11',NOW(),'MRP11',NOW())",actor,"MRP11 source",new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(UUID.randomUUID().toString()),scope);
     long actorId=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,actor);
     jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,11)",actorId);
     var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(actor,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_BU_STAFF")));
     auth.setDetails(Map.of("businessUnitType",scope,"userId",actorId));org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
     String family="MRP11-"+scope.charAt(0)+"-"+UUID.randomUUID().toString().substring(0,6);
     String b="000"+family+"-B",c="000"+family+"-C",d="000"+family+"-D",e="000"+family+"-E";
     for(String code:List.of(b,c,e))comparisonFixedPrice(code,code.equals(b)?"11.3":"5",oldDate);
     comparisonFixedPrice(d,"2",targetDate);
     jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES(?,?,?,'件',3,0,'MRP11-S2',?,'2099-01-01',?,'PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",priceOrg,d,d,targetDate.toLocalDate().withDayOfMonth(1),unit);
     var objects=new ArrayList<Map<String,Object>>();
     for(int index=0;index<3;index++) {
       boolean ordinary=index==2;String key=family+"-P"+index;
       String name=(scope.equals("HOUSEHOLD")?"板换隔离父件":"隔离父件")+index;
       jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,?,80000)",key,unit);
       long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,key);
       jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,spec,sunl_model,business_unit_type) VALUES(?,?,?,NULL,NULL,?)",form,key,name,unit);
       long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
       var date=ordinary?targetDate:oldDate;String month=ordinary?current:baseline;
       ordinarySources(key,b,form,date);
       jdbc.update("UPDATE lp_bom_raw_hierarchy SET material_name=? WHERE top_product_code=? AND level=0",name,key);
       jdbc.update("UPDATE lp_material_master_raw SET material_name=? WHERE material_code=? AND organization_code=?",name,key,materialOrg);
       comparisonAdditionalPart(key,b,ordinary?d:c,"2",date);comparisonAdditionalPart(key,b,e,"1",date);
       buildOriginalFixture(form,item,key,month,date,b,Map.of());
       Long version=null;
       if(!ordinary) {
         try(var calendar=org.mockito.Mockito.mockStatic(com.sanhua.marketingcost.util.CostPricingPeriodUtils.class,org.mockito.Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE).defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS))) {
         calendar.when(com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingDate).thenReturn(date.toLocalDate());
         var cost=pipeline.execute(new com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest(key,item,month,actor,true));
         assertThat(cost.getPipelineStatus()).as(cost.getMessage()).isEqualTo("SUCCESS");
         version=cost.getCostVersionId();assertThat(version).isNotNull();
         assertThat(jdbc.queryForObject("SELECT part_item_count FROM lp_quote_cost_run_version WHERE id=?",Integer.class,version)).isEqualTo(3);
         assertThat(jdbc.queryForObject("SELECT status FROM lp_quote_cost_run_version WHERE id=?",String.class,version)).isEqualTo("SUCCESS");
         }
         ordinaryExpenseSources(key,form,targetDate);
         jdbc.update("UPDATE lp_bom_raw_hierarchy SET qty_per_parent=3.75,qty_per_top=3.75 WHERE top_product_code=? AND material_code=?",key,b);
         jdbc.update("UPDATE lp_bom_raw_hierarchy SET effective_to=? WHERE top_product_code=? AND material_code=?",targetDate.toLocalDate().withDayOfMonth(1).minusDays(1),key,c);
         comparisonAdditionalPart(key,b,d,"2",targetDate);
         jdbc.update("UPDATE lp_bom_raw_hierarchy SET effective_from=? WHERE top_product_code=? AND material_code=?",targetDate.toLocalDate().withDayOfMonth(1),key,d);
       }
       var object=new LinkedHashMap<String,Object>();object.put("oaNo",key);object.put("itemId",item);object.put("formId",form);object.put("sourceVersionId",version);object.put("ordinary",ordinary);objects.add(object);
     }
     samples.add(Map.of("unit",unit,"priceOrg",priceOrg,"baseline",baseline,"month",current,"partB",b,"partC",c,"partD",d,"partE",e,"objects",objects,"stableHeader",true));
   }
   org.springframework.security.core.context.SecurityContextHolder.clearContext();
   MonthlyRepriceMainChainBrowserSession.await(jdbc,samples);
 }
  private void comparisonFixedPrice(String material,String price,LocalDateTime date) {
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('"+priceOrg+"',?,?,'件',?,0,'MRP11-S1',?,'2099-01-01','"+unit+"','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",material,material,new BigDecimal(price),date.toLocalDate().withDayOfMonth(1));
  }

  private void comparisonAdditionalPart(String product,String original,String material,String quantity,LocalDateTime date) {
    jdbc.update("INSERT IGNORE INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'件','"+materialOrg+"',?,1,'15','采购件')",material,material,product);
    var raw=rawHierarchy.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.sanhua.marketingcost.entity.BomRawHierarchy>lambdaQuery().eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getBuildBatchId,product).eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getMaterialCode,original));
    var copy=new com.sanhua.marketingcost.entity.BomRawHierarchy();org.springframework.beans.BeanUtils.copyProperties(raw,copy);
    copy.setId(null);copy.setSourceLineKey("MRP08-"+material);copy.setMaterialCode(material);copy.setMaterialName(material);copy.setPath("/"+product+"/"+material+"/");
    copy.setQtyPerParent(new BigDecimal(quantity));copy.setQtyPerTop(new BigDecimal(quantity));copy.setSortSeq(10);rawHierarchy.insert(copy);
  }

  private BomCostingRow buildOriginalFixture(long form, long item, String key, String month,
      LocalDateTime date, String pricedMaterial, Map<String, String> units) {
    var original = monthlyU9.read(new com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext(
        form, item, key, month, unit, key, key, null, null, priceOrg, materialOrg, date.toLocalDate(), date));
    assertThat(original.status()).as(original.message())
        .isEqualTo(com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult.Status.AVAILABLE);
    var sources = rawHierarchy.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
        .lambdaQuery(com.sanhua.marketingcost.entity.BomRawHierarchy.class)
        .eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getBuildBatchId, key));
    var keys = new HashMap<String, String>(); sources.forEach(raw -> keys.put(raw.getPath(), "NODE-" + raw.getId()));
    String build = key + "-EFFECTIVE";
    for (var raw : sources) {
      var node = new com.sanhua.marketingcost.entity.QuoteEffectiveBomNode();
      node.setOriginMonthlySnapshotId(original.monthlySnapshotId()); node.setEffectiveVariantHash(original.structureFingerprint());
      node.setBuildBatchId(build); node.setTopProductCode(key); node.setCostPeriodMonth(month); node.setPriceOrgCode(priceOrg);
      node.setNodeKey(keys.get(raw.getPath()));
      int slash = raw.getPath().lastIndexOf('/', raw.getPath().length() - 2);
      node.setParentNodeKey(raw.getLevel() == 0 ? null : keys.get(raw.getPath().substring(0, slash + 1)));
      node.setNodeLevel(raw.getLevel()); node.setSortSeq(raw.getSortSeq()); node.setNodePath(raw.getPath());
      node.setMaterialCode(raw.getMaterialCode()); node.setMaterialName(raw.getMaterialName());
      node.setMaterialUnit(units.getOrDefault(raw.getMaterialCode(), "件"));
      node.setQtyPerParent(raw.getQtyPerParent()); node.setQtyPerTop(raw.getQtyPerTop());
      node.setEffectiveMaterialShape(com.sanhua.marketingcost.enums.QuoteMaterialShape.fromU9(raw.getShapeAttr()).name());
      node.setSourceMaterialShape(node.getEffectiveMaterialShape()); node.setShapeResolutionSource("U9");
      node.setSourceBomType("U9"); node.setSourceBomBatchId(key); node.setSourceHierarchyId(raw.getId());
      node.setSourceNodePath(raw.getPath()); effectiveNodes.insert(node);
    }
    var record = new com.sanhua.marketingcost.entity.QuoteBomPreparationRecord();
    record.setOaFormId(form); record.setOaFormItemId(item); record.setOaNo(key); record.setQuoteProductCode(key);
    record.setPriceOrgCode(priceOrg); record.setMaterialOrganizationCode(materialOrg); record.setProductType("NON_BARE");
    record.setCostPeriodMonth(month); record.setPreparationStatus("READY"); record.setActiveFlag(1);
    preparationRecords.insert(record);
    costingBoms.buildFromEffectiveBom(item, build);
    return bomRows.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(BomCostingRow.class)
        .eq(BomCostingRow::getOaFormItemId, item).eq(BomCostingRow::getMaterialCode, pricedMaterial));
  }

  private void ordinarySources(String product, String material, long formId, LocalDateTime date) {
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
