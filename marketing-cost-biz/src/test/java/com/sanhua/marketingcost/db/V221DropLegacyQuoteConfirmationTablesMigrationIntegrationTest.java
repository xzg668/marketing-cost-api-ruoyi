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
import org.testcontainers.containers.Container;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("V221 真实MySQL删除旧整单确认模型迁移")
class V221DropLegacyQuoteConfirmationTablesMigrationIntegrationTest {

  private static final String DATABASE = "t221_drop_quote_confirm";
  private static final String MIGRATION_PATH = "/tmp/V221.sql";

  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(
              DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql"))
          .withDatabaseName(DATABASE)
          .withUsername("root")
          .withPassword("root123")
          .withCommand(
              "--sql-mode=NO_ENGINE_SUBSTITUTION",
              "--default-storage-engine=InnoDB",
              "--character-set-server=utf8mb4",
              "--collation-server=utf8mb4_0900_ai_ci");

  @BeforeAll
  static void setUpDatabase() throws Exception {
    MYSQL.start();
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V221__drop_legacy_quote_confirmation_tables.sql"),
        MIGRATION_PATH);
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE lp_quote_bom_confirmation (id BIGINT PRIMARY KEY, confirm_no VARCHAR(64))");
      statement.execute("CREATE TABLE lp_quote_bom_confirmation_log (id BIGINT PRIMARY KEY)");
      statement.execute(
          "CREATE TABLE lp_quote_price_type_confirm_batch "
              + "(id BIGINT PRIMARY KEY, bom_confirm_no VARCHAR(64))");
      statement.execute("CREATE TABLE lp_quote_price_type_confirm_item (id BIGINT PRIMARY KEY)");
      statement.execute(
          "CREATE TABLE lp_cost_run_result "
              + "(id BIGINT PRIMARY KEY, price_type_confirm_no VARCHAR(64), total_cost DECIMAL(18,6))");
      statement.execute(
          "CREATE TABLE lp_price_prepare_batch "
              + "(id BIGINT PRIMARY KEY, price_type_confirm_no VARCHAR(64), status VARCHAR(32))");
      statement.execute(
          "CREATE TABLE lp_price_prepare_gap "
              + "(id BIGINT PRIMARY KEY, price_type_confirm_no VARCHAR(64), "
              + "price_type_confirm_item_id BIGINT, gap_type VARCHAR(32), "
              + "KEY idx_pp_gap_confirm (price_type_confirm_no))");
      statement.execute(
          "CREATE TABLE lp_price_prepare_item "
              + "(id BIGINT PRIMARY KEY, price_type_confirm_no VARCHAR(64), "
              + "price_type_confirm_item_id BIGINT, unit_price DECIMAL(18,6), "
              + "KEY idx_pp_item_confirm (price_type_confirm_no))");
      statement.execute(
          "CREATE TABLE lp_quote_cost_run_version "
              + "(id BIGINT PRIMARY KEY, bom_confirm_no VARCHAR(64), "
              + "price_type_confirm_no VARCHAR(64), total_cost DECIMAL(18,6), "
              + "KEY idx_quote_cost_bom_confirm (bom_confirm_no), "
              + "KEY idx_quote_cost_type_confirm (price_type_confirm_no))");
      statement.executeUpdate(
          "INSERT INTO lp_cost_run_result VALUES (1,'PTC-1',123.450000)");
      statement.executeUpdate(
          "INSERT INTO lp_quote_cost_run_version VALUES (1,'BOM-1','PTC-1',456.780000)");
    }
  }

  @AfterAll
  static void tearDownDatabase() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("迁移可重复执行并保留现行成本数据")
  void migrationIsIdempotentAndKeepsCurrentCostData() throws Exception {
    applyMigration();
    applyMigration();

    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      assertThat(scalarInt(
              statement,
              "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"
                  + DATABASE
                  + "' AND table_name IN ('lp_quote_bom_confirmation',"
                  + "'lp_quote_bom_confirmation_log','lp_quote_price_type_confirm_batch',"
                  + "'lp_quote_price_type_confirm_item')"))
          .isZero();
      assertThat(scalarInt(
              statement,
              "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='"
                  + DATABASE
                  + "' AND column_name IN ('bom_confirm_no','price_type_confirm_no',"
                  + "'price_type_confirm_item_id')"))
          .isZero();
      assertThat(singleString(
              statement,
              "SELECT CAST(total_cost AS CHAR) FROM lp_cost_run_result WHERE id=1"))
          .isEqualTo("123.450000");
      assertThat(singleString(
              statement,
              "SELECT CAST(total_cost AS CHAR) FROM lp_quote_cost_run_version WHERE id=1"))
          .isEqualTo("456.780000");
    }
  }

  private static void applyMigration() throws Exception {
    Container.ExecResult result =
        MYSQL.execInContainer(
            "sh",
            "-lc",
            "MYSQL_PWD='"
                + MYSQL.getPassword()
                + "' mysql -uroot "
                + DATABASE
                + " < "
                + MIGRATION_PATH);
    assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
  }

  private static Connection connection() throws Exception {
    String url =
        "jdbc:mysql://"
            + MYSQL.getHost()
            + ":"
            + MYSQL.getMappedPort(3306)
            + "/"
            + DATABASE
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
  }

  private static int scalarInt(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private static String singleString(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }
}
