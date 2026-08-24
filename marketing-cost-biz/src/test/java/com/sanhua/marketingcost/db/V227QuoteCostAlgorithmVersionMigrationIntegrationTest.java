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
@DisplayName("V227 真实MySQL成本算法版本迁移")
class V227QuoteCostAlgorithmVersionMigrationIntegrationTest {

  private static final String DATABASE = "t227_cost_algorithm_version";
  private static final String MIGRATION_PATH = "/tmp/V227.sql";

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
        MountableFile.forClasspathResource("db/V227__quote_cost_algorithm_version.sql"),
        MIGRATION_PATH);
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE lp_quote_cost_run_version ("
              + "id BIGINT PRIMARY KEY, input_fingerprint CHAR(64) NULL)");
      statement.executeUpdate(
          "INSERT INTO lp_quote_cost_run_version (id,input_fingerprint) VALUES (1,'FP-1')");
    }
  }

  @AfterAll
  static void tearDownDatabase() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("迁移可重复执行且旧成功版本标记为 LEGACY")
  void migrationIsIdempotentAndMarksExistingRowsLegacy() throws Exception {
    applyMigration();
    applyMigration();

    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      assertThat(singleString(
              statement,
              "SELECT algorithm_version FROM lp_quote_cost_run_version WHERE id=1"))
          .isEqualTo("LEGACY");
      assertThat(singleInt(
              statement,
              "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='"
                  + DATABASE
                  + "' AND table_name='lp_quote_cost_run_version' "
                  + "AND column_name='algorithm_version' AND is_nullable='NO'"))
          .isEqualTo(1);
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

  private static int singleInt(Statement statement, String sql) throws Exception {
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
