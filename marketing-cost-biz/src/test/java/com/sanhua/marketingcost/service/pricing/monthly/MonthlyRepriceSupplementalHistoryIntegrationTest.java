package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Real storage, successful cost references and complete historical ownership, without live approvals. */
@Tag("integration")
class MonthlyRepriceSupplementalHistoryIntegrationTest {
  static JdbcTemplate jdbc;
  static MonthlyPriceStorage storage;
  static MonthlyMaterialPriceService prices;
  @BeforeAll static void start() throws Exception {
    MonthlyMaterialPriceServiceIntegrationTest.database();
    jdbc=MonthlyMaterialPriceServiceIntegrationTest.jdbc;storage=MonthlyMaterialPriceServiceIntegrationTest.storage;
    jdbc.execute("CREATE TABLE lp_cost_run_cost_item(id BIGINT PRIMARY KEY AUTO_INCREMENT,cost_run_version_id BIGINT,cost_code VARCHAR(32),amount DECIMAL(20,6))");
    prices=new MonthlyMaterialPriceService(storage,new DataSourceTransactionManager(jdbc.getDataSource()),
        MonthlyMaterialPriceServiceIntegrationTest.codec,(context,requests,dependencies)->{
          var found=new LinkedHashMap<MonthlyPriceRequest,Candidate>();
          for(var request:requests)found.put(request,new Candidate(new Selection(null,
              MonthlyMaterialPriceServiceIntegrationTest.shared(),PriceFact.normal(new BigDecimal("7.12345678"),
              SourceEvidence.ofJson("{\"snapshotSchema\":1,\"sourceKind\":\"TECH_FIXED\",\"source\":{\"selected\":{\"id\":154,\"fixedPrice\":7.12345678}}}"),Set.of())),null));
          return new Candidates(found,Map.of());
        });
  }
  @AfterAll static void stop(){MonthlyMaterialPriceServiceIntegrationTest.stop();}
  record Fixture(String code,long price,long version) {}
  Fixture fixture() {
    String code="HISTORY-"+UUID.randomUUID();var request=MonthlyMaterialPriceServiceIntegrationTest.request(code,"210");
    var selected=prices.resolveCandidates(MonthlyMaterialPriceServiceIntegrationTest.context(),Set.of(request)).values().get(request).selection();
    var saved=prices.adopt(MonthlyMaterialPriceServiceIntegrationTest.context(),MonthlyMaterialPriceServiceIntegrationTest.plan(request,selected));
    long price=saved.plan().selections().get(request).monthlyPriceId();String run="RUN-"+UUID.randomUUID();
    jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,status,total_cost,part_item_count,cost_item_count,trial_finished_at,business_unit_type) VALUES(?,'OA',1,'TOP','2026-10','2026-10','TRIAL',7,1,1,NOW(),'COMMERCIAL')",run);
    long version=jdbc.queryForObject("SELECT id FROM lp_quote_cost_run_version WHERE cost_run_no=?",Long.class,run);
    jdbc.update("INSERT INTO lp_cost_run_part_item(oa_no,oa_form_item_id,cost_run_version_id,cost_run_no,product_code,part_code,business_unit_type,monthly_price_id,monthly_pricing_type,price_org_code) VALUES('OA',1,?,?,'TOP',?,'COMMERCIAL',?,'NORMAL','210')",version,run,code,price);
    jdbc.update("INSERT INTO lp_cost_run_cost_item(cost_run_version_id,cost_code,amount) VALUES(?,'TOTAL',7)",version);
    jdbc.update("UPDATE lp_quote_cost_run_version SET status='SUCCESS' WHERE id=?",version);
    return new Fixture(code,price,version);
  }
  List<MonthlyPriceStorage.StoredPrice> history(Fixture fixture) {
    return storage.previousSupplemental("COMMERCIAL",YearMonth.of(2026,12),List.of(fixture.code()));
  }
  @Test void onlyACompleteSuccessfulHistoricalCostMakesItsSupplementalPriceReusable() {
    var fixture=fixture();assertThat(history(fixture)).singleElement().satisfies(row->{
      assertThat(row.id()).isEqualTo(fixture.price());assertThat(row.unitPrice()).isEqualByComparingTo("7.12345678");
    });
    jdbc.update("UPDATE lp_quote_cost_run_version SET status='FAILED' WHERE id=?",fixture.version());
    assertThat(history(fixture)).isEmpty();
    jdbc.update("UPDATE lp_quote_cost_run_version SET status='HISTORY' WHERE id=?",fixture.version());
    assertThat(history(fixture)).hasSize(1);
    jdbc.update("DELETE FROM lp_cost_run_cost_item WHERE cost_run_version_id=?",fixture.version());
    assertThat(history(fixture)).isEmpty();
  }
  @Test void anotherOriginalOaOrProductCannotSupplyAnApparentlySuccessfulPriceReference() {
    var fixture=fixture();
    for(var column:List.of("oa_no","product_code","business_unit_type")) {
      String original=jdbc.queryForObject("SELECT "+column+" FROM lp_quote_cost_run_version WHERE id=?",String.class,fixture.version());
      jdbc.update("UPDATE lp_quote_cost_run_version SET "+column+"='WRONG' WHERE id=?",fixture.version());
      assertThat(history(fixture)).isEmpty();
      jdbc.update("UPDATE lp_quote_cost_run_version SET "+column+"=? WHERE id=?",original,fixture.version());
    }
    assertThat(history(fixture)).hasSize(1);
  }
  @Test void otherBusinessUnitsCurrentMonthAndUnconsumedPricesCannotBeBorrowed() {
    var fixture=fixture();
    assertThat(storage.previousSupplemental("HOUSEHOLD",YearMonth.of(2026,12),List.of(fixture.code()))).isEmpty();
    assertThat(storage.previousSupplemental("COMMERCIAL",YearMonth.of(2026,10),List.of(fixture.code()))).isEmpty();
    jdbc.update("UPDATE lp_quote_cost_run_version SET part_item_count=2 WHERE id=?",fixture.version());
    assertThat(history(fixture)).isEmpty();
  }
}
