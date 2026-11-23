package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15219",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false",
    "quote.drawing-verification.enabled=false", "price.material-sources.enabled=false",
    "quote.month-close.mode=ENABLED"})
class QuoteMonthCloseBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper bom;
  @Autowired BomSettlementRuleMapper rules;

  @DynamicPropertySource
  static void quoteMonth(DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL", CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test
  void savesOrdinaryCostClosesItAndChecksBrowserNavigationAndRejection() throws Exception {
    var fixture = MaterialShapeSwitchTestData.create(jdbc, bom, rules);
    jdbc.update("""
        INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,visible,status)
        VALUES(40161,'核算与月度调价',0,20,'cost','Layout','M','0','0'),
              (40197,'月度调价',40161,20,'/cost/monthly-reprice','pages:MonthlyRepricePage','C','0','0')
        """);
    jdbc.update("UPDATE sys_menu SET component='pages:MonthlyRepricePage',path='/cost/monthly-reprice' WHERE menu_id=40197");
    new ResourceDatabasePopulator(new ClassPathResource("db/V392__quote_month_close.sql")).execute(jdbc.getDataSource());
    new ResourceDatabasePopulator(new ClassPathResource("db/V393__quote_month_close_revocation.sql")).execute(jdbc.getDataSource());
    jdbc.update("UPDATE oa_form SET accounting_period_month=?,customer='结账验收客户' WHERE oa_no=?",
        fixture.month(), fixture.oaNo());
    var accounts = new LinkedHashMap<String, Map<String, String>>();
    for (String role : java.util.List.of("admin", "bu_director", "bu_staff")) accounts.put(role, account(role));
    var json = new ObjectMapper();
    Path state = Path.of(System.getProperty("quote.close.browser.state"));
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state, json.writeValueAsString(Map.of("port", port, "accounts", accounts,
        "oaNo", fixture.oaNo(), "itemId", fixture.itemId(), "month", fixture.month())));
    Path completed = Path.of(state + ".result");
    long deadline = System.nanoTime() + Duration.ofMinutes(6).toNanos();
    while (!Files.exists(completed) && System.nanoTime() < deadline) Thread.sleep(200);
    assertThat(completed).exists();
    var result = json.readTree(Files.readString(completed));
    assertThat(result.path("passed").asBoolean()).isTrue();
    Long version = result.path("versionId").asLong();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_month_close WHERE revoked_at IS NOT NULL", Long.class)).isEqualTo(2L);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_month_close WHERE active_period_month IS NOT NULL", Long.class)).isZero();
    assertThat(jdbc.queryForList("SELECT cost_version_id FROM lp_quote_month_close_item WHERE oa_form_item_id=?",
        Long.class, fixture.itemId())).containsExactly(version, version);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",
        Long.class, fixture.itemId())).isEqualTo(1L);
    assertThat(jdbc.queryForObject("SELECT status FROM lp_quote_cost_run_version WHERE id=?", String.class, version))
        .isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject("""
        SELECT status FROM lp_cost_run_task WHERE scene='QUOTE' AND oa_form_item_id=? ORDER BY id DESC LIMIT 1
        """, String.class, fixture.itemId())).isEqualTo("SKIPPED_CURRENT");
  }

  private Map<String, String> account(String role) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    String username = "close_" + suffix;
    String password = UUID.randomUUID().toString();
    jdbc.update("""
        INSERT INTO sys_user(user_name,nick_name,password,employee_no,business_unit_type,status,del_flag,create_time,update_time)
        VALUES(?,'月末结账验收',?,?,'COMMERCIAL','0','0',NOW(),NOW())
        """, username, new BCryptPasswordEncoder().encode(password), "TEST-" + suffix);
    int assigned = jdbc.update("""
        INSERT INTO sys_user_role(user_id,role_id)
        SELECT u.user_id,r.role_id FROM sys_user u JOIN sys_role r ON r.role_key=? WHERE u.user_name=?
        """, role, username);
    assertThat(assigned).isEqualTo(1);
    return Map.of("username", username, "password", password);
  }
}
