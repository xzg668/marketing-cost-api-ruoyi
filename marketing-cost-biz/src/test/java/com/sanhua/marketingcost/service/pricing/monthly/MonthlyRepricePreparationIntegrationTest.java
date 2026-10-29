package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.reprice.MonthlyRepricePreparationService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.Context;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

/** Actual production sources, SQL lookup and adoption in the disposable MySQL fixture. */
@Tag("integration")
class MonthlyRepricePreparationIntegrationTest {
  static MonthlyRepricePreparationService service;
  @BeforeAll static void database() throws Exception {
    MonthlyFixedPriceSourceIntegrationTest.database();
    service=new MonthlyRepricePreparationService(MonthlyFixedPriceSourceIntegrationTest.operation);
  }
  @AfterAll static void stop(){MonthlyFixedPriceSourceIntegrationTest.stop();}
  static Context context(String month,String org){return new Context("COMMERCIAL","PREPARATION",4L,"TOP",YearMonth.parse(month),YearMonth.parse(month).atDay(23).atStartOfDay(),"finance",org);}
  static MonthlyPriceRequest request(String code,String month,String org){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),"COMMERCIAL",code,null,"件","CNY",TaxBasis.EXCLUDED,null),org,NonCuConditions.empty(),BigDecimal.ONE);}
  @Test void previewDoesNotAdoptAndSourceRefreshCannotReplaceAnAdoptedMonthlyFact(){
    String code="MRP04-"+UUID.randomUUID();var fixture=MonthlyFixedPriceSourceIntegrationTest.jdbc;
    MonthlyFixedPriceSourceIntegrationTest.price(code,"210","S","10","2026-10-01","2027-12-01");
    var r=request(code,"2026-10","210");var ctx=context("2026-10","210");
    var first=service.preview(ctx,List.of(r));assertThat(first.ready()).isTrue();assertThat(first.lines().getFirst().status()).isEqualTo("READY");
    assertThat(fixture.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,code)).isZero();
    var price=first.lines().getFirst().price();var adopted=MonthlyFixedPriceSourceIntegrationTest.operation.adopt(ctx,new MonthlyAdoptionPlan(Map.of(r,price),List.of(new MonthlyAdoptionPlan.Use("root",r,BigDecimal.ONE)),Set.of()));
    long id=adopted.plan().selections().get(r).monthlyPriceId();var saved=fixture.queryForMap("SELECT * FROM lp_material_monthly_price WHERE id=?",id);
    fixture.update("UPDATE lp_price_fixed_item SET fixed_price=30 WHERE material_code=?",code);
    var same=service.preview(context("2026-10","210"),List.of(r));assertThat(same.lines().getFirst().status()).isEqualTo("ADOPTED");assertThat(same.lines().getFirst().price().fact().price()).isEqualByComparingTo("10");
    var next=service.preview(context("2026-11","210"),List.of(request(code,"2026-11","210")));assertThat(next.lines().getFirst().price().fact().price()).isEqualByComparingTo("30");
    assertThat(fixture.queryForMap("SELECT * FROM lp_material_monthly_price WHERE id=?",id)).isEqualTo(saved);
    assertThat(fixture.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,code)).isEqualTo(1);
  }
  @Test void exactOrganizationAndDateRangeProduceSpecificMissingResults(){
    String code="MRP04-"+UUID.randomUUID();MonthlyFixedPriceSourceIntegrationTest.price(code,"210","S","0","2026-10-01","2026-11-01");
    assertThat(service.preview(context("2026-10","210"),List.of(request(code,"2026-10","210"))).lines().getFirst().price().fact().price()).isZero();
    for(var c:List.of(context("2026-11","210"),context("2026-10","220"))){var result=service.preview(c,List.of(request(code,c.month().toString(),c.consumerOrg())));assertThat(result.ready()).isFalse();assertThat(result.lines().getFirst().reason()).isEqualTo("MISSING");assertThat(result.lines().getFirst().message()).isNotBlank();}
  }
  @Test void invalidScopeNeverReadsOrAdopts(){
    String code="MRP04-"+UUID.randomUUID();var c=context("2026-10","210");
    for(var r:List.of(request(code,"2026-11","210"),request(code,"2026-10","220")))assertThatThrownBy(()->service.preview(c,List.of(r))).hasMessageContaining("范围不一致");
    assertThatThrownBy(()->service.preview(c,List.of())).hasMessageContaining("1至256");
    var r=request(code,"2026-10","210");assertThatThrownBy(()->service.preview(c,List.of(r,r))).hasMessageContaining("重复");
    var wrong=new Context(c.businessUnitType(),c.oaNo(),c.oaFormItemId(),c.productCode(),c.month(),LocalDateTime.of(2026,11,1,0,0),c.actor(),c.consumerOrg());assertThatThrownBy(()->service.preview(wrong,List.of(r))).hasMessageContaining("目标核算月份");
  }
  @Test void actualDailySrmPublisherFeedsBothOrganizationsAndRefreshPreservesAdoption(){
    var jdbc=MonthlyFixedPriceSourceIntegrationTest.jdbc;
    jdbc.execute("CREATE TABLE IF NOT EXISTS lp_price_fixed_item_srm_raw(id BIGINT PRIMARY KEY AUTO_INCREMENT,company VARCHAR(255),material_code VARCHAR(64),material_name VARCHAR(255),spec VARCHAR(255),sup_code VARCHAR(64),sup_name VARCHAR(255),unit VARCHAR(32),price DECIMAL(32,8),eff_date VARCHAR(32),exp_date VARCHAR(32),source VARCHAR(255),dt VARCHAR(10),synced_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
    String a="SRM04-A-"+UUID.randomUUID(),b="SRM04-B-"+UUID.randomUUID();
    for(String day:List.of("2026-10-28","2026-10-29")) {
      jdbc.update("INSERT INTO lp_price_fixed_item_srm_raw(company,material_code,material_name,spec,sup_code,sup_name,unit,price,eff_date,exp_date,source,dt,synced_at) VALUES('浙江三花商用制冷有限公司',?,'raw fixture','SPEC','S','S','件',?,'2026-10-01','2027-12-31','固定价',?,NOW()),('浙江三花板换科技有限公司',?,'raw fixture','SPEC','S','S','件',?,'2026-10-01','2027-12-31','固定价',?,NOW())",a,day.endsWith("28")?10:30,day,b,day.endsWith("28")?20:40,day);
    }
    var publisher=new com.sanhua.marketingcost.service.srm.SrmFixedPricePublishService(jdbc,new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())),2,new BigDecimal("0.1"),0);
    assertThat(publisher.publishIfReady(LocalDate.of(2026,10,28)).status()).isEqualTo(com.sanhua.marketingcost.service.srm.SrmFixedPricePublishService.Status.PUBLISHED);
    var ctx=context("2026-10","210");var r=request(a,"2026-10","210");var first=service.preview(ctx,List.of(r));assertThat(first.ready()).isTrue();
    var selected=first.lines().getFirst().price();assertThat(selected.fact().price()).isEqualByComparingTo("10");assertThat(selected.fact().evidence().json()).contains("SRM","SYSTEM_SRM_SYNC","2026-10-28");
    assertThat(service.preview(context("2026-10","220"),List.of(request(b,"2026-10","220"))).lines().getFirst().price().fact().price()).isEqualByComparingTo("20");
    var receipt=MonthlyFixedPriceSourceIntegrationTest.operation.adopt(ctx,new MonthlyAdoptionPlan(Map.of(r,selected),List.of(new MonthlyAdoptionPlan.Use("root",r,BigDecimal.ONE)),Set.of()));
    assertThat(publisher.publishIfReady(LocalDate.of(2026,10,29)).status()).isEqualTo(com.sanhua.marketingcost.service.srm.SrmFixedPricePublishService.Status.PUBLISHED);
    var saved=service.preview(context("2026-10","210"),List.of(r)).lines().getFirst();assertThat(saved.status()).isEqualTo("ADOPTED");assertThat(saved.price().monthlyPriceId()).isEqualTo(receipt.plan().selections().get(r).monthlyPriceId());assertThat(saved.price().fact().price()).isEqualByComparingTo("10");
    assertThat(service.preview(context("2026-11","210"),List.of(request(a,"2026-11","210"))).lines().getFirst().price().fact().price()).isEqualByComparingTo("30");
  }
}
