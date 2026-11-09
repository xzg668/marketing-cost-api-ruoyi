package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** New owned fixtures only: real API, MySQL, browser downloads/import and normal costing. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15228",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class SupplierRatioExportBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;

  @org.springframework.test.context.DynamicPropertySource
  static void period(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state = Path.of(System.getProperty("supplier.export.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    try (var connection = jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V90__supplier_supply_ratio_menu.sql"));
    }
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(1).atTime(12, 0);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)", date.getYear());
    var sources = new OrdinaryCostingTestSources(jdbc, hierarchy);
    var samples = new ArrayList<Map<String, Object>>();
    for (String scenario : List.of("PENDING", "HIGHEST")) {
      String product = "SRE-" + UUID.randomUUID().toString().substring(0, 8), material = product + "-PART";
      jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')", product);
      long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, product);
      jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type,business_type,shipping_fee) VALUES(?,?,?,'COMMERCIAL','批量品',0)", form, product, "供货补齐导出 " + scenario);
      long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
      sources.ordinarySources(product, material, form, date);
      jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=? AND organization_code='COMMERCIAL'", product + "-BARE", product);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')", product + "-BARE", product + "-BARE", product);
      jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?", product + "-BARE", product);
      jdbc.update("UPDATE lp_material_master_raw SET material_name=?,material_spec=?,material_model=? WHERE material_code=? AND organization_code='COMMERCIAL'",
          "验收阀套" + scenario, "SPEC-" + scenario, scenario.equals("PENDING") ? "MODEL-PENDING" : null, material);
      jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,material_spec,material_model,organization_code,import_batch_id,active_flag) VALUES(?,'其他组织名称','其他组织规格','其他组织型号','PLATE',?,1)", material, product);
      jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0", product);
      for (String supplier : List.of("A", "B", "C")) {
        jdbc.update("INSERT INTO lp_price_fixed_item(org_code,supplier_code,supplier_name,material_code,material_name,unit,fixed_price,tax_included,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('210',?,?,?,?,'件',?,0,?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",
            supplier, "供应商" + supplier, material, material, new BigDecimal(supplier.equals("A") ? "10" : supplier.equals("B") ? "30" : "20"), date.toLocalDate());
      }
      for (String supplier : List.of("A", "B", "C", "D")) {
        jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,unit,material_shape,supplier_code,supplier_name,supply_ratio,source_type,source_batch_no,is_active,deleted) VALUES('COMMERCIAL',?,?,?,'件','采购件',?,?,?,'EXCEL',?,1,0)",
            material, "验收阀套" + scenario, scenario.equals("PENDING") ? "MODEL-PENDING" : "", supplier, "供应商" + supplier,
            supplier.equals("C") ? BigDecimal.ZERO : supplier.equals("D") ? new BigDecimal("0.1") : null, product);
      }
      samples.add(Map.of("scenario", scenario, "oaNo", product, "itemId", item, "material", material));
    }
    var beforePrices = jdbc.queryForList("SELECT * FROM lp_price_fixed_item ORDER BY id");
    var beforeMaster = jdbc.queryForList("SELECT * FROM lp_material_master_raw ORDER BY id");
    String actor = "ratio_export_" + UUID.randomUUID().toString().substring(0, 8), password = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'供货补齐验收',?,'COMMERCIAL','0','0','SSR_EXPORT_TEST',NOW(),'SSR_EXPORT_TEST',NOW())", actor, new BCryptPasswordEncoder().encode(password));
    long user = jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?", Long.class, actor);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)", user);
    var json = new ObjectMapper().findAndRegisterModules();
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state, json.writeValueAsString(Map.of("apiPort", port, "username", actor, "password", password,
        "month", month, "samples", samples)));
    Path release = Path.of(state + ".release");
    boolean pendingVerified = false;
    long deadline = System.nanoTime() + Duration.ofMinutes(35).toNanos();
    while (!Files.exists(release) && System.nanoTime() < deadline) {
      if (!pendingVerified && Files.exists(Path.of(state + ".pending-exported"))) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_supplier_price_decision", Integer.class)).isZero();
        Files.writeString(Path.of(state + ".pending-export-verified"), "ok");
        pendingVerified = true;
      }
      Thread.sleep(200);
    }
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(pendingVerified).isTrue();
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_fixed_item ORDER BY id")).isEqualTo(beforePrices);
    assertThat(jdbc.queryForList("SELECT * FROM lp_material_master_raw ORDER BY id")).isEqualTo(beforeMaster);
    var checked = new ArrayList<Map<String, Object>>();
    for (var sample : samples) {
      var version = jdbc.queryForMap("SELECT id,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'", sample.get("itemId"));
      String expected = sample.get("scenario").equals("PENDING") ? "10" : "30";
      assertThat(jdbc.queryForObject("SELECT unit_price FROM lp_material_monthly_price WHERE material_code=?", BigDecimal.class, sample.get("material"))).isEqualByComparingTo(expected);
      assertThat(jdbc.queryForList("SELECT supplier_code FROM lp_supplier_supply_ratio WHERE material_code=? AND is_active=1 ORDER BY supplier_code", String.class, sample.get("material"))).containsExactly("A", "B", "C", "D");
      assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE material_code=? AND supplier_code='C' AND is_active=1", BigDecimal.class, sample.get("material"))).isZero();
      assertThat(jdbc.queryForObject("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE material_code=? AND supplier_code='D' AND is_active=1", BigDecimal.class, sample.get("material"))).isEqualByComparingTo("0.1");
      checked.add(Map.of("scenario", sample.get("scenario"), "itemId", sample.get("itemId"), "monthlyUnitPrice", expected, "cost", version));
    }
    Files.writeString(Path.of(state + ".db-verified"), json.writeValueAsString(Map.of("passed", true,
        "pendingExportCreatedNoMonthlyPriceCostOrDecision", true, "originalPriceAndMasterRowsUnchanged", true,
        "zeroAndNonCandidateSupplierPreserved", true, "cases", checked)));
  }
}
