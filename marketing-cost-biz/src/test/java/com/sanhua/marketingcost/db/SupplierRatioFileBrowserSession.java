package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Actual workbook upload through the UI and production API in a fresh disposable database. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15230",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class SupplierRatioFileBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @Test void originalShellWorkbookImportAndCorrection() throws Exception {
    Path state = Path.of(System.getProperty("supplier.file.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    try (var connection = jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V90__supplier_supply_ratio_menu.sql"));
    }
    String actor = "ratio_file_" + UUID.randomUUID().toString().substring(0,8), password = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'文件兼容验收',?,'COMMERCIAL','0','0','SSR_FILE_TEST',NOW(),'SSR_FILE_TEST',NOW())", actor, new BCryptPasswordEncoder().encode(password));
    long user = jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?", Long.class, actor);
    grant(user, true);
    String reader = actor + "_read";
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'只读验收',?,'COMMERCIAL','0','0','SSR_FILE_TEST',NOW(),'SSR_FILE_TEST',NOW())", reader, new BCryptPasswordEncoder().encode(password));
    grant(jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?", Long.class, reader), false);
    var json = new ObjectMapper().findAndRegisterModules();
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state, json.writeValueAsString(Map.of("apiPort", port, "username", actor, "password", password, "reader", reader)));
    boolean imported = false;
    Path release = Path.of(state + ".release");
    long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
    while (!Files.exists(release) && System.nanoTime() < deadline) {
      if (!imported && Files.exists(Path.of(state + ".imported"))) {
        assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE supplier_code='S000219' AND is_active=1", BigDecimal.class)).isEqualByComparingTo("0.2");
        assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE supplier_code='S000241' AND is_active=1", BigDecimal.class)).isEqualByComparingTo("0.8");
        imported = true;
        Files.writeString(Path.of(state + ".imported-verified"), "ok");
      }
      Thread.sleep(200);
    }
    assertThat(imported).isTrue(); assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_supplier_supply_ratio", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_supplier_supply_ratio WHERE material_code='721300148' AND spec_model='DPF-S03-024001' AND is_active=1", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE supplier_code='S000219' AND is_active=1", BigDecimal.class)).isEqualByComparingTo("0.25");
    assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE supplier_code='S000219' AND is_active=0", BigDecimal.class)).isEqualByComparingTo("0.2");
    assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE supplier_code='S000241' AND is_active=1", BigDecimal.class)).isEqualByComparingTo("0.8");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_business_change_log WHERE biz_domain='SUPPLIER_SUPPLY_RATIO'", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version", Integer.class)).isZero();
    Files.writeString(Path.of(state + ".db-verified"), json.writeValueAsString(Map.of("passed", true,
        "originalImport20And80", true, "edited25AndOtherSupplier80", true,
        "original20RetainedAsHistory", true, "noMonthlyPriceOrCostCreated", true)));
  }

  private void grant(long user, boolean editor) {
    String key = "ssr_file_" + user;
    jdbc.update("INSERT INTO sys_role(role_name,role_key,role_sort,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,?,90,'0','0','SSR_FILE_TEST',NOW(),'SSR_FILE_TEST',NOW())", key, key);
    long role = jdbc.queryForObject("SELECT role_id FROM sys_role WHERE role_key=?", Long.class, key);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", user, role);
    jdbc.update("""
        INSERT INTO sys_role_menu(role_id,menu_id)
        WITH RECURSIVE menus AS (
          SELECT menu_id,parent_id FROM sys_menu WHERE perms='base:supplier-supply-ratio:list'
            OR (? AND perms='base:supplier-supply-ratio:import')
          UNION DISTINCT SELECT p.menu_id,p.parent_id FROM sys_menu p JOIN menus c ON c.parent_id=p.menu_id
        ) SELECT ?,menu_id FROM menus
        """, editor, role);
  }
}
