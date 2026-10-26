package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

@Tag("integration")
@org.springframework.boot.test.context.SpringBootTest(webEnvironment=org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"srm.fixed-price.enabled=false", "oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false", "monthly-reprice.enabled=false", "cms.sync.publish.enabled=false",
    "quote.drawing-verification.enabled=false"})
class MonthlyQuotationPreparationIntegrationTest extends OriginalPricingIntegrationTestBase {
  static final java.nio.file.Path HOUSEHOLD_FILES = temporaryHouseholdFiles();
  private static java.nio.file.Path temporaryHouseholdFiles() {
    try { return java.nio.file.Files.createTempDirectory("mpp12-household-"); }
    catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
  }
  @org.springframework.test.context.DynamicPropertySource
  static void householdFiles(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("price.household.workbooks-root", HOUSEHOLD_FILES::toString);
    registry.add("app.cors.allowed-origins",()->"http://127.0.0.1:"+OdsBrowserAcceptance.FRONTEND_PORT);
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired MonthlyMaterialPrices monthly;
  @Autowired NormalMaterialPricePrepareStrategy normal;
  @Autowired com.sanhua.marketingcost.service.PricePrepareService preparations;
  @Autowired com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService workbench;
  @Autowired com.sanhua.marketingcost.mapper.BomCostingRowMapper bomRows;
  @Autowired com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService manufacturingPrices;
  @Autowired com.sanhua.marketingcost.service.MakePartPricePrepareStrategy manufacturing;
  @Autowired com.sanhua.marketingcost.mapper.BomU9SourceMapper u9Rows;
  @Autowired com.sanhua.marketingcost.mapper.MaterialScrapRefMapper scrapMappings;
  @Autowired com.sanhua.marketingcost.mapper.BomRawHierarchyMapper rawHierarchy;
  @Autowired com.sanhua.marketingcost.service.PackageComponentPricePrepareStrategy packaging;
  @Autowired com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper packageSnapshots;
  @Autowired com.sanhua.marketingcost.mapper.PackageComponentSnapshotDetailMapper packageDetails;
  @Autowired com.sanhua.marketingcost.service.FinancePricePrepareService financePreparations;
  @Autowired com.sanhua.marketingcost.mapper.PriceLinkedItemMapper linkedRules;
  @Autowired com.sanhua.marketingcost.service.impl.OriginalLinkedRuleSnapshot linkedSnapshots;
  @Autowired com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService effectivePrices;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired com.sanhua.marketingcost.service.costing.MonthlyCostVersionService monthlyCosts;
  @Autowired com.sanhua.marketingcost.service.QuoteCostRunVersionService costVersions;
  @Autowired com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper versionRows;
  @Autowired com.sanhua.marketingcost.mapper.PricePrepareItemMapper preparedRows;
  @Autowired com.sanhua.marketingcost.service.QuoteCuAdjustmentCalcService quotationCosting;
  @Autowired com.sanhua.marketingcost.mapper.OaFormMapper oaForms;
  @Autowired com.sanhua.marketingcost.mapper.OaFormItemMapper oaItems;
  @Autowired com.sanhua.marketingcost.service.QuoteCuMaterialDiffService materialDifferences;
  @Autowired com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService costingBoms;
  @Autowired com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper preparationRecords;
  @Autowired com.sanhua.marketingcost.mapper.QuoteEffectiveBomNodeMapper effectiveNodes;
  @Autowired com.sanhua.marketingcost.mapper.BomSettlementRuleMapper settlementRules;
  @Autowired com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryService householdInquiries;
  @Autowired com.sanhua.marketingcost.service.quotebom.CurrentU9BomGateway monthlyU9;
  @Autowired com.sanhua.marketingcost.service.MaterialPricePriorityService priorities;
  @Autowired com.sanhua.marketingcost.security.JwtUtils jwt;
  @Autowired HouseholdMonthlyPriceSource householdSource;
  @org.springframework.boot.test.web.server.LocalServerPort int httpPort;

  @BeforeEach void tenant() {
    var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("MPP12", "", List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
  }
  @AfterEach void clearTenant() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void financeDesignationIsExclusiveInTheRealSourceChainAndResetRestoresFallback(boolean hasHousehold) throws Exception {
    String code="ODS-FIN-"+UUID.randomUUID().toString().substring(0,10);
    var c=new MonthlyMaterialPrices.Context("COMMERCIAL",code,1L,code,YearMonth.of(2026,10),LocalDateTime.of(2026,10,25,0,0),"test","210");
    var r=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(c.month(),"COMMERCIAL",code,null,"件","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);
    jdbc.update("INSERT INTO ods_sy_v_mm_material_order_price_cg05_df(company_name,material_code,supply_code,unit,price,eff_date,dt,source) VALUES('浙江三花商用制冷有限公司',?,'ODS-S','件',11.3,'2026-10-01','2026-10-24','ODS fixture')",code);
    var scope=new com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope("COMMERCIAL","2026-10","210",code);
    var initial=priorities.initialize(scope,code);
    priorities.change(scope,List.of(com.sanhua.marketingcost.enums.MaterialPriceSourceType.FIXED,
        com.sanhua.marketingcost.enums.MaterialPriceSourceType.LINKED,com.sanhua.marketingcost.enums.MaterialPriceSourceType.SETTLE_LINKED,
        com.sanhua.marketingcost.enums.MaterialPriceSourceType.RANGE,com.sanhua.marketingcost.enums.MaterialPriceSourceType.SETTLE_FIXED),initial.revision(),1L);
    if(hasHousehold)publishHousehold(code,"2026-10");
    var probe=new MonthlyQuotationPrices(monthly,householdSource).probeHousehold(c,List.of(new MonthlyQuotationPrices.Item(code,"210","件",null,TaxBasis.EXCLUDED,null,NonCuConditions.empty(),BigDecimal.ONE,MonthlyQuotationPrices.Purpose.MATERIAL)));
    assertThat(probe.values()).isEmpty();
    var blocked=monthly.resolveCandidates(c,Set.of(r));
    assertThat(blocked.values()).isEmpty();assertThat(blocked.problems().get(r).message()).contains("财务指定");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,code)).isZero();
    priorities.change(scope,null,priorities.read(scope).revision(),1L);
    assertThat(monthly.resolveCandidates(c,Set.of(r)).values().get(r).selection().fact().price()).isEqualByComparingTo(hasHousehold?"20.9853":"11.3");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"ORDER","TIER"})
  void odsFallbackRunsThroughPreviewPreparationCostingAndSavedHistory(String sourceKind) {
    String key="ODS-FLOW-"+UUID.randomUUID().toString().substring(0,10), material=key+"-PART";
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(25).atTime(12,0);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",key);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type,annual_volume) VALUES(?,?,'COMMERCIAL',?)",form,key,sourceKind.equals("TIER")?new BigDecimal("24"):null);
    long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    if(sourceKind.equals("TIER")) {
      jdbc.update("INSERT INTO lp_oa_form_item_extra_field(oa_form_id,oa_form_item_id,business_unit_type,field_code,field_name,field_value,value_type) VALUES(?,?,'COMMERCIAL','ANNUAL_VOLUME_UNIT','预计年用量单位','TEN_THOUSAND_PIECES','TEXT')",form,item);
      for(var band:List.of(new String[]{"1","20000","19.9"},new String[]{"20000",null,"11.3"}))
        jdbc.update("INSERT INTO ods_sy_v_mm_material_jt_price_cg05_df(company_name,material_code,supply_code,supply_name,unit,price,eff_date,exp_date,jieti_down,jieti_up,dt,source) VALUES('浙江三花商用制冷有限公司',?,'ODS-S','ODS supplier','件',?,?,?, ?,?,'2026-10-24','ODS fixture')",
            material,new BigDecimal(band[2]),date.toLocalDate().withDayOfMonth(1),date.toLocalDate().plusMonths(1),new BigDecimal(band[0]),band[1]==null?null:new BigDecimal(band[1]));
    } else {
      jdbc.update("INSERT INTO ods_sy_v_mm_material_order_price_cg05_df(company_name,material_code,supply_code,supply_name,unit,price,eff_date,dt,source) VALUES('浙江三花商用制冷有限公司',?,'ODS-S','ODS supplier','件',11.3,?,'2026-10-24','ODS fixture')",material,date.toLocalDate().plusMonths(1));
    }
    ordinarySources(key,material,form,date);buildOriginalFixture(form,item,key,month,date,material,Map.of());
    var request=new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    request.setOaNo(key);request.setOaFormItemId(item);request.setTopProductCode(key);request.setPeriodMonth(month);request.setPriceAsOfTime(date);request.setBusinessUnitType("COMMERCIAL");
    assertThat(preparations.calculate(request).getSummary().getStatus()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,material)).isZero();
    var saved=generateThroughWorkbench(request);
    var adopted=jdbc.queryForMap("SELECT id,source_type,supplier_code,unit_price FROM lp_material_monthly_price WHERE material_code=?",material);
    assertThat(adopted.get("source_type")).isEqualTo("ODS_"+sourceKind);
    assertThat(adopted.get("supplier_code")).isEqualTo("ODS-S");
    assertThat((BigDecimal)adopted.get("unit_price")).isEqualByComparingTo("11.3");
    var calculated=quotationCosting.calculate(new com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest(
        oaForms.selectById(form),oaItems.selectById(item),month,saved.getPrepareNo(),key,null,true));
    assertThat(calculated.totalCost()).isEqualByComparingTo("46.642360");
    assertThat(calculated.finalQuoteAmount()).isEqualByComparingTo("46.642360");
    if(sourceKind.equals("TIER") && System.getProperty("ods.browser.script")!=null) {
      priorities.initialize(new com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope("COMMERCIAL",month,"210",material),material);
      OdsBrowserAcceptance.verify(jdbc,httpPort,jwt.generateToken("admin","COMMERCIAL"),key,item,material,month,
          ((Number)adopted.get("id")).longValue(),calculated.version().getId());
    }
    jdbc.update("DELETE FROM ods_sy_v_mm_material_order_price_cg05_df WHERE material_code=?",material);
    jdbc.update("DELETE FROM ods_sy_v_mm_material_jt_price_cg05_df WHERE material_code=?",material);
    var repeated=generateThroughWorkbench(request);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,material)).isEqualTo(1);
    var trial=quotationCosting.calculate(new com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest(
        oaForms.selectById(form),oaItems.selectById(item),month,repeated.getPrepareNo(),key,null,false));
    assertThat(trial.finalQuoteAmount()).isEqualByComparingTo(calculated.finalQuoteAmount());
    assertThat(monthlyCosts.read("COMMERCIAL",calculated.version().getId()).version().getFinalQuoteAmount()).isEqualByComparingTo("46.642360");
    if(sourceKind.equals("TIER")) {
      jdbc.update("UPDATE oa_form_item SET annual_volume=1 WHERE id=?",item);
      assertThat(preparations.calculate(request).getSummary().getStatus()).isNotEqualTo("SUCCESS");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,material)).isEqualTo(1);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"MISSING","ZERO","UNKNOWN_UNIT"})
  void ordinaryPreparationPreservesAnnualUsageProblemAcrossTaxVariants(String invalidUsage) {
    String key="ODS-ANNUAL-"+UUID.randomUUID().toString().substring(0,10), material=key+"-PART";
    var date=LocalDateTime.of(2026,10,25,12,0);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",key);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type,annual_volume) VALUES(?,?,'COMMERCIAL',?)",
        form,key,invalidUsage.equals("MISSING")?null:invalidUsage.equals("ZERO")?BigDecimal.ZERO:BigDecimal.valueOf(100));
    long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    if(invalidUsage.equals("UNKNOWN_UNIT"))
      jdbc.update("INSERT INTO lp_oa_form_item_extra_field(oa_form_id,oa_form_item_id,business_unit_type,field_code,field_name,field_value,value_type) VALUES(?,?,'COMMERCIAL','ANNUAL_VOLUME_UNIT','预计年用量单位','UNKNOWN','TEXT')",form,item);
    jdbc.update("INSERT INTO ods_sy_v_mm_material_jt_price_cg05_df(company_name,material_code,supply_code,unit,price,eff_date,exp_date,jieti_down,jieti_up,dt,source) VALUES('浙江三花商用制冷有限公司',?,'ODS-S','件',11.3,'2026-10-01','2026-10-31',0,NULL,'2026-10-24','ODS fixture')",material);
    var row=new BomCostingRow();row.setOaNo(key);row.setOaFormItemId(item);row.setTopProductCode(key);
    row.setMaterialCode(material);row.setUnit("件");row.setPath("root/part");row.setQtyPerTop(new BigDecimal("2.5"));
    row.setPriceOrgCode("210");row.setMaterialOrganizationCode("COMMERCIAL");row.setBusinessUnitType("COMMERCIAL");row.setPeriodMonth("2026-10");
    var plan=new PricePreparePlanItem();plan.setBomRow(row);plan.setMaterialCode(material);plan.setTopProductCode(key);plan.setItemType("NORMAL");plan.setStatus("READY");
    var result=normal.calculate(key,"COMMERCIAL","2026-10",date,PricePrepareScenarioContext.oaLocked(item,"210"),plan);
    assertThat(result.getReasonCode()).isEqualTo("INVALID_INPUT");
    assertThat(result.getMessage()).contains("阶梯","年用量");
    assertThat(result.getUnitPrice()).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,material)).isZero();
  }

  @Test void ordinaryQuotationPreparationReusesAdoptedRawPriceAfterDailySourceReplacement() {
    String key = "MPP12-" + UUID.randomUUID().toString().substring(0, 12);
    String material = key + "-PART";
    String month = "2026-10";
    var date = LocalDateTime.of(2026, 10, 24, 12, 0);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')", form, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,supplier_name,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,source_batch_no,created_at,updated_at) "
        + "VALUES('210',?,'MPP12 普通料','件',11.3,0,'MPP12-S','MPP12 supplier','2026-10-01','2027-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM','MPP12-fixture',NOW(),NOW())", material);
    var context = new MonthlyMaterialPrices.Context("COMMERCIAL", key, item, key, YearMonth.parse(month), date, "MPP12", "210");
    var request = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(context.month(), "COMMERCIAL", material,
        null, "件", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), new BigDecimal("2.5"));
    var selection = monthly.resolveCandidates(context, Set.of(request)).values().get(request).selection();
    var adopted = monthly.adopt(context, new MonthlyAdoptionPlan(Map.of(request, selection),
        List.of(new MonthlyAdoptionPlan.Use("root/part", request, new BigDecimal("2.5"))), Set.of())).plan();
    jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=19,tax_included=1 WHERE material_code=?", material);
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) "
        + "VALUES('COMMERCIAL',?,?,'later scrap registration','FIXED',7,'{}','IMPORT',?,?,'MPP12',NOW(),'MPP12',NOW())",
        month, material, month, "e".repeat(64));
    var row = new BomCostingRow(); row.setOaNo(key); row.setOaFormItemId(item); row.setTopProductCode(key);
    row.setMaterialCode(material); row.setMaterialName("MPP12 普通料"); row.setUnit("件"); row.setPath("root/part");
    row.setQtyPerTop(new BigDecimal("2.5")); row.setPriceOrgCode("210"); row.setMaterialOrganizationCode("COMMERCIAL");
    row.setBusinessUnitType("COMMERCIAL"); row.setPeriodMonth(month);
    var plan = new PricePreparePlanItem(); plan.setBomRow(row); plan.setMaterialCode(material);
    plan.setMaterialName(row.getMaterialName()); plan.setTopProductCode(key); plan.setItemType("NORMAL"); plan.setStatus("READY");
    var scenario = PricePrepareScenarioContext.oaLocked(item, "210");
    var result = normal.calculate(key, "COMMERCIAL", month, date, scenario, plan);
    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getUnitPrice()).isEqualByComparingTo("11.3");
    assertThat(result.getAmount()).isEqualByComparingTo("28.25");
    assertThat(result.getInputSnapshotJson()).contains("monthlyPriceId", adopted.selections().get(request).monthlyPriceId().toString());
    assertThat(scenario.monthlyPrices().get().plan().uses()).hasSize(1);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void previewDoesNotAdoptButFormalPreparationSavesAndReferencesTheExactMonthlyFact(boolean copperLinked) {
    String key = "MPP12-" + UUID.randomUUID().toString().substring(0, 12);
    String material = key + "-PART";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(15).atStartOfDay();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')", form, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,supplier_name,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,source_batch_no,created_at,updated_at) "
        + "VALUES('210',?,'MPP12 普通料','件',11.3,0,'MPP12-S','MPP12 supplier',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM','MPP12-fixture',NOW(),NOW())", material, date.toLocalDate().withDayOfMonth(1));
    if (copperLinked) {
      jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?", material);
      MonthlyQuotationSourceFixture.copperLinked(jdbc, linkedRules, linkedSnapshots, effectivePrices,
          transactions, material, month, "件");
    }
    String p1 = copperLinked ? "8" : "11.3", p0 = copperLinked ? "10" : "11.3";
    String customerAmount = copperLinked ? "20" : "28.25", financeAmount = copperLinked ? "25" : "28.25";
    String total1 = copperLinked ? "41.791555" : "46.642360", total2 = copperLinked ? "36.791555" : "46.642360";
    ordinarySources(key, material, form, date);
    var row = buildOriginalFixture(form, item, key, month, date, material, Map.of());
    var request = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    request.setOaNo(key); request.setOaFormItemId(item); request.setTopProductCode(key);
    request.setPeriodMonth(month); request.setPriceAsOfTime(date); request.setBusinessUnitType("COMMERCIAL");
    var preview = preparations.calculate(request);
    assertThat(preview.getSummary().getStatus()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, material)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_item WHERE oa_no=?", Integer.class, key)).isZero();
    var saved = generateThroughWorkbench(request);
    assertThat(saved.getStatus()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, material)).isEqualTo(1);
    var monthlyId = jdbc.queryForObject("SELECT id FROM lp_material_monthly_price WHERE material_code=?", Long.class, material);
    var stored = jdbc.queryForMap("SELECT monthly_price_id,monthly_pricing_type,unit_price,amount,input_snapshot_json FROM lp_price_prepare_item WHERE prepare_no=?", saved.getPrepareNo());
    assertThat(((Number) stored.get("monthly_price_id")).longValue()).isEqualTo(monthlyId);
    assertThat(stored.get("monthly_pricing_type")).isEqualTo("NORMAL");
    assertThat((BigDecimal) stored.get("unit_price")).isEqualByComparingTo(p1);
    assertThat((BigDecimal) stored.get("amount")).isEqualByComparingTo(customerAmount);
    assertThat(stored.get("input_snapshot_json").toString()).contains(monthlyId.toString());
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?", material);
    if (copperLinked) jdbc.update("DELETE FROM lp_price_linked_item WHERE material_code=?", material);
    var finance = financePreparations.generateFromOa(saved.getPrepareNo());
    assertThat(finance.prepareResult().getStatus()).isEqualTo("SUCCESS");
    assertThat(finance.financeCuPricePerKg()).as("月价财务场景不伪造统一 Cu 基准").isNull();
    var financial = jdbc.queryForMap("SELECT monthly_price_id,unit_price,amount,CAST(current_flag AS SIGNED) current_flag FROM lp_price_prepare_item WHERE prepare_no=?", finance.financePrepareNo());
    assertThat(((Number) financial.get("monthly_price_id")).longValue()).isEqualTo(monthlyId);
    assertThat((BigDecimal) financial.get("unit_price")).isEqualByComparingTo(p0);
    assertThat((BigDecimal) financial.get("amount")).isEqualByComparingTo(financeAmount);
    assertThat(((Number) financial.get("current_flag")).intValue()).isZero();
    assertThat(financePreparations.loadPreparedFromOa(saved.getPrepareNo()).financePrepareNo()).isEqualTo(finance.financePrepareNo());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, material)).isEqualTo(1);
    var version = costVersions.createRunning(key, item, key, month, month, finance.financePrepareNo(), "COMMERCIAL");
    version.setOaPricePrepareNo(saved.getPrepareNo()); version.setFinancePricePrepareNo(finance.financePrepareNo());
    versionRows.updateById(version);
    var codec = new MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper());
    var inputs = codec.decode(stored.get("input_snapshot_json").toString(), MonthlyPreparationPrice.class).monthlyInputs();
    var line = new com.sanhua.marketingcost.service.costing.MonthlyProductCost.MaterialLine(
        inputs.rootPath(), material, new BigDecimal("2.5"), List.of(monthlyId), new BigDecimal(financeAmount), new BigDecimal(customerAmount), null);
    var ordinary = new com.sanhua.marketingcost.service.costing.MonthlyProductCost.Ordinary(
        List.of(line), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    long oaId = jdbc.queryForObject("SELECT id FROM lp_price_prepare_item WHERE prepare_no=?", Long.class, saved.getPrepareNo());
    long financeId = jdbc.queryForObject("SELECT id FROM lp_price_prepare_item WHERE prepare_no=?", Long.class, finance.financePrepareNo());
    var calculated = monthlyCosts.calculateAndSave("COMMERCIAL", version.getId(), inputs.plan(), ordinary,
        null, new BigDecimal("80000"), Map.of(inputs.rootPath(), new com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.PreparationBinding(financeId, oaId)));
    assertThat(calculated.version().getStatus()).as("保存成本底稿后交给工作台完成发布").isEqualTo("RUNNING");
    assertThat(calculated.version().getFinalQuoteAmount()).isEqualByComparingTo(customerAmount);
    assertThat(calculated.snapshot().calculation().fees()).hasSize(4).allSatisfy(fee -> assertThat(fee.amount()).isNull());
    assertThat(calculated.parts()).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT monthly_price_id FROM lp_cost_run_part_item WHERE cost_run_version_id=?", Long.class, version.getId())).isEqualTo(monthlyId);
    var formal = quotationCosting.calculate(new com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest(
        oaForms.selectById(form), oaItems.selectById(item), month, saved.getPrepareNo(), key, null, true));
    assertThat(formal.version().getAlgorithmVersion()).isEqualTo("MONTHLY_PRODUCT_COST_V1");
    assertThat(formal.totalCost()).isEqualByComparingTo(total1);
    assertThat(formal.finalQuoteAmount()).isEqualByComparingTo(total2);
    assertThat(monthlyCosts.read("COMMERCIAL", formal.version().getId()).snapshot()).isNotNull();
    assertThat(materialDifferences.calculate(formal.version().getId()).adjustmentAmount()).isEqualByComparingTo(copperLinked ? "-5" : "0");
    if (!copperLinked) jdbc.update("UPDATE oa_form SET copper_price=NULL WHERE id=?", form);
    var trialRequest = new com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest(
        oaForms.selectById(form), oaItems.selectById(item), month, saved.getPrepareNo(), key, null, false);
    var trial = quotationCosting.calculate(trialRequest);
    assertThat(trial.version().getStatus()).isEqualTo("TRIAL");
    assertThat(trial.version().getId()).isNotEqualTo(formal.version().getId());
    assertThat(trial.finalQuoteAmount()).isEqualByComparingTo(total2);
    assertThat(monthlyCosts.read("COMMERCIAL", formal.version().getId()).version().getFinalQuoteAmount()).isEqualByComparingTo(total2);
    int versionCount = jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_no=?", Integer.class, key);
    int partCount = jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE oa_no=?", Integer.class, key);
    jdbc.execute("CREATE TRIGGER mpp12_cost_abort BEFORE INSERT ON lp_cost_run_cost_item FOR EACH ROW BEGIN IF NEW.oa_no='" + key
        + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP12-controlled-write-failure'; END IF; END");
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> quotationCosting.calculate(trialRequest))
          .hasStackTraceContaining("MPP12-controlled-write-failure");
    } finally { jdbc.execute("DROP TRIGGER mpp12_cost_abort"); }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_no=?", Integer.class, key)).isEqualTo(versionCount);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE oa_no=?", Integer.class, key)).isEqualTo(partCount);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, material)).isEqualTo(1);
  }

  @Test void manufacturingAndTechnicalPriceReadsReuseTheMonthlyBaseWithoutAdoptingUnusedMaterials() {
    String key = "MPP12-" + UUID.randomUUID().toString().substring(0, 12);
    String material = key + "-RAW";
    var date = LocalDateTime.of(2026, 10, 24, 12, 0);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')", form, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag) VALUES(?,'MPP12 原料','KG','COMMERCIAL','MPP12-fixture',1)", material);
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) "
        + "VALUES('210',?,'MPP12 原料','KG',100,0,'MPP12-S','2026-10-01','2027-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())", material);
    var scenario = PricePrepareScenarioContext.oaLocked(item, "210");
    var first = manufacturingPrices.calculateMaterialUnitPrice(material,"2026-10",date.toLocalDate(),date,key,"COMMERCIAL",scenario,
        com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.MATERIAL);
    assertThat(first.getUnitPrice()).isEqualByComparingTo("100");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, material)).isZero();
    assertThat(scenario.monthlyPrices().get()).isNotNull();
    assertThat(scenario.monthlyPrices().get().plan().uses()).isEmpty();
    var context = scenario.monthlyPrices().get().context();
    var request = new MonthlyPriceRequest(new MonthlyPriceFamilyKey(context.month(), "COMMERCIAL", material,
        null, "KG", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), BigDecimal.ONE);
    var selection = monthly.resolveCandidates(context, Set.of(request)).values().get(request).selection();
    monthly.adopt(context, new MonthlyAdoptionPlan(Map.of(request, selection),
        List.of(new MonthlyAdoptionPlan.Use("root/make/raw", request, new BigDecimal("0.4"))), Set.of()));
    jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=180 WHERE material_code=?", material);
    var later = manufacturingPrices.calculateMaterialUnitPrice(material,"2026-10",date.toLocalDate(),date,key,"COMMERCIAL",
        PricePrepareScenarioContext.oaLocked(item,"210"),com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.MATERIAL);
    assertThat(later.getUnitPrice()).isEqualByComparingTo("100");
    assertThat(later.getEvidence().inputSnapshotJson()).contains("MONTHLY_PRICE");
    var absentScrap = manufacturingPrices.calculateMaterialUnitPrice(material,"2026-11",date.plusMonths(1).toLocalDate(),date.plusMonths(1),key,"COMMERCIAL",
        PricePrepareScenarioContext.oaLocked(item,"210"),com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.SCRAP);
    assertThat(absentScrap.getUnitPrice()).isNull();
    assertThat(absentScrap.getStatus()).isEqualTo("MISSING_PRICE");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void manufacturingKeepsItsWeightAlgorithmAndAdoptsOnlyTheConsumedBaseAndScrapPrices(boolean copperLinked) throws Exception {
    String key = "MPP12-" + UUID.randomUUID().toString().substring(0, 12);
    String raw = key + "-RAW", parentCode = key + "-MAKE", scrapCode = key + "-SCRAP";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(15).atStartOfDay();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')", form, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    jdbc.update("UPDATE oa_form_item SET product_name='商用蒸发器' WHERE id=?", item);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,global_seg_3_theoretical_net_weight) VALUES(?,'MPP12 母件','件','COMMERCIAL','MPP12-fixture',1,'300')", parentCode);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag) VALUES(?,'MPP12 原料','KG','COMMERCIAL','MPP12-fixture',1)", raw);
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) "
        + "VALUES('210',?,'MPP12 原料','KG',100,0,'MPP12-S',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())", raw, date.toLocalDate().withDayOfMonth(1));
    if (copperLinked) {
      jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?", raw);
      MonthlyQuotationSourceFixture.copperLinked(jdbc, linkedRules, linkedSnapshots, effectivePrices,
          transactions, raw, month, "KG");
    }
    jdbc.update("INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,price_type,fixed_price,factor_bindings_json,origin_type,rule_source_month,content_hash,created_by,created_at,updated_by,updated_at) "
        + "VALUES('COMMERCIAL',?,?,'MPP12 废料','FIXED',10,'{}','IMPORT',?,?,'MPP12',NOW(),'MPP12',NOW())", month, scrapCode, month, "f".repeat(64));
    var child = new com.sanhua.marketingcost.entity.BomU9Source();
    child.setImportBatchId(key); child.setSourceType("U9"); child.setPriceOrgCode("210");
    child.setImportedAt(date); child.setImportedBy("MPP12");
    child.setParentMaterialNo(parentCode); child.setChildMaterialNo(raw); child.setStockUnit("KG");
    child.setBomPurpose("主制造"); child.setBomVersion("MPP12"); child.setChildSeq(1);
    child.setQtyPerParent(new BigDecimal("0.4")); child.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1)); child.setEffectiveTo(java.time.LocalDate.of(2099, 1, 1));
    u9Rows.insert(child);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code) VALUES(?,'商用蒸发器','件','COMMERCIAL','MPP12-fixture',1,'10')", key);
    manufacturingRawTree(key, parentCode, raw, date, new BigDecimal("2.5"));
    zeroPackaging(key, date);
    ordinaryExpenseSources(key, form, date);
    var mapping = new com.sanhua.marketingcost.entity.MaterialScrapRef();
    mapping.setMaterialCode(raw); mapping.setScrapCode(scrapCode); mapping.setScrapUnit("KG");
    mapping.setBusinessUnitType("COMMERCIAL"); mapping.setRatio(BigDecimal.ONE); scrapMappings.insert(mapping);
    // Use the existing special-purchase rollup rule to produce a manufacturing parent and
    // its actual base sub-reference; the test never inserts settlement rows itself.
    var rollup = new com.sanhua.marketingcost.entity.BomSettlementRule();
    rollup.setRuleCode(key); rollup.setRuleName(key); rollup.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rollup.setSettlementAction("ROLLUP_TO_PARENT"); rollup.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    rollup.setSubRefType("SPECIAL_ROLLUP_CHILD"); rollup.setMarkSubtreeCostRequired(1);
    rollup.setMatchConditionJson("{\"nodeConditions\":[{\"field\":\"material_code\",\"op\":\"EQ\",\"value\":\"" + raw + "\"}]}");
    rollup.setPriority(1); rollup.setEnabled(1); rollup.setDeleted(0); rollup.setBusinessUnitType("COMMERCIAL");
    settlementRules.insert(rollup);
    var parent = buildOriginalFixture(form, item, key, month, date, parentCode, Map.of(raw, "KG"));
    var plan = new PricePreparePlanItem(); plan.setBomRow(parent); plan.setMaterialCode(parentCode);
    plan.setTopProductCode(key); plan.setItemType("MAKE"); plan.setStatus("READY");
    var scenario = PricePrepareScenarioContext.oaLocked(item, "210");
    var preview = manufacturing.calculate(key,"COMMERCIAL",month,date,scenario,plan);
    assertThat(preview.getStatus()).as(preview.getMessage()).isEqualTo("READY");
    assertThat(preview.getUnitPrice()).isEqualByComparingTo(copperLinked ? "2.2" : "39");
    assertThat(preview.getAmount()).isEqualByComparingTo(copperLinked ? "5.5" : "97.5");
    var uses = scenario.monthlyPrices().get().plan().uses();
    assertThat(uses).hasSize(2);
    assertThat(uses.stream().filter(use -> use.request().family().materialCode().equals(raw)).findFirst().orElseThrow().cumulativeQuantity()).isEqualByComparingTo("1");
    assertThat(uses.stream().filter(use -> use.request().family().materialCode().equals(scrapCode)).findFirst().orElseThrow().cumulativeQuantity()).isEqualByComparingTo("0.25");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code IN (?,?,?)",Integer.class,parentCode,raw,scrapCode)).isZero();
    var request = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    request.setOaNo(key); request.setOaFormItemId(item); request.setTopProductCode(key); request.setPeriodMonth(month);
    request.setPriceAsOfTime(date); request.setBusinessUnitType("COMMERCIAL");
    var prepared = generateThroughWorkbench(request);
    assertThat(prepared.getStatus()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForList("SELECT material_code FROM lp_material_monthly_price WHERE material_code IN (?,?,?)",String.class,parentCode,raw,scrapCode)).containsExactlyInAnyOrder(raw,scrapCode);
    var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    var stored = json.readTree(jdbc.queryForObject("SELECT input_snapshot_json FROM lp_price_prepare_item WHERE prepare_no=?", String.class, prepared.getPrepareNo()));
    assertThat(stored.path("monthlyInputs").isTextual()).as("制造准备必须保存实际采用请求、基础用量和依赖闭包").isTrue();
    var inputs = json.readTree(json.readTree(stored.path("monthlyInputs").asText()).path("document").asText());
    assertThat(inputs.path("prices")).hasSize(2);
    assertThat(inputs.path("uses")).hasSize(2);
    assertThat(inputs.path("rootPath").asText()).isEqualTo(MonthlyBomPricingScope.pathKey(parent.getPath()));
    var quantities = new HashMap<String, BigDecimal>();
    inputs.path("uses").forEach(use -> quantities.put(use.path("request").path("family").path("materialCode").asText(), use.path("cumulativeQuantity").decimalValue()));
    assertThat(quantities.get(raw)).isEqualByComparingTo("1");
    assertThat(quantities.get(scrapCode)).isEqualByComparingTo("0.25");
    inputs.path("prices").forEach(price -> assertThat(price.path("selection").path("monthlyPriceId").asLong()).isPositive());
    var finance = financePreparations.loadPreparedFromOa(prepared.getPrepareNo());
    var financial = jdbc.queryForMap("SELECT unit_price,amount,input_snapshot_json FROM lp_price_prepare_item WHERE prepare_no=?", finance.financePrepareNo());
    assertThat((BigDecimal) financial.get("unit_price")).isEqualByComparingTo(copperLinked ? "3" : "39");
    assertThat((BigDecimal) financial.get("amount")).isEqualByComparingTo(copperLinked ? "7.5" : "97.5");
    assertThat(financePreparations.loadPreparedFromOa(prepared.getPrepareNo()).financePrepareNo()).isEqualTo(finance.financePrepareNo());
    saveAggregateCost(key, item, month, parentCode, prepared.getPrepareNo(), finance.financePrepareNo(),
        copperLinked ? "7.5" : "97.5", copperLinked ? "5.5" : "97.5");
    var damaged = json.readTree(financial.get("input_snapshot_json").toString());
    ((com.fasterxml.jackson.databind.node.ObjectNode) damaged.path("rows").get(0)).put("rawUnitPrice", new BigDecimal("999"));
    jdbc.update("UPDATE lp_price_prepare_item SET input_snapshot_json=? WHERE prepare_no=?", damaged.toString(), finance.financePrepareNo());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> financePreparations.loadPreparedFromOa(prepared.getPrepareNo()))
        .as("保存的自制件用价损坏必须拒绝，不能以当前计算结果表掩盖")
        .isInstanceOf(IllegalStateException.class);
    jdbc.update("UPDATE lp_price_prepare_item SET input_snapshot_json=? WHERE prepare_no=?", financial.get("input_snapshot_json").toString(), finance.financePrepareNo());
    jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=180 WHERE material_code=?", raw);
    // A new quote's original tree doubles both the parent and the consumed base quantities.
    // Updating only a persisted parent would leave the rollup numerator frozen at 1 KG and
    // incorrectly describe 0.2 KG per parent; that is not the intended quantity-change case.
    String repeatKey = key + "-Q2";
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", repeatKey);
    long repeatForm = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, repeatKey);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,'商用蒸发器','COMMERCIAL')", repeatForm, repeatKey);
    long repeatItem = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, repeatForm);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code) VALUES(?,'商用蒸发器','件','COMMERCIAL','MPP12-fixture',1,'10')", repeatKey);
    manufacturingRawTree(repeatKey, parentCode, raw, date, new BigDecimal("5"));
    var repeatParent = buildOriginalFixture(repeatForm, repeatItem, repeatKey, month, date, parentCode, Map.of(raw, "KG"));
    assertThat(repeatParent.getQtyPerTop()).isEqualByComparingTo("5");
    var repeatPlan = new PricePreparePlanItem(); repeatPlan.setBomRow(repeatParent);
    repeatPlan.setMaterialCode(parentCode); repeatPlan.setTopProductCode(repeatKey);
    repeatPlan.setItemType("MAKE"); repeatPlan.setStatus("READY");
    var repeatScenario = PricePrepareScenarioContext.oaLocked(repeatItem, "210");
    var repeated = manufacturing.calculate(repeatKey,"COMMERCIAL",month,date,repeatScenario,repeatPlan);
    assertThat(repeated.getUnitPrice()).isEqualByComparingTo(copperLinked ? "2.2" : "39");
    assertThat(repeated.getAmount()).isEqualByComparingTo(copperLinked ? "11" : "195");
    var repeatUses = repeatScenario.monthlyPrices().get().plan().uses();
    assertThat(repeatUses.stream().filter(use -> raw.equals(use.request().family().materialCode())).findFirst().orElseThrow().cumulativeQuantity()).isEqualByComparingTo("2");
    assertThat(repeatUses.stream().filter(use -> scrapCode.equals(use.request().family().materialCode())).findFirst().orElseThrow().cumulativeQuantity()).isEqualByComparingTo("0.5");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code IN (?,?,?)", Integer.class, parentCode, raw, scrapCode)).isEqualTo(2);
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void packagingUsesMonthlyChildrenWithCurrentQuantitiesAndBothExistingDenominators(boolean copperLinked) {
    String key = "MPP12-" + UUID.randomUUID().toString().substring(0, 12);
    String parentCode = key + "-PACK", first = key + "-A", second = key + "-B";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(15).atStartOfDay();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')", form, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    for (String code : List.of(first, second)) {
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag) VALUES(?,'MPP12 包装材料','张','COMMERCIAL','MPP12-fixture',1)", code);
      jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) "
          + "VALUES('210',?,'MPP12 包装材料','张',?,0,'MPP12-S',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())", code, code.equals(first) ? 7 : 2, date.toLocalDate().withDayOfMonth(1));
    }
    if (copperLinked) {
      jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?", first);
      MonthlyQuotationSourceFixture.copperLinked(jdbc, linkedRules, linkedSnapshots, effectivePrices,
          transactions, first, month, "张");
    }
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_name,main_category_code,shape_attr) VALUES(?,'MPP12 包装母件','套','COMMERCIAL','MPP12-fixture',1,'包装组件','15155','虚拟')", parentCode);
    var snapshot = new com.sanhua.marketingcost.entity.PackageComponentSnapshot();
    snapshot.setPackageMaterialCode(parentCode); snapshot.setPackageMaterialName("MPP12 包装母件");
    snapshot.setPeriodMonth(month); snapshot.setPriceOrgCode("210"); snapshot.setBusinessUnitType("COMMERCIAL");
    snapshot.setSourceTopProductCode(key); snapshot.setSourceBomPurpose("主制造"); snapshot.setSourceBomSourceType("U9");
    snapshot.setStatus("NORMAL"); snapshot.setPackageParentBaseQty(new BigDecimal("5"));
    packageSnapshots.insert(snapshot);
    for (int index = 0; index < 3; index++) {
      var detail = new com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail();
      detail.setSnapshotId(snapshot.getId()); detail.setPackageMaterialCode(parentCode); detail.setPeriodMonth(month);
      detail.setLineNo(index + 1); detail.setChildMaterialCode(index == 0 ? first : index == 1 ? second : key + "-UNUSED");
      detail.setChildMaterialName("MPP12 包装子件"); detail.setChildShapeAttr("采购件");
      detail.setQtyPerParent(new BigDecimal(index == 0 ? "12" : index == 1 ? "3" : "0"));
      detail.setChildParentBaseQty(new BigDecimal(index == 0 ? "3" : "2"));
      detail.setBusinessUnitType("COMMERCIAL"); packageDetails.insert(detail);
    }
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'件','COMMERCIAL',?,1,'10','制造件')", key, key, key);
    for (int index = 0; index < 4; index++) {
      String code = index == 0 ? key : index == 1 ? parentCode : index == 2 ? first : second;
      String path = "/" + key + "/" + (index == 0 ? "" : parentCode + "/")
          + (index < 2 ? "" : code + "/");
      var source = new com.sanhua.marketingcost.entity.BomRawHierarchy();
      source.setTopProductCode(key); source.setParentCode(index < 2 ? key : parentCode);
      source.setMaterialCode(code); source.setMaterialName(code); source.setPath(path);
      source.setLevel(index == 0 ? 0 : index == 1 ? 1 : 2); source.setSortSeq(index);
      source.setQtyPerParent(new BigDecimal(index == 0 ? "1" : index == 1 ? "2.5" : index == 2 ? "12" : "3"));
      source.setQtyPerTop(new BigDecimal(index == 0 ? "1" : index == 1 ? "2.5" : index == 2 ? "2" : "0.75"));
      source.setShapeAttr(index == 0 ? "制造件" : index == 1 ? "虚拟" : "采购件");
      if (index == 1) { source.setMaterialCategory1("15155"); source.setMaterialCategory2("包装组件"); }
      source.setBomPurpose("主制造"); source.setBomVersion("MPP12"); source.setBomStatus("已核准");
      source.setIsLeaf(index > 1 ? 1 : 0); source.setU9IsCostFlag(1); source.setSourceType("U9");
      source.setSourceImportBatchId(key); source.setBuildBatchId(key); source.setBuiltAt(date);
      source.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1)); source.setEffectiveTo(java.time.LocalDate.of(2099, 1, 1)); source.setBusinessUnitType("COMMERCIAL");
      source.setPriceOrgCode("210"); rawHierarchy.insert(source);
    }
    ordinaryExpenseSources(key, form, date);
    var parent = buildOriginalFixture(form, item, key, month, date, parentCode,
        Map.of(parentCode, "套", first, "张", second, "张"));
    var plan = new PricePreparePlanItem(); plan.setBomRow(parent); plan.setMaterialCode(parentCode);
    plan.setTopProductCode(key); plan.setItemType("PACK"); plan.setStatus("READY");
    var scenario = PricePrepareScenarioContext.oaLocked(item, "210");
    var preview = packaging.calculate(key,key,month,date,"主制造","U9",plan,scenario);
    assertThat(preview.getStatus()).as(preview.getMessage()).isEqualTo("READY");
    assertThat(preview.getUnitPrice()).isEqualByComparingTo(copperLinked ? "7" : "6.2");
    assertThat(preview.getAmount()).isEqualByComparingTo(copperLinked ? "17.5" : "15.5");
    assertThat(scenario.monthlyPrices().get()).as("包装基础单价必须来自同次月价会话").isNotNull();
    var uses = scenario.monthlyPrices().get().plan().uses();
    assertThat(uses).hasSize(2);
    assertThat(uses.stream().filter(use -> use.request().family().materialCode().equals(first)).findFirst().orElseThrow().cumulativeQuantity()).isEqualByComparingTo("2");
    assertThat(uses.stream().filter(use -> use.request().family().materialCode().equals(second)).findFirst().orElseThrow().cumulativeQuantity()).isEqualByComparingTo("0.75");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code IN (?,?,?)", Integer.class, parentCode, first, second)).isZero();
    var request = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    request.setOaNo(key); request.setOaFormItemId(item); request.setTopProductCode(key); request.setPeriodMonth(month);
    request.setPriceAsOfTime(date); request.setBusinessUnitType("COMMERCIAL");
    var saved = generateThroughWorkbench(request);
    assertThat(saved.getStatus()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForList("SELECT material_code FROM lp_material_monthly_price WHERE material_code IN (?,?,?)", String.class, parentCode, first, second)).containsExactlyInAnyOrder(first,second);
    var finance = financePreparations.loadPreparedFromOa(saved.getPrepareNo());
    assertThat(finance.prepareResult().getStatus()).isEqualTo("SUCCESS");
    var financeRow = jdbc.queryForMap("SELECT unit_price,amount FROM lp_price_prepare_item WHERE prepare_no=?", finance.financePrepareNo());
    assertThat((BigDecimal) financeRow.get("unit_price")).isEqualByComparingTo(copperLinked ? "8.6" : "6.2");
    assertThat((BigDecimal) financeRow.get("amount")).isEqualByComparingTo(copperLinked ? "21.5" : "15.5");
    assertThat(financePreparations.loadPreparedFromOa(saved.getPrepareNo()).financePrepareNo()).isEqualTo(finance.financePrepareNo());
    saveAggregateCost(key, item, month, parentCode, saved.getPrepareNo(), finance.financePrepareNo(),
        copperLinked ? "21.5" : "15.5", copperLinked ? "17.5" : "15.5");
    jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=70 WHERE material_code=?", first);
    parent.setQtyPerTop(new BigDecimal("5")); bomRows.updateById(parent);
    var repeated = packaging.calculate(key,key,month,date,"主制造","U9",plan,PricePrepareScenarioContext.oaLocked(item,"210"));
    assertThat(repeated.getUnitPrice()).isEqualByComparingTo(copperLinked ? "7" : "6.2");
    assertThat(repeated.getAmount()).isEqualByComparingTo(copperLinked ? "35" : "31");
    jdbc.update("UPDATE lp_package_component_snapshot SET package_parent_base_qty=10 WHERE id=?", snapshot.getId());
    jdbc.update("UPDATE lp_package_component_snapshot_detail SET qty_per_parent=18 WHERE snapshot_id=? AND child_material_code=?", snapshot.getId(), first);
    var changed = packaging.calculate(key,key,month,date,"主制造","U9",plan,PricePrepareScenarioContext.oaLocked(item,"210"));
    assertThat(changed.getUnitPrice()).isEqualByComparingTo(copperLinked ? "5.1" : "4.5");
    assertThat(changed.getAmount()).isEqualByComparingTo(copperLinked ? "25.5" : "22.5");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void aHouseholdStructuralParentIsSelectedBeforeOrdinaryPackagingAndItsChildrenAreNotPriced(boolean sourceChangedAfterTree) throws Exception {
    String key = "MPP12-" + UUID.randomUUID().toString().substring(0, 12);
    String parentCode = key + "-HH", childCode = key + "-BASE";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(15).atStartOfDay();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?, ?,?,'COMMERCIAL')", form, key, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    ordinarySources(key, parentCode, form, date);
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='15155',main_category_name='包装组件',shape_attr='虚拟' WHERE material_code=?", parentCode);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET shape_attr='虚拟',is_leaf=0,material_category_1='15155',material_category_2='包装组件' WHERE top_product_code=? AND material_code=?", key, parentCode);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,shape_attr) VALUES(?,?,'件','COMMERCIAL',?,1,'采购件')", childCode, childCode, key);
    var child = new com.sanhua.marketingcost.entity.BomRawHierarchy();
    child.setTopProductCode(key); child.setParentCode(parentCode); child.setMaterialCode(childCode); child.setMaterialName(childCode);
    child.setPath("/" + key + "/" + parentCode + "/" + childCode + "/"); child.setLevel(2); child.setSortSeq(1);
    child.setQtyPerParent(new BigDecimal("2")); child.setQtyPerTop(new BigDecimal("5")); child.setShapeAttr("采购件");
    child.setBomPurpose("主制造"); child.setBomVersion("MPP12"); child.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1)); child.setEffectiveTo(java.time.LocalDate.of(2099, 1, 1));
    child.setBomStatus("已核准"); child.setIsLeaf(1); child.setU9IsCostFlag(1); child.setSourceType("U9");
    child.setSourceImportBatchId(key); child.setBuildBatchId(key); child.setBuiltAt(date); child.setPriceOrgCode("210");
    child.setBusinessUnitType("COMMERCIAL"); rawHierarchy.insert(child);
    var original = monthlyU9.read(new com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext(
        form, item, key, month, "COMMERCIAL", key, key, null, null, "210", "COMMERCIAL", date.toLocalDate(), date));
    assertThat(original.status()).as(original.message()).isEqualTo(com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult.Status.AVAILABLE);
    var rawRows = rawHierarchy.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
        .lambdaQuery(com.sanhua.marketingcost.entity.BomRawHierarchy.class)
        .eq(com.sanhua.marketingcost.entity.BomRawHierarchy::getBuildBatchId, key));
    var keys = new HashMap<String, String>(); rawRows.forEach(raw -> keys.put(raw.getPath(), "NODE-" + raw.getId()));
    String build = key + "-EFFECTIVE";
    for (var raw : rawRows) {
      var node = new com.sanhua.marketingcost.entity.QuoteEffectiveBomNode();
      node.setOriginMonthlySnapshotId(original.monthlySnapshotId()); node.setEffectiveVariantHash(original.structureFingerprint());
      node.setBuildBatchId(build); node.setTopProductCode(key); node.setCostPeriodMonth(month); node.setPriceOrgCode("210");
      node.setNodeKey(keys.get(raw.getPath()));
      int slash = raw.getPath().lastIndexOf('/', raw.getPath().length() - 2);
      node.setParentNodeKey(raw.getLevel() == 0 ? null : keys.get(raw.getPath().substring(0, slash + 1)));
      node.setNodeLevel(raw.getLevel()); node.setSortSeq(raw.getSortSeq()); node.setNodePath(raw.getPath());
      node.setMaterialCode(raw.getMaterialCode()); node.setMaterialName(raw.getMaterialName()); node.setMaterialUnit("件");
      node.setQtyPerParent(raw.getQtyPerParent()); node.setQtyPerTop(raw.getQtyPerTop());
      node.setEffectiveMaterialShape(com.sanhua.marketingcost.enums.QuoteMaterialShape.fromU9(raw.getShapeAttr()).name());
      node.setSourceMaterialShape(node.getEffectiveMaterialShape()); node.setShapeResolutionSource("U9");
      node.setSourceBomType("U9"); node.setSourceBomBatchId(key); node.setSourceHierarchyId(raw.getId()); node.setSourceNodePath(raw.getPath());
      effectiveNodes.insert(node);
    }
    var record = new com.sanhua.marketingcost.entity.QuoteBomPreparationRecord();
    record.setOaFormId(form); record.setOaFormItemId(item); record.setOaNo(key); record.setQuoteProductCode(key);
    record.setPriceOrgCode("210"); record.setMaterialOrganizationCode("COMMERCIAL"); record.setProductType("NON_BARE");
    record.setCostPeriodMonth(month); record.setPreparationStatus("READY"); record.setActiveFlag(1); preparationRecords.insert(record);
    if (!sourceChangedAfterTree) publishHousehold(parentCode, month);
    costingBoms.buildFromEffectiveBom(item, build);
    if (sourceChangedAfterTree) publishHousehold(parentCode, month);
    var priced = bomRows.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(BomCostingRow.class)
        .eq(BomCostingRow::getOaFormItemId, item));
    var household = priced.stream().filter(row -> parentCode.equals(row.getMaterialCode())).findFirst().orElseThrow();
    assertThat(household.getSettlementRowType()).isEqualTo(sourceChangedAfterTree ? "PACKAGE_PARENT" : "HOUSEHOLD_INQUIRY");
    if (!sourceChangedAfterTree) assertThat(household.getSubtreeCostRequired()).isZero();
    assertThat(household.getQtyPerTop()).isEqualByComparingTo("2.5");
    assertThat(priced).noneSatisfy(row -> assertThat(row.getMaterialCode()).isEqualTo(childCode));
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code IN (?,?)", Integer.class, parentCode, childCode)).isZero();
    var request = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    request.setOaNo(key); request.setOaFormItemId(item); request.setTopProductCode(key); request.setPeriodMonth(month);
    request.setPriceAsOfTime(date); request.setBusinessUnitType("COMMERCIAL");
    var preview = preparations.calculate(request);
    assertThat(preview.getSummary().getStatus()).as(preview.getSummary().getMessage()).isEqualTo("SUCCESS");
    assertThat(preview.getItems()).hasSize(1);
    assertThat(preview.getItems().getFirst().getUnitPrice()).isEqualByComparingTo("20.9853");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code IN (?,?)", Integer.class, parentCode, childCode)).isZero();
    assertThat(bomRows.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(BomCostingRow.class)
        .eq(BomCostingRow::getOaFormItemId, item)).stream().filter(row -> parentCode.equals(row.getMaterialCode()))
        .findFirst().orElseThrow().getSettlementRowType()).isEqualTo(household.getSettlementRowType());
    var saved = generateThroughWorkbench(request);
    assertThat(saved.getStatus()).as(saved.getMessage()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForList("SELECT material_code FROM lp_material_monthly_price WHERE material_code IN (?,?)", String.class, parentCode, childCode)).containsExactly(parentCode);
    assertThat(jdbc.queryForObject("SELECT monthly_pricing_type FROM lp_price_prepare_item WHERE prepare_no=?", String.class, saved.getPrepareNo())).isEqualTo("HOUSEHOLD_INQUIRY");
    var encoded = jdbc.queryForObject("SELECT input_snapshot_json FROM lp_price_prepare_item WHERE prepare_no=?", String.class, saved.getPrepareNo());
    var adopted = new MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper()).decode(encoded, MonthlyPreparationPrice.class);
    assertThat(adopted.monthlyInputs().suppressedPaths()).as("正式家用准备必须保存原树中被覆盖的路径")
        .anySatisfy(path -> assertThat(MonthlyBomPricingScope.pathKey(path)).isEqualTo(key + "/" + parentCode + "/" + childCode));
    financePreparations.loadPreparedFromOa(saved.getPrepareNo());
    var cost = quotationCosting.calculate(new com.sanhua.marketingcost.dto.financequote.QuoteCuAdjustmentCalcRequest(
        oaForms.selectById(form), oaItems.selectById(item), month, saved.getPrepareNo(), key, null, true));
    assertThat(cost.financeMaterialCost()).as("家用整体不得重复计入普通 M0").isZero();
    assertThat(cost.totalCost()).isEqualByComparingTo("66.611562");
    assertThat(cost.finalQuoteAmount()).isEqualByComparingTo("66.611562");
    var stored = monthlyCosts.read("COMMERCIAL", cost.version().getId());
    assertThat(stored.snapshot().calculation().householdSubtotal()).isEqualByComparingTo("62.133895");
    assertThat(stored.parts()).hasSize(1);
    assertThat(stored.parts().getFirst().getPartCode()).isEqualTo(parentCode);
  }

  @Test
  void workbenchFinanceFailureRollsBackBothPreparationsAndRetryReusesAdoptedFact() {
    String key = "MPP12-TX-" + UUID.randomUUID().toString().substring(0, 10);
    String material = key + "-PART";
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(15).atStartOfDay();
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)", key);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, key);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')", form, key);
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    ordinarySources(key, material, form, date);
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) "
        + "VALUES('210',?,?,'件',11.3,0,'MPP12-S',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())", material, material, date.toLocalDate().withDayOfMonth(1));
    buildOriginalFixture(form, item, key, month, date, material, Map.of());
    var request = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest();
    request.setOaNo(key); request.setOaFormItemId(item); request.setTopProductCode(key);
    request.setPeriodMonth(month); request.setPriceAsOfTime(date); request.setBusinessUnitType("COMMERCIAL");
    // This extra disposable-test trigger only rejects this fixture's finance write. Existing
    // production monthly-reference triggers and their locks remain active throughout the test.
    String trigger = "mpp12_finance_reject_" + key.replace("-", "");
    jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON lp_price_prepare_batch FOR EACH ROW BEGIN "
        + "IF NEW.oa_no='" + key + "' AND NEW.scenario_type='FINANCE_QUOTE_BASE' THEN "
        + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP12_FINANCE_PROJECTION_REJECT'; END IF; END");
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> generateThroughWorkbench(request))
          .hasStackTraceContaining("MPP12_FINANCE_PROJECTION_REJECT");
    } finally { jdbc.execute("DROP TRIGGER " + trigger); }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_batch WHERE oa_no=?", Integer.class, key)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_item WHERE oa_no=?", Integer.class, key)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_costing_workspace WHERE oa_form_item_id=? AND current_prepare_no IS NOT NULL", Integer.class, item)).isZero();
    long adopted = jdbc.queryForObject("SELECT id FROM lp_material_monthly_price WHERE material_code=?", Long.class, material);
    var saved = generateThroughWorkbench(request);
    assertThat(saved.getStatus()).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_batch WHERE oa_no=?", Integer.class, key)).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT monthly_price_id FROM lp_price_prepare_item WHERE oa_no=? ORDER BY id", Long.class, key)).containsExactly(adopted, adopted);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?", Integer.class, material)).isEqualTo(1);
  }

  private com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult generateThroughWorkbench(
      com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest request) {
    var command = new com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest();
    command.setPeriodMonth(request.getPeriodMonth()); command.setPriceAsOfTime(request.getPriceAsOfTime());
    command.setIncludeFinanceComparison(true);
    var result = workbench.generate(request.getOaNo(), request.getOaFormItemId(), command);
    assertThat(result.getGeneratedResult().getStatus()).as(result.getGeneratedResult().getMessage()).isEqualTo("SUCCESS");
    assertThat(result.getFinanceGeneratedResult().getStatus()).as(result.getFinanceGeneratedResult().getMessage()).isEqualTo("SUCCESS");
    assertThat(result.getSourceRevision()).isNotBlank();
    return result.getGeneratedResult();
  }

  private void publishHousehold(String parentCode, String month) throws Exception {
    try (var stream = getClass().getResourceAsStream("/household-inquiry/real-20261023.xls");
         var book = org.apache.poi.ss.usermodel.WorkbookFactory.create(stream);
         var bytes = new java.io.ByteArrayOutputStream()) {
      var sheet = book.getSheet("10月线圈询价");
      for (int index = sheet.getLastRowNum(); index > 2; index--)
        if (sheet.getRow(index) != null) sheet.removeRow(sheet.getRow(index));
      sheet.getRow(2).getCell(0).setCellValue(parentCode); sheet.getRow(2).getCell(4).setCellValue("20.9853"); book.write(bytes);
      var preview = householdInquiries.preview("COMMERCIAL", "MPP12", bytes.toByteArray(), "mpp12-controlled-household.xls", month,
          new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.PriceContext("件", "CNY", TaxBasis.EXCLUDED));
      householdInquiries.publish("COMMERCIAL", "MPP12", new com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.PublishRequest(
          preview.previewId(), preview.publishRequestId(), preview.previewFingerprint()));
    }
  }

  private void manufacturingRawTree(String top, String parent, String raw, LocalDateTime date,
      BigDecimal parentQuantity) {
    for (int level = 0; level < 3; level++) {
      String code = level == 0 ? top : level == 1 ? parent : raw;
      var source = new com.sanhua.marketingcost.entity.BomRawHierarchy();
      source.setTopProductCode(top); source.setParentCode(level < 2 ? top : parent); source.setMaterialCode(code);
      source.setPath("/" + top + "/" + (level == 0 ? "" : parent + "/") + (level < 2 ? "" : raw + "/"));
      source.setLevel(level); source.setSortSeq(1);
      source.setQtyPerParent(level == 0 ? BigDecimal.ONE : level == 1 ? parentQuantity : new BigDecimal("0.4"));
      source.setQtyPerTop(level == 0 ? BigDecimal.ONE : level == 1 ? parentQuantity : parentQuantity.multiply(new BigDecimal("0.4")));
      source.setMaterialName(code); source.setShapeAttr(level == 2 ? "采购件" : "制造件");
      source.setBomPurpose("主制造"); source.setBomVersion("MPP12"); source.setBomStatus("已核准");
      source.setIsLeaf(level == 2 ? 1 : 0); source.setU9IsCostFlag(1); source.setSourceType("U9");
      source.setSourceImportBatchId(top); source.setBuildBatchId(top); source.setBuiltAt(date);
      source.setEffectiveFrom(date.toLocalDate().withDayOfMonth(1)); source.setEffectiveTo(java.time.LocalDate.of(2099, 1, 1)); source.setBusinessUnitType("COMMERCIAL");
      source.setPriceOrgCode("210"); rawHierarchy.insert(source);
    }
  }

  private BomCostingRow buildOriginalFixture(long form, long item, String key, String month,
      LocalDateTime date, String pricedMaterial, Map<String, String> units) {
    var original = monthlyU9.read(new com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext(
        form, item, key, month, "COMMERCIAL", key, key, null, null, "210", "COMMERCIAL", date.toLocalDate(), date));
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
      node.setBuildBatchId(build); node.setTopProductCode(key); node.setCostPeriodMonth(month); node.setPriceOrgCode("210");
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
    record.setPriceOrgCode("210"); record.setMaterialOrganizationCode("COMMERCIAL"); record.setProductType("NON_BARE");
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
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,production_division,product_attr) VALUES(?,?,'件','COMMERCIAL',?,1,?, ?,?,'标准品')",
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
      raw.setBuildBatchId(product); raw.setBuiltAt(date); raw.setPriceOrgCode("210"); raw.setBusinessUnitType("COMMERCIAL"); rawHierarchy.insert(raw);
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
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,main_category_name,shape_attr) VALUES(?,?,'件','COMMERCIAL',?,1,?,?,?)",
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
      raw.setPriceOrgCode("210"); raw.setBusinessUnitType("COMMERCIAL"); rawHierarchy.insert(raw);
    }
  }

  private void ordinaryExpenseSources(String product, long formId, LocalDateTime date) {
    String division = "MPP12-" + product;
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='11',production_division=?,product_attr='标准品' WHERE material_code=?", division, product);
    jdbc.update("UPDATE oa_form SET process_code='FI-SC-005',accounting_period_month=?,source_business_division=?,applicant_dept=?,expense_product_category='商用直销产品' WHERE id=?", YearMonth.from(date).toString(), division, division, formId);
    int year = date.getYear();
    for (String type : List.of("SALARY_DIRECT", "SALARY_INDIRECT"))
      jdbc.update("INSERT INTO cms_cost_source_effective(cost_year,source_type,parent_code,period,subject_code,source_table,source_row_ids,amount_yuan,business_unit_type) VALUES(?,?,?,?,?,'MPP12-controlled','[]',?,'COMMERCIAL')",
          year, type, product, YearMonth.from(date).toString(), type, type.equals("SALARY_DIRECT") ? 1 : 2);
    jdbc.update("INSERT INTO lp_quality_loss_rate(bare_product_code,rate_year,business_unit_type,loss_rate) VALUES(?,?,'COMMERCIAL',0.01)", product, year);
    jdbc.update("INSERT INTO lp_manufacture_rate(company,business_unit,product_category,product_subcategory,fee_rate,period,rate_year,business_unit_type,product_code,match_level,match_key) VALUES(?,'COMMERCIAL',?,?,0.1,?,?,'COMMERCIAL',?,'MATERIAL_CODE',?)",
        product, product, product, YearMonth.from(date).toString(), year, product, product);
    jdbc.update("INSERT INTO lp_three_expense_rate(company,business_unit,department,management_expense_rate,finance_expense_rate,sales_expense_rate,three_expense_rate_2025,three_expense_rate_2026,period,period_year,business_unit_type,product_category,product_line,applicant_department) VALUES(?,'COMMERCIAL',?,0.1,0.03,0.2,0.33,0.33,?,?,'COMMERCIAL','商用直销产品','国内产线',?)",
        product, division, YearMonth.from(date).toString(), year, division);
  }

  private void saveAggregateCost(String oa, long itemId, String month, String material,
      String customerNo, String financeNo, String expectedFinance, String expectedCustomer) {
    var customer = preparedRows.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
        .lambdaQuery(com.sanhua.marketingcost.entity.PricePrepareItem.class)
        .eq(com.sanhua.marketingcost.entity.PricePrepareItem::getPrepareNo, customerNo));
    var finance = preparedRows.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
        .lambdaQuery(com.sanhua.marketingcost.entity.PricePrepareItem.class)
        .eq(com.sanhua.marketingcost.entity.PricePrepareItem::getPrepareNo, financeNo));
    var codec = new MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper());
    var inputs = new MonthlyPreparationEvidence(codec).read(customer);
    var version = costVersions.createRunning(oa, itemId, oa, month, month, financeNo, "COMMERCIAL");
    version.setOaPricePrepareNo(customerNo); version.setFinancePricePrepareNo(financeNo); versionRows.updateById(version);
    var line = new com.sanhua.marketingcost.service.costing.MonthlyProductCost.MaterialLine(
        inputs.rootPath(), material, customer.getQuantity(), inputs.prices().stream()
            .map(price -> price.selection().monthlyPriceId()).distinct().toList(),
        finance.getAmount(), customer.getAmount(), null);
    var ordinary = new com.sanhua.marketingcost.service.costing.MonthlyProductCost.Ordinary(
        List.of(line), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
        BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    var result = monthlyCosts.calculateAndSave("COMMERCIAL", version.getId(), inputs.plan(), ordinary,
        null, new BigDecimal("80000"), Map.of(inputs.rootPath(),
            new com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.PreparationBinding(finance.getId(), customer.getId())));
    assertThat(result.version().getStatus()).isEqualTo("RUNNING");
    assertThat(result.version().getTotalCost()).isEqualByComparingTo(expectedFinance);
    assertThat(result.version().getFinalQuoteAmount()).isEqualByComparingTo(expectedCustomer);
    assertThat(result.parts()).as("聚合母件计入一次，基础料件只保存月价依据").hasSize(1);
    assertThat(result.parts().getFirst().getPartCode()).isEqualTo(material);
    assertThat(result.snapshot().calculation().adoptedPrices()).hasSize(2);
  }
}
