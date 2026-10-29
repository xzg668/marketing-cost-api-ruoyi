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
        new HouseholdMonthlyPriceSource(new HouseholdInquiryStorage(mapper, codec), codec, org.mockito.Mockito.mock(com.sanhua.marketingcost.service.MaterialPricePriorityService.class)));
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
}
