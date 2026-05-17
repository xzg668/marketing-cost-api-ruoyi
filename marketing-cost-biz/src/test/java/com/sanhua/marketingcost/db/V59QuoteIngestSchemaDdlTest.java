package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("V59 报价单接入 DDL")
class V59QuoteIngestSchemaDdlTest {

  private static final DockerImageName MYSQL_IMAGE =
      DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql");

  @SuppressWarnings("resource")
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(MYSQL_IMAGE)
          .withDatabaseName("marketing_cost")
          .withUsername("root")
          .withPassword("root123")
          .withCommand("--sql-mode=NO_ENGINE_SUBSTITUTION", "--default-storage-engine=InnoDB",
              "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      createMinimalLegacyTables(stmt);
      seedGoldenOa(stmt);
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("V59 可在已有库执行两次，并保留历史 OA 样例")
  void v59CanRunTwiceOnExistingSchema() throws Exception {
    runV59("v59_first.sql");
    runV59("v59_second.sql");

    assertThat(columnExists("oa_form", "source_type")).isTrue();
    assertThat(columnExists("oa_form", "calc_at")).isTrue();
    assertThat(columnExists("oa_form", "classification_status")).isTrue();
    assertThat(columnExists("oa_form_item", "external_line_id")).isTrue();
    assertThat(columnExists("oa_form_item", "classification_status")).isTrue();

    assertThat(tableExists("lp_quote_ingest_log")).isTrue();
    assertThat(tableExists("lp_quote_ingest_type_rule")).isTrue();
    assertThat(tableExists("lp_oa_form_extra_field")).isTrue();
    assertThat(tableExists("lp_oa_form_extra_fee")).isTrue();
    assertThat(tableExists("lp_quote_bom_status")).isTrue();
    assertThat(tableExists("lp_quote_writeback_task")).isTrue();

    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT source_type, quote_scenario, classification_status "
                + "FROM oa_form WHERE oa_no = 'OA-GOLDEN-001'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("source_type")).isEqualTo("LEGACY");
      assertThat(rs.getString("quote_scenario")).isEqualTo("UNKNOWN");
      assertThat(rs.getString("classification_status")).isEqualTo("CONFIRMED");
    }

    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT COUNT(*) FROM lp_quote_ingest_type_rule "
                + "WHERE rule_code IN ('RULE_FI_SC_020', 'RULE_FI_SC_006', 'RULE_TECH_MANUAL')")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(3);
    }
  }

  private static void createMinimalLegacyTables(Statement stmt) throws Exception {
    stmt.execute(
        "CREATE TABLE oa_form ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
            + "oa_no VARCHAR(64) NOT NULL,"
            + "form_type VARCHAR(64),"
            + "apply_date DATE,"
            + "customer VARCHAR(255),"
            + "copper_price DECIMAL(12,2),"
            + "zinc_price DECIMAL(12,2),"
            + "aluminum_price DECIMAL(12,2),"
            + "steel_price DECIMAL(12,2),"
            + "other_material DECIMAL(12,2),"
            + "base_shipping DECIMAL(12,2),"
            + "sale_link VARCHAR(255),"
            + "remark VARCHAR(500),"
            + "created_at DATETIME,"
            + "updated_at DATETIME,"
            + "deleted TINYINT(1) DEFAULT 0,"
            + "calc_status VARCHAR(20) NOT NULL DEFAULT '未核算',"
            + "calc_at DATETIME DEFAULT NULL,"
            + "UNIQUE KEY uk_oa_form_oa_no (oa_no)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

    stmt.execute(
        "CREATE TABLE oa_form_item ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
            + "oa_form_id BIGINT NOT NULL,"
            + "seq INT,"
            + "product_name VARCHAR(255),"
            + "customer_drawing VARCHAR(255),"
            + "material_no VARCHAR(255),"
            + "sunl_model VARCHAR(255),"
            + "spec VARCHAR(255),"
            + "shipping_fee DECIMAL(12,4),"
            + "support_qty DECIMAL(12,4),"
            + "total_with_ship DECIMAL(12,4),"
            + "total_no_ship DECIMAL(12,4),"
            + "material_cost DECIMAL(12,4),"
            + "labor_cost DECIMAL(12,4),"
            + "manufacturing_cost DECIMAL(12,4),"
            + "management_cost DECIMAL(12,4),"
            + "valid_date DATE,"
            + "created_at DATETIME,"
            + "updated_at DATETIME,"
            + "deleted TINYINT(1) DEFAULT 0,"
            + "KEY idx_oa_form_id (oa_form_id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
  }

  private static void seedGoldenOa(Statement stmt) throws Exception {
    stmt.execute(
        "INSERT INTO oa_form (oa_no, form_type, apply_date, customer, calc_status, created_at, updated_at) "
            + "VALUES ('OA-GOLDEN-001', '商用-SHF-AA-79', '2026-04-20', "
            + "'GOLDEN-财务样本', '未核算', NOW(), NOW())");
    stmt.execute(
        "INSERT INTO oa_form_item (oa_form_id, seq, product_name, material_no, sunl_model, support_qty, created_at, updated_at) "
            + "VALUES ((SELECT id FROM oa_form WHERE oa_no='OA-GOLDEN-001'), 1, "
            + "'四通换向阀', '1079900000536', 'SHF-AA-79', 1, NOW(), NOW())");
  }

  private static void runV59(String containerFileName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V59__quote_ingest_schema.sql"),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/" + containerFileName);
    assertThat(result.getExitCode())
        .as("V59 执行失败 stderr=" + result.getStderr())
        .isZero();
  }

  private static boolean tableExists(String tableName) throws Exception {
    try (Connection conn = openConnection();
        ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null)) {
      return rs.next();
    }
  }

  private static boolean columnExists(String tableName, String columnName) throws Exception {
    try (Connection conn = openConnection();
        ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, columnName)) {
      return rs.next();
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }
}
