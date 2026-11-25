package com.sanhua.marketingcost.service.pricing.inquiry;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The actual old and new trigger run against the minimal schema of the failed update. */
@Tag("integration")
class InquiryPreparationHistoryScopeIntegrationTest {
  static final MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse(
      System.getProperty("mpp.mysql.image", "mysql:8.0.36")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("inquiry_history_test").withUsername("root").withPassword("fixture-only");
  static JdbcTemplate jdbc;

  @BeforeAll static void start() {
    mysql.start();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()));
    jdbc.execute("CREATE TABLE lp_material_monthly_price(id BIGINT PRIMARY KEY,business_unit_type VARCHAR(20),material_code VARCHAR(64),pricing_type VARCHAR(20),pricing_month VARCHAR(7),organization_mask INT,first_use_json JSON,specification VARCHAR(255))");
    jdbc.execute("CREATE TABLE lp_bom_costing_row(id BIGINT PRIMARY KEY,material_code VARCHAR(64),material_spec VARCHAR(255),business_unit_type VARCHAR(20),oa_form_item_id BIGINT,top_product_code VARCHAR(64),period_month VARCHAR(7))");
    jdbc.execute("CREATE TABLE lp_price_prepare_item(id BIGINT PRIMARY KEY,prepare_no VARCHAR(64),oa_no VARCHAR(64),oa_form_item_id BIGINT,top_product_code VARCHAR(64),bom_row_id BIGINT,material_code VARCHAR(64),business_unit_type VARCHAR(20),period_month VARCHAR(7),price_org_code VARCHAR(10),monthly_price_id BIGINT,monthly_pricing_type VARCHAR(20),current_flag INT,updated_at DATETIME)");
  }
  @AfterAll static void stop() { mysql.stop(); }

  static void apply(String sql) {
    for (String statement : sql.replace("DELIMITER $$", "").replace("DELIMITER ;", "").split("\\$\\$"))
      if (!statement.isBlank()) jdbc.execute(statement.trim());
  }
  static String read(String file) throws Exception {
    return new ClassPathResource("db/"+file).getContentAsString(StandardCharsets.UTF_8);
  }
  @Test void oldTriggerFailsOnRetirementAndNewTriggerKeepsEveryLinkScopeGuard() throws Exception {
    String old = read("V369__bom_specification_price_matching.sql");
    apply(old.substring(old.indexOf("DROP TRIGGER IF EXISTS trg_prepare_monthly_scope_update$$"),
        old.indexOf("DROP TRIGGER IF EXISTS trg_cost_part_monthly_scope_insert$$")));
    jdbc.update("INSERT INTO lp_material_monthly_price VALUES(1,'COMMERCIAL','','NORMAL','2026-11',1,?, 'EXACT-MODEL')",
        "{\"document\":\"{\\\"specificationOnly\\\":true}\"}");
    jdbc.update("INSERT INTO lp_price_prepare_item VALUES(1,'PREP','OA',10,'TOP',5,'','COMMERCIAL','2026-11','210',1,'NORMAL',1,NOW())");
    assertThatThrownBy(() -> jdbc.update("UPDATE lp_price_prepare_item SET current_flag=0 WHERE id=1"))
        .hasStackTraceContaining("MPP_PRICE_LINK_SCOPE");
    apply(read("V395__price_prepare_history_scope.sql"));
    assertThat(jdbc.update("UPDATE lp_price_prepare_item SET current_flag=0,updated_at=NOW() WHERE id=1")).isEqualTo(1);
    assertThat(jdbc.update("UPDATE lp_price_prepare_item SET current_flag=0 WHERE id=1")).isEqualTo(1);
    for (String change : new String[] {"current_flag=1", "period_month='2026-10'", "price_org_code='220'",
        "business_unit_type='HOUSEHOLD'", "material_code='WRONG'", "bom_row_id=6", "oa_form_item_id=11", "top_product_code='OTHER'"}) {
      assertThatThrownBy(() -> jdbc.update("UPDATE lp_price_prepare_item SET "+change+" WHERE id=1"))
          .as(change).hasStackTraceContaining("MPP_PRICE_LINK_SCOPE");
    }
    assertThatThrownBy(() -> jdbc.update("UPDATE lp_price_prepare_item SET monthly_price_id=2 WHERE id=1"))
        .hasStackTraceContaining("MPP_PRICE_LINK_IMMUTABLE");
    jdbc.update("INSERT INTO lp_bom_costing_row VALUES(5,'','EXACT-MODEL','COMMERCIAL',10,'TOP','2026-11')");
    assertThat(jdbc.update("UPDATE lp_price_prepare_item SET current_flag=1 WHERE id=1")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT monthly_price_id FROM lp_price_prepare_item WHERE id=1",Long.class)).isEqualTo(1L);
  }
}
