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
@DisplayName("V187 财务 Cu 与 OA 锁价双场景 DDL")
class V187FinanceCuQuoteScenarioDdlTest {

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
      createPreV187Schema(stmt);
      stmt.execute(
          "INSERT INTO lp_price_prepare_batch "
              + "(source_type, oa_no, oa_form_item_id, top_product_code, period_month) "
              + "VALUES ('U9', 'OA-HISTORY', 1, 'TOP-HISTORY', '2026-06')");
      stmt.execute(
          "INSERT INTO lp_price_prepare_item (prepare_no, current_flag) "
              + "VALUES ('PPR-HISTORY', 1)");
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("旧库可连续升级两次，历史批次回填 OA 场景且新结构可用")
  void migratesExistingSchemaTwiceAndBackfillsHistoricalRows() throws Exception {
    runV187("v187_first.sql");
    runV187("v187_second.sql");

    assertThat(columnExists("lp_price_prepare_batch", "scenario_type")).isTrue();
    assertThat(columnExists("lp_price_prepare_batch", "scenario_group_no")).isTrue();
    assertThat(columnExists("lp_price_prepare_batch", "source_prepare_no")).isTrue();
    assertThat(columnExists("lp_price_prepare_item", "settlement_key")).isTrue();
    assertThat(indexExists("lp_price_prepare_batch", "idx_price_prepare_scenario")).isTrue();
    assertThat(indexExists("lp_price_prepare_item", "idx_price_prepare_item_settlement")).isTrue();

    assertThat(tableExists("lp_quote_cost_price_scenario")).isTrue();
    assertThat(tableExists("lp_quote_cu_material_diff_item")).isTrue();
    assertThat(indexExists("lp_quote_cost_price_scenario", "uk_quote_cost_version_scenario"))
        .isTrue();
    assertThat(indexExists("lp_quote_cu_material_diff_item", "uk_quote_cu_diff_line")).isTrue();

    assertThat(columnExists("lp_quote_cost_run_version", "finance_cu_price")).isTrue();
    assertThat(columnExists("lp_quote_cost_run_version", "final_quote_amount")).isTrue();
    assertThat(columnExists("lp_cost_run_result", "cu_material_adjustment")).isTrue();
    assertThat(columnExists("lp_cost_run_result", "final_quote_amount")).isTrue();
    assertThat(decimalScale("lp_quote_cost_run_version", "finance_cu_price")).isEqualTo(8);
    assertThat(decimalScale("lp_cost_run_result", "final_quote_amount")).isEqualTo(8);

    assertThat(singleString(
        "SELECT scenario_type FROM lp_price_prepare_batch WHERE oa_no='OA-HISTORY'"))
        .isEqualTo("OA_LOCKED");
    assertThat(singleString(
        "SELECT settlement_key FROM lp_price_prepare_item WHERE prepare_no='PPR-HISTORY'"))
        .isNull();

    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "INSERT INTO lp_price_prepare_batch "
              + "(source_type, oa_no, oa_form_item_id, top_product_code, period_month) "
              + "VALUES ('U9', 'OA-NEW', 2, 'TOP-NEW', '2026-07')");
    }
    assertThat(singleString(
        "SELECT scenario_type FROM lp_price_prepare_batch WHERE oa_no='OA-NEW'"))
        .isEqualTo("OA_LOCKED");
  }

  private static void createPreV187Schema(Statement stmt) throws Exception {
    stmt.execute(
        "CREATE TABLE lp_price_prepare_batch ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "source_type VARCHAR(32) DEFAULT NULL,"
            + "oa_no VARCHAR(64) DEFAULT NULL,"
            + "oa_form_item_id BIGINT DEFAULT NULL,"
            + "top_product_code VARCHAR(64) DEFAULT NULL,"
            + "period_month VARCHAR(7) DEFAULT NULL,"
            + "PRIMARY KEY (id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    stmt.execute(
        "CREATE TABLE lp_price_prepare_item ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "prepare_no VARCHAR(64) NOT NULL,"
            + "current_flag TINYINT NOT NULL DEFAULT 1,"
            + "PRIMARY KEY (id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    stmt.execute(
        "CREATE TABLE lp_quote_cost_run_version ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "price_prepare_no VARCHAR(64) DEFAULT NULL,"
            + "PRIMARY KEY (id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    stmt.execute(
        "CREATE TABLE lp_cost_run_result ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "total_cost DECIMAL(20,8) DEFAULT NULL,"
            + "PRIMARY KEY (id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
  }

  private static void runV187(String containerFileName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V187__finance_cu_quote_scenario_schema.sql"),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/" + containerFileName);
    assertThat(result.getExitCode())
        .as("V187 执行失败 stderr=" + result.getStderr())
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

  private static boolean indexExists(String tableName, String indexName) throws Exception {
    try (Connection conn = openConnection();
        ResultSet rs = conn.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
      while (rs.next()) {
        if (indexName.equals(rs.getString("INDEX_NAME"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static int decimalScale(String tableName, String columnName) throws Exception {
    try (Connection conn = openConnection();
        ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, columnName)) {
      assertThat(rs.next()).isTrue();
      return rs.getInt("DECIMAL_DIGITS");
    }
  }

  private static String singleString(String sql) throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      return rs.getString(1);
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }
}
