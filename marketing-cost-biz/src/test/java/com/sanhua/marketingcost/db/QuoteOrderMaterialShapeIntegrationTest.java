package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","integration.oa-person-directory.enabled=false",
    "integration.oa-person-directory.run-at-startup=false","cms.sync-publish.enabled=false",
    "srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false","price.material-sources.enabled=false"})
class QuoteOrderMaterialShapeIntegrationTest extends OriginalPricingIntegrationTestBase {
  @org.springframework.test.context.DynamicPropertySource
  static void period(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;
  @Autowired BomSettlementRuleMapper rules;
  @Autowired MaterialPriceSourceService catalog;
  @org.springframework.boot.test.mock.mockito.SpyBean MaterialPriceSourceScanner sourceScanner;
  @Autowired TestRestTemplate rest;
  @Autowired com.sanhua.marketingcost.service.QuoteProductBomPreparationService preparations;
  @LocalServerPort int port;
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  String token;
  @BeforeEach void login() throws Exception {
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/V381__quotation_order_material_shape.sql"),
        new org.springframework.core.io.ClassPathResource("db/V382__quotation_order_price_source.sql")).execute(jdbc.getDataSource());
    rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
    String username="shape_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'形态测试',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",username,new BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    token=call(HttpMethod.POST,"/api/v1/auth/login",Map.of("username",username,"password",password,"businessUnitType","COMMERCIAL")).path("token").asText();
  }
  JsonNode call(HttpMethod method,String path,Object body) throws Exception {
    var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
    if(token!=null) headers.setBearerAuth(token);
    var response=rest.exchange("http://127.0.0.1:"+port+path,method,new HttpEntity<>(body,headers),String.class);
    var root=json.readTree(response.getBody());
    assertThat(response.getStatusCode().is2xxSuccessful()).as(path+" "+root).isTrue();
    assertThat(root.path("code").asInt()).as(path+" "+root).isZero();return root.path("data");
  }
  String base(MaterialShapeSwitchTestData.Sample s) { return "/api/v1/quote-requests/"+s.oaNo()+"/items/"+s.itemId(); }
  JsonNode bom(MaterialShapeSwitchTestData.Sample s) throws Exception { return call(HttpMethod.GET,base(s)+"/effective-bom",null); }
  JsonNode calculate(MaterialShapeSwitchTestData.Sample s) throws Exception {
    var result=call(HttpMethod.POST,base(s)+"/cost-runs",Map.of("periodMonth",s.month(),"reason","MATERIAL_SHAPE_TEST"));
    assertThat(result.path("blockingStatus").asText()).as(result.toString()).isNotEqualTo("SYSTEM_FAILED");
    return result;
  }

  @AfterEach void removeOnlyDisposableOrderSchema() { jdbc.execute("DROP TABLE lp_quote_order_material_shape"); }
  String order(MaterialShapeSwitchTestData.Sample s) { return "/api/v1/quote-requests/"+s.oaNo()+"/material-shapes"; }
  JsonNode collect(MaterialShapeSwitchTestData.Sample s) throws Exception {return call(HttpMethod.POST,order(s)+"/prepare",Map.of());}
  JsonNode row(JsonNode view,String code) {
    for(var r:view.path("rows")) if(code.equals(r.path("materialCode").asText()))return r;
    throw new AssertionError("Missing "+code+" in "+view);
  }
  Map<String,Object> selection(JsonNode row,String shape) {
    var result=new LinkedHashMap<String,Object>();result.put("periodMonth",row.path("periodMonth").asText());
    result.put("organization",row.path("organization").asText());result.put("materialCode",row.path("materialCode").asText());
    result.put("selectedShape",shape);result.put("expectedVersion",row.path("version").asLong());return result;
  }
  JsonNode choose(MaterialShapeSwitchTestData.Sample s,String shape) throws Exception {
    var view=collect(s);assertThat(view.path("problems")).isEmpty();
    return call(HttpMethod.PATCH,order(s),Map.of("expectedFingerprint",view.path("fingerprint").asText(),"selections",List.of(selection(row(view,s.parent()),shape))));
  }
  JsonNode chooseSource(MaterialShapeSwitchTestData.Sample s,String shape,String source) throws Exception {
    var view=collect(s);var chosen=selection(row(view,s.parent()),shape);chosen.put("selectedSourceType",source);
    return call(HttpMethod.PATCH,order(s),Map.of("expectedFingerprint",view.path("fingerprint").asText(),"selections",List.of(chosen)));
  }
  long latest(MaterialShapeSwitchTestData.Sample s) {return jdbc.queryForObject("SELECT MAX(id) FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'",Long.class,s.itemId());}
  BigDecimal part(long version,String code) {return jdbc.queryForObject("SELECT SUM(amount) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,version,code);}
  BigDecimal cost(long version,String code) {return jdbc.queryForObject("SELECT amount FROM lp_cost_run_cost_item WHERE cost_run_version_id=? AND cost_code=?",BigDecimal.class,version,code);}
  void classifyPurchase(MaterialShapeSwitchTestData.Sample s) {
    var found=catalog.list("COMMERCIAL",new MaterialPriceSourceCatalog.Filter(s.month(),s.parent(),"210","",1,100)).rows().stream().filter(r -> r.materialCode().equals(s.parent())).toList();
    boolean fixed=found.stream().anyMatch(r -> r.sourceType().equals("FIXED"));
    for(var source:found) if(!source.automatic()) catalog.change("COMMERCIAL",new MaterialPriceSourceCatalog.Change(s.month(),"210",s.parent(),source.sourceType(),fixed && source.sourceType().equals("LINKED")?"OUTSOURCE":"PURCHASE",source.version()),"finance");
  }
  @Test void openingPreparedOrderScansAllNeededMaterialCodesTogether() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    collect(s);
    org.mockito.Mockito.clearInvocations(sourceScanner);
    var view=collect(s);
    assertThat(view.path("problems")).isEmpty();
    assertThat(row(view,s.parent()).path("sources").toString()).contains("MAKE","LINKED");
    var scan=org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
    org.mockito.Mockito.verify(sourceScanner,org.mockito.Mockito.times(1)).scan(scan.capture());
    assertThat((java.util.Collection<String>)scan.getValue().get("materialCodes")).contains(s.parent(),s.raw());
  }
  @Test void unpricedManufactureAndLinkedAreTwoSourcesAndPurchaseBypassesOnlyManufacturingGaps() throws Exception {
    for(String gap:List.of("MATERIAL","SCRAP","WEIGHT")) {
      var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
      jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?",s.parent());
      if(gap.equals("MATERIAL")) jdbc.update("DELETE FROM lp_price_linked_item WHERE material_code=?",s.raw());
      if(gap.equals("SCRAP")) jdbc.update("DELETE FROM lp_scrap_price WHERE material_code=?",s.scrap());
      if(gap.equals("WEIGHT")) {
        jdbc.update("UPDATE lp_material_master_raw SET global_seg_3_theoretical_net_weight=NULL WHERE material_code=?",s.parent());
        jdbc.update("DELETE FROM lp_u9_bom_byproduct_master WHERE parent_material_no=?",s.parent());
        jdbc.update("UPDATE lp_price_linked_item SET net_weight=NULL WHERE material_code=?",s.raw());
      }
      assertThat(calculate(s).path("pipelineStatus").asText()).as(gap).isNotEqualTo("SUCCESS");
      var view=collect(s);assertThat(view.path("problems")).as(view.toString()).isEmpty();
      var a=row(view,s.parent());assertThat(a.path("sources").size()).isEqualTo(2);
      assertThat(a.path("sources").toString()).contains("MAKE","LINKED");
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_make_part_price_calc_row WHERE parent_material_no=? AND price_complete=1 AND status='OK'",Integer.class,s.parent())).isZero();
      classifyPurchase(s);choose(s,"PURCHASE");
      var result=calculate(s);assertThat(result.path("pipelineStatus").asText()).as(result.toString()).isEqualTo("SUCCESS");
      long purchased=latest(s);assertThat(part(purchased,s.parent())).isEqualByComparingTo("7.5");
      assertThat(part(purchased,s.raw())).isNull();assertThat(cost(purchased,"DIRECT_LABOR")).isEqualByComparingTo("1");
      assertThat(cost(purchased,"INDIRECT_LABOR")).isEqualByComparingTo("1");assertThat(cost(purchased,"AUX_0201")).isEqualByComparingTo("2.1");
      choose(s,"MANUFACTURE");assertThat(calculate(s).path("pipelineStatus").asText()).isNotEqualTo("SUCCESS");
      assertThat(part(purchased,s.parent())).isEqualByComparingTo("7.5");
    }
  }
  @Test void oneOrderChoiceReachesAllOccurrencesButAnotherOrderKeepsItsDefault() throws Exception {
    var first=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    var second=sharedConsumer(first,true,"5");var other=sharedConsumer(first,false,"1");
    var view=collect(first);assertThat(view.path("problems")).as(view.toString()).isEmpty();
    assertThat(row(view,first.parent()).path("products").size()).isEqualTo(2);
    classifyPurchase(first);choose(first,"PURCHASE");
    assertThat(calculate(first).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(calculate(second).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(part(latest(first),first.parent())).isEqualByComparingTo("50");
    assertThat(part(latest(second),first.parent())).isEqualByComparingTo("100");
    assertThat(cost(latest(first),"DIRECT_LABOR")).isEqualByComparingTo("1");
    assertThat(cost(latest(second),"DIRECT_LABOR")).isEqualByComparingTo("1");
    collect(other);var otherBom=bom(other);assertThat(otherBom.path("nodes").toString()).doesNotContain("QUOTATION_SELECTION");
    long original=latest(first);assertThat(calculate(first).path("reusedSuccess").asBoolean()).isTrue();assertThat(latest(first)).isEqualTo(original);
    choose(first,"OUTSOURCE");assertThat(calculate(first).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(part(latest(first),first.parent())).isEqualByComparingTo("7.5");
    assertThat(part(latest(first),first.raw())).isEqualByComparingTo("25");
    assertThat(part(latest(first),first.scrap())).isEqualByComparingTo("-2.5");
    choose(first,null);assertThat(calculate(first).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(part(latest(first),first.parent())).isEqualByComparingTo("22.5");
    assertThat(part(original,first.parent())).isEqualByComparingTo("50");
  }

  @Test void fiftyProductsShareOneRowOneDecisionAndEachKeepsItsQuantity() throws Exception {
    var first=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    var consumers=new ArrayList<MaterialShapeSwitchTestData.Sample>();consumers.add(first);
    for(int i=2;i<=50;i++)consumers.add(sharedConsumer(first,true,String.valueOf(i)));
    var view=collect(first);assertThat(view.path("problems")).as(view.toString()).isEmpty();
    assertThat(row(view,first.parent()).path("products").size()).isEqualTo(50);
    var saved=chooseSource(first,"PURCHASE","FIXED");assertThat(saved.path("affectedItemIds").size()).isEqualTo(50);
    for(int i=0;i<consumers.size();i++) {
      var consumer=consumers.get(i);var result=calculate(consumer);
      assertThat(result.path("pipelineStatus").asText()).as("consumer "+i+" "+result).isEqualTo("SUCCESS");
      assertThat(part(latest(consumer),first.parent())).isEqualByComparingTo(i==0?"50":String.valueOf(20*(i+1)));
    }
    var later=sharedConsumer(first,true,"4");
    assertThat(calculate(later).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(part(latest(later),first.parent())).isEqualByComparingTo("80");
  }

  @Test void explicitSourceChangesAloneRecostEveryConsumerWithoutClassifyingThePublicCatalog() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);var second=sharedConsumer(s,true,"5");var other=sharedConsumer(s,false,"1");
    chooseSource(s,"PURCHASE","FIXED");
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(calculate(second).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long old=latest(s),oldSecond=latest(second);
    assertThat(part(old,s.parent())).isEqualByComparingTo("50");assertThat(part(oldSecond,s.parent())).isEqualByComparingTo("100");
    assertThat(chooseSource(s,"PURCHASE","LINKED").path("affectedItemIds").size()).isEqualTo(2);
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(calculate(second).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(latest(s)).isNotEqualTo(old);assertThat(part(latest(s),s.parent())).isEqualByComparingTo("7.5");
    assertThat(part(latest(second),s.parent())).isEqualByComparingTo("15");
    assertThat(part(old,s.parent())).isEqualByComparingTo("50");assertThat(part(oldSecond,s.parent())).isEqualByComparingTo("100");
    jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=99 WHERE material_code=?",s.parent());
    chooseSource(s,"PURCHASE","FIXED");assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(part(latest(s),s.parent())).as("切回同来源沿用本月已采用的固定价20，不改用后来原始价99").isEqualByComparingTo("50");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_price_attribute WHERE material_code=?",Integer.class,s.parent())).isZero();
    assertThat(calculate(other).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(part(latest(other),s.parent())).isEqualByComparingTo("9");
    var view=collect(s);assertThat(row(view,s.parent()).path("selectedSourceType").asText()).isEqualTo("FIXED");
  }

  @Test void chosenMissingSourceDoesNotFallBackAndZeroSourcePricesRemainValid() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    chooseSource(s,"PURCHASE","FIXED");jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?",s.parent());
    var result=calculate(s);assertThat(result.path("pipelineStatus").asText()).as(result.toString()).isNotEqualTo("SUCCESS");
    assertThat(result.toString()).contains("FIXED");
    var zero=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=0 WHERE material_code=?",zero.parent());
    chooseSource(zero,"PURCHASE","FIXED");assertThat(calculate(zero).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(part(latest(zero),zero.parent())).isEqualByComparingTo("0");
  }

  @Test void sourceMustBelongToTheMaterialAndMatchItsExplicitShape() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    for(var invalid:List.of(new String[]{"PURCHASE","MAKE"},new String[]{"MANUFACTURE","FIXED"},new String[]{"PURCHASE","UNKNOWN"}))
      assertThatThrownBy(()->chooseSource(s,invalid[0],invalid[1])).isInstanceOf(AssertionError.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_order_material_shape",Integer.class)).isZero();
    chooseSource(s,"PURCHASE","FIXED");chooseSource(s,null,null);
    assertThat(jdbc.queryForObject("SELECT selected_source_type FROM lp_quote_order_material_shape WHERE material_code=?",String.class,s.parent())).isNull();
  }

  @Test void batchIsAtomicAndStaleViewsCannotOverwriteChoicesOrUseTheLegacyProductEndpoint() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);String second=MaterialShapeSwitchTestData.secondParent(jdbc,hierarchy,s);
    collect(s);
    assertThatThrownBy(()->choose(s,"PURCHASE")).hasMessageContaining("填写各来源的形态属性");
    classifyPurchase(s);
    for(var source:catalog.list("COMMERCIAL",new MaterialPriceSourceCatalog.Filter(s.month(),second,"210","",1,100)).rows())
      if(!source.automatic())catalog.change("COMMERCIAL",new MaterialPriceSourceCatalog.Change(s.month(),"210",second,source.sourceType(),"PURCHASE",source.version()),"finance");
    var view=collect(s);var a=selection(row(view,s.parent()),"PURCHASE");var b=selection(row(view,second),"INVALID");
    assertThatThrownBy(()->call(HttpMethod.PATCH,order(s),Map.of("expectedFingerprint",view.path("fingerprint").asText(),"selections",List.of(a,b)))).hasMessageContaining("不允许");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_order_material_shape",Integer.class)).isZero();
    b.put("selectedShape","PURCHASE");var body=Map.of("expectedFingerprint",view.path("fingerprint").asText(),"selections",List.of(a,b));
    assertThat(call(HttpMethod.PATCH,order(s),body).path("changedMaterials").asInt()).isEqualTo(2);
    assertThatThrownBy(()->call(HttpMethod.PATCH,order(s),body)).hasMessageContaining("变化");
    assertThat(jdbc.queryForList("SELECT selected_shape FROM lp_quote_order_material_shape",String.class)).containsOnly("PURCHASE");
    assertThatThrownBy(()->call(HttpMethod.PATCH,base(s)+"/effective-bom/material-shape",Map.of("materialCode",s.parent()))).hasMessageContaining("统一调整");
  }

  @Test void nativePurchaseAndOutsourceCannotBecomeManufactureAndSingleSourceIsNotSelectable() throws Exception {
    for(String original:List.of("采购件","委外加工件")) {
      var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET shape_attr=? WHERE top_product_code=? AND material_code=?",original,s.product(),s.parent());
      jdbc.update("UPDATE lp_material_master_raw SET shape_attr=? WHERE material_code=?",original,s.parent());
      var view=collect(s);
      if("采购件".equals(original)) {
        assertThat(view.path("rows").findValuesAsText("materialCode")).doesNotContain(s.parent());
        assertThat(catalog.quotationSources("COMMERCIAL",s.month(),"210",s.parent())).hasSizeGreaterThanOrEqualTo(2);
        var fake=new LinkedHashMap<String,Object>();fake.put("periodMonth",s.month());fake.put("organization","210");
        fake.put("materialCode",s.parent());fake.put("expectedVersion",0L);
        for(String forbidden:List.of("OUTSOURCE","MANUFACTURE")) {
          fake.put("selectedShape",forbidden);
          assertThatThrownBy(()->call(HttpMethod.PATCH,order(s),Map.of("expectedFingerprint",view.path("fingerprint").asText(),"selections",List.of(fake))))
              .hasMessageContaining("原采购件不能调整形态");
        }
        classifyPurchase(s);
        assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
      } else {
        assertThat(row(view,s.parent()).path("allowedShapes").toString()).doesNotContain("MANUFACTURE");
        assertThatThrownBy(()->choose(s,"MANUFACTURE")).hasMessageContaining("不能改成自制件");
      }
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_order_material_shape",Integer.class)).isZero();
    }
    var single=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET shape_attr='采购件' WHERE top_product_code=? AND material_code=?",single.product(),single.parent());
    jdbc.update("DELETE FROM lp_price_linked_item WHERE material_code=?",single.parent());
    var view=collect(single);assertThat(view.path("rows").toString()).doesNotContain(single.parent());
    assertThat(calculate(single).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
  }

  @Test void storedIllegalShapeCannotConvertNativePurchaseOrOverwriteItsSavedCost() throws Exception {
    for(String illegal:List.of("OUTSOURCE","MANUFACTURE")) {
      var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET shape_attr='采购件' WHERE top_product_code=? AND material_code=?",s.product(),s.parent());
      jdbc.update("UPDATE lp_material_master_raw SET shape_attr='采购件' WHERE material_code=?",s.parent());
      collect(s);classifyPurchase(s);
      assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
      long saved=latest(s);BigDecimal amount=part(saved,s.parent());
      long form=jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?",Long.class,s.itemId());
      jdbc.update("INSERT INTO lp_quote_order_material_shape(business_unit_type,oa_form_id,period_month,price_org_code,material_code,selected_shape,row_version,updated_by) VALUES('COMMERCIAL',?,?,'210',?,?,1,'TEST')",form,s.month(),s.parent(),illegal);
      var result=call(HttpMethod.POST,base(s)+"/cost-runs",Map.of("periodMonth",s.month(),"reason","ILLEGAL_STORED_SHAPE_TEST"));
      assertThat(result.path("pipelineStatus").asText()).as(result.toString()).isNotEqualTo("SUCCESS");
      assertThat(result.path("errorCode").asText()).isEqualTo("BOM_SYSTEM_ERROR");
      assertThat(result.path("message").asText()).contains("原始采购件不能改成");
      assertThat(latest(s)).isEqualTo(saved);assertThat(part(saved,s.parent())).isEqualByComparingTo(amount);
    }
  }

  @Test void calculatedMakeRemainsVisibleAfterPurchaseRebuildAndPublicRefresh() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    var original=catalog.quotationSources("COMMERCIAL",s.month(),"210",s.parent()).stream().filter(r -> r.sourceType().equals("MAKE")).findFirst().orElseThrow();
    assertThat(original.prices()).hasSize(1);assertThat(original.prices().getFirst().value()).isEqualTo("9");
    classifyPurchase(s);choose(s,"PURCHASE");assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    collect(s);
    for(int refresh=0;refresh<2;refresh++) {
      var retained=catalog.quotationSources("COMMERCIAL",s.month(),"210",s.parent()).stream().filter(r -> r.sourceType().equals("MAKE")).findFirst().orElseThrow();
      assertThat(retained.prices()).hasSize(1);assertThat(retained.prices().getFirst().value()).isEqualTo("9");
      assertThat(retained.prices().getFirst().unit()).isEqualTo(original.prices().getFirst().unit());
      catalog.refresh("COMMERCIAL",s.month(),"finance");
    }
  }

  @Test void changingOneMaterialDoesNotInvalidateAnUnrelatedProductInTheSameOrder() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);var unrelated=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    long form=jdbc.queryForObject("SELECT oa_form_id FROM oa_form_item WHERE id=?",Long.class,s.itemId());
    jdbc.update("UPDATE oa_form_item SET oa_form_id=?,seq=2 WHERE id=?",form,unrelated.itemId());
    var other=new MaterialShapeSwitchTestData.Sample(s.oaNo(),unrelated.itemId(),unrelated.product(),unrelated.parent(),unrelated.raw(),unrelated.scrap(),unrelated.month());
    assertThat(calculate(other).path("pipelineStatus").asText()).isEqualTo("SUCCESS");long version=latest(other);
    collect(s);classifyPurchase(s);choose(s,"PURCHASE");
    assertThat(calculate(other).path("reusedSuccess").asBoolean()).isTrue();assertThat(latest(other)).isEqualTo(version);
  }
  MaterialShapeSwitchTestData.Sample sharedConsumer(MaterialShapeSwitchTestData.Sample shared,boolean sameOrder,String quantity) {
    return QuoteOrderMaterialShapeTestData.sharedConsumer(jdbc,hierarchy,rules,shared,sameOrder,quantity);
  }
}
