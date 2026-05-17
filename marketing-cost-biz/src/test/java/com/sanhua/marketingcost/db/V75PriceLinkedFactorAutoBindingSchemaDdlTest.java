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
@DisplayName("V75 联动价影响因素自动绑定 DDL")
class V75PriceLinkedFactorAutoBindingSchemaDdlTest {

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
      createExistingBindingTable(stmt);
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("V75 可执行两次并补齐影响因素自动绑定结构")
  void v75CanRunTwiceAndExtendBindingSchema() throws Exception {
    runV75("v75_first.sql");
    runV75("v75_second.sql");

    assertThat(tableExists("lp_factor_identity")).isTrue();
    assertThat(tableExists("lp_factor_monthly_price")).isTrue();
    assertThat(tableExists("lp_factor_upload_batch")).isTrue();
    assertThat(tableExists("lp_factor_row_ref")).isTrue();
    assertThat(tableExists("lp_material_factor_binding_std")).isTrue();
    assertThat(tableExists("lp_factor_monthly_price_change_log")).isTrue();
    assertThat(tableExists("lp_excel_auto_binding_import_log")).isTrue();

    assertThat(indexExists("lp_factor_identity", "uk_factor_identity")).isTrue();
    assertThat(indexExists("lp_factor_monthly_price", "uk_factor_monthly_price")).isTrue();
    assertThat(indexExists("lp_factor_row_ref", "uk_factor_row_ref")).isTrue();
    assertThat(indexExists("lp_material_factor_binding_std", "uk_material_factor_binding_std"))
        .isTrue();

    assertThat(columnExists("lp_price_variable_binding", "factor_identity_id")).isTrue();
    assertThat(columnExists("lp_price_variable_binding", "factor_monthly_price_id")).isTrue();
    assertThat(columnExists("lp_price_variable_binding", "factor_upload_batch_id")).isTrue();
    assertThat(columnExists("lp_price_variable_binding", "excel_source_sheet_name")).isTrue();
    assertThat(columnExists("lp_price_variable_binding", "excel_source_cell_ref")).isTrue();
    assertThat(columnExists("lp_price_variable_binding", "excel_formula")).isTrue();
    assertThat(indexExists("lp_price_variable_binding", "idx_binding_factor_identity")).isTrue();
    assertThat(indexExists("lp_price_variable_binding", "idx_binding_factor_batch")).isTrue();
  }

  @Test
  @DisplayName("V75 唯一键保证身份、月度价格、行号映射去重")
  void v75UniqueKeysDedupeFactorIdentityMonthlyPriceAndRowRef() throws Exception {
    runV75("v75_dedupe.sql");
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "INSERT IGNORE INTO lp_factor_identity "
              + "(business_unit_type, factor_seq_no, factor_name, short_name, price_source) "
              + "VALUES "
              + "('COMMERCIAL', '64', '上月宁波宝新SUS304出厂价', 'SUS304/2Bδ0.6-900', '出厂价'),"
              + "('COMMERCIAL', '64', '上月宁波宝新SUS304出厂价', 'SUS304/2Bδ0.6-900', '出厂价')");
      stmt.execute(
          "INSERT IGNORE INTO lp_factor_monthly_price "
              + "(factor_identity_id, price_month, price) VALUES (1, '2026-05', 16.400000),"
              + "(1, '2026-05', 16.400000)");
      stmt.execute(
          "INSERT INTO lp_factor_upload_batch "
              + "(batch_no, price_month, business_unit_type, status) "
              + "VALUES ('U001', '2026-05', 'COMMERCIAL', 'SUCCESS')");
      stmt.execute(
          "INSERT IGNORE INTO lp_factor_row_ref "
              + "(factor_upload_batch_id, source_sheet_name, source_row_number, "
              + "factor_identity_id, factor_monthly_price_id) "
              + "VALUES (1, '影响因素', 64, 1, 1), (1, '影响因素', 64, 1, 1)");
    }

    assertThat(count("SELECT COUNT(*) FROM lp_factor_identity")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM lp_factor_monthly_price")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM lp_factor_row_ref")).isEqualTo(1);
  }

  private static void createExistingBindingTable(Statement stmt) throws Exception {
    stmt.execute(
        "CREATE TABLE lp_price_variable_binding ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "linked_item_id BIGINT NOT NULL,"
            + "token_name VARCHAR(32) NOT NULL,"
            + "factor_code VARCHAR(64) NULL,"
            + "price_source VARCHAR(16) NULL,"
            + "bu_scoped TINYINT NOT NULL DEFAULT 1,"
            + "effective_date DATE NOT NULL,"
            + "expiry_date DATE NULL,"
            + "source VARCHAR(16) NOT NULL,"
            + "confirmed_by VARCHAR(32) NULL,"
            + "confirmed_at TIMESTAMP NULL,"
            + "remark VARCHAR(256) NULL,"
            + "created_by VARCHAR(32) NULL,"
            + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_by VARCHAR(32) NULL,"
            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "deleted TINYINT NOT NULL DEFAULT 0,"
            + "PRIMARY KEY (id),"
            + "UNIQUE KEY uk_item_token_effective (linked_item_id, token_name, effective_date, deleted)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
  }

  private static void runV75(String containerFileName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V75__price_linked_factor_auto_binding_schema.sql"),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/" + containerFileName);
    assertThat(result.getExitCode())
        .as("V75 执行失败 stderr=" + result.getStderr())
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

  private static int count(String sql) throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      return rs.getInt(1);
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }
}
