package com.sanhua.marketingcost.security;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.service.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import java.net.URI;
import java.net.http.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.when;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
  "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15229",
  "monthly.reprice.go-live-months.COMMERCIAL=2026-11", "integration.oa-person-directory.enabled=false",
  "integration.oa-person-directory.run-at-startup=false", "cms.sync-publish.enabled=false",
  "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class QuoteVisibilityIntegrationTest extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired JwtUtils jwt;
  @Autowired QuoteVisibilityPolicy policy;
  @MockBean TechnicalDataQuoteSourceReader sources;
  final HttpClient http=HttpClient.newHttpClient();
  final String prefix="SCOPE-"+UUID.randomUUID().toString().substring(0,8);
  Map<String,String> tokens=new HashMap<>();
  Map<String,Long> users=new HashMap<>();
  List<Sample> samples=new ArrayList<>();
  record Sample(String oaNo,long form,long item,long task,long product,long log,long version,String batch,boolean plate,boolean commercial) {}

  @BeforeAll void fixture() throws Exception {
    for (var entry : Map.of("plate",11,"commercial",11,"admin",1,"director",10).entrySet()) {
      String name=prefix+"-"+entry.getKey();
      jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_time,update_time) VALUES(?,?,?,'COMMERCIAL','0','0',NOW(),NOW())",name,name,"unused");
      long id=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,name);
      jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",id,entry.getValue());
      users.put(entry.getKey(),id);tokens.put(entry.getKey(),jwt.generateToken(name,"COMMERCIAL"));
    }
    policy.configure(users.get("plate"),"PLATE","scope-test");
    // 本机报价员角色实际带菜单通配权限；它不能提升报价范围或获得配置权。
    for (String perm : List.of("*:*:*","ingest:quote:list","ingest:quote:query","ingest:quote:cost-run:execute",
        "ingest:quote:cost-run:query","technical:data:task:list","technical:data:admin:operate",
        "technical:data:task:edit","ingest:quote:log:list","ingest:quote:log:query","ingest:log:list","ingest:log:query","ingest:quote-log:list")) {
      jdbc.update("INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_time,update_time) VALUES(?,0,1,'','F','0','0',?,NOW(),NOW())",prefix+perm,perm);
      long menu=jdbc.queryForObject("SELECT MAX(menu_id) FROM sys_menu WHERE menu_name=?",Long.class,prefix+perm);
      for(long role:List.of(10L,11L)) jdbc.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES(?,?)",role,menu);
    }
    record Case(String process,String name,String masterName,String pendingName,boolean plate,boolean commercial) {}
    var cases=List.of(
        new Case("FI-SC-020","验收产品",null,"未分派产品",true,false),
        new Case("FI-SC-006","验收产品",null,"未分派产品",false,true),
        new Case("FI-SR-005","验收产品",null,"未分派产品",false,true),
        new Case(null,"验收产品",null,"未分派产品",false,true),
        new Case(" fi-sc-020 ","验收产品",null,"未分派产品",true,false),
        new Case("FI-SC-006","钎焊板式换热器",null,"钎焊板式换热器",true,false),
        new Case("FI-SC-005","板式热交换器",null,"板式热交换器",true,false),
        new Case("FI-SR-005","板换",null,"排水泵",true,true),
        new Case("FI-SC-006",null,"钎焊板式换热器",null,true,false),
        new Case("FI-SC-006","电磁阀","钎焊板式换热器","电磁阀",false,true));
    for (var scenario : cases) {
      String process=scenario.process();
      String oaNo=prefix+"-"+samples.size();
      boolean plate=scenario.plate();
      jdbc.update("INSERT INTO lp_quote_ingest_log(request_id,idempotency_key,payload_hash,source_type,oa_no,process_code,payload_json) VALUES(?,?,REPEAT('a',64),'OA',?,?,'{}')",oaNo,oaNo,oaNo,process);
      long log=jdbc.queryForObject("SELECT id FROM lp_quote_ingest_log WHERE request_id=?",Long.class,oaNo);
      jdbc.update("INSERT INTO oa_form(oa_no,process_code,source_business_division,applicant_name,business_unit_type,ingest_log_id) VALUES(?,?,'板换事业部','营业员张三','COMMERCIAL',?)",oaNo,process,log);
      long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oaNo);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,oaNo,scenario.name());
      long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
      jdbc.update("""
          INSERT INTO lp_quote_tech_task(task_no,oa_form_id,oa_form_item_id,oa_no,accounting_month,
            business_unit_type,applicable_org_code,assignee_user_id,assignee_name,active_flag,active_lock_key,task_status)
          VALUES(?,?,?,?,'2026-11','COMMERCIAL','210',NULL,NULL,1,?,'UNASSIGNED')
          """,oaNo,form,item,oaNo,"ITEM:"+item+":MONTH:2026-11");
      long task=jdbc.queryForObject("SELECT id FROM lp_quote_tech_task WHERE task_no=?",Long.class,oaNo);
      jdbc.update("""
          INSERT INTO lp_quote_tech_product(task_id,oa_form_item_id,level_no,source_model,quote_no,accounting_month,
            source_snapshot_json,source_fingerprint,active_flag,active_lock_key,content_schema_version)
          VALUES(?,?,1,'验收型号',?,'2026-11','{"sourceModel":"验收型号","businessUnitType":"COMMERCIAL","materialOrganizationCode":"COMMERCIAL"}',REPEAT('a',64),1,?,2)
          """,task,item,oaNo,"ITEM:"+item+":MONTH:2026-11");
      long product=jdbc.queryForObject("SELECT id FROM lp_quote_tech_product WHERE task_id=?",Long.class,task);
      jdbc.update("INSERT INTO lp_quote_tech_module(product_id,module_type,required_flag,requirement_reason_code,requirement_reason,module_status,source_availability,source_checked_at,source_reference) VALUES(?,'PROFILE',1,'SCOPE_TEST','验收缺口','PENDING','MISSING',NOW(),'SCOPE_TEST')",product);
      // 每张单再有一个尚未分派的待补录产品，覆盖独立的待分派SQL和汇总。
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')",form,oaNo+"-P",scenario.pendingName());
      long pending=jdbc.queryForObject("SELECT MAX(id) FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
      if(scenario.masterName()!=null) {
        for(String code:List.of(oaNo,oaNo+"-P")) for(String org:List.of("COMMERCIAL","PLATE")) {
          jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,active_flag,import_batch_id) VALUES(?,?,?,1,?)",code,scenario.masterName(),org,prefix);
        }
      }
      var now=LocalDateTime.now();
      var check=new TechnicalDataSourceCheckResponse(pending,"2026-11","scope-check",now,List.of(
          new TechnicalDataModuleRequirement("PROFILE",true,"MISSING","缺产品资料",TechnicalDataAvailability.MISSING,"scope",now)),null,List.of());
      when(sources.preview(pending,"2026-11")).thenReturn(check);
      jdbc.update("""
          INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,
            workspace_status,gap_count,last_checked_at,technical_check_json,technical_check_fingerprint)
          VALUES(?,?,?,'2026-11','COMMERCIAL','WAIT_PRICE',1,NOW(),?,'scope-check')
          """,oaNo,pending,oaNo+"-P",json.writeValueAsString(Map.of("check",check)));
      jdbc.update("INSERT INTO lp_quote_cost_run_version(cost_run_no,oa_no,oa_form_item_id,product_code,pricing_month,result_period,business_unit_type) VALUES(?,?,?,?,'2026-11','2026-11','COMMERCIAL')",oaNo+"-RUN",oaNo,item,oaNo);
      long version=jdbc.queryForObject("SELECT id FROM lp_quote_cost_run_version WHERE cost_run_no=?",Long.class,oaNo+"-RUN");
      String batch=UUID.randomUUID().toString();
      jdbc.update("INSERT INTO lp_oa_technical_batch(id,operation,oa_form_id,actor_user_id,request_key,input_fingerprint,request_json) VALUES(?,'I02',?,?,?,REPEAT('a',64),'{}')",batch,form,users.get("admin"),batch);
      samples.add(new Sample(oaNo,form,item,task,product,log,version,batch,plate,scenario.commercial()));
    }
  }
  @Test void quotationListsNamesPaginationAndExactProcessBoundary() throws Exception {
    for(String actor:tokens.keySet()) {
      int expected=(int)samples.stream().filter(sample -> visible(sample,actor)).count();
      var first=get(actor,"/api/v1/quote-requests?oaNo="+prefix+"&pageSize=1");
      assertThat(first.path("data").path("total").asInt()).as(first.toString()).isEqualTo(expected);
      for(int page=1;page<=expected;page++) {
        var data=get(actor,"/api/v1/quote-requests?oaNo="+prefix+"&pageSize=1&pageNo="+page).path("data");
        assertThat(data.path("list").size()).isEqualTo(1);
        var row=data.path("list").get(0);
        assertThat(row.path("salespersonName").asText()).isEqualTo("营业员张三");
        var sample=samples.stream().filter(x->x.oaNo().equals(row.path("oaNo").asText())).findFirst().orElseThrow();
        assertThat(visible(sample,actor)).isTrue();
      }
      var logs=get(actor,"/api/v1/quote-ingest/logs?oaNo="+prefix);
      assertThat(logs.path("data").path("total").asInt()).as(logs.toString()).isEqualTo(expected);
    }
  }
  @Test void workbenchRowsCountsAndPendingProductsUseTheSameBoundary() throws Exception {
    // Mocks reset after tests by Spring; restore current authoritative check per saved pending row.
    for (var row:jdbc.queryForList("SELECT technical_check_json,oa_form_item_id FROM lp_quote_costing_workspace WHERE oa_no LIKE ?",prefix+"%")) {
      var check=json.treeToValue(json.readTree(row.get("technical_check_json").toString()).path("check"),TechnicalDataSourceCheckResponse.class);
      when(sources.preview(((Number)row.get("oa_form_item_id")).longValue(),"2026-11")).thenReturn(check);
    }
    for(String actor:tokens.keySet()) {
      int expected=2*(int)samples.stream().filter(sample -> visible(sample,actor)).count();
      var data=get(actor,"/api/v2/technical-data/products?keyword="+prefix+"&size=1").path("data");
      assertThat(data.path("total").asInt()).as(data.toString()).isEqualTo(expected);
      assertThat(data.path("summary").path("total").asInt()).isEqualTo(expected);
      assertThat(data.path("records").size()).isEqualTo(1);
      var other=samples.get(actor.equals("plate")?1:0);
      if(actor.equals("plate")||actor.equals("commercial")) assertThat(get(actor,"/api/v2/technical-data/products?oaNo="+other.oaNo()).path("data").path("total").asInt()).isZero();
    }
  }
  @Test void directLinksExportsMutationsAndBodyIdsCannotCrossScope() throws Exception {
    for(String actor:List.of("plate","commercial")) {
      Sample other=samples.get(actor.equals("plate")?1:0), own=samples.get(actor.equals("plate")?0:1);
      for(String path:List.of("/api/v1/quote-requests/"+other.oaNo(),
          "/api/v1/oa-forms/"+other.oaNo(),
          "/api/v1/cost-run/"+other.oaNo()+"/export-jjb?productCode=X",
          "/api/v1/cost-run/detail?oaNo="+other.oaNo()+"&productCode=X",
          "/api/v1/quote-requests/"+own.oaNo()+"/items/"+other.item()+"/price-prepare",
          "/api/v1/quote-ingest/logs/"+other.log(),
          "/api/v1/cost-run/detail?oaNo="+own.oaNo()+"&productCode=X&versionId="+other.version(),
          "/api/v1/cost-run/detail/"+other.oaNo()+"-RUN/traces",
          "/api/v2/technical-data/returns/"+other.batch(),
          "/api/v2/technical-data/tasks/"+other.task(),
          "/api/v2/technical-data/forms/"+other.form()+"/workbench",
          "/api/v2/technical-data/products/"+other.product()+"/salary")) {
        denied(actor,"GET",path,null);
      }
      denied(actor,"POST","/api/v1/quote-requests/"+other.oaNo()+"/cost-runs","{}");
      denied(actor,"POST","/api/v2/technical-data/tasks/publish-from-quote","{\"oaFormItemIds\":["+own.item()+","+other.item()+"]}");
      denied(actor,"POST","/api/v2/technical-data/returns/preview","{\"taskIds\":["+other.task()+"]}");
      denied(actor,"POST","/api/v2/technical-data/returns","{\"targets\":[{\"taskId\":"+other.task()+"}]}");
      denied(actor,"POST","/api/v1/quote-ingest/bom-status/check","{\"oaNo\":\""+other.oaNo()+"\"}");
      assertThat(get(actor,"/api/v1/quote-requests/"+own.oaNo()).path("code").asInt()).isZero();
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_no LIKE ?",Integer.class,prefix+"%")).isEqualTo(samples.size());
  }
  @Test void configurationIsAdminOnlyAndTakesEffectOnExistingToken() throws Exception {
    String path="/api/v1/system/user/"+users.get("commercial")+"/quote-scope";
    for(String actor:List.of("plate","commercial","director")) {
      denied(actor,"GET",path,null);
      denied(actor,"PUT",path,"{\"quoteScope\":\"PLATE\"}");
    }
    assertThat(get("admin",path).path("data").path("quoteScope").asText()).isEqualTo("COMMERCIAL");
    try {
      assertThat(call("admin","PUT",path,"{\"quoteScope\":\"PLATE\"}").statusCode()).isEqualTo(200);
      assertThat(get("commercial","/api/v1/quote-requests?oaNo="+prefix).path("data").path("total").asInt()).isEqualTo((int)samples.stream().filter(Sample::plate).count());
      assertThat(call("admin","PUT",path,"{\"quoteScope\":\"ALL\"}").statusCode()).isEqualTo(400);
    } finally { policy.configure(users.get("commercial"),"COMMERCIAL","test"); }
    for(String actor:List.of("admin","director")) {
      policy.configure(users.get(actor),"PLATE","test");
      assertThat(get(actor,"/api/v1/quote-requests?oaNo="+prefix).path("data").path("total").asInt()).isEqualTo(samples.size());
      assertThat(get("admin","/api/v1/system/user/"+users.get(actor)+"/quote-scope").path("data").path("allQuotes").asBoolean()).isTrue();
    }
  }
  @Test void initialAccountConfigurationIsByIdentityAndNeverOverwritesAnExistingChoice() throws Exception {
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_time,update_time) VALUES('胡芳玉','验收昵称','unused','COMMERCIAL','0','0',NOW(),NOW())");
    long id=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name='胡芳玉'",Long.class);
    try(var c=jdbc.getDataSource().getConnection()) {
      var script=new org.springframework.core.io.ClassPathResource("db/V371__initial_plate_quoter_account.sql");
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,script);
      assertThat(policy.account(id).quoteScope()).isEqualTo("PLATE");
      policy.configure(id,"COMMERCIAL","test");
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,script);
      assertThat(policy.account(id).quoteScope()).isEqualTo("COMMERCIAL");
    }
  }
  @Test void failedIngestsWithoutAFormRemainVisibleOnlyInTheirProcessScope() throws Exception {
    String no="FAILED-"+UUID.randomUUID();
    jdbc.update("INSERT INTO lp_quote_ingest_log(request_id,idempotency_key,payload_hash,source_type,oa_no,process_code,payload_json) VALUES(?,?,REPEAT('a',64),'OA',?,'FI-SC-020','{}')",no,no,no);
    long id=jdbc.queryForObject("SELECT id FROM lp_quote_ingest_log WHERE request_id=?",Long.class,no);
    assertThat(get("plate","/api/v1/quote-ingest/logs?oaNo="+no).path("data").path("total").asInt()).isEqualTo(1);
    assertThat(get("commercial","/api/v1/quote-ingest/logs?oaNo="+no).path("data").path("total").asInt()).isZero();
    get("plate","/api/v1/quote-ingest/logs/"+id);
    denied("commercial","GET","/api/v1/quote-ingest/logs/"+id,null);
  }
  @AfterAll void browserIfRequested() throws Exception {
    String file=System.getProperty("quote.scope.browser.state");
    if(file==null) return;
    try(var c=jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(c,
          new org.springframework.core.io.ClassPathResource("db/V60__quote_ingest_enums_menu_seed.sql"));
    }
    for(var row:jdbc.queryForList("SELECT technical_check_json,oa_form_item_id FROM lp_quote_costing_workspace WHERE oa_no LIKE ?",prefix+"%")) {
      var check=json.treeToValue(json.readTree(row.get("technical_check_json").toString()).path("check"),TechnicalDataSourceCheckResponse.class);
      when(sources.preview(((Number)row.get("oa_form_item_id")).longValue(),"2026-11")).thenReturn(check);
    }
    var state=java.nio.file.Path.of(file);
    java.nio.file.Files.createFile(state,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
    java.nio.file.Files.writeString(state,json.writeValueAsString(Map.of("port",port,"tokens",tokens,"users",users,"prefix",prefix,"samples",samples)));
    var release=java.nio.file.Path.of(file+".release");
    long until=System.nanoTime()+java.time.Duration.ofMinutes(20).toNanos();
    while(!java.nio.file.Files.exists(release)&&System.nanoTime()<until) Thread.sleep(200);
    assertThat(java.nio.file.Files.exists(release)).isTrue();
    assertThat(json.readTree(java.nio.file.Files.readString(release)).path("passed").asBoolean()).isTrue();
  }
  @Test void historicalMixedFormIsVisibleToBothButPurePlate006And005AreNotCommercial() throws Exception {
    for (var sample:samples.subList(5,samples.size())) {
      for(String actor:List.of("plate","commercial")) {
        if(visible(sample,actor)) get(actor,"/api/v1/quote-requests/"+sample.oaNo());
        else denied(actor,"GET","/api/v1/quote-requests/"+sample.oaNo(),null);
      }
    }
  }
  boolean visible(Sample sample,String actor) {
    return actor.equals("plate")?sample.plate():actor.equals("commercial")?sample.commercial():true;
  }
  JsonNode get(String actor,String path) throws Exception {
    var response=call(actor,"GET",path,null);
    assertThat(response.statusCode()).as(path+": "+response.body()).isEqualTo(200);
    var value=json.readTree(response.body());assertThat(value.path("code").asInt()).as(value.toString()).isZero();return value;
  }
  void denied(String actor,String method,String path,String body) throws Exception {
    var response=call(actor,method,path,body);
    assertThat(response.statusCode()).as(actor+" "+method+" "+path+": "+response.body()).isEqualTo(403);
  }
  HttpResponse<String> call(String actor,String method,String path,String body) throws Exception {
    var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
      .header("Authorization","Bearer "+tokens.get(actor)).header("Content-Type","application/json")
      .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    return http.send(request,HttpResponse.BodyHandlers.ofString());
  }
}
