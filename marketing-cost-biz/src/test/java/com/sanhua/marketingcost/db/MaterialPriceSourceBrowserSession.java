package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.MaterialPriceSourceTestData.*;
import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceService;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Fresh Testcontainers data only. This fixture must never be run against a business database. */
@Tag("integration")
@EnabledIfSystemProperty(named="catalog.browser.state", matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15239",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "price.material-sources.enabled=false", "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class MaterialPriceSourceBrowserSession extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired MaterialPriceSourceService sourceService;
  @LocalServerPort int port;

  @Test void quotationStaffCanMaintainAttributesWithoutChangingSourcesOrCosts() throws Exception {
    var state = Path.of(System.getProperty("catalog.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    schema(jdbc);
    for (String month:List.of("2026-10","2026-11")) {
      fixed(jdbc,"COMMERCIAL","210","CATALOG-001",month,"0.18");
      linked(jdbc,"COMMERCIAL","210","CATALOG-001",month);
      fixed(jdbc,"COMMERCIAL","210","CATALOG-002",month,"20");
      tree(jdbc,"CATALOG-002",month);
    }
    jdbc.update("INSERT INTO lp_material_master_raw(organization_code,material_code,shape_attr,active_flag,import_batch_id,material_model,material_spec) VALUES('COMMERCIAL','CATALOG-001','制造件',1,'CATALOG-TEST','MODEL-001','SPEC-001')");
    sourceService.refresh("COMMERCIAL","2026-10","TEST_SETUP");
    sourceService.refresh("COMMERCIAL","2026-11","TEST_SETUP");
    var accounts = new LinkedHashMap<String, Object>();
    accounts.put("staff", account("bu_staff"));
    accounts.put("finance", account("finance_reviewer"));
    jdbc.update("INSERT INTO sys_role(role_name,role_key,role_sort,status,del_flag,create_time,update_time) VALUES('来源只读验收','catalog_reader',98,'0','0',NOW(),NOW()),('来源无权验收','catalog_denied',99,'0','0',NOW(),NOW())");
    jdbc.update("""
        INSERT INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m
          ON m.perms='price:material-sources:list' OR m.menu_id=(SELECT parent_id FROM sys_menu WHERE perms='price:material-sources:list')
        WHERE r.role_key='catalog_reader'
        """);
    accounts.put("reader", account("catalog_reader"));
    accounts.put("denied", account("catalog_denied"));
    var sourcesBefore = new LinkedHashMap<String, Object>();
    for (String table : List.of("lp_price_fixed_item", "lp_price_linked_item", "lp_price_prepare_item",
        "lp_material_monthly_price", "lp_quote_cost_run_version", "lp_cost_run_part_item", "lp_make_part_price_calc_row"))
      sourcesBefore.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    var json = new ObjectMapper().findAndRegisterModules();
    Files.writeString(state, json.writeValueAsString(Map.of("apiPort", port, "accounts", accounts, "month", "2026-11")));
    var released = Path.of(state + ".release");
    long deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
    while (!Files.exists(released) && System.nanoTime() < deadline) Thread.sleep(250);
    assertThat(Files.exists(released)).isTrue();
    assertThat(json.readTree(Files.readString(released)).path("passed").asBoolean()).isTrue();
    for (var entry : sourcesBefore.entrySet())
      assertThat(jdbc.queryForList("SELECT * FROM " + entry.getKey() + " ORDER BY id")).isEqualTo(entry.getValue());
    assertThat(jdbc.queryForObject("SELECT material_attribute FROM lp_material_price_attribute WHERE material_code='CATALOG-001' AND price_source_type='FIXED' AND price_month='2026-10'", String.class)).isEqualTo("OUTSOURCE");
    Files.writeString(Path.of(state + ".db-verified"), "{\"passed\":true,\"sourcesAndCostsUnchanged\":true}");
  }

  private Map<String, String> account(String role) {
    String username = "catalog_" + UUID.randomUUID().toString().substring(0, 8), password = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'来源汇总验收',?,'COMMERCIAL','0','0','TEST',NOW(),'TEST',NOW())",
        username, new BCryptPasswordEncoder().encode(password));
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) SELECT u.user_id,r.role_id FROM sys_user u JOIN sys_role r ON r.role_key=? WHERE u.user_name=?", role, username);
    return Map.of("username", username, "password", password);
  }
}
