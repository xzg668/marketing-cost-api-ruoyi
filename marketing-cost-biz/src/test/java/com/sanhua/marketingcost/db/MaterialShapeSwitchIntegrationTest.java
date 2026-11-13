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
class MaterialShapeSwitchIntegrationTest extends OriginalPricingIntegrationTestBase {
  @org.springframework.test.context.DynamicPropertySource
  static void period(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;
  @Autowired BomSettlementRuleMapper rules;
  @Autowired MaterialPriceSourceService catalog;
  @Autowired TestRestTemplate rest;
  @Autowired com.sanhua.marketingcost.service.QuoteProductBomPreparationService preparations;
  @LocalServerPort int port;
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  String token;
  @BeforeEach void login() throws Exception {
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
    return call(HttpMethod.POST,base(s)+"/cost-runs",Map.of("periodMonth",s.month(),"reason","MATERIAL_SHAPE_TEST"));
  }
  void classify(MaterialShapeSwitchTestData.Sample s) {
    catalog.refresh("COMMERCIAL",s.month(),"shape-test");
    assertThat(catalog.list("COMMERCIAL",new MaterialPriceSourceCatalog.Filter(s.month(),s.parent(),"210","",1,100)).rows())
        .as("source rows: "+jdbc.queryForList("SELECT material_code,pricing_month,org_code,source_kind,rule_status,deleted FROM lp_price_linked_item WHERE material_code=?",s.parent())
            +" fixed: "+jdbc.queryForList("SELECT material_code,source_kind,source_type,pricing_month FROM lp_price_fixed_item WHERE material_code=?",s.parent()))
        .hasSizeGreaterThanOrEqualTo(2);
    catalog.change("COMMERCIAL",new MaterialPriceSourceCatalog.Change(s.month(),"210",s.parent(),"FIXED","PURCHASE",0L),"finance");
    catalog.change("COMMERCIAL",new MaterialPriceSourceCatalog.Change(s.month(),"210",s.parent(),"LINKED","OUTSOURCE",0L),"finance");
  }
  void choose(MaterialShapeSwitchTestData.Sample s,String shape) throws Exception {
    preparations.prepareByOaFormItem(s.itemId(),java.time.LocalDate.now(),s.month());
    var tree=bom(s);JsonNode node=null;for(var n:tree.path("nodes")) if(s.parent().equals(n.path("materialCode").asText()))node=n;
    assertThat(node).as(tree.toString()).isNotNull();
    var change=new LinkedHashMap<String,Object>();change.put("periodMonth",s.month());change.put("materialCode",s.parent());
    change.put("organization","210");change.put("selectedShape",shape);change.put("expectedVersion",node.path("shapeSelectionVersion").asLong());
    change.put("expectedVariantHash",tree.path("effectiveVariantHash").asText());
    call(HttpMethod.PATCH,base(s)+"/effective-bom/material-shape",change);
  }
  long latest(MaterialShapeSwitchTestData.Sample s) {return jdbc.queryForObject("SELECT MAX(id) FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'",Long.class,s.itemId());}
  BigDecimal part(long version,String code) {return jdbc.queryForObject("SELECT SUM(amount) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,version,code);}
  BigDecimal cost(long version,String code) {return jdbc.queryForObject("SELECT amount FROM lp_cost_run_cost_item WHERE cost_run_version_id=? AND cost_code=?",BigDecimal.class,version,code);}

  @Test void calculatedMakeIsVisibleAndBecomesSecondPublicSourceBeforeShapeSelection() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code=?",s.parent());
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long original=latest(s);
    var page=call(HttpMethod.GET,"/api/v1/make-part-price-calc/page?parentMaterialNo="+s.parent()+"&pricingMonth="+s.month(),null);
    assertThat(page.path("total").asLong()).isPositive();
    var publicRows=catalog.list("COMMERCIAL",new MaterialPriceSourceCatalog.Filter(s.month(),s.parent(),"210","",1,100)).rows();
    assertThat(publicRows).extracting(MaterialPriceSourceCatalog.Entry::sourceType).containsExactlyInAnyOrder("MAKE","LINKED");
    var make=publicRows.stream().filter(e -> e.sourceType().equals("MAKE")).findFirst().orElseThrow();
    assertThat(make.prices()).anyMatch(price -> price.value()!=null && new BigDecimal(price.value()).compareTo(new BigDecimal("9"))==0);
    catalog.change("COMMERCIAL",new MaterialPriceSourceCatalog.Change(s.month(),"210",s.parent(),"LINKED","PURCHASE",0L),"quoter");
    choose(s,"PURCHASE");
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long purchase=latest(s);
    assertThat(part(purchase,s.parent())).isEqualByComparingTo("7.5");
    assertThat(part(purchase,s.raw())).isNull();
    assertThat(cost(purchase,"DIRECT_LABOR")).isEqualByComparingTo("1");
    assertThat(cost(purchase,"INDIRECT_LABOR")).isEqualByComparingTo("1");
    assertThat(cost(purchase,"AUX_0201")).isEqualByComparingTo("2.1");
    assertThat(part(original,s.parent())).isEqualByComparingTo("22.5");
    for(int i=0;i<3;i++) {assertThat(calculate(s).path("reusedSuccess").asBoolean()).isTrue();assertThat(latest(s)).isEqualTo(purchase);}
  }

  @Test void absentCatalogDiscoversAllSourcesAndKeepsThemAfterUnclassifiedChoiceIsRejected() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    preparations.prepareByOaFormItem(s.itemId(),java.time.LocalDate.now(),s.month());
    var tree=bom(s);JsonNode node=null;
    for(var n:tree.path("nodes")) if(s.parent().equals(n.path("materialCode").asText())) node=n;
    var body=Map.of("periodMonth",s.month(),"materialCode",s.parent(),"organization","210",
        "selectedShape","PURCHASE","expectedVersion",node.path("shapeSelectionVersion").asLong(),
        "expectedVariantHash",tree.path("effectiveVariantHash").asText());
    var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(token);
    var response=rest.exchange("http://127.0.0.1:"+port+base(s)+"/effective-bom/material-shape",HttpMethod.PATCH,new HttpEntity<>(body,headers),String.class);
    var rejected=json.readTree(response.getBody());
    assertThat(rejected.path("code").asInt()).isNotZero();
    assertThat(rejected.path("msg").asText()).contains("公共", "形态");
    assertThat(jdbc.queryForList("SELECT price_source_type FROM lp_material_price_source_row WHERE material_code=? AND price_source_type<>'MAKE'",String.class,s.parent()))
        .containsExactlyInAnyOrder("FIXED","LINKED");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_material_shape_selection WHERE oa_form_item_id=?",Integer.class,s.itemId())).isZero();
    classify(s);choose(s,"PURCHASE");
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(part(latest(s),s.parent())).isEqualByComparingTo("50");
  }

  @Test void realHttpMakePurchaseOutsourceAndRestoreKeepHistoryAndOnlyDeductOwnCmsCosts() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long original=latest(s);assertThat(part(original,s.parent())).isEqualByComparingTo("22.5");
    var originalTree=call(HttpMethod.GET,base(s)+"/effective-bom/material-shape?versionId="+original,null);
    assertThat(originalTree.path("nodes").size()).isGreaterThan(0);
    assertThat(cost(original,"DIRECT_LABOR")).isEqualByComparingTo("3");
    assertThat(cost(original,"INDIRECT_LABOR")).isEqualByComparingTo("2");
    classify(s);
    choose(s,"PURCHASE");assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    var purchasedTree=bom(s);
    var purchasedParent=java.util.stream.StreamSupport.stream(purchasedTree.path("nodes").spliterator(),false)
        .filter(n -> s.parent().equals(n.path("materialCode").asText())).findFirst().orElseThrow();
    assertThat(purchasedParent.path("sourceHasChildren").asBoolean()).isTrue();
    long purchase=latest(s);assertThat(purchase).isNotEqualTo(original);
    assertThat(part(purchase,s.parent())).isEqualByComparingTo("50");assertThat(part(purchase,s.raw())).isNull();
    assertThat(cost(purchase,"DIRECT_LABOR")).isEqualByComparingTo("1");
    assertThat(cost(purchase,"INDIRECT_LABOR")).isEqualByComparingTo("1");
    assertThat(cost(purchase,"AUX_0201")).isEqualByComparingTo("2.1");
    for(int i=0;i<3;i++) { assertThat(calculate(s).path("reusedSuccess").asBoolean()).isTrue();assertThat(latest(s)).isEqualTo(purchase); }
    choose(s,"OUTSOURCE");assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long outsource=latest(s);assertThat(part(outsource,s.parent())).isEqualByComparingTo("7.5");
    assertThat(part(outsource,s.raw())).isEqualByComparingTo("25");assertThat(part(outsource,s.scrap())).isEqualByComparingTo("-2.5");
    var basis=jdbc.queryForList("SELECT evidence_json FROM lp_quote_outsource_scrap_basis WHERE oa_form_item_id=?",String.class,s.itemId());
    assertThat(basis).isNotEmpty();var weights=json.readTree(basis.getLast());
    assertThat(weights.path("grossWeightMaterialCode").asText()).isEqualTo(s.raw());
    assertThat(weights.path("netWeightMaterialCode").asText()).isEqualTo(s.parent());
    assertThat(weights.path("scrapWeightG").decimalValue()).isEqualByComparingTo("50");
    assertThat(call(HttpMethod.GET,base(s)+"/effective-bom/material-shape?versionId="+original,null)).isEqualTo(originalTree);
    var sheet=call(HttpMethod.GET,base(s)+"/cost-run?periodMonth="+s.month()+"&versionId="+outsource,null).path("sheet");
    assertThat(sheet.path("rows").toString()).contains("委外加工费", "委外废料回收",s.raw(),"grossWeightMaterialCode");
    for(int i=0;i<3;i++) { assertThat(calculate(s).path("reusedSuccess").asBoolean()).isTrue();assertThat(latest(s)).isEqualTo(outsource); }
    choose(s,null);assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long restored=latest(s);assertThat(part(restored,s.parent())).isEqualByComparingTo("22.5");
    assertThat(cost(restored,"DIRECT_LABOR")).isEqualByComparingTo("3");
    assertThat(part(original,s.parent())).isEqualByComparingTo("22.5");
    assertThat(cost(original,"DIRECT_LABOR")).isEqualByComparingTo("3");
  }

  @Test void missingU9NetWeightNamesTheActualParentAndDoesNotSaveASuccess() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);classify(s);choose(s,"OUTSOURCE");
    jdbc.update("UPDATE lp_material_master_raw SET global_seg_3_theoretical_net_weight=NULL WHERE material_code=?",s.parent());
    var result=calculate(s);assertThat(result.path("pipelineStatus").asText()).isEqualTo("BLOCKED");
    assertThat(result.path("message").asText()).contains(s.parent(),"U9理论净重");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'",Integer.class,s.itemId())).isZero();
  }

  @Test void missingSrmWeightNamesTheActualRawMaterial() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);classify(s);choose(s,"OUTSOURCE");
    jdbc.update("UPDATE lp_price_linked_item SET net_weight=NULL WHERE material_code=?",s.raw());
    var result=calculate(s);assertThat(result.path("pipelineStatus").asText()).isEqualTo("BLOCKED");
    assertThat(result.path("message").asText()).contains(s.raw(),"SRM","重量");
  }

  @Test void publicPriceLeafCannotSwitchEvenWhenFinanceClassifiedAnotherShape() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    preparations.prepareByOaFormItem(s.itemId(),java.time.LocalDate.now(),s.month());bom(s);
    new OrdinaryCostingTestSources(jdbc,hierarchy).comparisonFixedPrice(s.raw(),"10",
        java.time.YearMonth.parse(s.month()).atDay(1).atTime(12,0));
    catalog.refresh("COMMERCIAL",s.month(),"leaf-test");
    catalog.change("COMMERCIAL",new MaterialPriceSourceCatalog.Change(s.month(),"210",s.raw(),"FIXED","OUTSOURCE",0L),"finance");
    var tree=bom(s);
    var node=java.util.stream.StreamSupport.stream(tree.path("nodes").spliterator(),false)
        .filter(n -> s.raw().equals(n.path("materialCode").asText())).findFirst().orElseThrow();
    assertThat(node.path("multiPriceSource").asBoolean()).isTrue();
    assertThat(node.path("sourceHasChildren").asBoolean()).isFalse();
    var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(token);
    var response=rest.exchange("http://127.0.0.1:"+port+base(s)+"/effective-bom/material-shape",HttpMethod.PATCH,
        new HttpEntity<>(Map.of("periodMonth",s.month(),"materialCode",s.raw(),"organization","210",
            "selectedShape","OUTSOURCE","expectedVersion",0,"expectedVariantHash",tree.path("effectiveVariantHash").asText()),headers),String.class);
    var result=json.readTree(response.getBody());
    assertThat(result.path("code").asInt()).isNotZero();
    assertThat(result.path("msg").asText()).contains(s.raw(),"不能切换");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_material_shape_selection WHERE oa_form_item_id=?",Integer.class,s.itemId())).isZero();
  }

  @Test void originallyOutsourcedMaterialRejectsManufactureAndRetainsPurchaseOutsourceCalculations() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    jdbc.update("UPDATE lp_material_master_raw SET shape_attr='委外加工件' WHERE material_code=?",s.parent());
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET shape_attr='委外加工件' WHERE top_product_code=? AND material_code=?",s.product(),s.parent());
    preparations.prepareByOaFormItem(s.itemId(),java.time.LocalDate.now(),s.month());
    assertThat(bom(s).path("nodes").toString()).contains("委外加工件");
    classify(s);
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long outsourced=latest(s);assertThat(part(outsourced,s.parent())).isEqualByComparingTo("7.5");
    assertThat(part(outsourced,s.raw())).isEqualByComparingTo("25");assertThat(part(outsourced,s.scrap())).isEqualByComparingTo("-2.5");
    assertThatThrownBy(()->choose(s,"MANUFACTURE")).hasMessageContaining("不能改成制造件");
    assertThat(latest(s)).isEqualTo(outsourced);
    choose(s,"PURCHASE");assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long purchased=latest(s);assertThat(part(purchased,s.parent())).isEqualByComparingTo("50");
    assertThat(part(purchased,s.raw())).isNull();
    choose(s,"OUTSOURCE");assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    assertThat(part(latest(s),s.parent())).isEqualByComparingTo("7.5");
    assertThat(part(outsourced,s.parent())).isEqualByComparingTo("7.5");
  }

  @Test void twoPublicMaterialsBatchSaveRecalculateOnceAndRollbackEverySelectionOnConflict() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);String second=MaterialShapeSwitchTestData.secondParent(jdbc,hierarchy,s);
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("SUCCESS");
    long original=latest(s);classify(s);
    catalog.changeBatch("COMMERCIAL",List.of(new MaterialPriceSourceCatalog.Change(s.month(),"210",second,"FIXED","PURCHASE",0L),
        new MaterialPriceSourceCatalog.Change(s.month(),"210",second,"LINKED","OUTSOURCE",0L)),"finance");
    var tree=bom(s);
    var body=Map.of("periodMonth",s.month(),"expectedVariantHash",tree.path("effectiveVariantHash").asText(),"selections",List.of(
        Map.of("materialCode",s.parent(),"organization","210","selectedShape","PURCHASE","expectedVersion",0),
        Map.of("materialCode",second,"organization","210","selectedShape","PURCHASE","expectedVersion",0)));
    call(HttpMethod.PATCH,base(s)+"/effective-bom/material-shape/batch",body);
    assertThat(latest(s)).isEqualTo(original);
    var batchCalculated=calculate(s);
    assertThat(batchCalculated.path("pipelineStatus").asText()).as(batchCalculated.toPrettyString()).isEqualTo("SUCCESS");
    long purchased=latest(s);assertThat(part(purchased,s.parent())).isEqualByComparingTo("50");
    assertThat(part(purchased,second)).isEqualByComparingTo("50");
    assertThat(cost(purchased,"DIRECT_LABOR")).isEqualByComparingTo("1");
    var after=bom(s);
    var bad=Map.of("periodMonth",s.month(),"expectedVariantHash",after.path("effectiveVariantHash").asText(),"selections",List.of(
        Map.of("materialCode",s.parent(),"organization","210","selectedShape","MANUFACTURE","expectedVersion",1),
        Map.of("materialCode",second,"organization","210","selectedShape","MANUFACTURE","expectedVersion",0)));
    assertThatThrownBy(()->call(HttpMethod.PATCH,base(s)+"/effective-bom/material-shape/batch",bad)).hasMessageContaining("更新");
    assertThat(jdbc.queryForList("SELECT selected_shape FROM lp_quote_material_shape_selection WHERE oa_form_item_id=?",String.class,s.itemId())).containsOnly("PURCHASE");
    assertThat(latest(s)).isEqualTo(purchased);
    assertThat(part(original,s.parent())).isEqualByComparingTo("22.5");
  }
  @Test void failedManufacturingPreparationPublishesItsSourceWithoutInventingAPrice() throws Exception {
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    jdbc.update("DELETE FROM lp_price_linked_item WHERE material_code=?",s.raw());
    assertThat(calculate(s).path("pipelineStatus").asText()).isEqualTo("BLOCKED");
    var make=catalog.list("COMMERCIAL",new MaterialPriceSourceCatalog.Filter(s.month(),s.parent(),"210","",1,100)).rows().stream()
        .filter(r -> r.sourceType().equals("MAKE")).findFirst().orElseThrow();
    assertThat(make.prices()).allMatch(p -> p.value()==null);
  }

}
