package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("V218 真实MySQL迁移与并发约束")
class V218QuoteCostingWorkspaceMigrationIntegrationTest {

  private static final String EMPTY_DATABASE = "t1_workspace_empty";
  private static final String EXISTING_DATABASE = "t1_workspace_existing";
  private static final String V137_CONTAINER_PATH = "/tmp/V137.sql";
  private static final String V218_CONTAINER_PATH = "/tmp/V218.sql";

  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(
              DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql"))
          .withDatabaseName("marketing_cost")
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
        MountableFile.forClasspathResource("db/V137__cost_run_task_queue_schema.sql"),
        V137_CONTAINER_PATH);
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(
            "db/V218__quote_costing_workspace_and_execution_guard.sql"),
        V218_CONTAINER_PATH);
    executeRoot("CREATE DATABASE " + EMPTY_DATABASE + " CHARACTER SET utf8mb4");
    executeRoot("CREATE DATABASE " + EXISTING_DATABASE + " CHARACTER SET utf8mb4");
    applyMigration(EMPTY_DATABASE, V137_CONTAINER_PATH);
    applyMigration(EXISTING_DATABASE, V137_CONTAINER_PATH);
    seedExistingDatabase();
  }

  @AfterAll
  static void tearDownDatabase() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("空库历史链升级成功且迁移重复执行仍保持一套结构")
  void upgradesEmptyDatabaseIdempotently() throws Exception {
    applyMigration(EMPTY_DATABASE, V218_CONTAINER_PATH);
    applyMigration(EMPTY_DATABASE, V218_CONTAINER_PATH);

    try (Connection connection = connection(EMPTY_DATABASE);
        Statement statement = connection.createStatement()) {
      assertThat(scalarInt(
              statement,
              "SELECT COUNT(*) FROM information_schema.tables "
                  + "WHERE table_schema='" + EMPTY_DATABASE + "' "
                  + "AND table_name='lp_quote_costing_workspace'"))
          .isEqualTo(1);
      assertThat(scalarInt(
              statement,
              "SELECT COUNT(*) FROM information_schema.columns "
                  + "WHERE table_schema='" + EMPTY_DATABASE + "' "
                  + "AND table_name IN ('lp_cost_run_batch','lp_cost_run_task') "
                  + "AND column_name='execution_no'"))
          .isEqualTo(2);
    }
  }

  @Test
  @DisplayName("已有批次升级不改变历史成功指针并正确回填前置状态")
  void preservesHistoricalPointers() throws Exception {
    applyMigration(EXISTING_DATABASE, V218_CONTAINER_PATH);
    applyMigration(EXISTING_DATABASE, V218_CONTAINER_PATH);

    try (Connection connection = connection(EXISTING_DATABASE);
        Statement statement = connection.createStatement()) {
      assertThat(scalarLong(
              statement,
              "SELECT confirmed_cost_version_id FROM oa_form_item WHERE id=180"))
          .isEqualTo(27L);
      assertThat(strings(
              statement,
              "SELECT CONCAT(batch_no,':',execution_no,':',prerequisite_status) "
                  + "FROM lp_cost_run_batch ORDER BY batch_no"))
          .containsExactly("MONTHLY-OLD:1:NOT_REQUIRED", "QUOTE-OLD:1:SUCCESS");
      assertThat(scalarInt(
              statement,
              "SELECT COUNT(*) FROM lp_cost_run_task WHERE execution_no IS NULL"))
          .isZero();
    }
  }

  @Test
  @DisplayName("并发创建同产品月份只有一行且旧乐观锁只有一个更新者成功")
  void enforcesUniqueWorkspaceAndOptimisticUpdate() throws Exception {
    applyMigration(EXISTING_DATABASE, V218_CONTAINER_PATH);
    int workers = 16;
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    CountDownLatch ready = new CountDownLatch(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<Callable<Integer>> inserts = new ArrayList<>();
    for (int index = 0; index < workers; index++) {
      inserts.add(() -> {
        ready.countDown();
        start.await();
        try (Connection connection = connection(EXISTING_DATABASE);
            Statement statement = connection.createStatement()) {
          return statement.executeUpdate(
              "INSERT IGNORE INTO lp_quote_costing_workspace "
                  + "(oa_no,oa_form_item_id,product_code,period_month,business_unit_type) "
                  + "VALUES ('OA-CONCURRENT',180,'P-CONCURRENT','2026-08','COMMERCIAL')");
        }
      });
    }
    List<Future<Integer>> futures = new ArrayList<>();
    for (Callable<Integer> insert : inserts) {
      futures.add(executor.submit(insert));
    }
    ready.await();
    start.countDown();
    int inserted = 0;
    for (Future<Integer> future : futures) {
      inserted += future.get();
    }
    executor.shutdownNow();
    assertThat(inserted).isEqualTo(1);

    try (Connection connection = connection(EXISTING_DATABASE);
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO lp_quote_costing_workspace "
              + "(oa_no,oa_form_item_id,product_code,period_month,business_unit_type) "
              + "VALUES ('OA-CONCURRENT',180,'P-CONCURRENT','2026-09','COMMERCIAL')");
      assertThat(scalarInt(
              statement,
              "SELECT COUNT(*) FROM lp_quote_costing_workspace "
                  + "WHERE oa_form_item_id=180"))
          .isEqualTo(2);
    }

    ExecutorService updates = Executors.newFixedThreadPool(2);
    CountDownLatch updateStart = new CountDownLatch(1);
    Callable<Integer> readyUpdate = () -> updateWorkspace(updateStart, "READY");
    Callable<Integer> waitBomUpdate = () -> updateWorkspace(updateStart, "WAIT_BOM");
    Future<Integer> first = updates.submit(readyUpdate);
    Future<Integer> second = updates.submit(waitBomUpdate);
    updateStart.countDown();
    assertThat(first.get() + second.get()).isEqualTo(1);
    updates.shutdownNow();

    try (Connection connection = connection(EXISTING_DATABASE);
        Statement statement = connection.createStatement()) {
      assertThat(scalarInt(
              statement,
              "SELECT lock_version FROM lp_quote_costing_workspace "
                  + "WHERE oa_form_item_id=180 AND period_month='2026-08'"))
          .isEqualTo(1);
    }
  }

  private static int updateWorkspace(CountDownLatch start, String status) throws Exception {
    start.await();
    try (Connection connection = connection(EXISTING_DATABASE);
        Statement statement = connection.createStatement()) {
      return statement.executeUpdate(
          "UPDATE lp_quote_costing_workspace "
              + "SET workspace_status='" + status + "',lock_version=lock_version+1 "
              + "WHERE oa_form_item_id=180 AND period_month='2026-08' AND lock_version=0");
    }
  }

  private static void seedExistingDatabase() throws Exception {
    try (Connection connection = connection(EXISTING_DATABASE);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE oa_form_item "
              + "(id BIGINT PRIMARY KEY, confirmed_cost_version_id BIGINT NULL)");
      statement.execute("INSERT INTO oa_form_item VALUES (180,27)");
      statement.execute(
          "INSERT INTO lp_cost_run_batch "
              + "(batch_no,scene,source_no,pricing_month,business_unit_type,status,total_count) "
              + "VALUES "
              + "('QUOTE-OLD','QUOTE','OA-OLD','2026-08','COMMERCIAL','SUCCESS',1),"
              + "('MONTHLY-OLD','MONTHLY_REPRICE','MR-OLD','2026-08','COMMERCIAL','SUCCESS',1)");
      statement.execute(
          "INSERT INTO lp_cost_run_task "
              + "(batch_no,scene,source_no,calc_object_key,oa_no,product_code,"
              + "business_unit_type,pricing_month,status) VALUES "
              + "('QUOTE-OLD','QUOTE','OA-OLD','QUOTE:180','OA-OLD','P-OLD',"
              + "'COMMERCIAL','2026-08','SUCCESS'),"
              + "('MONTHLY-OLD','MONTHLY_REPRICE','MR-OLD','MR:180','OA-OLD','P-OLD',"
              + "'COMMERCIAL','2026-08','SUCCESS')");
    }
  }

  private static void applyMigration(String database, String path) throws Exception {
    Container.ExecResult result = MYSQL.execInContainer(
        "sh",
        "-lc",
        "MYSQL_PWD='" + MYSQL.getPassword() + "' mysql -uroot " + database + " < " + path);
    assertThat(result.getExitCode())
        .withFailMessage(result.getStderr())
        .isZero();
  }

  private static void executeRoot(String sql) throws Exception {
    try (Connection connection = connection("marketing_cost");
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static Connection connection(String database) throws Exception {
    String url =
        "jdbc:mysql://"
            + MYSQL.getHost()
            + ":"
            + MYSQL.getMappedPort(3306)
            + "/"
            + database
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
  }

  private static int scalarInt(Statement statement, String sql) throws Exception {
    return Math.toIntExact(scalarLong(statement, sql));
  }

  private static long scalarLong(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getLong(1);
    }
  }

  private static List<String> strings(Statement statement, String sql) throws Exception {
    List<String> values = new ArrayList<>();
    try (ResultSet result = statement.executeQuery(sql)) {
      while (result.next()) {
        values.add(result.getString(1));
      }
    }
    return values;
  }
}
