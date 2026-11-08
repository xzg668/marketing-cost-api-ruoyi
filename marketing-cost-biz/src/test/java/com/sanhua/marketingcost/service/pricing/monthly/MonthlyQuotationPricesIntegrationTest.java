package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.monthly.HouseholdInquiryStorageMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Use;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

@Tag("integration")
class MonthlyQuotationPricesIntegrationTest {
  static MonthlyQuotationPrices reader;

  @BeforeAll
  static void start() throws Exception {
    MonthlyFixedPriceSourceIntegrationTest.database();
    var config = new org.apache.ibatis.session.Configuration();
    config.setMapUnderscoreToCamelCase(true);
    config.addMapper(HouseholdInquiryStorageMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(MonthlyFixedPriceSourceIntegrationTest.jdbc.getDataSource());
    factory.setConfiguration(config);
    var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(HouseholdInquiryStorageMapper.class);
    var codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
    reader = new MonthlyQuotationPrices(MonthlyFixedPriceSourceIntegrationTest.operation,
        new HouseholdMonthlyPriceSource(new HouseholdInquiryStorage(mapper, codec), codec, org.mockito.Mockito.mock(com.sanhua.marketingcost.service.MaterialPricePriorityService.class), org.mockito.Mockito.mock(com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader.class)));
  }

  @AfterAll
  static void stop() {
    MonthlyFixedPriceSourceIntegrationTest.stop();
  }

  @Test
  void onlySupplementalPackagingAutomaticallyUsesHighestWithoutRatioAndAdoptedMonthIsReused() {
    String code = MonthlyFixedPriceSourceIntegrationTest.code();
    for (String supplier : List.of("P1", "P2"))
      MonthlyFixedPriceSourceIntegrationTest.price(code,"210",supplier,supplier.equals("P1")?"3":"5","2026-10-01","2099-01-01");
    var context = MonthlyFixedPriceSourceIntegrationTest.context();
    var ordinary = new MonthlyQuotationPrices.Item(code,"210","件",null,null,null,
        NonCuConditions.empty(),BigDecimal.ONE,MonthlyQuotationPrices.Purpose.MATERIAL);
    var packaging = new MonthlyQuotationPrices.Item(code,"210","件",null,null,null,
        NonCuConditions.empty(),BigDecimal.ONE,MonthlyQuotationPrices.Purpose.PACKAGE);
    var fresh = MonthlyFixedPriceSourceIntegrationTest.context();
    var result = reader.read(fresh,List.of(packaging));
    assertThat(result.problems()).isEmpty();
    var bound = result.values().get(packaging);
    assertThat(bound.candidate().selection().fact().price()).isEqualByComparingTo("5");
    assertThat(bound.candidate().selection().fact().evidence().json()).contains("PACKAGE_AUTO_HIGH");
    assertThat(fresh.supplierReviews().reviews()).isEmpty();
    assertThat(fresh.supplierReviews().packagingHighestFallback()).isFalse();
    assertThat(reader.read(context,List.of(ordinary)).problems().get(ordinary).reason())
        .isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);
    MonthlyFixedPriceSourceIntegrationTest.operation.adopt(fresh,new MonthlyAdoptionPlan(
        Map.of(bound.request(),bound.candidate().selection()),List.of(new Use("root/package",bound.request(),BigDecimal.ONE)),Set.of()));
    assertThat(reader.read(MonthlyFixedPriceSourceIntegrationTest.context(),List.of(ordinary)).values().get(ordinary)
        .candidate().selection().fact().price()).isEqualByComparingTo("5");
  }

  @Test
  void legacyRawAmountBindsItsTaxBasisOnceAndSavedPriceWinsOverNewSources() {
    String code = MonthlyFixedPriceSourceIntegrationTest.code();
    MonthlyFixedPriceSourceIntegrationTest.price(code, "210", "S1", "11.3", "2026-10-01", "2027-01-01");
    var db = MonthlyFixedPriceSourceIntegrationTest.jdbc;
    db.update("UPDATE lp_price_fixed_item SET tax_included=1 WHERE material_code=?", code);
    var context = MonthlyFixedPriceSourceIntegrationTest.context();
    var item = new MonthlyQuotationPrices.Item(code, "210", "件", null, null, null,
        NonCuConditions.empty(), BigDecimal.ONE, MonthlyQuotationPrices.Purpose.MATERIAL);
    var first = reader.read(context, List.of(item));
    assertThat(first.problems()).isEmpty();
    var bound = first.values().get(item);
    assertThat(bound.request().family().taxBasis()).isEqualTo(TaxBasis.INCLUDED);
    assertThat(bound.candidate().selection().monthlyPriceId()).isNull();
    assertThat(bound.candidate().selection().fact().price()).isEqualByComparingTo("11.3");
    var module = MonthlyFixedPriceSourceIntegrationTest.operation;
    assertThat(module.lookup(Set.of(bound.request())).missing()).containsExactly(bound.request());
    var adopted = module.adopt(context, new MonthlyAdoptionPlan(
        Map.of(bound.request(), bound.candidate().selection()),
        List.of(new Use("root/part", bound.request(), BigDecimal.ONE)), Set.of()));
    db.update("UPDATE lp_price_fixed_item SET fixed_price=19,tax_included=0 WHERE material_code=?", code);
    var repeated = reader.read(context, List.of(item));
    assertThat(repeated.problems()).isEmpty();
    assertThat(repeated.values().get(item).request()).isEqualTo(bound.request());
    assertThat(repeated.values().get(item).candidate().selection())
        .isEqualTo(adopted.plan().selections().get(bound.request()));
  }

  @Test void manyConsumersReuseOneBoundedSavedLookupWithIdenticalResultsAndNoWrites() {
    var context=MonthlyFixedPriceSourceIntegrationTest.context();
    var items=new java.util.ArrayList<MonthlyQuotationPrices.Item>();
    var selected=new java.util.LinkedHashMap<MonthlyPriceRequest,MonthlyAdoptionPlan.Selection>();
    var uses=new java.util.ArrayList<Use>();
    for(int i=0;i<10;i++) {
      String code=MonthlyFixedPriceSourceIntegrationTest.code();
      MonthlyFixedPriceSourceIntegrationTest.price(code,"210","S1","11.3","2026-10-01","2027-01-01");
      var item=new MonthlyQuotationPrices.Item(code,"210","件",null,TaxBasis.EXCLUDED,null,
          NonCuConditions.empty(),BigDecimal.ONE,MonthlyQuotationPrices.Purpose.MATERIAL);
      items.add(item);
      var bound=reader.read(context,List.of(item)).values().get(item);
      selected.put(bound.request(),bound.candidate().selection());
      uses.add(new Use("root/"+i,bound.request(),BigDecimal.ONE));
    }
    MonthlyFixedPriceSourceIntegrationTest.operation.adopt(context,new MonthlyAdoptionPlan(selected,uses,Set.of()));
    var demands=new java.util.ArrayList<MonthlyQuotationPrices.Demand>();
    for(long id=100;id<132;id++) demands.add(new MonthlyQuotationPrices.Demand(
        new MonthlyMaterialPrices.Context(context.businessUnitType(),"OA-"+id,id,"TOP-"+id,
            context.month(),context.priceAsOfTime(),context.actor(),context.consumerOrg()),items));
    var db=MonthlyFixedPriceSourceIntegrationTest.jdbc;
    var before=db.queryForList("SELECT * FROM lp_material_monthly_price ORDER BY id");
    int initial=MonthlyFixedPriceSourceIntegrationTest.prepared.get();
    var individual=demands.stream().map(demand -> reader.read(demand.context(),demand.items())).toList();
    int serialSql=MonthlyFixedPriceSourceIntegrationTest.prepared.get()-initial;
    initial=MonthlyFixedPriceSourceIntegrationTest.prepared.get();
    var prepared=reader.prepareBatch(demands);
    int batchSql=MonthlyFixedPriceSourceIntegrationTest.prepared.get()-initial;
    initial=MonthlyFixedPriceSourceIntegrationTest.prepared.get();
    var together=new java.util.ArrayList<MonthlyQuotationPrices.Batch>();
    for(int i=0;i<demands.size();i++) {
      var demand=demands.get(i);
      together.add(reader.usePrepared(prepared.get(i), () -> reader.read(demand.context(),demand.items())));
    }
    int consumeSql=MonthlyFixedPriceSourceIntegrationTest.prepared.get()-initial;
    assertThat(consumeSql).isZero();
    assertThat(together).isEqualTo(individual);
    assertThat(together).hasSize(32).allSatisfy(batch -> {
      assertThat(batch.problems()).isEmpty();assertThat(batch.values()).hasSize(10);
      assertThat(batch.candidates().values()).allSatisfy(value -> assertThat(value.selection().monthlyPriceId()).isNotNull());
    });
    assertThat(batchSql).isPositive().isLessThan(serialSql/8);
    System.out.println("REPRICE_INITIAL_PRICE_SQL serial="+serialSql+" preparation="+batchSql+" consumption="+consumeSql+" consumers=32");
    assertThat(db.queryForList("SELECT * FROM lp_material_monthly_price ORDER BY id")).isEqualTo(before);
    System.out.println("REPRICE_PRICE_READ_SQL serial="+serialSql+" batch="+batchSql+" consumers=32 materials=10");
  }

  @Test void mixedConsumersDoNotShareUnadoptedPackagingSupplierFallback() {
    String code=MonthlyFixedPriceSourceIntegrationTest.code();
    MonthlyFixedPriceSourceIntegrationTest.price(code,"210","P1","3","2026-10-01","2099-01-01");
    MonthlyFixedPriceSourceIntegrationTest.price(code,"210","P2","5","2026-10-01","2099-01-01");
    var ordinary=new MonthlyQuotationPrices.Item(code,"210","件",null,TaxBasis.EXCLUDED,null,
        NonCuConditions.empty(),BigDecimal.ONE,MonthlyQuotationPrices.Purpose.MATERIAL);
    var pack=new MonthlyQuotationPrices.Item(code,"210","件",null,TaxBasis.EXCLUDED,null,
        NonCuConditions.empty(),BigDecimal.ONE,MonthlyQuotationPrices.Purpose.PACKAGE);
    var context=MonthlyFixedPriceSourceIntegrationTest.context();
    var other=new MonthlyMaterialPrices.Context(context.businessUnitType(),"OTHER-OA",99L,"TOP",
        context.month(),context.priceAsOfTime(),context.actor(),context.consumerOrg());
    int before=MonthlyFixedPriceSourceIntegrationTest.jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Integer.class);
    var result=reader.readBatch(List.of(new MonthlyQuotationPrices.Demand(context,List.of(pack)),
        new MonthlyQuotationPrices.Demand(other,List.of(ordinary))));
    assertThat(result.get(0).values().get(pack).candidate().selection().fact().price()).isEqualByComparingTo("5");
    assertThat(result.get(1).values()).isEmpty();
    assertThat(result.get(1).problems().get(ordinary).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.WAITING);
    assertThat(MonthlyFixedPriceSourceIntegrationTest.jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price",Integer.class)).isEqualTo(before);
    assertThat(context.supplierReviews().packagingHighestFallback()).isFalse();
    assertThat(other.supplierReviews().packagingHighestFallback()).isFalse();
  }
  @Test void unadoptedPrefetchReadsFreshAtConsumptionAfterAnotherConsumerAdopts() {
    String code=MonthlyFixedPriceSourceIntegrationTest.code();
    MonthlyFixedPriceSourceIntegrationTest.price(code,"210","S1","2","2026-10-01","2027-01-01");
    var c=MonthlyFixedPriceSourceIntegrationTest.context();
    var item=new MonthlyQuotationPrices.Item(code,"210","件",null,TaxBasis.EXCLUDED,null,
        NonCuConditions.empty(),BigDecimal.ONE,MonthlyQuotationPrices.Purpose.MATERIAL);
    var first=reader.prepareBatch(List.of(new MonthlyQuotationPrices.Demand(c,List.of(item)))).getFirst();
    assertThat(first.batch().values()).isEmpty();
    assertThat(first.batch().problems().get(item).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);
    MonthlyFixedPriceSourceIntegrationTest.jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=3 WHERE material_code=?",code);
    var winner=reader.read(c,List.of(item)).values().get(item);
    var module=MonthlyFixedPriceSourceIntegrationTest.operation;
    var adopted=module.adopt(c,new MonthlyAdoptionPlan(Map.of(winner.request(),winner.candidate().selection()),
        List.of(new Use("root/part",winner.request(),BigDecimal.ONE)),Set.of()));
    var attempts=new java.util.concurrent.atomic.AtomicInteger();
    var actual=reader.usePrepared(first, () -> new MonthlyAdoptionExecutor(module).execute(c,budget -> {
      attempts.incrementAndGet();
      var found=reader.read(c,List.of(item)).values().get(item);
      return new MonthlyAdoptionPlan(Map.of(found.request(),found.candidate().selection()),
          List.of(new Use("root/part",found.request(),BigDecimal.ONE)),Set.of());
    }));
    assertThat(attempts.get()).isEqualTo(1);
    assertThat(actual.plan()).isEqualTo(adopted.plan());
    assertThat(actual.plan().selections().values()).allSatisfy(value -> assertThat(value.fact().price()).isEqualByComparingTo("3"));
    assertThat(MonthlyFixedPriceSourceIntegrationTest.jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,code)).isEqualTo(1);
  }

}
