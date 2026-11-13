package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.pricing.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Tag("integration")
@EnabledIfSystemProperty(named="shape.browser.state",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "server.address=127.0.0.1","app.cors.allowed-origins=http://127.0.0.1:15240",
    "integration.oa-person-directory.enabled=false","integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false","srm.fixed-price.enabled=false","quote.drawing-verification.enabled=false","price.material-sources.enabled=false"})
class MaterialShapeSwitchBrowserSession extends OriginalPricingIntegrationTestBase {
  @org.springframework.test.context.DynamicPropertySource
  static void period(org.springframework.test.context.DynamicPropertyRegistry r) {
    r.add("monthly.reprice.go-live-months.COMMERCIAL",com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;
  @Autowired BomSettlementRuleMapper rules;
  @Autowired QuoteProductBomPreparationService preparations;
  @Autowired QuoteEffectiveBomApplicationService boms;
  @Autowired MaterialPriceSourceService catalog;
  @LocalServerPort int port;
  @Test void browser() throws Exception {
    var state=Path.of(System.getProperty("shape.browser.state"));assertThat(Files.exists(state)).isFalse();
    var s=MaterialShapeSwitchTestData.create(jdbc,hierarchy,rules);
    var authentication=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("fixture","",
        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")));
    authentication.setDetails(Map.of("businessUnitType","COMMERCIAL"));
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
    try { preparations.prepareByOaFormItem(s.itemId(),LocalDate.now(),s.month());boms.rebuildPreview(s.oaNo(),s.itemId()); }
    finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    catalog.refresh("COMMERCIAL",s.month(),"BROWSER_SETUP");
    String user="shape_browser_"+UUID.randomUUID().toString().substring(0,8),password=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'多形态页面验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",user,new BCryptPasswordEncoder().encode(password));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT user_id,1 FROM sys_user WHERE user_name=?",user);
    var json=new ObjectMapper().findAndRegisterModules();
    Files.createFile(state,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state,json.writeValueAsString(Map.of("apiPort",port,"sample",s,"username",user,"password",password,
        "jdbcUrl",MYSQL.getJdbcUrl(),"databaseUser",MYSQL.getUsername(),"databasePassword",MYSQL.getPassword())));
    var release=Path.of(state+".release");long deadline=System.nanoTime()+Duration.ofMinutes(20).toNanos();
    while(!Files.exists(release)&&System.nanoTime()<deadline) Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,s.itemId())).isGreaterThanOrEqualTo(4);
    Files.writeString(Path.of(state+".db-verified"),"{\"passed\":true}");
  }
}
