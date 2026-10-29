package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Tag("integration")
class MonthlyOdsPriceSourceIntegrationTest {
  static JdbcTemplate jdbc;static MonthlyOdsPriceSource source;static MonthlyMaterialPriceService operation;
  static MonthlyPriceDocumentCodec codec;static boolean useTiers;static AtomicInteger sourceCalls=new AtomicInteger();
  @BeforeAll static void start() throws Exception {
    MonthlyFixedPriceSourceIntegrationTest.database();jdbc=MonthlyFixedPriceSourceIntegrationTest.jdbc;
    var ds=jdbc.getDataSource();
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/ods-price-source-schema.sql")).execute(ds);
    var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
    for(var type:List.of(MonthlyOdsPriceSourceMapper.class,MonthlySupplierInputMapper.class,MonthlyPriceStorageMapper.class,MonthlyPriceLinkMapper.class))config.addMapper(type);
    var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
    var sql=new SqlSessionTemplate(factory.getObject());codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    source=new MonthlyOdsPriceSource(sql.getMapper(MonthlyOdsPriceSourceMapper.class),sql.getMapper(MonthlySupplierInputMapper.class),new SupplierPriceSelectionService(null,null),codec);
    var storage=new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class));
    operation=new MonthlyMaterialPriceService(storage,new DataSourceTransactionManager(ds),codec,(context,requests,dependencies)->{
      sourceCalls.incrementAndGet();return useTiers?source.tiers(context,requests):source.orders(context,requests);
    });
  }
  @AfterAll static void stop(){MonthlyFixedPriceSourceIntegrationTest.stop();}
  @BeforeEach void defaults(){useTiers=false;sourceCalls.set(0);}
  static String code(){return "ODS-"+UUID.randomUUID();}
  static Context context(String annual,String unit){return new Context("COMMERCIAL","ODS-TEST",1L,"FINISHED-ONE",YearMonth.of(2026,10),LocalDateTime.of(2026,10,25,0,0),"test","210",null,
      new AnnualUsageInput(annual==null?null:new BigDecimal(annual),unit));}
  static MonthlyPriceRequest request(String code,String annual){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",code,null,"只","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE,annual==null?null:new BigDecimal(annual));}
  static void order(String code,String supplier,String from,String price){jdbc.update("INSERT INTO ods_sy_v_mm_material_order_price_cg05_df(company_name,material_code,material_name,spec,supply_code,supply_name,unit,price,eff_date,source,order_no,order_line_no,dt) VALUES('浙江三花商用制冷有限公司',?,'fixture','SPEC',?,?,'只',?,?,'fixture order','ORDER-TRACE','7','2026-10-24')",code,supplier,supplier,new BigDecimal(price),from);}
  static void tier(String code,String supplier,String from,String to,String low,String high,String price){jdbc.update("INSERT INTO ods_sy_v_mm_material_jt_price_cg05_df(company_name,material_code,material_name,spec,supply_code,supply_name,unit,price,eff_date,exp_date,source,jieti_down,jieti_up,dt) VALUES('浙江三花商用制冷有限公司',?,'fixture','SPEC',?,?,'只',?,?,?,'fixture tier',?,?,'2026-10-24')",code,supplier,supplier,new BigDecimal(price),from,to,new BigDecimal(low),high==null?null:new BigDecimal(high));}
  static void ratio(String code,String supplier,String value){jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted,source_batch_no) VALUES('COMMERCIAL',?,'fixture','SPEC',?,?,?,1,0,'ODS-TEST')",code,supplier,supplier,new BigDecimal(value));}
  static Selection selected(Candidates result,MonthlyPriceRequest request){assertThat(result.problems()).isEmpty();return result.values().get(request).selection();}
  static MonthlyAdoptionPlan plan(MonthlyPriceRequest request,Selection selected){return new MonthlyAdoptionPlan(Map.of(request,selected),List.of(new Use("finished/material",request,BigDecimal.ONE)),Set.of());}

  @Test void currentDatedPriceWinsOverNullAndFuturePrices() {
    String code=code();order(code,"S1",null,"999");order(code,"S1","2026-09-01","888");order(code,"S1","2026-11-01","11");order(code,"S1","2026-11-01","12");
    var request=request(code,null);var price=selected(source.orders(context(null,null),Set.of(request)),request);
    assertThat(price.fact().price()).isEqualByComparingTo("888");
    assertThat(price.fact().evidence().json()).contains("ORDER-TRACE","2026-09-01","ODS_ORDER","supplierCode");
  }
  @Test void currentOrdersExcludeExpiredAndFutureSuppliersBeforeRatioSelection() {
    String code=code();order(code,"CURRENT","2026-01-01","10");
    order(code,"EXPIRED","2025-01-01","99");order(code,"FUTURE","2027-01-01","88");
    jdbc.update("UPDATE ods_sy_v_mm_material_order_price_cg05_df SET exp_date='2025-12-31' WHERE material_code=? AND supply_code='EXPIRED'",code);
    var r=request(code,null);var c=context(null,null);var result=source.orders(c,Set.of(r));
    assertThat(selected(result,r).fact().price()).isEqualByComparingTo("10");
    assertThat(c.supplierReviews().reviews()).isEmpty();
  }
  @Test void currentTierSetExcludesExpiredSuppliersAndFutureBands() {
    String code=code();tier(code,"CURRENT","2026-01-01","2026-12-31","1","20000","20");
    tier(code,"CURRENT","2026-01-01","2026-12-31","20000",null,"10");
    tier(code,"CURRENT","2027-01-01","2027-12-31","1",null,"88");
    tier(code,"EXPIRED","2025-01-01","2025-12-31","1",null,"99");
    var r=request(code,"360000");var c=context("360000","PIECES");var result=source.tiers(c,Set.of(r));
    assertThat(selected(result,r).fact().price()).isEqualByComparingTo("10");
    assertThat(c.supplierReviews().reviews()).isEmpty();
  }
  @Test void supplierWithOnlyNullDatesStillGetsAHighestOrderPriceAndNeedsNoAnnualUsage() {
    String code=code();order(code,"S1",null,"10");order(code,"S1",null,"12");var r=request(code,null);
    assertThat(selected(source.orders(context(null,null),Set.of(r)),r).fact().price()).isEqualByComparingTo("12");
  }
  @Test void expiredOrdersUseTheLatestExpiryBeforeTheHighestPrice() {
    String code=code();order(code,"S1","2025-01-01","999");order(code,"S1","2025-01-01","10");
    jdbc.update("UPDATE ods_sy_v_mm_material_order_price_cg05_df SET exp_date=IF(price=10,'2025-12-31','2025-06-30') WHERE material_code=?",code);
    var r=request(code,null);var picked=selected(source.orders(context(null,null),Set.of(r)),r);
    assertThat(picked.fact().price()).isEqualByComparingTo("10");assertThat(picked.fact().evidence().json()).contains("2025-12-31");
  }
  @Test void expiredTierSetsUseTheLatestExpiryWithoutMixingDifferentPeriodBands() {
    String code=code();tier(code,"S1","2025-01-01","2025-06-30","1",null,"99");
    tier(code,"S1","2025-01-01","2025-12-31","1","20000","20");tier(code,"S1","2025-01-01","2025-12-31","20000",null,"10");
    var r=request(code,"360000");var picked=selected(source.tiers(context("360000","PIECES"),Set.of(r)),r);
    assertThat(picked.fact().price()).isEqualByComparingTo("10");
    assertThat(picked.fact().evidence().json()).contains("2025-12-31").doesNotContain("2025-06-30");
  }
  @Test void ordersUseSupplyRatiosThenHighestPriceAmongEqualRatios() {
    String code=code();order(code,"S1","2026-01-01","10");order(code,"S2","2026-01-01","20");ratio(code,"S1","0.8");ratio(code,"S2","0.2");var r=request(code,null);
    assertThat(selected(source.orders(context(null,null),Set.of(r)),r).fact().price()).isEqualByComparingTo("10");
    jdbc.update("UPDATE lp_supplier_supply_ratio SET supply_ratio=0.5 WHERE material_code=?",code);
    assertThat(selected(source.orders(context(null,null),Set.of(r)),r).fact().price()).isEqualByComparingTo("20");
  }
  @Test void insufficientRatiosWaitAndExactConsumerApprovalAllowsHighestWithoutPublishingApproval() {
    String code=code();order(code,"S1",null,"10");order(code,"S2",null,"20");var r=request(code,null);var c=context(null,null);
    var pending=source.orders(c,Set.of(r));assertThat(pending.problems().get(r).reason()).isEqualTo(Failure.WAITING);
    var review=c.supplierReviews().reviews().getFirst();
    jdbc.update("INSERT INTO lp_supplier_price_decision(scope_key,fingerprint,business_unit_type,oa_no,oa_form_item_id,period_month,pricing_date,org_code,material_code,price_type,decision,confirmed_by,confirmed_at) VALUES(?,?,'COMMERCIAL','ODS-TEST',1,'2026-10','2026-10-25','210',?,'ODS_ORDER','FALLBACK_HIGH','ods-private-reviewer',NOW())",review.scopeKey(),review.fingerprint(),code);
    var next=context(null,null);var picked=selected(source.orders(next,Set.of(r)),r);
    assertThat(picked.fact().price()).isEqualByComparingTo("20");assertThat(next.supplierReviews().successfulReviews()).hasSize(1);
    assertThat(picked.fact().evidence().json()).doesNotContain("ods-private-reviewer","confirmedBy","scopeKey");
  }
  @Test void tiersFallBackToLatestSetWhenNoCurrentSetExistsAndKeepExactBounds() {
    String code=code();tier(code,"S1","2025-01-01","2025-12-31","1",null,"99");
    tier(code,"S1","2026-12-01","2027-11-30","1","20000","0.570");tier(code,"S1","2026-12-01","2027-11-30","20000","200000","0.375");tier(code,"S1","2026-12-01","2027-11-30","200000",null,"0.250");
    for(var pair:List.of(new String[]{"239999.999999","0.570"},new String[]{"240000","0.375"},new String[]{"360000","0.375"},new String[]{"2400000","0.250"})) {
      var r=request(code,pair[0]);var picked=selected(source.tiers(context(pair[0],"PIECES"),Set.of(r)),r);
      assertThat(picked.fact().price()).isEqualByComparingTo(pair[1]);assertThat(picked.fact().evidence().json()).contains("2027-11-30").doesNotContain("2026-12-31","2025-12-31");
    }
  }
  @Test void annualUsageIsValidatedWhenLatestTierRowsExistIncludingExpiredRows() {
    String live=code();String expired=code();String absent=code();tier(live,"S1","2026-01-01","2026-12-31","1",null,"10");tier(expired,"S1","2025-01-01","2025-12-31","1",null,"10");
    for(var c:List.of(context(null,null),context("0","PIECES"),context("3","UNKNOWN"))) {
      var requests=Set.of(request(live,null),request(expired,null),request(absent,null));var result=source.tiers(c,requests);
      assertThat(result.problems().get(request(live,null)).reason()).isEqualTo(Failure.INVALID_INPUT);
      assertThat(result.problems().get(request(expired,null)).reason()).isEqualTo(Failure.INVALID_INPUT);
      assertThat(result.problems().get(request(absent,null)).reason()).isEqualTo(Failure.MISSING);
    }
  }
  @Test void tenThousandPieceUnitsAreConvertedOnceAndNoBomConsumptionMultiplierIsUsed() {
    String code=code();tier(code,"S1","2026-01-01","2026-12-31","1","2500","10");tier(code,"S1","2026-01-01","2026-12-31","2500",null,"20");
    var base=request(code,"30000");var r=new MonthlyPriceRequest(base.family(),"210",base.conditions(),new BigDecimal("999"),base.annualUsagePieces());
    assertThat(selected(source.tiers(context("3","TEN_THOUSAND_PIECES"),Set.of(r)),r).fact().price()).isEqualByComparingTo("20");
  }
  @Test void supplyRatioCannotSelectASupplierWhoseQuantityBandDoesNotMatch() {
    String code=code();tier(code,"S1","2026-01-01","2026-12-31","1","50000","10");tier(code,"S2","2026-01-01","2026-12-31","50000",null,"20");ratio(code,"S1","0.1");ratio(code,"S2","0.9");var r=request(code,"360000");
    assertThat(selected(source.tiers(context("360000","PIECES"),Set.of(r)),r).fact().price()).isEqualByComparingTo("10");
  }
  @Test void adoptedTierSurvivesSourceReplacementAndReusesOnlyItsApplicableDemand() {
    useTiers=true;String code=code();tier(code,"S1","2026-01-01","2026-12-31","1","20000","10");tier(code,"S1","2026-01-01","2026-12-31","20000",null,"20");
    var r=request(code,"240000");var c=context("240000","PIECES");var candidate=selected(operation.resolveCandidates(c,Set.of(r)),r);
    var saved=operation.adopt(c,plan(r,candidate)).plan().selections().get(r);assertThat(saved.monthlyPriceId()).isPositive();
    jdbc.update("DELETE FROM ods_sy_v_mm_material_jt_price_cg05_df WHERE material_code=?",code);sourceCalls.set(0);
    var sameBand=request(code,"360000");var reused=selected(operation.resolveCandidates(context("360000","PIECES"),Set.of(sameBand)),sameBand);
    assertThat(reused.monthlyPriceId()).isEqualTo(saved.monthlyPriceId());assertThat(reused.fact().price()).isEqualByComparingTo("20");assertThat(sourceCalls.get()).isZero();
    assertThat(operation.lookup(Set.of(request(code,"120000"),request(code,null))).missing()).hasSize(2);
    assertThat(operation.readByIds("COMMERCIAL",Set.of(saved.monthlyPriceId())).get(saved.monthlyPriceId())).isEqualTo(saved);
  }
  @Test void generalSavedPriceBypassesTierAndAnnualUsageValidation() {
    useTiers=true;String code=code();var r=request(code,null);var fact=PriceFact.normal(new BigDecimal("7"),SourceEvidence.ofJson("{\"snapshotSchema\":1,\"sourceKind\":\"PURCHASE_FIXED\",\"source\":{\"selected\":{\"id\":1,\"fixedPrice\":7}}}"),Set.of());
    var candidate=new Selection(null,new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()),fact);
    operation.adopt(context(null,null),plan(r,candidate));tier(code,"S1","2026-01-01","2026-12-31","1",null,"10");sourceCalls.set(0);
    assertThat(selected(operation.resolveCandidates(context(null,null),Set.of(r)),r).fact().price()).isEqualByComparingTo("7");assertThat(sourceCalls.get()).isZero();
  }
  @Test void latestValidTierOverlapsWithDifferentPricesAreExplicitSourceErrors() {
    String code=code();tier(code,"S1","2026-01-01","2026-12-31","1",null,"10");tier(code,"S1","2026-01-01","2026-12-31","1",null,"20");var r=request(code,"360000");
    assertThat(source.tiers(context("360000","PIECES"),Set.of(r)).problems().get(r).reason()).isEqualTo(Failure.INVALID_INPUT);
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
  void thousandMaterialsUseBoundedQueriesForEachOdsSource(boolean tierSource) {
    String prefix="ODS-SCALE-"+UUID.randomUUID();var requests=new LinkedHashSet<MonthlyPriceRequest>();var values=new ArrayList<Object[]>();
    for(int i=0;i<1000;i++){String code=prefix+"-"+i;requests.add(request(code,"360000"));values.add(new Object[]{code});}
    String table=tierSource?"ods_sy_v_mm_material_jt_price_cg05_df":"ods_sy_v_mm_material_order_price_cg05_df";
    String extraColumns=tierSource?",exp_date,jieti_down,jieti_up":"";String extraValues=tierSource?",'2026-12-31',1,NULL":"";
    jdbc.batchUpdate("INSERT INTO "+table+"(company_name,material_code,supply_code,unit,price,eff_date,dt"+extraColumns+") VALUES('浙江三花商用制冷有限公司',?,'S1','只',10,'2026-01-01','2026-10-24'"+extraValues+")",values);
    MonthlyFixedPriceSourceIntegrationTest.prepared.set(0);long start=System.nanoTime();
    var result=tierSource?source.tiers(context("360000","PIECES"),requests):source.orders(context("360000","PIECES"),requests);
    long elapsed=(System.nanoTime()-start)/1_000_000;int sql=MonthlyFixedPriceSourceIntegrationTest.prepared.get();
    assertThat(result.values()).hasSize(1000);assertThat(result.problems()).isEmpty();
    assertThat(sql).as("SQL count for 1000 materials, including supplier inputs").isLessThanOrEqualTo(8);
    assertThat(elapsed).as("1000 ODS candidates, milliseconds").isLessThan(15000);
  }

  @Test void wrongOrganizationUnitOrSpecificationDoesNotReadAnotherCompanysPrice() {
    String code=code();order(code,"S1",null,"10");var base=request(code,null);
    var board=new MonthlyPriceRequest(base.family(),"220",base.conditions(),BigDecimal.ONE);
    assertThat(source.orders(context(null,null),Set.of(board)).problems().get(board).reason()).isEqualTo(Failure.MISSING);
    var other=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(base.family().month(),"COMMERCIAL",code,"OTHER-SPEC","KG","CNY",TaxBasis.EXCLUDED,null),"210",base.conditions(),BigDecimal.ONE);
    assertThat(source.orders(context(null,null),Set.of(other)).problems().get(other).reason()).isEqualTo(Failure.MISSING);
  }
  @Test void singleOrderAndTierPricesDoNotRequireSupplierRatios() {
    String code=code(); order(code,"S1","2026-01-01","10"); tier(code,"S1","2026-01-01","2027-12-31","0","100","10");
    var req=request(code,"10"); assertThat(source.orders(context("10","PIECES"),Set.of(req)).problems()).isEmpty();
    assertThat(source.tiers(context("10","PIECES"),Set.of(req)).problems()).isEmpty(); ratio(code,"S1","0");
    assertThat(source.orders(context("10","PIECES"),Set.of(req)).values()).hasSize(1);
    assertThat(source.tiers(context("10","PIECES"),Set.of(req)).values()).hasSize(1);
  }
}
