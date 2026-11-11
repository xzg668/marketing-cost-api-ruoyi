package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.service.ProductPartCostRuleService;
import com.sanhua.marketingcost.service.ProductPartCostRuleService.Input;
import com.sanhua.marketingcost.service.costing.ProductPartCostRuleStore;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@Tag("integration")
@SpringBootTest(properties={"integration.oa-person-directory.enabled=false","cms.sync-publish.enabled=false","srm.fixed-price.enabled=false"})
class ProductPartCostRuleDatabaseTest extends OriginalPricingIntegrationTestBase {
  @Autowired ProductPartCostRuleService service;
  @Autowired ProductPartCostRuleStore store;
  @Autowired JdbcTemplate jdbc;
  String product;
  @BeforeEach void setup() { product="PART-DB-"+UUID.randomUUID();scope("COMMERCIAL"); }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  void scope(String unit) {
    var auth=new UsernamePasswordAuthenticationToken("test","test",List.of());
    auth.setDetails(Map.of("businessUnitType",unit));SecurityContextHolder.getContext().setAuthentication(auth);
  }
  Input input(String code,String model,String rate,Long revision) {
    return new Input(product,"成品","规格","型号",code,"零件",model,"采购件",new BigDecimal(rate),revision);
  }
  @Test void crudPreservesCodesPrecisionAndRejectsStaleWriters() {
    var created=service.create(input("000123",null,"0.123456789012",null));
    assertThat(created.partCode()).isEqualTo("000123");
    assertThat(created.manufactureRate()).isEqualByComparingTo("0.123456789012");
    var changed=service.update(created.id(),input("000123",null,"0",created.revision()));
    assertThat(changed.revision()).isEqualTo(created.revision()+1);
    assertThatThrownBy(()->service.update(created.id(),input("000123",null,".4",created.revision()))).hasMessageContaining("刷新");
    assertThatThrownBy(()->service.delete(created.id(),created.revision())).hasMessageContaining("刷新");
    assertThat(service.get(created.id()).manufactureRate()).isZero();
    service.delete(changed.id(),changed.revision());
    assertThatThrownBy(()->service.get(changed.id())).hasMessageContaining("不存在");
  }
  @Test void codeAndModelRulesCoexistButSameKeyDuplicatesAreRejected() {
    var a=service.create(input("0001","MODEL",".2",null));
    var model=service.create(input(null,"MODEL",".4",null));
    assertThatThrownBy(()->service.create(input("0001",null,".5",null))).hasMessageContaining("已存在");
    assertThatThrownBy(()->service.create(input(null,"MODEL",".5",null))).hasMessageContaining("已存在");
    assertThat(com.sanhua.marketingcost.service.costing.ProductPartCostRule.match(store.forProduct("COMMERCIAL",product),"0001","MODEL")).isEqualTo(a);
    assertThat(store.forProduct("COMMERCIAL",product+"-other")).isEmpty();
    assertThat(service.get(model.id())).isEqualTo(model);
  }
  @Test void sameProductInOtherUnitCannotBeReadChangedOrRemoved() {
    var commercial=service.create(input("0001",null,"0",null));scope("HOUSEHOLD");
    assertThatThrownBy(()->service.get(commercial.id())).hasMessageContaining("不存在");
    assertThatThrownBy(()->service.update(commercial.id(),input("0001",null,".2",1L))).hasMessageContaining("不存在");
    assertThatThrownBy(()->service.delete(commercial.id(),1)).hasMessageContaining("刷新");
    var household=service.create(input("0001",null,".4",null));
    assertThat(household.id()).isNotEqualTo(commercial.id());
    assertThat(store.forProduct("HOUSEHOLD",product)).containsExactly(household);
    assertThat(store.forProduct("COMMERCIAL",product)).containsExactly(commercial);
  }
  @Test void invalidRatesAndEmptyIdentityAreRejectedBeforeWriting() {
    for(String rate:List.of("-0.1","1","1.000000000001","0.1234567890123"))
      assertThatThrownBy(()->service.create(input("0001",null,rate,null))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.create(input(" "," ",".2",null))).hasMessageContaining("至少");
    assertThat(store.forProduct("COMMERCIAL",product)).isEmpty();
  }
  @Test void databaseAlsoEnforcesRateAndShapeBoundaries() {
    for(String rate:List.of("-0.1","1"))
      assertThatThrownBy(()->jdbc.update("INSERT INTO lp_product_part_cost_rule(business_unit_type,product_code,part_code,shape,manufacture_rate) VALUES('COMMERCIAL',?,'0001','采购件',?)",product,new BigDecimal(rate))).isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(()->jdbc.update("INSERT INTO lp_product_part_cost_rule(business_unit_type,product_code,part_code,shape,manufacture_rate) VALUES('COMMERCIAL',?,'0001','虚拟',0)",product)).isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
  @Test void bulkImportAddsCodeAndModelRulesWithExactRatesAndSeparateBusinessUnits() {
    var result=service.importNew(List.of(new ProductPartCostRuleService.ImportRow(2,input("0001","M",".123456789012",null)),new ProductPartCostRuleService.ImportRow(3,input(null,"M","0",null))));
    assertThat(result.errors()).isEmpty();assertThat(result.imported()).isEqualTo(2);
    assertThat(store.forProduct("COMMERCIAL",product)).hasSize(2);
    scope("HOUSEHOLD");assertThat(service.importNew(List.of(new ProductPartCostRuleService.ImportRow(2,input("0001",null,".2",null)))).imported()).isEqualTo(1);
  }
  @Test void invalidOrDuplicateRowRejectsWholeFileAndPreservesExistingRecord() {
    var old=service.create(input("EXISTING",null,".4",null));
    var result=service.importNew(List.of(
        new ProductPartCostRuleService.ImportRow(2,input("NEW",null,".1",null)),
        new ProductPartCostRuleService.ImportRow(3,input("EXISTING",null,".2",null)),
        new ProductPartCostRuleService.ImportRow(4,input("NEW",null,".2",null)),
        new ProductPartCostRuleService.ImportRow(5,input("BAD",null,"1",null))));
    assertThat(result.imported()).isZero();assertThat(result.errors()).extracting(e->e.row()).containsExactly(3,4,5);
    assertThat(store.forProduct("COMMERCIAL",product)).containsExactly(old);
  }
}
