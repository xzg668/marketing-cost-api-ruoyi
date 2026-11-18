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

/** Local disposable database: committed confirmation survives salary-blocked repricing. */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "app.cors.allowed-origins=http://127.0.0.1:15867",
    "integration.oa-person-directory.enabled=false", "integration.oa-person-directory.run-at-startup=false",
    "cms.sync-publish.enabled=false", "srm.fixed-price.enabled=false", "quote.drawing-verification.enabled=false"})
class SupplierPriceConfirmationBrowserSession extends OriginalPricingIntegrationTestBase {
  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;
  @Autowired BomRawHierarchyMapper hierarchy;

  @org.springframework.test.context.DynamicPropertySource
  static void period(org.springframework.test.context.DynamicPropertyRegistry registry) {
    registry.add("monthly.reprice.go-live-months.COMMERCIAL",
        com.sanhua.marketingcost.util.CostPricingPeriodUtils::currentPricingMonth);
  }

  @Test void browserSession() throws Exception {
    Path state = Path.of(System.getProperty("supplier.confirmation.browser.state"));
    assertThat(Files.exists(state)).isFalse();
    try (var connection = jdbc.getDataSource().getConnection()) {
      org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
          new org.springframework.core.io.ClassPathResource("db/V90__supplier_supply_ratio_menu.sql"));
    }
    String month = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date = YearMonth.parse(month).atDay(1).atTime(12, 0);
    String product = "SHR-" + UUID.randomUUID().toString().substring(0, 8), material = product + "-PART";
    String packaging = product + "-ZERO-CHILD";
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type) VALUES(?,'COMMERCIAL')", product);
    long form = jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?", Long.class, product);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,product_name,business_unit_type,business_type,shipping_fee) VALUES(?,?,?,'COMMERCIAL','批量品',0)", form, product, "最高价审核与供货比率导入验收");
    long item = jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?", Long.class, form);
    new OrdinaryCostingTestSources(jdbc, hierarchy).ordinarySources(product, material, form, date);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET child_type='STANDARD' WHERE top_product_code=? AND level>0", product);
    jdbc.update("UPDATE lp_bom_raw_hierarchy SET qty_per_parent=1,qty_per_top=1 WHERE top_product_code=? AND material_code IN (?,?)", product, product+"-ZERO-PACK", packaging);
    jdbc.update("UPDATE lp_material_master_raw SET main_category_code='12',bare_code=? WHERE material_code=?", product+"-BARE", product);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,organization_code,import_batch_id,active_flag,main_category_code,shape_attr,unit) VALUES(?,?,'COMMERCIAL',?,1,'11','制造件','件')", product+"-BARE", product+"-BARE", product);
    jdbc.update("UPDATE lp_quality_loss_rate SET bare_product_code=? WHERE bare_product_code=?", product+"-BARE", product);
    jdbc.update("INSERT IGNORE INTO lp_product_property_rule(business_unit_type,property_year,product_attr,uplift_rate) VALUES('COMMERCIAL',?,'标准品',0)", date.getYear());
    for (String code : List.of(material, packaging))
      for (String supplier : List.of("A", "B"))
        jdbc.update("INSERT INTO lp_price_fixed_item(org_code,supplier_code,supplier_name,material_code,material_name,unit,fixed_price,tax_included,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('210',?,?,?,?,'件',?,0,?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",
            supplier, "供应商"+supplier, code, code, new BigDecimal(supplier.equals("A") ? "10" : "30"), date.toLocalDate());
    var originalPrices = jdbc.queryForList("SELECT * FROM lp_price_fixed_item ORDER BY id");
    String actor = "highest_"+UUID.randomUUID().toString().substring(0, 8), password = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO sys_user(user_name,nick_name,password,business_unit_type,status,del_flag,create_by,create_time,update_by,update_time) VALUES(?,'最高价审核验收',?,'COMMERCIAL','0','0','SHR_TEST',NOW(),'SHR_TEST',NOW())", actor, new BCryptPasswordEncoder().encode(password));
    long user = jdbc.queryForObject("SELECT user_id FROM sys_user WHERE user_name=?", Long.class, actor);
    jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,1)", user);
    jdbc.update("DELETE FROM cms_cost_source_effective WHERE parent_code=? AND source_type IN ('SALARY_DIRECT','SALARY_INDIRECT')", product);
    jdbc.update("INSERT INTO cms_subject_setting_raw(import_batch_id,row_no,first_subject_code,first_subject_name,second_subject_code,second_subject_name,business_unit_type) VALUES(0,1,'03','工资','0302','辅助人员工资','COMMERCIAL')");
    jdbc.update("INSERT INTO cms_sync_publish_signal(batch_no,cost_year,business_unit_type,status) VALUES(?,?,'COMMERCIAL','SUCCESS')", product, date.getYear());
    var json = new ObjectMapper().findAndRegisterModules();
    Files.createFile(state, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    Files.writeString(state, json.writeValueAsString(Map.of("apiPort",port,"username",actor,"password",password,
        "month",month,"oaNo",product,"itemId",item,"material",material,"packaging",packaging)));
    Path release = Path.of(state+".release");
    long deadline = System.nanoTime()+Duration.ofMinutes(20).toNanos();
    while (!Files.exists(release) && System.nanoTime()<deadline) Thread.sleep(200);
    assertThat(Files.exists(release)).isTrue();
    assertThat(json.readTree(Files.readString(release)).path("passed").asBoolean()).isTrue();
    for (String code : List.of(material,packaging)) {
      var decisions = jdbc.queryForList("SELECT decision,retry_status,retry_message FROM lp_supplier_price_decision WHERE material_code=?", code);
      assertThat(decisions).hasSize(1);
      assertThat(decisions.getFirst().get("decision")).isEqualTo("FALLBACK_HIGH");
      assertThat(decisions.getFirst().get("retry_status")).isEqualTo("WAITING");
      assertThat(decisions.getFirst().get("retry_message").toString()).contains("最高价选择已保存", "工资");
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_cost_run_version WHERE oa_form_item_id=?",Integer.class,item)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cms_cost_source_effective WHERE parent_code=?",Integer.class,product)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_prepare_batch WHERE oa_form_item_id=?",Integer.class,item)).isZero();
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_fixed_item ORDER BY id")).isEqualTo(originalPrices);
    Files.writeString(Path.of(state+".db-verified"), json.writeValueAsString(Map.of("passed",true,
        "singleConfirmationPerMaterial",true,"salaryStillRequired",true,"noFormalCost",true,
        "priceGenerationRolledBack",true,"sourcePricesUnchanged",true)));
  }
}
