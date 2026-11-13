package com.sanhua.marketingcost.service.pricing;
import static com.sanhua.marketingcost.service.pricing.MaterialPriceSourceCatalog.*;
import static com.sanhua.marketingcost.service.pricing.MaterialPriceSourceTestData.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
@Tag("integration")
class MaterialPriceSourceIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Test void anOlderFixedSourceMonthDoesNotDiscardAnApplicableLongTermContract() {
    String c="CAT-OLDER-FIXED-MONTH";
    fixed(jdbc,"COMMERCIAL","210",c,"2026-03","1.495");
    jdbc.update("UPDATE lp_price_fixed_item SET effective_from='2026-05-01',effective_to='2027-04-30' WHERE material_code=?",c);
    linked(jdbc,"COMMERCIAL","210",c,"2026-11");
    service.refresh("COMMERCIAL","2026-11","finance");
    assertThat(read("2026-11",c,"").rows()).hasSize(2);
    assertThat(row("2026-11",c,"FIXED").prices().getFirst().value()).isEqualTo("1.495");
    assertThat(read("2026-10",c,"").rows()).isEmpty();
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired MaterialPriceSourceService service;
  @Autowired MaterialPriceSourceRefresh refreshAll;
  @SpyBean MaterialPriceSourceScanner scanner;
  @BeforeEach void setup(){schema(jdbc);}
  private Page read(String m,String c,String a){return service.list("COMMERCIAL",new Filter(m,c,"",a,1,100));}
  private void both(String c,String m){fixed(jdbc,"COMMERCIAL","210",c,m,"0.18");linked(jdbc,"COMMERCIAL","210",c,m);}
  private Entry row(String m,String c,String t){return read(m,c,"").rows().stream().filter(r->r.sourceType().equals(t)).findFirst().orElseThrow();}
  @Test void queryDoesNotGenerateAndOneSourceIsNotSaved(){
    String c="CAT-QUERY";both(c,"2026-11");assertThat(read("2026-11",c,"").total()).isZero();
    fixed(jdbc,"COMMERCIAL","210","CAT-SINGLE","2026-11","0");service.refresh("COMMERCIAL","2026-11","finance");
    assertThat(read("2026-11","CAT-SINGLE","").rows()).isEmpty();assertThat(read("2026-11",c,"").rows()).hasSize(2);
  }
  @Test void oneSourcePerRowHasRealZeroPriceAndPendingPriceIsNotZero(){
    String c="CAT-PRICES";both(c,"2026-11");jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=0 WHERE material_code=?",c);service.refresh("COMMERCIAL","2026-11","finance");
    assertThat(row("2026-11",c,"FIXED").prices().getFirst().value()).isEqualTo("0");assertThat(row("2026-11",c,"LINKED").prices().getFirst().value()).isNull();assertThat(row("2026-11",c,"FIXED").organizationName()).contains("商用");
  }
  @Test void financeChoicesAreIndependentPerSourceAndSurviveRefresh(){
    String c="CAT-SOURCE-ATTR";both(c,"2026-11");service.refresh("COMMERCIAL","2026-11","finance");
    service.change("COMMERCIAL",new Change("2026-11","210",c,"FIXED","PURCHASE",0L),"finance");service.change("COMMERCIAL",new Change("2026-11","210",c,"LINKED","OUTSOURCE",0L),"finance");
    jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=8.25 WHERE material_code=?",c);service.refresh("COMMERCIAL","2026-11","finance");
    assertThat(row("2026-11",c,"FIXED").materialAttribute()).isEqualTo("PURCHASE");assertThat(row("2026-11",c,"FIXED").prices().getFirst().value()).isEqualTo("8.25");assertThat(row("2026-11",c,"LINKED").materialAttribute()).isEqualTo("OUTSOURCE");
    assertThat(read("2026-11",c,"PURCHASE").rows()).hasSize(1);assertThat(read("2026-11",c,"OUTSOURCE").rows()).hasSize(1);assertThat(read("2026-11",c,"EMPTY").rows()).isEmpty();
  }
  @Test void monthlyChoicesAreIsolatedAndRefreshUsesDataMonthsNotToday(){
    String c="CAT-MONTH";both(c,"2026-10");both(c,"2026-11");refreshAll.refresh("COMMERCIAL","finance");service.change("COMMERCIAL",new Change("2026-10","210",c,"FIXED","OUTSOURCE",0L),"finance");
    assertThat(row("2026-11",c,"FIXED").materialAttribute()).isNull();assertThat(service.availableMonths("COMMERCIAL")).contains("2026-10","2026-11");refreshAll.refresh("COMMERCIAL","finance");assertThat(row("2026-10",c,"FIXED").materialAttribute()).isEqualTo("OUTSOURCE");
  }
  @Test void manufactureComesFromPricingTreeEvenWithoutCalculatedPrice(){
    String c="CAT-TREE";fixed(jdbc,"COMMERCIAL","210",c,"2026-11","10");tree(jdbc,c,"2026-11");service.refresh("COMMERCIAL","2026-11","finance");
    var m=row("2026-11",c,"MAKE");assertThat(m.materialAttribute()).isEqualTo("MANUFACTURE");assertThat(m.prices().getFirst().value()).isNull();assertThat(row("2026-11",c,"FIXED").materialAttribute()).isNull();assertThat(read("2026-11",c,"MANUFACTURE").rows()).hasSize(1);
    assertThatThrownBy(()->service.change("COMMERCIAL",new Change("2026-11","210",c,"MAKE","PURCHASE",0L),"finance")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无需选择");
    assertThat(row("2026-11",c,"MAKE").materialAttribute()).isEqualTo("MANUFACTURE");
  }
  @Test void materialMasterManufactureAloneDoesNotCreateSelfPriceSource(){
    String c="CAT-MASTER";both(c,"2026-11");jdbc.update("INSERT INTO lp_material_master_raw(organization_code,material_code,shape_attr,active_flag,import_batch_id,material_model,material_spec) VALUES('COMMERCIAL',?,'制造件',1,'CAT','MODEL-X','SPEC-Y')",c);service.refresh("COMMERCIAL","2026-11","finance");
    assertThat(read("2026-11",c,"").rows()).allMatch(r->r.materialAttribute()==null);assertThat(row("2026-11",c,"FIXED").materialModel()).isEqualTo("MODEL-X");assertThat(row("2026-11",c,"FIXED").materialSpec()).isEqualTo("SPEC-Y");
  }
  @Test void failedRefreshRollsBackAndPreservesPreviousGeneration(){
    String c="CAT-FAIL";both(c,"2026-11");service.refresh("COMMERCIAL","2026-11","finance");var before=read("2026-11",c,"");doThrow(new IllegalStateException("模拟来源读取失败")).when(scanner).scan(anyMap());
    try{assertThatThrownBy(()->service.refresh("COMMERCIAL","2026-11","finance")).hasMessageContaining("模拟");}finally{doCallRealMethod().when(scanner).scan(anyMap());}assertThat(read("2026-11",c,"")).isEqualTo(before);
  }
  @Test void removedSourceDropsRowsButKeepsFinanceChoiceForLaterReappearance(){
    String c="CAT-REMOVE";both(c,"2026-11");service.refresh("COMMERCIAL","2026-11","finance");service.change("COMMERCIAL",new Change("2026-11","210",c,"FIXED","PURCHASE",0L),"finance");
    jdbc.update("UPDATE lp_price_linked_item SET deleted=1 WHERE material_code=?",c);service.refresh("COMMERCIAL","2026-11","finance");assertThat(read("2026-11",c,"").rows()).isEmpty();jdbc.update("UPDATE lp_price_linked_item SET deleted=0 WHERE material_code=?",c);service.refresh("COMMERCIAL","2026-11","finance");assertThat(row("2026-11",c,"FIXED").materialAttribute()).isEqualTo("PURCHASE");
  }
  @Test void staleFinanceEditIsRejected(){
    String c="CAT-CONFLICT";both(c,"2026-11");service.refresh("COMMERCIAL","2026-11","finance");service.change("COMMERCIAL",new Change("2026-11","210",c,"FIXED","PURCHASE",0L),"finance");
    assertThatThrownBy(()->service.change("COMMERCIAL",new Change("2026-11","210",c,"FIXED","OUTSOURCE",0L),"other")).isInstanceOf(OptimisticLockingFailureException.class);
  }
  @Test void organizationAndBusinessUnitRemainIsolated(){
    String c="CAT-SCOPE";fixed(jdbc,"COMMERCIAL","210",c,"2026-11","1");linked(jdbc,"COMMERCIAL","220",c,"2026-11");service.refresh("COMMERCIAL","2026-11","finance");assertThat(read("2026-11",c,"").rows()).isEmpty();assertThat(service.list("HOUSEHOLD",new Filter("2026-11",c,"","",1,100)).rows()).isEmpty();
  }
  @Test void blankMonthFixedContractRemainsAvailableInItsValidMonths() {
    String c="CAT-BLANK-MONTH";
    fixed(jdbc,"COMMERCIAL","210",c,"","0.18");
    jdbc.update("UPDATE lp_price_fixed_item SET effective_from='2026-05-01',effective_to='2027-04-30' WHERE material_code=?",c);
    linked(jdbc,"COMMERCIAL","210",c,"2026-11");
    service.refresh("COMMERCIAL","2026-11","finance");
    assertThat(read("2026-11",c,"").rows()).hasSize(2);
    assertThat(row("2026-11",c,"FIXED").prices().getFirst().value()).isEqualTo("0.18");
  }

  @Test void manufactureAndSelfMadeTextAreBothAutomaticAndCannotBeOverridden() {
    for (String shape:java.util.List.of("制造件","自制件")) {
      String c="CAT-AUTO-"+shape;
      fixed(jdbc,"COMMERCIAL","210",c,"2026-11","1");tree(jdbc,c,"2026-11");
      jdbc.update("UPDATE lp_quote_effective_bom_node SET effective_material_shape=? WHERE material_code=?",shape,c);
      service.refresh("COMMERCIAL","2026-11","finance");
      assertThat(row("2026-11",c,"MAKE").automatic()).isTrue();
      assertThat(row("2026-11",c,"MAKE").materialAttribute()).isEqualTo("MANUFACTURE");
      assertThatThrownBy(()->service.change("COMMERCIAL",new Change("2026-11","210",c,"MAKE","OUTSOURCE",0L),"finance"))
          .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无需选择");
    }
  }

  @Test void fixedSourceWithExpiredContractUsesTheOriginalLatestPeriodFallback() {
    String c="CAT-EXPIRED-FIXED";
    fixed(jdbc,"COMMERCIAL","210",c,"","1.495");
    jdbc.update("UPDATE lp_price_fixed_item SET effective_from='2026-05-01',effective_to='2026-05-31' WHERE material_code=?",c);
    fixed(jdbc,"COMMERCIAL","210",c,"","9.99");
    jdbc.update("UPDATE lp_price_fixed_item SET effective_from='2025-05-01',effective_to='2025-05-31' WHERE material_code=? AND fixed_price=9.99",c);
    linked(jdbc,"COMMERCIAL","210",c,"2026-11");
    service.refresh("COMMERCIAL","2026-11","finance");
    assertThat(read("2026-11",c,"").rows()).hasSize(2);
    assertThat(row("2026-11",c,"FIXED").prices()).hasSize(1);
    assertThat(row("2026-11",c,"FIXED").prices().getFirst().value()).isEqualTo("1.495");
  }
  @Test void currentFixedPriceExcludesOtherSupplierExpiredPrice() {
    String c="CAT-CURRENT-FIXED";
    fixed(jdbc,"COMMERCIAL","210",c,"","1");
    jdbc.update("UPDATE lp_price_fixed_item SET effective_from='2026-11-01',effective_to='2026-11-30',supplier_code='S-CURRENT' WHERE material_code=?",c);
    fixed(jdbc,"COMMERCIAL","210",c,"","99");
    jdbc.update("UPDATE lp_price_fixed_item SET effective_from='2025-01-01',effective_to='2025-12-31',supplier_code='S-OLD' WHERE material_code=? AND fixed_price=99",c);
    linked(jdbc,"COMMERCIAL","210",c,"2026-11");service.refresh("COMMERCIAL","2026-11","finance");
    assertThat(row("2026-11",c,"FIXED").prices()).hasSize(1);
    assertThat(row("2026-11",c,"FIXED").prices().getFirst().value()).isEqualTo("1");
  }

}
