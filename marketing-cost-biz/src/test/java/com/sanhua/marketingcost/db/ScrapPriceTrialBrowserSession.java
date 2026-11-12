package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.mapper.ScrapPriceMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Owned disposable database only; copies captured price rows without invoking import or pricing writes. */
@Tag("integration")
@EnabledIfSystemProperty(named="scrap.trial.browser.state",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15238",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false"})
class ScrapPriceTrialBrowserSession extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired ScrapPriceMapper prices;
  @Autowired javax.sql.DataSource dataSource;
  @LocalServerPort int port;

  @Test void actualPriceTrialsLeaveAllSavedRowsUnchanged() throws Exception {
    var state=Path.of(System.getProperty("scrap.trial.browser.state"));assertThat(Files.exists(state)).isFalse();
    try(var connection=dataSource.getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V331__scrap_price_menu.sql"));
    }
    var json=new ObjectMapper().findAndRegisterModules();
    var reader=json.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    // This is a new empty Testcontainers database; never execute this fixture against a business DB.
    jdbc.update("DELETE FROM lp_scrap_price");
    for(var original:json.readTree(Files.readString(Path.of(System.getProperty("scrap.trial.original.path"))))) {
      ObjectNode copy=original.deepCopy();
      for (String field:List.of("created_at","updated_at","imported_at","calculated_at"))
        if(copy.path(field).isTextual()) copy.put(field,copy.path(field).asText().replace(" ","T"));
      prices.insert(reader.treeToValue(copy,ScrapPrice.class));
    }
    var before=jdbc.queryForList("SELECT * FROM lp_scrap_price ORDER BY id");
    assertThat(before).hasSize(13);
    var protectedCounts=new LinkedHashMap<String,Integer>();
    for(String table:List.of("lp_material_monthly_price","lp_quote_cost_run_version","lp_price_linked_calc_item"))
      protectedCounts.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
    String user="scrap_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'废料因素试算验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",user,new BCryptPasswordEncoder().encode(password));
    long id=jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?",Long.class,user);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)",id);
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(Map.of("apiPort",port,"username",user,"password",password,"month","2026-11")));
    var released=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(6).toNanos();
    while(!Files.exists(released)&&System.nanoTime()<deadline)Thread.sleep(200);
    assertThat(Files.exists(released)).isTrue();
    assertThat(json.readTree(Files.readString(released)).path("passed").asBoolean()).isTrue();
    assertThat(jdbc.queryForList("SELECT * FROM lp_scrap_price ORDER BY id")).isEqualTo(before);
    for(var entry:protectedCounts.entrySet())
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+entry.getKey(),Integer.class)).isEqualTo(entry.getValue());
    Files.writeString(Path.of(state+".db-verified"),json.writeValueAsString(Map.of("passed",true,"all13OriginalPriceRowsUnchanged",true,"protectedCounts",protectedCounts)));
  }
}
