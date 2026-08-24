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
@DisplayName("V219 真实MySQL取价证据迁移")
class V219PriceResolutionEvidenceMigrationIntegrationTest {

  private static final String DATABASE = "t4_price_evidence";
  private static final String MIGRATION_PATH = "/tmp/V219.sql";

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
        MountableFile.forClasspathResource("db/V219__price_resolution_evidence.sql"),
        MIGRATION_PATH);
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE lp_price_linked_calc_item "
              + "(id BIGINT PRIMARY KEY, price_as_of_time DATETIME NULL)");
      statement.execute(
          "CREATE TABLE lp_price_prepare_item "
              + "(id BIGINT PRIMARY KEY, price_source VARCHAR(64) NULL, result_ref_id BIGINT NULL)");
      statement.execute(
          "CREATE TABLE lp_price_prepare_gap "
              + "(id BIGINT PRIMARY KEY, gap_type VARCHAR(64) NULL)");
    }
  }

  @AfterAll
  static void tearDownDatabase() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("迁移可重复执行并能保存最小取价证据和结构化缺口原因")
  void migrationIsIdempotentAndEvidenceIsWritable() throws Exception {
    applyMigration();
    applyMigration();

    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      assertThat(scalarInt(
              statement,
              "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='"
                  + DATABASE
                  + "' AND ((table_name='lp_price_linked_calc_item' AND column_name IN "
                  + "('source_price_record_id','source_price_batch_no','supplier_name',"
                  + "'supplier_code','supply_ratio','supply_ratio_record_id',"
                  + "'source_effective_from','source_effective_to','carried_forward',"
                  + "'warning_message','failure_code')) OR "
                  + "(table_name='lp_price_prepare_item' AND column_name IN "
                  + "('price_type','source_price_record_id','source_price_batch_no',"
                  + "'supplier_name','supplier_code','supply_ratio','supply_ratio_record_id',"
                  + "'source_effective_from','source_effective_to','carried_forward',"
                  + "'warning_message')) OR "
                  + "(table_name='lp_price_prepare_gap' AND column_name='reason_code'))"))
          .isEqualTo(23);

      statement.executeUpdate(
          "INSERT INTO lp_price_prepare_item "
              + "(id,price_type,source_price_record_id,supplier_code,supply_ratio,"
              + "source_effective_from,source_effective_to,carried_forward,warning_message) "
              + "VALUES (1,'FIXED',801,'SUP-001',0.700000,'2026-05-31','2026-07-31',1,"
              + "'审批有效期已到，报价沿用最近价格')");
      statement.executeUpdate(
          "INSERT INTO lp_price_prepare_gap (id,gap_type,reason_code) "
              + "VALUES (1,'MISSING_PRICE','PRIMARY_SUPPLIER_PRICE_MISSING')");
      assertThat(scalarInt(
              statement,
              "SELECT carried_forward FROM lp_price_prepare_item "
                  + "WHERE id=1 AND source_price_record_id=801 AND supplier_code='SUP-001'"))
          .isEqualTo(1);
      assertThat(singleString(
              statement,
              "SELECT reason_code FROM lp_price_prepare_gap WHERE id=1"))
          .isEqualTo("PRIMARY_SUPPLIER_PRICE_MISSING");
    }
  }

  private static void applyMigration() throws Exception {
    Container.ExecResult result = MYSQL.execInContainer(
        "sh",
        "-lc",
        "MYSQL_PWD='" + MYSQL.getPassword() + "' mysql -uroot " + DATABASE + " < "
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
