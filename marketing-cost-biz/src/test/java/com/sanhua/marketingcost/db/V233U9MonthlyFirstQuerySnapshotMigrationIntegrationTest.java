package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
class V233U9MonthlyFirstQuerySnapshotMigrationIntegrationTest {
  private static final String DATABASE = "t233_u9_monthly";
  private static final String MIGRATION = "/tmp/V233.sql";
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
      DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql"))
      .withDatabaseName(DATABASE)
      .withUsername("root")
      .withPassword("root123")
      .withCommand("--sql-mode=NO_ENGINE_SUBSTITUTION", "--default-storage-engine=InnoDB",
          "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V233__u9_monthly_first_query_snapshot.sql"),
        MIGRATION);
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE lp_quote_bom_monthly_snapshot (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            product_code VARCHAR(64) NOT NULL,
            price_org_code VARCHAR(32) DEFAULT NULL,
            customer_code VARCHAR(128) NOT NULL DEFAULT '',
            package_method VARCHAR(128) NOT NULL DEFAULT '',
            cost_period_month VARCHAR(7) NOT NULL,
            bom_source VARCHAR(32) DEFAULT NULL,
            bom_purpose VARCHAR(64) DEFAULT NULL,
            bom_version VARCHAR(128) DEFAULT NULL,
            sync_type VARCHAR(32) NOT NULL,
            sync_status VARCHAR(32) NOT NULL,
            sync_at DATETIME DEFAULT NULL,
            sync_by VARCHAR(64) DEFAULT NULL,
            source_oa_no VARCHAR(64) DEFAULT NULL,
            source_oa_form_item_id BIGINT DEFAULT NULL,
            bom_batch_id VARCHAR(128) DEFAULT NULL,
            active_flag TINYINT NOT NULL DEFAULT 0,
            error_message VARCHAR(1000) DEFAULT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
      statement.executeUpdate("""
          INSERT INTO lp_quote_bom_monthly_snapshot
            (product_code,price_org_code,customer_code,package_method,cost_period_month,
             sync_type,sync_status,active_flag)
          VALUES ('P-1','210','CUST-A','BOX','2026-08','AUTO','SUCCESS',1),
                 ('P-1','210','CUST-B','PALLET','2026-08','AUTO','SUCCESS',1)
          """);
    }
    applyMigration();
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  void preservesHistoricalRowsAndAllowsOnlyOneConcurrentMonthlyClaim() throws Exception {
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      assertThat(scalar(statement,
          "SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot WHERE snapshot_identity_key IS NULL"))
          .isEqualTo(2);
    }

    int workers = 12;
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Integer>> inserts = new ArrayList<>();
    for (int index = 0; index < workers; index++) {
      inserts.add(() -> {
        ready.countDown();
        start.await();
        try (Connection connection = connection(); PreparedStatement statement =
            connection.prepareStatement("""
                INSERT IGNORE INTO lp_quote_bom_monthly_snapshot
                  (product_code,price_org_code,business_unit_type,material_organization_code,
                   snapshot_identity_key,customer_code,package_method,cost_period_month,
                   bom_source,bom_purpose,sync_type,sync_status,active_flag,line_count)
                VALUES ('P-1','210','COMMERCIAL','COMMERCIAL',?,'','','2026-08',
                        'U9','主制造','AUTO','SYNCING',1,0)
                """)) {
          statement.setString(1, "A".repeat(64));
          return statement.executeUpdate();
        }
      });
    }
    List<Future<Integer>> futures = inserts.stream().map(executor::submit).toList();
    ready.await();
    start.countDown();
    int inserted = 0;
    for (Future<Integer> future : futures) inserted += future.get();
    executor.shutdownNow();

    assertThat(inserted).isEqualTo(1);
    try (Connection connection = connection(); Statement statement = connection.createStatement()) {
      assertThat(scalar(statement,
          "SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot WHERE snapshot_identity_key='"
              + "A".repeat(64) + "'"))
          .isEqualTo(1);
    }
  }

  private static void applyMigration() throws Exception {
    Container.ExecResult result = MYSQL.execInContainer(
        "sh", "-lc", "MYSQL_PWD='" + MYSQL.getPassword() + "' mysql -uroot "
            + DATABASE + " < " + MIGRATION);
    assertThat(result.getExitCode()).withFailMessage(result.getStderr()).isZero();
  }

  private static Connection connection() throws Exception {
    return DriverManager.getConnection(
        "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/"
            + DATABASE + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        MYSQL.getUsername(), MYSQL.getPassword());
  }

  private static int scalar(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }
}
