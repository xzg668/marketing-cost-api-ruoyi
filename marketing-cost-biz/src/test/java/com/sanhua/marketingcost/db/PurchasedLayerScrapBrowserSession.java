package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Holds a disposable fixture for comparing released API/worker jars and saved sheets. */
@Tag("integration")
@EnabledIfSystemProperty(named="purchase.scrap.browser.state",matches=".+")
class PurchasedLayerScrapBrowserSession extends MonthlyRepriceCostingFixture {
  @Autowired BomSettlementRuleMapper rules;
  @Test void keepIsolatedFixtureForReleasedJarComparison() throws Exception {
    var state=Path.of(System.getProperty("purchase.scrap.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    new ResourceDatabasePopulator(new ClassPathResource("db/V381__quotation_order_material_shape.sql"),
        new ClassPathResource("db/V382__quotation_order_price_source.sql")).execute(jdbc.getDataSource());
    var shape=MaterialShapeSwitchTestData.create(jdbc,rawHierarchy,rules);
    String layer=PurchasedLayerScrapIntegrationTest.addLayer(jdbc,rawHierarchy,shape);
    String user="purchase_scrap_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'整件采购废料验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",user,new BCryptPasswordEncoder().encode(password));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,1 FROM sys_user WHERE user_name=?",user);
    var data=new LinkedHashMap<String,Object>();
    data.put("sample",shape);data.put("layer",layer);data.put("recovery",layer+"-SCRAP");
    data.put("username",user);data.put("password",password);
    data.put("jdbcUrl",MYSQL.getJdbcUrl());data.put("databaseUser",MYSQL.getUsername());data.put("databasePassword",MYSQL.getPassword());
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    var json=new ObjectMapper().findAndRegisterModules();Files.writeString(state,json.writeValueAsString(data));
    var release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(30).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline) Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,shape.itemId())).isGreaterThanOrEqualTo(4);
    Files.writeString(Path.of(state+".db-verified"),"{\"passed\":true}");
  }
}
