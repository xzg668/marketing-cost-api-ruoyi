package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioExcelRow;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioImportService;
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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** Fresh isolated sources; the browser performs the replacement import and both full costing paths. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15208",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class SupplierRatioMaterialBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper rawHierarchy;
  @Autowired SupplierSupplyRatioImportService ratios;

  @org.springframework.test.context.DynamicPropertySource
  static void period(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state = Path.of(System.getProperty("supplier.ratio.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    try (var connection = jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V90__supplier_supply_ratio_menu.sql"));
    }
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(1).atTime(12, 0);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)", date.getYear());
    var sources = new OrdinaryCostingTestSources(jdbc, rawHierarchy);
    var samples = new ArrayList<Map<String, Object>>();
    var auth = new UsernamePasswordAuthenticationToken("ratio-fixture", null);
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      for (String scenario : List.of("REPLACE", "CARRY")) {
        String product = "SSR-" + UUID.randomUUID().toString().substring(0, 8);
        String material = product + "-PART";
        jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')", product);
        long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, product);
        jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type) VALUES(?,?,?,'COMMERCIAL')", form, product, "供货比率验收 " + scenario);
        long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
        sources.ordinarySources(product, material, form, date);
        jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?", product + "-BARE", product);
        jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')", product + "-BARE", product + "-BARE", product);
        jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?", product + "-BARE", product);
        for (String supplier : List.of("A", "B", "C")) {
          String price = supplier.equals("A") ? "10" : supplier.equals("B") ? "30" : "20";
          jdbc.update("INSERT INTO lp_price_fixed_item(org_code,supplier_code,supplier_name,material_code,material_name,unit,fixed_price,tax_included,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('210',?,?,?,?,'件',?,0,?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",
              supplier, supplier, material, material, new BigDecimal(price), date.toLocalDate());
        }
        ratios.importRows(List.of(row(material, "A", "0.1"), row(material, "B", "0.1"), row(material, "C", "0.8")), "previous-quarter.xlsx", "COMMERCIAL", "ratio-fixture");
        samples.add(Map.of("scenario", scenario, "oaNo", product, "itemId", item,
            "material", material, "base", scenario.equals("REPLACE") ? "10" : "20", "difference", 0));
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
    String replaced = samples.getFirst().get("material").toString();
    String carried = samples.getLast().get("material").toString();
    var beforeCarried = jdbc.queryForList("SELECT * FROM lp_supplier_supply_ratio WHERE material_code=? ORDER BY id", carried);
    var beforePrices = jdbc.queryForList("SELECT * FROM lp_price_fixed_item ORDER BY id");
    Path workbook = Path.of(state + ".xlsx");
    try (var book = new XSSFWorkbook(); var out = Files.newOutputStream(workbook)) {
      var sheet = book.createSheet("本季度供货比率");
      String[] headers = {"物料代码", "物料名称", "供应商", "供应商代码", "供货比例", "单位"};
      var header = sheet.createRow(0);
      for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
      for (int i = 0; i < 2; i++) {
        var row = sheet.createRow(i + 1);
        String supplier = i == 0 ? "A" : "B";
        row.createCell(0).setCellValue(replaced); row.createCell(1).setCellValue(replaced);
        row.createCell(2).setCellValue(supplier); row.createCell(3).setCellValue(supplier);
        row.createCell(4).setCellValue(i == 0 ? 0.6 : 0.4); row.createCell(5).setCellValue("件");
      }
      book.write(out);
    }
    String actor = "ratio_" + UUID.randomUUID().toString().substring(0, 8);
    String password = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'供货验收',?,'COMMERCIAL','0','0','SSR_TEST',NOW(),'SSR_TEST',NOW())", actor, new BCryptPasswordEncoder().encode(password));
    long user = jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?", Long.class, actor);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)", user);
    var json = new ObjectMapper().findAndRegisterModules();
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state, json.writeValueAsString(Map.of("apiPort", port, "username", actor,
        "password", password, "month", month, "workbook", workbook.toString(), "samples", samples)));
    Path release = Path.of(state + ".release");
    long deadline = System.nanoTime() + Duration.ofMinutes(35).toNanos();
    while (!Files.exists(release) && System.nanoTime() < deadline) Thread.sleep(250);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    assertThat(jdbc.queryForList("SELECT * FROM lp_supplier_supply_ratio WHERE material_code=? ORDER BY id", carried)).isEqualTo(beforeCarried);
    assertThat(jdbc.queryForList("SELECT supplier_code FROM lp_supplier_supply_ratio WHERE material_code=? AND is_active=1 ORDER BY supplier_code", String.class, replaced)).containsExactly("A", "B");
    assertThat(jdbc.queryForList("SELECT supply_ratio FROM lp_supplier_supply_ratio WHERE material_code=? AND supplier_code='C'", BigDecimal.class, replaced)).containsExactly(new BigDecimal("0.800000"));
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_fixed_item ORDER BY id")).isEqualTo(beforePrices);
    var checked = new ArrayList<Map<String, Object>>();
    for (var sample : samples) {
      var version = jdbc.queryForMap("SELECT id,total_cost,final_quote_amount FROM lp_quote_cost_run_version WHERE oa_form_item_id=? AND status='SUCCESS'", sample.get("itemId"));
      assertThat((BigDecimal) version.get("final_quote_amount")).isEqualByComparingTo((BigDecimal) version.get("total_cost"));
      assertThat(jdbc.queryForObject("SELECT unit_price FROM lp_material_monthly_price WHERE material_code=?", BigDecimal.class, sample.get("material"))).isEqualByComparingTo(sample.get("base").toString());
      checked.add(Map.of("scenario", sample.get("scenario"), "itemId", sample.get("itemId"), "version", version));
    }
    Files.writeString(Path.of(state + ".db-verified"), json.writeValueAsString(Map.of(
        "passed", true, "unmentionedMaterialUnchanged", true, "originalPricesUnchanged", true, "cases", checked)));
  }

  private SupplierSupplyRatioExcelRow row(String material, String supplier, String value) {
    var row = new SupplierSupplyRatioExcelRow();
    row.setMaterialCode(material); row.setMaterialName(material); row.setUnit("件");
    row.setSupplierCode(supplier); row.setSupplierName(supplier); row.setSupplyRatio(new BigDecimal(value));
    return row;
  }
}
