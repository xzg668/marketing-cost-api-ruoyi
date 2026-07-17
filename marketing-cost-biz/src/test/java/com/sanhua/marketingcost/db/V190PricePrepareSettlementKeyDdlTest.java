package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@DisplayName("V190 价格准备稳定结算键唯一性 DDL")
class V190PricePrepareSettlementKeyDdlTest {

  private static final DockerImageName MYSQL_IMAGE =
      DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql");

  @SuppressWarnings("resource")
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(MYSQL_IMAGE)
          .withDatabaseName("marketing_cost")
          .withUsername("root")
          .withPassword("root123")
          .withCommand(
              "--sql-mode=NO_ENGINE_SUBSTITUTION",
              "--default-storage-engine=InnoDB",
              "--character-set-server=utf8mb4",
              "--collation-server=utf8mb4_0900_ai_ci");

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();
    try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE lp_price_prepare_item ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "prepare_no VARCHAR(64) NOT NULL,"
              + "oa_form_item_id BIGINT DEFAULT NULL,"
              + "top_product_code VARCHAR(64) NOT NULL,"
              + "material_code VARCHAR(64) NOT NULL,"
              + "settlement_key VARCHAR(192) DEFAULT NULL,"
              + "PRIMARY KEY (id),"
              + "UNIQUE KEY uk_price_prepare_item_batch "
              + "(prepare_no, oa_form_item_id, top_product_code, material_code),"
              + "KEY idx_price_prepare_item_settlement (prepare_no, settlement_key)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("迁移可重复执行，不同BOM位置可共存、同稳定键防重且历史空键兼容")
  void migratesTwiceAndEnforcesSettlementIdentity() throws Exception {
    runV190("v190_first.sql");
    runV190("v190_second.sql");

    assertThat(indexExists("uk_price_prepare_item_batch")).isFalse();
    assertThat(indexExists("idx_price_prepare_item_settlement")).isFalse();
    assertThat(indexExists("uk_price_prepare_item_settlement")).isTrue();
    assertThat(indexIsUnique("uk_price_prepare_item_settlement")).isTrue();

    try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
      stmt.execute(
          "INSERT INTO lp_price_prepare_item "
              + "(prepare_no, oa_form_item_id, top_product_code, material_code, settlement_key) "
              + "VALUES ('PPR-NEW', 101, 'TOP', 'MAT-SAME', 'SET:v1:path-a')");
      stmt.execute(
          "INSERT INTO lp_price_prepare_item "
              + "(prepare_no, oa_form_item_id, top_product_code, material_code, settlement_key) "
              + "VALUES ('PPR-NEW', 101, 'TOP', 'MAT-SAME', 'SET:v1:path-b')");
      assertThatThrownBy(() -> stmt.execute(
          "INSERT INTO lp_price_prepare_item "
              + "(prepare_no, oa_form_item_id, top_product_code, material_code, settlement_key) "
              + "VALUES ('PPR-NEW', 101, 'TOP', 'MAT-OTHER', 'SET:v1:path-a')"))
          .hasMessageContaining("Duplicate entry");
      stmt.execute(
          "INSERT INTO lp_price_prepare_item "
              + "(prepare_no, oa_form_item_id, top_product_code, material_code, settlement_key) "
              + "VALUES ('PPR-HISTORY', 101, 'TOP', 'MAT-HISTORY-1', NULL)");
      stmt.execute(
          "INSERT INTO lp_price_prepare_item "
              + "(prepare_no, oa_form_item_id, top_product_code, material_code, settlement_key) "
              + "VALUES ('PPR-HISTORY', 101, 'TOP', 'MAT-HISTORY-2', NULL)");
    }
    assertThat(rowCount("PPR-NEW")).isEqualTo(2);
    assertThat(rowCount("PPR-HISTORY")).isEqualTo(2);
  }

  private static void runV190(String containerFileName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(
            "/db/V190__price_prepare_settlement_key_uniqueness.sql"),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh",
        "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p"
            + MYSQL.getPassword()
            + " "
            + MYSQL.getDatabaseName()
            + " < /tmp/"
            + containerFileName);
    assertThat(result.getExitCode())
        .as("V190 执行失败 stderr=" + result.getStderr())
        .isZero();
  }

  private static boolean indexExists(String indexName) throws Exception {
    try (Connection conn = openConnection();
        ResultSet rs = conn.getMetaData().getIndexInfo(
            null, null, "lp_price_prepare_item", false, false)) {
      while (rs.next()) {
        if (indexName.equals(rs.getString("INDEX_NAME"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static boolean indexIsUnique(String indexName) throws Exception {
    try (Connection conn = openConnection();
        ResultSet rs = conn.getMetaData().getIndexInfo(
            null, null, "lp_price_prepare_item", false, false)) {
      while (rs.next()) {
        if (indexName.equals(rs.getString("INDEX_NAME"))) {
          return !rs.getBoolean("NON_UNIQUE");
        }
      }
      return false;
    }
  }

  private static int rowCount(String prepareNo) throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(
            "SELECT COUNT(*) FROM lp_price_prepare_item WHERE prepare_no='" + prepareNo + "'")) {
      assertThat(rs.next()).isTrue();
      return rs.getInt(1);
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }
}
