package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Owned disposable database; production HTTP and Vue drive the two complete-BOM paths. */
@Tag("integration")
@EnabledIfSystemProperty(named="technical.bom.browser.state", matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15471",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class TechnicalCompleteBomBrowserSession extends TechnicalCompleteBomCostingIntegrationTest {
  @LocalServerPort int port;

  @Test void browserSession() throws Exception {
    var state=Path.of(System.getProperty("technical.bom.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    createFixture(true);
    var cases=new ArrayList<Map<String,Object>>();
    var uploadFile=Path.of(state+".xlsx");Files.write(uploadFile,upload("4"));
    cases.add(scenario("UPLOAD","8"));
    fixture();
    if (Boolean.getBoolean("technical.bom.browser.replacement")) {
      String newMaterial=material+"-REPLACE", model=newMaterial+"-MODEL";
      modelOnlyPurchase(newMaterial,model);
      var replacement=new LinkedHashMap<>(scenario("REFERENCE","21"));
      replacement.put("replaceFrom",material);
      replacement.put("material",newMaterial);
      replacement.put("replacementModel",model);
      cases.add(replacement);
    } else cases.add(scenario("REFERENCE","12"));
    String username="bom_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'BOM补录验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",username,new BCryptPasswordEncoder().encode(password));
    long user=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,username);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",user);
    var json=new ObjectMapper();
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(Map.of("apiPort",port,"cases",cases,"month",month,
        "username",username,"password",password,"uploadFile",uploadFile.toString(),
        "jdbcUrl",MYSQL.getJdbcUrl(),"dbUsername",MYSQL.getUsername(),"dbPassword",MYSQL.getPassword())));
    var release=Path.of(state+".release");
    long deadline=System.nanoTime()+Duration.ofMinutes(45).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline) Thread.sleep(200);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    var evidence=new ArrayList<Map<String,Object>>();
    for(var scenario:cases) {
      var versions=jdbc.queryForList("SELECT id FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",scenario.get("item"));
      assertThat(versions).hasSize(1);var version=versions.getFirst().get("id");
      var amount=jdbc.queryForObject("SELECT SUM(amount) FROM lp_cost_run_part_item WHERE cost_run_version_id=? AND part_code=?",BigDecimal.class,version,scenario.get("material"));
      assertThat(amount).isEqualByComparingTo((String)scenario.get("amount"));
      evidence.add(Map.of("item",scenario.get("item"),"version",version,"amount",amount));
    }
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"cases",evidence)));
  }

  private Map<String,Object> scenario(String mode,String amount) {
    return Map.of("mode",mode,"oaNo",code,"item",item,"product",product,"task",task,
        "sample",sample,"material",material,"amount",amount);
  }
}
