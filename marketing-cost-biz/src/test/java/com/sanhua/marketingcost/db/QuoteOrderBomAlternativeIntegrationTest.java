package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.*;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeWorkflowInvalidationService;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","integration.oa-person-directory.enabled=false",
    "integration.oa-person-directory.run-at-startup=false","cms.sync-publish.enabled=false",
    "srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false","price.material-sources.enabled=false"})
class QuoteOrderBomAlternativeIntegrationTest extends MonthlyRepriceCostingFixture {
  @Autowired TestRestTemplate rest;
  @LocalServerPort int port;
  @org.springframework.boot.test.mock.mockito.SpyBean QuoteBomAlternativeWorkflowInvalidationService invalidation;
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  String token,month,std,alt,stdLeaf,altLeaf;
  record Product(String oa,long id,String code,String quantity) {}
  record Scenario(Product first,Product second,Product other) {}

  @BeforeEach void loginAndScope() throws Exception {
    unit="COMMERCIAL";priceOrg="210";materialOrg="COMMERCIAL";
    month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();repriceProperties.setGoLiveMonths(Map.of(unit,month));
    String user="order_alt_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'整单替代测试',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",user,new BCryptPasswordEncoder().encode(password));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,1 FROM sys_user WHERE user_name=?",user);
    rest.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
    token=call(HttpMethod.POST,"/api/v1/auth/login",Map.of("username",user,"password",password,"businessUnitType",unit)).path("token").asText();
  }
  JsonNode raw(HttpMethod method,String path,Object body) throws Exception {
    var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(token!=null)headers.setBearerAuth(token);
    var response=rest.exchange("http://127.0.0.1:"+port+path,method,new HttpEntity<>(body,headers),String.class);
    assertThat(response.getBody()).as(path).isNotBlank();return json.readTree(response.getBody());
  }
  JsonNode call(HttpMethod method,String path,Object body) throws Exception {
    var root=raw(method,path,body);assertThat(root.path("code").asInt()).as(path+" "+root).isZero();return root.path("data");
  }
  String order(Product p){return "/api/v1/quote-requests/"+p.oa()+"/bom-alternatives";}
  String base(Product p){return "/api/v1/quote-requests/"+p.oa()+"/items/"+p.id();}
  JsonNode view(Product p) throws Exception{return call(HttpMethod.GET,order(p),null);}
  Object choice(JsonNode view,String code){return Map.of("expectedFingerprint",view.path("fingerprint").asText(),"selections",List.of(Map.of("key",view.path("rows").get(0).path("key").asText(),"selectedMaterialCode",code)));}
  void choose(Product p,String code) throws Exception {var view=view(p);assertThat(view.path("problems")).as(view.toString()).isEmpty();call(HttpMethod.PATCH,order(p),choice(view,code));}
  long calculate(Product p) throws Exception {
    var result=call(HttpMethod.POST,base(p)+"/cost-runs",Map.of("periodMonth",month,"reason","ORDER_ALTERNATIVE_TEST"));
    assertThat(result.path("pipelineStatus").asText()).as(result.toString()).isEqualTo("SUCCESS");
    return jdbc.queryForObject("SELECT MAX(id) FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'",Long.class,p.id());
  }
  BigDecimal amount(long version,String code){return jdbc.queryForObject("SELECT SUM(amount) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,version,code);}
  List<Map<String,Object>> parts(long version){return jdbc.queryForList("SELECT * FROM lp_cost_run_part_item WHERE cost_run_version_id=? ORDER BY id",version);}
  List<Map<String,Object>> selections(String oa){return jdbc.queryForList("SELECT * FROM lp_quote_bom_alternative_selection WHERE oa_no=? ORDER BY id",oa);}
  void assertExcel(Product p,long version,String included,String excluded) throws Exception {
    var headers=new HttpHeaders();headers.setBearerAuth(token);
    var response=rest.exchange("http://127.0.0.1:"+port+base(p)+"/cost-run/versions/"+version+"/export",HttpMethod.GET,new HttpEntity<>(headers),byte[].class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    try(var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(response.getBody()))) {
      var values=new ArrayList<String>();for(var row:book.getSheetAt(0))for(var cell:row)values.add(cell.toString());
      assertThat(String.join("|",values)).contains(included).doesNotContain(excluded);
    }
  }
  Scenario scenario() throws Exception {
    String prefix="OAALT-"+UUID.randomUUID().toString().substring(0,8);
    std=prefix+"-STD";alt=prefix+"-ALT";stdLeaf=prefix+"-RAW1";altLeaf=prefix+"-RAW2";
    for(String oa:List.of(prefix,prefix+"-OTHER"))jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",oa);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,prefix),other=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,prefix+"-OTHER");
    var first=product(form,prefix,prefix+"-P1","2");var second=product(form,prefix,prefix+"-P2","5");var outside=product(other,prefix+"-OTHER",prefix+"-P3","7");
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);comparisonFixedPrice(stdLeaf,"2",date);comparisonFixedPrice(altLeaf,"3",date);
    call(HttpMethod.POST,order(first)+"/prepare",Map.of());call(HttpMethod.POST,order(outside)+"/prepare",Map.of());
    return new Scenario(first,second,outside);
  }
  Product product(long form,String oa,String product,String quantity) {
    var date=YearMonth.parse(month).atDay(1).atTime(12,0);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_type,shipping_fee,business_unit_type) VALUES(?,?,'标准替代验收','批量品',0,'COMMERCIAL')",form,product);
    long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=? AND material_no=?",Long.class,form,product);
    ordinarySources(product,stdLeaf,form,date);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)",date.getYear());
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?",product+"-BARE",product);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')",product+"-BARE",product+"-BARE",product);
    jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?",product+"-BARE",product);
    for(String code:List.of(std,alt,altLeaf)) jdbc.update("INSERT IGNORE INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag,main_category_code,shape_attr) VALUES(?,?,'件','COMMERCIAL',?,1,'15',?)",code,code,product,code.equals(altLeaf)?"采购件":"虚拟");
    var leaf=rawHierarchy.selectOne(Wrappers.<BomRawHierarchy>lambdaQuery().eq(BomRawHierarchy::getTopProductCode,product).eq(BomRawHierarchy::getMaterialCode,stdLeaf));
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='NORMAL' WHERE top_product_code=?",product);
    for(String branch:List.of(std,alt)) {
      var parent=new BomRawHierarchy();BeanUtils.copyProperties(leaf,parent);parent.setId(null);parent.setMaterialCode(branch);parent.setMaterialName(branch);parent.setShapeAttr("虚拟");
      parent.setPath("/"+product+"/"+branch+"/");parent.setParentCode(product);parent.setLevel(1);parent.setSortSeq(20);parent.setProcessSeq("010");
      parent.setAlternativeGroupKey("GROUP-"+product);parent.setChildType(branch.equals(std)?"STANDARD":"ALTERNATIVE");
      parent.setQtyPerParent(new BigDecimal(quantity));parent.setQtyPerTop(new BigDecimal(quantity));parent.setIsLeaf(0);parent.setSourceLineKey(branch);rawHierarchy.insert(parent);
      var child=new BomRawHierarchy();BeanUtils.copyProperties(leaf,child);child.setId(branch.equals(std)?leaf.getId():null);
      child.setMaterialCode(branch.equals(std)?stdLeaf:altLeaf);child.setMaterialName(child.getMaterialCode());child.setParentCode(branch);child.setPath(parent.getPath()+child.getMaterialCode()+"/");
      child.setLevel(2);child.setSortSeq(10);child.setProcessSeq("010");child.setChildType("NORMAL");child.setAlternativeGroupKey(null);child.setQtyPerParent(BigDecimal.ONE);child.setQtyPerTop(new BigDecimal(quantity));child.setSourceLineKey(child.getMaterialCode());
      if(branch.equals(std))rawHierarchy.updateById(child);else rawHierarchy.insert(child);
    }
    jdbc.update("""
        INSERT INTO lp_bom_u9_source(import_batch_id,source_type,imported_at,price_org_code,parent_material_no,child_material_no,
          child_material_name,stock_unit,bom_purpose,bom_version,child_seq,qty_per_parent,parent_base_qty,effective_from,effective_to,child_type)
        SELECT r.source_import_batch_id,'U9',NOW(),r.price_org_code,r.parent_code,r.material_code,r.material_name,
          '件',r.bom_purpose,r.bom_version,r.sort_seq,r.qty_per_parent,1,r.effective_from,r.effective_to,r.child_type
        FROM lp_bom_raw_hierarchy r WHERE r.top_product_code=? AND r.level>0
        ON DUPLICATE KEY UPDATE id=lp_bom_u9_source.id
        """,product);
    return new Product(oa,item,product,quantity);
  }

  @Test void allProductsSwitchAndRestoreWithOwnQuantitiesAndSavedExcelHistory() throws Exception {
    var s=scenario();
    jdbc.update("UPDATE oa_form SET accounting_period_month=? WHERE oa_no=?",YearMonth.parse(month).minusMonths(1).toString(),s.first().oa());
    var initial=view(s.first());assertThat(initial.path("problems")).as(initial.toString()).isEmpty();assertThat(initial.path("rows")).hasSize(1);assertThat(initial.path("rows").get(0).path("occurrences")).hasSize(2);
    assertThat(initial.path("rows").get(0).path("periodMonth").asText()).isEqualTo(month);
    var beforeRead=selections(s.first().oa());view(s.first());assertThat(selections(s.first().oa())).isEqualTo(beforeRead);
    long first=calculate(s.first()),second=calculate(s.second()),other=calculate(s.other());
    var firstParts=parts(first);var secondParts=parts(second);
    assertThat(amount(first,stdLeaf)).isEqualByComparingTo("4");assertThat(amount(second,stdLeaf)).isEqualByComparingTo("10");
    choose(s.first(),alt);
    long nextFirst=calculate(s.first()),nextSecond=calculate(s.second());
    assertThat(nextFirst).isNotEqualTo(first);assertThat(nextSecond).isNotEqualTo(second);
    assertThat(amount(nextFirst,altLeaf)).isEqualByComparingTo("6");assertThat(amount(nextSecond,altLeaf)).isEqualByComparingTo("15");
    assertThat(amount(nextFirst,stdLeaf)).isNull();assertThat(amount(nextSecond,stdLeaf)).isNull();
    assertThat(parts(first)).isEqualTo(firstParts);assertThat(parts(second)).isEqualTo(secondParts);
    assertThat(calculate(s.other())).isEqualTo(other);assertThat(view(s.other()).path("rows").get(0).path("selectedMaterialCode").asText()).isEqualTo(std);
    assertExcel(s.first(),first,stdLeaf,altLeaf);assertExcel(s.first(),nextFirst,altLeaf,stdLeaf);
    choose(s.first(),std);
    long restoredFirst=calculate(s.first()),restoredSecond=calculate(s.second());
    assertThat(amount(restoredFirst,stdLeaf)).isEqualByComparingTo("4");assertThat(amount(restoredSecond,stdLeaf)).isEqualByComparingTo("10");
    assertThat(amount(restoredFirst,altLeaf)).isNull();assertThat(calculate(s.first())).isEqualTo(restoredFirst);
    assertExcel(s.first(),restoredFirst,stdLeaf,altLeaf);
    assertThat(parts(first)).isEqualTo(firstParts);assertThat(parts(second)).isEqualTo(secondParts);
  }

  @Test void secondProductFailureRollsBackAllSelectionsAndWorkflowChanges() throws Exception {
    var s=scenario();var before=selections(s.first().oa());var states=jdbc.queryForList("SELECT * FROM lp_quote_costing_workspace WHERE oa_no=? ORDER BY id",s.first().oa());
    doThrow(new IllegalStateException("SECOND_PRODUCT_FAILURE")).when(invalidation).invalidate(eq(s.first().oa()),eq(s.second().id()),anyString(),eq(month));
    try {
      var response=raw(HttpMethod.PATCH,order(s.first()),choice(view(s.first()),alt));assertThat(response.path("code").asInt()).isNotZero();
      assertThat(selections(s.first().oa())).isEqualTo(before);
      assertThat(jdbc.queryForList("SELECT * FROM lp_quote_costing_workspace WHERE oa_no=? ORDER BY id",s.first().oa())).isEqualTo(states);
    } finally { reset(invalidation); }
  }

  @Test void staleFingerprintAndForgedMaterialCannotOverwriteOtherUsersChoice() throws Exception {
    var s=scenario();var old=view(s.first());choose(s.first(),alt);var saved=selections(s.first().oa());
    var stale=raw(HttpMethod.PATCH,order(s.first()),choice(old,std));assertThat(stale.path("code").asInt()).isEqualTo(409);
    var forged=raw(HttpMethod.PATCH,order(s.first()),choice(view(s.first()),"NOT-A-CANDIDATE"));assertThat(forged.path("code").asInt()).isEqualTo(400);
    assertThat(selections(s.first().oa())).isEqualTo(saved);
  }

  @Test void officiallyAbsentBomDoesNotPreventUnifiedSelectionOfAvailableProducts() throws Exception {
    var s=scenario();long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,s.first().oa());
    var missing=product(form,s.first().oa(),s.first().oa()+"-P4","1");
    jdbc.update("DELETE FROM lp_bom_raw_hierarchy WHERE top_product_code=?",missing.code());
    jdbc.update("DELETE FROM lp_bom_u9_source WHERE parent_material_no=?",missing.code());
    var blocked=call(HttpMethod.POST,base(missing)+"/cost-runs",Map.of("periodMonth",month));
    assertThat(blocked.path("blockingStatus").asText()).as(blocked.toString()).isEqualTo("WAIT_BOM");
    var view=view(s.first());assertThat(view.path("problems")).hasSize(1);
    assertThat(view.path("problems").get(0).path("blocking").asBoolean(true)).isFalse();
    var before=jdbc.queryForList("SELECT * FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",missing.id());
    var saved=call(HttpMethod.PATCH,order(s.first()),choice(view,alt));
    assertThat(saved.path("affectedItemIds")).hasSize(2);
    assertThat(view(s.first()).path("rows").get(0).path("selectedMaterialCode").asText()).isEqualTo(alt);
    assertThat(jdbc.queryForList("SELECT * FROM lp_quote_costing_workspace WHERE oa_form_item_id=?",missing.id())).isEqualTo(before);
  }

  @Test
  void singleProductRetryRebuildsAlternativeAfterLegacyBomFailure() throws Exception {
    var scenario = scenario();
    var product = scenario.first();
    long original = calculate(product);
    var originalParts = parts(original);
    var groups = call(HttpMethod.GET, base(product) + "/costing-bom/alternative-groups?periodMonth=" + month, null);
    var group = groups.path("groups").get(0);
    call(HttpMethod.PUT, base(product) + "/costing-bom/alternative-groups/" + group.path("alternativeGroupKey").asText() + "/selection",
        Map.of("periodMonth", month, "selectedMaterialCode", alt,
            "expectedSelectionVersion", group.path("selectionVersion").asInt(),
            "expectedBuildBatchId", group.path("sourceBuildBatchId").asText()));

    // Reproduce the persisted state left by the old continuation path, without changing old costs.
    jdbc.update("""
        UPDATE lp_quote_costing_workspace
        SET workspace_status='WAIT_BOM',current_step='QUOTE_BOM',gap_count=1,
            stale_reason_code='BOM_MISSING',
            last_error_code='BOM_MISSING',last_error_step='QUOTE_BOM',
            last_error_message='当前产品没有可用于核算的 BOM，请由产品技术补录后重试'
        WHERE oa_form_item_id=? AND period_month=?
        """, product.id(), month);
    var response = call(HttpMethod.POST, "/api/v1/quote-requests/" + product.oa() + "/costing-preparation",
        Map.of("itemId", product.id(), "periodMonth", month));
    var result = response.path("product");
    assertThat(result.path("pipelineStatus").asText()).as(response.toString()).isEqualTo("SUCCESS");
    long next = jdbc.queryForObject("SELECT MAX(id) FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'", Long.class, product.id());
    assertThat(next).isNotEqualTo(original);
    assertThat(amount(next, altLeaf)).isEqualByComparingTo("6");
    assertThat(amount(next, stdLeaf)).isNull();
    assertThat(parts(original)).isEqualTo(originalParts);
    assertThat(calculate(product)).isEqualTo(next);
    assertExcel(product, original, stdLeaf, altLeaf);
    assertExcel(product, next, altLeaf, stdLeaf);
  }
}
