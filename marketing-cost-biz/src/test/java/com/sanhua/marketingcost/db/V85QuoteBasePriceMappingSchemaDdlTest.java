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
@DisplayName("V85 报价单基价映射 DDL 集成验证")
class V85QuoteBasePriceMappingSchemaDdlTest {

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
  static void setUp() {
    MYSQL.start();
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("V85 可执行两次并创建规则表和识别结果表")
  void v85CanRunTwiceAndCreateMappingTables() throws Exception {
    runV85("v85_first.sql");
    runV85("v85_second.sql");

    assertThat(tableExists("lp_quote_base_price_mapping_rule")).isTrue();
    assertThat(tableExists("lp_factor_quote_base_mapping")).isTrue();

    assertThat(indexExists("lp_quote_base_price_mapping_rule", "uk_quote_base_mapping_rule"))
        .isTrue();
    assertThat(indexExists("lp_factor_quote_base_mapping", "uk_factor_quote_base_mapping"))
        .isTrue();

    assertThat(columnExists("lp_quote_base_price_mapping_rule", "match_keywords_json")).isTrue();
    assertThat(columnExists("lp_factor_quote_base_mapping", "matched_keyword")).isTrue();
  }

  @Test
  @DisplayName("唯一键避免重复规则和重复影响因素映射")
  void v85UniqueKeysDedupeRulesAndMappings() throws Exception {
    runV85("v85_dedupe.sql");
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "INSERT IGNORE INTO lp_quote_base_price_mapping_rule "
              + "(business_unit_type, quote_field_code, quote_field_name, variable_code, "
              + "match_keywords_json) VALUES "
              + "('', 'copper_price', '铜基价', 'Cu', '[\"铜\"]'),"
              + "('', 'copper_price', '铜基价', 'Cu', '[\"铜\"]')");
      stmt.execute(
          "INSERT IGNORE INTO lp_factor_quote_base_mapping "
              + "(factor_identity_id, rule_id, quote_field_code, quote_field_name, variable_code) "
              + "VALUES "
              + "(101, 1, 'copper_price', '铜基价', 'Cu'),"
              + "(101, 1, 'copper_price', '铜基价', 'Cu')");
    }

    assertThat(count("SELECT COUNT(*) FROM lp_quote_base_price_mapping_rule")).isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM lp_factor_quote_base_mapping")).isEqualTo(1);
  }

  private static void runV85(String containerFileName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V85__quote_base_price_mapping_schema.sql"),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/" + containerFileName);
    assertThat(result.getExitCode())
        .as("V85 执行失败 stderr=" + result.getStderr())
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
