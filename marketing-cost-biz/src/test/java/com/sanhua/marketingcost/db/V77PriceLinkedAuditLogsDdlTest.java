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
@DisplayName("V77 联动价审计日志 DDL")
class V77PriceLinkedAuditLogsDdlTest {

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
      stmt.execute(
          "CREATE TABLE lp_excel_auto_binding_import_log ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "factor_upload_batch_id BIGINT NULL,"
              + "linked_item_id BIGINT NULL,"
              + "token_name VARCHAR(32) NULL,"
              + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
              + "PRIMARY KEY (id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("V77 可执行两次并创建公式、绑定、自动导入日志结构")
  void v77CanRunTwiceAndCreateAuditLogTables() throws Exception {
    runV77("v77_first.sql");
    runV77("v77_second.sql");

    assertThat(tableExists("lp_price_linked_formula_change_log")).isTrue();
    assertThat(tableExists("lp_price_variable_binding_change_log")).isTrue();
    assertThat(columnExists("lp_price_linked_formula_change_log", "old_formula_expr")).isTrue();
    assertThat(columnExists("lp_price_variable_binding_change_log", "old_factor_identity_id"))
        .isTrue();
    assertThat(indexExists("lp_excel_auto_binding_import_log", "idx_auto_binding_log_created"))
        .isTrue();
  }

  private static void runV77(String containerFileName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V77__price_linked_audit_logs.sql"),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/" + containerFileName);
    assertThat(result.getExitCode())
        .as("V77 执行失败 stderr=" + result.getStderr())
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

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }
}
