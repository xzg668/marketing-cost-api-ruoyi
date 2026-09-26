package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("V246 技术资料工作台真实 MySQL 迁移")
class V246QuoteTechnicalDataMigrationIntegrationTest {
  private static final String FRESH_SCHEMA = "tech_v244_fresh";
  private static final String UPGRADE_SCHEMA = "tech_v244_upgrade";
  private static final String MIGRATION = "/db/V246__quote_technical_data_workspace.sql";
  private static final List<String> EXPECTED_TABLES = List.of(
      "lp_quote_tech_aux_item",
      "lp_quote_tech_data_version",
      "lp_quote_tech_module",
      "lp_quote_tech_package_item",
      "lp_quote_tech_product",
      "lp_quote_tech_review_item",
      "lp_quote_tech_salary_item",
      "lp_quote_tech_task");
  private static final DockerImageName MYSQL_IMAGE =
      DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql");

  @SuppressWarnings("resource")
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
      .withDatabaseName("marketing_cost")
      .withUsername("root")
      .withPassword("root123")
      .withCommand(
          "--sql-mode=NO_ENGINE_SUBSTITUTION",
          "--default-storage-engine=InnoDB",
          "--character-set-server=utf8mb4",
          "--collation-server=utf8mb4_0900_ai_ci",
          "--default-time-zone=+08:00");

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();
    try (Connection connection = openConnection(MYSQL.getDatabaseName());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + FRESH_SCHEMA
          + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      statement.execute("CREATE DATABASE " + UPGRADE_SCHEMA
          + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    }
    execute(UPGRADE_SCHEMA, """
        CREATE TABLE lp_quote_collaboration_task (
          id BIGINT NOT NULL PRIMARY KEY,
          collaboration_no VARCHAR(64) NOT NULL,
          task_status VARCHAR(32) NOT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
    execute(UPGRADE_SCHEMA, """
        INSERT INTO lp_quote_collaboration_task(id,collaboration_no,task_status)
        VALUES (990001,'LEGACY-KEEP','WAIT_TECH')
        """);
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("空库连续执行两次后精确存在8张新表")
  void migratesFreshDatabaseIdempotently() throws Exception {
    runMigration(FRESH_SCHEMA, "fresh-first.sql");
    runMigration(FRESH_SCHEMA, "fresh-second.sql");

    assertThat(technicalTables(FRESH_SCHEMA)).containsExactlyElementsOf(EXPECTED_TABLES);
    assertColumn(FRESH_SCHEMA, "lp_quote_tech_aux_item", "quantity", "decimal(20,8)", false);
    assertColumn(FRESH_SCHEMA, "lp_quote_tech_salary_item", "working_hours", "decimal(20,8)", false);
    assertColumn(FRESH_SCHEMA, "lp_quote_tech_data_version", "package_total_amount", "decimal(20,8)", false);
    assertIndex(FRESH_SCHEMA, "lp_quote_tech_product",
        "uk_quote_tech_product_active_lock", true, "active_lock_key");
    assertIndex(FRESH_SCHEMA, "lp_quote_tech_review_item",
        "uk_quote_tech_review_round_module", true,
        "task_id", "review_round", "product_id", "module_type");
  }

  @Test
  @DisplayName("历史结构升级保留旧表和旧数据")
  void upgradesWithoutChangingLegacyData() throws Exception {
    String before = singleString(UPGRADE_SCHEMA,
        "SELECT CONCAT_WS('|',id,collaboration_no,task_status) "
            + "FROM lp_quote_collaboration_task WHERE id=990001");
    runMigration(UPGRADE_SCHEMA, "upgrade-first.sql");
    runMigration(UPGRADE_SCHEMA, "upgrade-second.sql");

    assertThat(singleString(UPGRADE_SCHEMA,
        "SELECT CONCAT_WS('|',id,collaboration_no,task_status) "
            + "FROM lp_quote_collaboration_task WHERE id=990001"))
        .isEqualTo(before);
    assertThat(technicalTables(UPGRADE_SCHEMA)).containsExactlyElementsOf(EXPECTED_TABLES);
    assertThat(singleInt(UPGRADE_SCHEMA,
        "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='"
            + UPGRADE_SCHEMA + "'"))
        .isEqualTo(9);
  }

  @Test
  @DisplayName("活动产品、版本、模块和审核轮次唯一键生效")
  void enforcesBusinessUniqueness() throws Exception {
    runMigration(FRESH_SCHEMA, "constraints.sql");
    long taskId = insertTask(FRESH_SCHEMA, "TASK-UQ", 88001, 701);
    long productId = insertProduct(FRESH_SCHEMA, taskId, 88101, "ITEM:88101:MONTH:2026-08");
    long versionId = insertVersion(FRESH_SCHEMA, productId, 1, "DRAFT");
    insertModule(FRESH_SCHEMA, productId, "PROFILE");
    insertReview(FRESH_SCHEMA, taskId, productId, versionId, 1, "PROFILE");

    assertThatThrownBy(() -> insertTask(FRESH_SCHEMA, "TASK-UQ-2", 88001, 701))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_quote_tech_task_active_lock");
    assertThatThrownBy(() -> insertProduct(
        FRESH_SCHEMA, taskId, 88101, "ITEM:88101:MONTH:2026-08"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_quote_tech_product_active_lock");
    assertThatThrownBy(() -> insertVersion(FRESH_SCHEMA, productId, 1, "DRAFT"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_quote_tech_version_product_no");
    assertThatThrownBy(() -> insertModule(FRESH_SCHEMA, productId, "PROFILE"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_quote_tech_module_product_type");
    assertThatThrownBy(() -> insertReview(
        FRESH_SCHEMA, taskId, productId, versionId, 1, "PROFILE"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_quote_tech_review_round_module");

    execute(FRESH_SCHEMA,
        "UPDATE lp_quote_tech_product SET active_flag=0,active_lock_key=NULL WHERE id=" + productId);
    long historicalReplacement = insertProduct(
        FRESH_SCHEMA, taskId, 88101, "ITEM:88101:MONTH:2026-08");
    assertThat(historicalReplacement).isPositive().isNotEqualTo(productId);
  }

  @Test
  @DisplayName("包装、辅料、工资的8位小数精度不丢失")
  void preservesEightDecimalPlaces() throws Exception {
    runMigration(FRESH_SCHEMA, "precision.sql");
    long taskId = insertTask(FRESH_SCHEMA, "TASK-DEC", 88201, 702);
    long productId = insertProduct(FRESH_SCHEMA, taskId, 88301, "ITEM:88301:MONTH:2026-08");
    long versionId = insertVersion(FRESH_SCHEMA, productId, 1, "DRAFT");

    execute(FRESH_SCHEMA, """
        INSERT INTO lp_quote_tech_package_item(
          version_id,line_no,component_material_no,component_name,quantity,original_unit,
          standard_quantity,standard_unit,conversion_factor,price_basis_type,
          reference_unit_price,amount)
        VALUES (%d,1,'PKG-1','包装',12345678901.12345678,'PCS',
          12345678901.12345678,'PCS',1.00000000,'HISTORY',9.87654321,121932631124.82853211)
        """.formatted(versionId));
    execute(FRESH_SCHEMA, """
        INSERT INTO lp_quote_tech_aux_item(
          version_id,line_no,subject_code,auxiliary_name,pricing_method,quantity,original_unit,
          standard_quantity,standard_unit,conversion_factor,reference_unit_price,amount)
        VALUES (%d,1,'AUX-1','银基焊料','QTY',0.00022000,'KG',
          0.00022000,'KG',1.00000000,3200.12345678,0.70402716)
        """.formatted(versionId));
    execute(FRESH_SCHEMA, """
        INSERT INTO lp_quote_tech_salary_item(
          version_id,line_no,process_code,process_name,labor_type,working_hours,
          original_time_unit,standard_hours,standard_time_unit,conversion_factor,hourly_rate,amount)
        VALUES (%d,1,'OP-1','阀体装配','DIRECT',0.42000000,'HOUR',
          0.42000000,'HOUR',1.00000000,45.12345678,18.95185185)
        """.formatted(versionId));

    assertThat(singleDecimal(FRESH_SCHEMA,
        "SELECT quantity FROM lp_quote_tech_package_item WHERE version_id=" + versionId))
        .isEqualByComparingTo("12345678901.12345678");
    assertThat(singleDecimal(FRESH_SCHEMA,
        "SELECT reference_unit_price FROM lp_quote_tech_aux_item WHERE version_id=" + versionId))
        .isEqualByComparingTo("3200.12345678");
    assertThat(singleDecimal(FRESH_SCHEMA,
        "SELECT working_hours FROM lp_quote_tech_salary_item WHERE version_id=" + versionId))
        .isEqualByComparingTo("0.42000000");
    assertThat(singleDecimal(FRESH_SCHEMA,
        "SELECT amount FROM lp_quote_tech_salary_item WHERE version_id=" + versionId))
        .isEqualByComparingTo("18.95185185");
  }

  @Test
  @DisplayName("关联约束异常时整个事务回滚")
  void rollsBackWholeTransaction() throws Exception {
    runMigration(FRESH_SCHEMA, "rollback.sql");
    try (Connection connection = openConnection(FRESH_SCHEMA);
        Statement statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      try {
        statement.execute("""
            INSERT INTO lp_quote_tech_task(
              task_no,oa_form_id,oa_no,accounting_month,business_unit_type,applicable_org_code,
              assignee_user_id,assignee_name,active_flag,active_lock_key)
            VALUES ('TASK-ROLLBACK',88901,'OA-88901','2026-08','COMMERCIAL','220',
              799,'回滚技术员',1,'OA:OA-88901:MONTH:2026-08:ASSIGNEE:799')
            """);
        statement.execute("""
            INSERT INTO lp_quote_tech_product(
              task_id,oa_form_item_id,quote_no,accounting_month,source_snapshot_json,
              source_fingerprint,active_flag,active_lock_key)
            VALUES (999999999,88902,'OA-88901','2026-08','{}',REPEAT('f',64),1,
              'ITEM:88902:MONTH:2026-08')
            """);
        connection.commit();
      } catch (SQLException expected) {
        connection.rollback();
      }
    }
    assertThat(singleInt(FRESH_SCHEMA,
        "SELECT COUNT(*) FROM lp_quote_tech_task WHERE task_no='TASK-ROLLBACK'"))
        .isZero();
  }

  private static long insertTask(
      String schema, String taskNo, long oaFormId, long assigneeId) throws Exception {
    String oaNo = "OA-" + oaFormId;
    execute(schema, """
        INSERT INTO lp_quote_tech_task(
          task_no,oa_form_id,oa_no,accounting_month,business_unit_type,applicable_org_code,
          assignee_user_id,assignee_name,active_flag,active_lock_key)
        VALUES ('%s',%d,'%s','2026-08','COMMERCIAL','220',%d,'技术员',1,
          'OA:%s:MONTH:2026-08:ASSIGNEE:%d')
        """.formatted(taskNo, oaFormId, oaNo, assigneeId, oaNo, assigneeId));
    return singleLong(schema,
        "SELECT id FROM lp_quote_tech_task WHERE task_no='" + taskNo + "'");
  }

  private static long insertProduct(
      String schema, long taskId, long itemId, String activeLockKey) throws Exception {
    execute(schema, """
        INSERT INTO lp_quote_tech_product(
          task_id,oa_form_item_id,material_no,product_name,quote_no,accounting_month,
          source_snapshot_json,source_fingerprint,active_flag,active_lock_key)
        VALUES (%d,%d,'M-%d','测试产品','OA-TEST','2026-08','{}',REPEAT('a',64),1,'%s')
        """.formatted(taskId, itemId, itemId, activeLockKey));
    return singleLong(schema,
        "SELECT id FROM lp_quote_tech_product WHERE active_lock_key='" + activeLockKey + "'");
  }

  private static long insertVersion(
      String schema, long productId, int versionNo, String status) throws Exception {
    execute(schema, """
        INSERT INTO lp_quote_tech_data_version(product_id,version_no,version_status)
        VALUES (%d,%d,'%s')
        """.formatted(productId, versionNo, status));
    return singleLong(schema,
        "SELECT id FROM lp_quote_tech_data_version WHERE product_id=" + productId
            + " AND version_no=" + versionNo);
  }

  private static void insertModule(String schema, long productId, String moduleType)
      throws Exception {
    execute(schema, """
        INSERT INTO lp_quote_tech_module(
          product_id,module_type,required_flag,requirement_reason_code,requirement_reason)
        VALUES (%d,'%s',1,'REQUIRED','测试必填')
        """.formatted(productId, moduleType));
  }

  private static void insertReview(
      String schema,
      long taskId,
      long productId,
      long versionId,
      int round,
      String moduleType) throws Exception {
    execute(schema, """
        INSERT INTO lp_quote_tech_review_item(
          task_id,review_round,product_id,submitted_version_id,module_type)
        VALUES (%d,%d,%d,%d,'%s')
        """.formatted(taskId, round, productId, versionId, moduleType));
  }

  private static void runMigration(String schema, String targetFile) throws Exception {
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource(MIGRATION), "/tmp/" + targetFile);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + schema + " < /tmp/" + targetFile);
    assertThat(result.getExitCode())
        .as("V246执行失败，schema=" + schema + "，stderr=" + result.getStderr())
        .isZero();
  }

  private static Connection openConnection(String schema) throws Exception {
    String jdbcUrl = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema)
        + "?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
    return DriverManager.getConnection(jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
  }

  private static void execute(String schema, String sql) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static List<String> technicalTables(String schema) throws Exception {
    List<String> result = new ArrayList<>();
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME LIKE 'lp_quote_tech_%' ORDER BY TABLE_NAME")) {
      statement.setString(1, schema);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) result.add(rows.getString(1));
      }
    }
    return result;
  }

  private static String singleString(String schema, String sql) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }

  private static int singleInt(String schema, String sql) throws Exception {
    return (int) singleLong(schema, sql);
  }

  private static long singleLong(String schema, String sql) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getLong(1);
    }
  }

  private static BigDecimal singleDecimal(String schema, String sql) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getBigDecimal(1);
    }
  }

  private static void assertColumn(
      String schema, String table, String column, String columnType, boolean nullable)
      throws Exception {
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT COLUMN_TYPE,IS_NULLABLE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, column);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).as(table + "." + column).isTrue();
        assertThat(result.getString("COLUMN_TYPE")).isEqualToIgnoringCase(columnType);
        assertThat(result.getString("IS_NULLABLE")).isEqualTo(nullable ? "YES" : "NO");
      }
    }
  }

  private static void assertIndex(
      String schema, String table, String index, boolean unique, String... columns)
      throws Exception {
    List<String> actualColumns = new ArrayList<>();
    List<Boolean> uniqueness = new ArrayList<>();
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT NON_UNIQUE,COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, index);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          uniqueness.add(result.getInt("NON_UNIQUE") == 0);
          actualColumns.add(result.getString("COLUMN_NAME"));
        }
      }
    }
    assertThat(actualColumns).containsExactly(columns);
    assertThat(uniqueness).allMatch(value -> value == unique);
  }
}
