package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.dto.QualityLossRateImportRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.PriceFixedItemService;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.QualityLossRateService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingProductQuery;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskApplicationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 真实核算检查、价格/净损失率导入、列表读取；所有数据只在本测试容器。 */
@Tag("integration")
class PendingSupplementPublicImportIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected BomRawHierarchyMapper hierarchy;
  @Autowired protected ProductCostingPipeline pipeline;
  @Autowired protected PriceFixedItemService prices;
  @Autowired protected QualityLossRateService rates;
  @Autowired protected TechnicalDataPendingProductQuery pending;
  @Autowired protected TechnicalDataTaskApplicationService tasks;
  protected String oaNo, material, month;
  protected long itemId;
  protected static final TechnicalDataActor ADMIN=new TechnicalDataActor(1L,"管理员",java.util.Set.of("*:*:*"));

  @DynamicPropertySource static void activeMonth(DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @BeforeEach void fixture() {
    var auth=new UsernamePasswordAuthenticationToken("admin",null,List.of(new SimpleGrantedAuthority("*:*:*")));
    auth.setDetails(Map.of("businessUnitType","COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    oaNo="IMPORT-GAP-"+UUID.randomUUID().toString().substring(0,8);material=oaNo+"-PART";
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",oaNo);
    long formId=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oaNo);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,seq,material_no,product_name,business_type,shipping_fee,business_unit_type) VALUES(?,1,?,'公共资料导入验收','批量品',0,'COMMERCIAL')",formId,oaNo);
    itemId=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,formId);
    new OrdinaryCostingTestSources(jdbc,hierarchy).ordinarySources(oaNo,material,formId,YearMonth.parse(month).atDay(1).atTime(12,0));
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",oaNo+"-BARE",oaNo);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",oaNo+"-BARE",oaNo+"-BARE",oaNo);
    jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",oaNo+"-BARE",oaNo);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0",oaNo);
    pipeline.prepare(new ProductCostingRequest(oaNo,itemId,month,"import-gap-test",false));
    jdbc.update("DELETE FROM lp_quality_loss_rate WHERE bare_product_code=? AND business_unit_type='COMMERCIAL'",oaNo+"-BARE");
    var checked=pipeline.prepare(new ProductCostingRequest(oaNo,itemId,month,"import-gap-test",false));
    assertThat(checked.getTechnicalDataCheck()).as(checked.getMessage()).isNotNull();
    assertThat(checked.getTechnicalDataCheck().modules()).as(checked.getMessage()+" "+checked.getTechnicalDataCheck().modules()).filteredOn(m->m.required())
        .extracting(m->m.moduleType()).contains("PRICE","NET_LOSS");
    assertThat(pending.count("ALL",null,month,null,oaNo)).isEqualTo(1);
  }

  @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }

  @Test void realImportsRemoveOnlyResolvedGapsWithoutCreatingSupplementOrPreviewRows() {
    var before=protectedRows();
    importPrice();
    var remaining=pending.page("ALL",null,month,null,oaNo,0,20).getFirst();
    assertThat(remaining.product().modules()).filteredOn(m->m.required())
        .extracting(m->m.moduleType()).containsExactly("NET_LOSS");
    assertThat(protectedRows()).isEqualTo(before);
    importRate();
    for(int i=0;i<2;i++) {
      var page=tasks.workbench(1,20,null,month,null,oaNo,ADMIN);
      assertThat(page.total()).isZero();
      assertThat(page.records()).isEmpty();
      assertThat(page.summary().pending()).isZero();
      assertThat(pending.hasPendingItem(oaNo,itemId)).isFalse();
    }
    assertThat(protectedRows()).isEqualTo(before);
  }

  protected void importPrice() {
    var row=new PriceFixedItemImportRequest.PriceFixedItemImportRow();
    row.setMaterialCode(material);row.setMaterialName("采购件");row.setOrgCode("210");row.setUnit("件");
    row.setSupplierCode("IMPORT-GAP-S1");row.setSourceType("PURCHASE_FIXED");row.setSourceSystem("EXCEL");
    row.setExternalRowId(material);row.setFixedPrice(new BigDecimal("2"));row.setTaxIncluded(false);
    row.setEffectiveFrom(YearMonth.parse(month).atDay(1));
    var request=new PriceFixedItemImportRequest();request.setRows(List.of(row));request.setSourceBatchNo(oaNo);
    var imported=prices.importItems(request);
    assertThat(imported.getCreatedCount()).isEqualTo(1);
    assertThat(imported.getErrors()).isEmpty();
  }

  protected void importRate() {
    var row=new QualityLossRateImportRequest.QualityLossRateRow();row.setBareProductCode(oaNo+"-BARE");
    row.setLossRate(new BigDecimal("0.02"));row.setProductName("公共资料导入验收");
    var request=new QualityLossRateImportRequest();request.setRows(List.of(row));request.setRateYear(YearMonth.parse(month).getYear());
    assertThat(rates.importItems(request).getInserted()).isEqualTo(1);
  }

  protected Map<String,Object> protectedRows() {
    var result=new LinkedHashMap<String,Object>();
    result.put("workspace",jdbc.queryForList("SELECT * FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",itemId));
    for(String table:List.of("lp_quote_tech_task","lp_quote_tech_product","lp_quote_tech_module","lp_quote_tech_data_version",
        "lp_quote_tech_oa_recipient","lp_quote_tech_submission","lp_quote_tech_price_claim","lp_price_prepare_batch",
        "lp_price_prepare_item","lp_price_prepare_gap","lp_material_monthly_price","lp_quote_cost_run_version"))
      result.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
    return result;
  }
}
