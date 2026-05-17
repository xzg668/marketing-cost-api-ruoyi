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
@DisplayName("V76 行级绑定标准关系字段 DDL")
class V76PriceVariableBindingStandardBindingDdlTest {

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
          "CREATE TABLE lp_price_variable_binding ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "linked_item_id BIGINT NOT NULL,"
              + "token_name VARCHAR(32) NOT NULL,"
              + "factor_code VARCHAR(64) NULL,"
              + "price_source VARCHAR(16) NULL,"
              + "factor_identity_id BIGINT NULL,"
              + "factor_monthly_price_id BIGINT NULL,"
              + "factor_upload_batch_id BIGINT NULL,"
              + "effective_date DATE NOT NULL,"
              + "deleted TINYINT NOT NULL DEFAULT 0,"
              + "PRIMARY KEY (id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("V76 可执行两次并补齐 standard_binding_id")
  void v76CanRunTwiceAndAddStandardBindingId() throws Exception {
    runV76("v76_first.sql");
    runV76("v76_second.sql");

    assertThat(columnExists("lp_price_variable_binding", "standard_binding_id")).isTrue();
    assertThat(indexExists("lp_price_variable_binding", "idx_binding_standard_binding")).isTrue();
  }

  private static void runV76(String containerFileName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(
            "/db/V76__price_variable_binding_standard_binding_id.sql"),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/" + containerFileName);
    assertThat(result.getExitCode())
        .as("V76 执行失败 stderr=" + result.getStderr())
        .isZero();
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
