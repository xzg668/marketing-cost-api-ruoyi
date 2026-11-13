package com.sanhua.marketingcost.service.pricing;

import static com.sanhua.marketingcost.service.pricing.MaterialPriceSourceTestData.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Tag("integration")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","price.material-sources.enabled=false","cms.sync-publish.enabled=false",
    "srm.fixed-price.enabled=false","integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false"})
class MaterialPriceSourceHttpIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired MaterialPriceSourceService service;
  @LocalServerPort int port;
  final ObjectMapper json=new ObjectMapper();
  final HttpClient http=HttpClient.newHttpClient();
  @Test void realHttpEnforcesFinancialWritesAndAcceptsAttributeSearch() throws Exception {
    schema(jdbc);
    fixed(jdbc,"COMMERCIAL","210","CAT-HTTP","2026-10","0.18");
    linked(jdbc,"COMMERCIAL","210","CAT-HTTP","2026-10");
    service.refresh("COMMERCIAL","2026-10","TEST_SETUP");
    String finance=token("finance_reviewer"),staff=token("bu_staff");
    String query="/api/v1/material-price-sources?month=2026-10&materialCode=CAT-HTTP";
    assertThat(send(staff,"GET",query,null).statusCode()).isEqualTo(200);
    var body=Map.of("month","2026-10","organization","210","materialCode","CAT-HTTP","sourceType","FIXED","materialAttribute","OUTSOURCE","expectedVersion",0);
    assertThat(send(staff,"PATCH","/api/v1/material-price-sources/attribute",body).statusCode()).isEqualTo(403);
    assertThat(send(staff,"POST","/api/v1/material-price-sources/refresh",null).statusCode()).isEqualTo(403);
    var manufactured=new java.util.HashMap<String,Object>(body);
    manufactured.put("materialAttribute","MANUFACTURE");
    assertThat(send(finance,"PATCH","/api/v1/material-price-sources/attribute",manufactured).statusCode()).isEqualTo(400);
    assertThat(send(finance,"PATCH","/api/v1/material-price-sources/attribute",body).statusCode()).isEqualTo(200);
    var selected=json.readTree(send(finance,"GET",query+"&materialAttribute=OUTSOURCE",null).body()).path("data");
    assertThat(selected.path("total").asInt()).isEqualTo(1);
    assertThat(selected.path("rows").get(0).path("sourceType").asText()).isEqualTo("FIXED");
    assertThat(selected.path("rows").get(0).path("prices").get(0).path("value").asText()).isEqualTo("0.18");
    assertThat(send(finance,"POST","/api/v1/material-price-sources/refresh",null).statusCode()).isEqualTo(200);
    assertThat(json.readTree(send(finance,"GET",query+"&materialAttribute=OUTSOURCE",null).body()).path("data").path("total").asInt()).isEqualTo(1);
  }
  @Test void quotationEditorCanBatchClassifySourcesButCannotRefreshOrChooseManufacture() throws Exception {
    schema(jdbc);String code="CAT-QUOTER-"+UUID.randomUUID().toString().substring(0,6);
    fixed(jdbc,"COMMERCIAL","210",code,"2026-11","1");linked(jdbc,"COMMERCIAL","210",code,"2026-11");
    service.refresh("COMMERCIAL","2026-11","TEST_SETUP");
    String role="shape_editor_"+UUID.randomUUID().toString().substring(0,6);
    long roleId=800000+new java.util.Random().nextInt(100000);
    jdbc.update("INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,?,1,'0','0','TEST',NOW(),'TEST',NOW())",roleId,role,role);
    for(String permission:List.of("price:material-sources:list","quote:costing:bom:alternative-select")) {
      long menuId=900000+new java.util.Random().nextInt(100000);
      jdbc.update("INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time,update_by,update_time) VALUES(?,?,0,1,'','F','0','0',?,'TEST',NOW(),'TEST',NOW())",menuId,permission,permission);
      jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(?,?)",roleId,menuId);
    }
    String quoter=token(role);
    var change=Map.of("month","2026-11","organization","210","materialCode",code,"sourceType","FIXED","materialAttribute","PURCHASE","expectedVersion",0);
    assertThat(send(quoter,"PATCH","/api/v1/material-price-sources/attribute/batch",List.of(change)).statusCode()).isEqualTo(200);
    var exportRequest=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/material-price-sources/export?month=2026-11&materialCode="+code+"&materialAttribute=PURCHASE"))
        .header("Authorization","Bearer "+quoter).GET().build();
    var exported=http.send(exportRequest,HttpResponse.BodyHandlers.ofByteArray());
    assertThat(exported.statusCode()).isEqualTo(200);
    try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(exported.body()))) {
      assertThat(book.getSheetAt(0).getLastRowNum()).isEqualTo(1);
      assertThat(book.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo(code);
      assertThat(book.getSheetAt(0).getRow(1).getCell(14).getStringCellValue()).isEqualTo("采购件");
    }
    assertThat(send(quoter,"POST","/api/v1/material-price-sources/refresh",null).statusCode()).isEqualTo(403);
    var invalid=new HashMap<String,Object>(change);invalid.put("materialAttribute","MANUFACTURE");invalid.put("expectedVersion",1);
    assertThat(send(quoter,"PATCH","/api/v1/material-price-sources/attribute/batch",List.of(invalid)).statusCode()).isEqualTo(400);
  }
  private String token(String role) throws Exception {
    String user="http_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'来源HTTP验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",user,new BCryptPasswordEncoder().encode(password));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT u.user_id,r.role_id FROM sys_user u JOIN sys_role r ON r.role_key=? WHERE u.user_name=?",role,user);
    var response=send(null,"POST","/api/v1/auth/login",Map.of("username",user,"password",password,"businessUnitType","COMMERCIAL"));
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body()).path("data").path("token").asText();
  }
  private HttpResponse<String> send(String token,String method,String path,Object body) throws Exception {
    var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
    if(token!=null) builder.header("Authorization","Bearer "+token);
    builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
  }
}
