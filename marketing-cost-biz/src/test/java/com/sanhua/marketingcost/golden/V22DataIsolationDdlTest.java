package com.sanhua.marketingcost.golden;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * V22 业务单元数据隔离扩展覆盖 - DDL 集成测试。
 *
 * <p>V21 覆盖了 OA / BOM / 成本结果 / 价格主线共 17 张业务表；V22 补齐剩下的辅助 / 率表 /
 * 财务基准价 / 物料主数据 共 10 张。本测试用 Testcontainers mysql:8.4 临时库，先跑完
 * V1..V16 基础迁移 + V21，再执行 V22（V22 同样含 DELIMITER // 存储过程块，必须走
 * 容器内 mysql CLI），然后验证：
 * <ol>
 *   <li>10 张 V22 表均含 {@code business_unit_type VARCHAR(20)} 列</li>
 *   <li>10 个对应索引 {@code idx_*_but} 均存在</li>
 *   <li>灌的历史数据被回填为 COMMERCIAL（系统唯一已投产租户）</li>
 *   <li>重复执行 V22 幂等（不报错、不重复加列）</li>
 *   <li>辅助存储过程 {@code *_v22} 最终被 DROP 清理</li>
 * </ol>
 *
 * <p>跑得慢，标 {@code @Tag("integration")}，默认 surefire 排除。手动触发：
 * <pre>{@code
 * mvn -pl marketing-cost-biz test -Dsurefire.excludedGroups= -Dsurefire.groups=integration \
 *     -Dtest=V22DataIsolationDdlTest
 * }</pre>
 */
@Tag("integration")
@DisplayName("V22 业务单元数据隔离扩展 - DDL 集成验证")
class V22DataIsolationDdlTest {

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

  /** V1..V16 的基础迁移（不含 DELIMITER 的走 JDBC 朴素切分）。 */
  private static final List<String> BASE_MIGRATION_SCRIPTS = List.of(
      "/db/marketing_cost.sql",
      "/db/oa_form_schema.sql",
      "/db/price_settle.sql",
      "/db/V2__add_indexes.sql",
      "/db/V3__auth_tables.sql",
      "/db/V4__ruoyi_permission_tables.sql",
      "/db/V5__ruoyi_init_data.sql",
      "/db/V6__operation_log_enhance.sql",
      "/db/V7__data_migration.sql",
      "/db/V8__sys_menu_is_cache.sql",
      "/db/V9__bu_director_system_scope.sql",
      "/db/V10__material_price_type_upgrade.sql",
      "/db/V11__product_property_coefficient.sql",
      "/db/V12__formula_engine.sql",
      "/db/V13__price_variable_tax_mode.sql",
      "/db/V14__make_part_spec.sql",
      "/db/V15__raw_material_breakdown.sql",
      "/db/V16__cost_run_result_coefficient.sql"
  );

  /**
   * V22 新覆盖的 10 张表。
   * 与 V22__business_unit_type_isolation_extended.sql 的 CALL 列表一一对应。
   */
  private static final List<String> V22_TABLES = List.of(
      "lp_aux_rate_item",
      "lp_aux_subject",
      "lp_finance_base_price",
      "lp_manufacture_rate",
      "lp_three_expense_rate",
      "lp_quality_loss_rate",
      "lp_department_fund_rate",
      "lp_other_expense_rate",
      "lp_salary_cost",
      "lp_material_master"
  );

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();

    // 1) 跑 V1..V16 基础迁移
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      for (String script : BASE_MIGRATION_SCRIPTS) {
        runScriptViaJdbc(stmt, script);
      }
    }

    // 2) 基础 marketing_cost.sql 里就含每张表的 Records 段（每张表 ≥ 2 行），
    //    这些行在 V22 执行前就已存在，V22 后应被回填为 COMMERCIAL，无需额外 seed。

    // 3) 先跑 V21（V22 之前），V21 的列只覆盖别的表，不影响这 10 张
    runDelimiterScriptViaMysqlCli("/db/V21__business_unit_type_isolation.sql", "/tmp/V21.sql");

    // 4) 跑 V22
    runV22ViaMysqlCli();
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  // ============================ 测试用例 ============================

  @Test
  @DisplayName("V22 的 10 张表均有 business_unit_type VARCHAR(20) 列")
  void allV22TablesHaveColumn() throws Exception {
    for (String table : V22_TABLES) {
      List<String> cols = listColumns(table);
      assertThat(cols)
          .as("表 %s 应含 business_unit_type 列", table)
          .contains("business_unit_type");
    }
  }

  @Test
  @DisplayName("V22 的 10 张表均有 idx_*_but 索引")
  void allV22TablesHaveIndex() throws Exception {
    for (String table : V22_TABLES) {
      List<String> indexes = listIndexes(table);
      boolean hasButIndex = indexes.stream().anyMatch(i -> i.endsWith("_but"));
      assertThat(hasButIndex)
          .as("表 %s 应有一个 *_but 结尾的索引，实际 = %s", table, indexes)
          .isTrue();
    }
  }

  @Test
  @DisplayName("历史数据均回填为 COMMERCIAL（系统唯一已投产租户）")
  void legacyRowsBackfilledToCommercial() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      for (String table : V22_TABLES) {
        try (ResultSet rs = stmt.executeQuery(
            "SELECT COUNT(*) FROM `" + table + "` "
                + "WHERE business_unit_type IS NULL OR business_unit_type = ''")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getLong(1))
              .as("表 %s 不应留下 business_unit_type 为 NULL/空的历史行（V22 应回填为 COMMERCIAL）", table)
              .isZero();
        }
        try (ResultSet rs = stmt.executeQuery(
            "SELECT COUNT(*) FROM `" + table + "` WHERE business_unit_type = 'COMMERCIAL'")) {
          assertThat(rs.next()).isTrue();
          assertThat(rs.getLong(1))
              .as("表 %s 至少应有 1 行回填为 COMMERCIAL", table)
              .isGreaterThanOrEqualTo(1);
        }
      }
    }
  }

  @Test
  @DisplayName("V22 重复执行幂等：再跑一次不报错、列总数不变")
  void v22IsIdempotent() throws Exception {
    long beforeCols = countBusinessUnitTypeColumns();
    runV22ViaMysqlCli();
    long afterCols = countBusinessUnitTypeColumns();
    assertThat(afterCols)
        .as("重跑 V22 后 business_unit_type 列总数应不变（幂等）")
        .isEqualTo(beforeCols);
  }

  @Test
  @DisplayName("辅助存储过程在 V22 末尾被 DROP，不残留 *_v22 命名")
  void helperProceduresDropped() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COUNT(*) FROM information_schema.ROUTINES "
                 + "WHERE ROUTINE_SCHEMA = '" + MYSQL.getDatabaseName() + "' "
                 + "AND ROUTINE_NAME LIKE '%\\_v22' ESCAPE '\\\\'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1))
          .as("V22 辅助存储过程应在脚本末尾 DROP，库里不应有残留")
          .isZero();
    }
  }

  // ============================ 私有辅助 ============================

  private static Connection openConnection() throws Exception {
    String url = MYSQL.getJdbcUrl()
        + "?allowMultiQueries=true&useUnicode=true&characterEncoding=utf8";
    return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
  }

  /** 跑不含 DELIMITER 的 .sql（与 V21DataIsolationDdlTest 逻辑一致）。 */
  private static void runScriptViaJdbc(Statement stmt, String classpathResource) throws Exception {
    try (InputStream in = V22DataIsolationDdlTest.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalStateException("迁移脚本不存在：" + classpathResource);
      }
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      // 剥掉 DELIMITER // ... DELIMITER ; 块
      content = content.replaceAll("(?is)DELIMITER\\s+//.*?DELIMITER\\s*;", "");
      // 去行注释
      StringBuilder cleaned = new StringBuilder(content.length());
      for (String line : content.split("\\r?\\n")) {
        String trimmed = line.trim();
        if (trimmed.startsWith("--")) {
          cleaned.append('\n');
          continue;
        }
        cleaned.append(line).append('\n');
      }
      List<String> failures = new ArrayList<>();
      for (String raw : cleaned.toString().split(";\\s*\\n")) {
        String sql = raw.trim();
        if (sql.isEmpty() || (sql.startsWith("/*") && sql.endsWith("*/"))) {
          continue;
        }
        try {
          stmt.execute(sql);
        } catch (Exception e) {
          failures.add("[" + classpathResource + "] " + e.getMessage()
              + "  on SQL: " + sql.substring(0, Math.min(80, sql.length())));
        }
      }
      long fatal = failures.stream().filter(f -> !isIdempotentFailure(f)).count();
      if (fatal > 0) {
        throw new IllegalStateException("迁移脚本执行失败 " + fatal + " 处：\n"
            + String.join("\n", failures.subList(0, Math.min(10, failures.size()))));
      }
    }
  }

  private static boolean isIdempotentFailure(String message) {
    return message.contains("Duplicate entry")
        || message.contains("Duplicate key name")
        || message.contains("Duplicate column name")
        || message.contains("already exists")
        || message.contains("doesn't exist")
        || message.contains("does not exist")
        || message.contains("Unknown table")
        || message.contains("Unknown column")
        || message.contains("Can't DROP")
        || message.contains("check that column/key exists");
  }

  /** 跑含 DELIMITER 的脚本：拷进容器 → mysql CLI 执行。 */
  private static void runDelimiterScriptViaMysqlCli(String classpathResource, String containerPath)
      throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(classpathResource), containerPath);
    ExecResult result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < " + containerPath);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(classpathResource + " 执行失败: exit=" + result.getExitCode()
          + "\nstdout:\n" + result.getStdout()
          + "\nstderr:\n" + result.getStderr());
    }
  }

  private static void runV22ViaMysqlCli() throws Exception {
    runDelimiterScriptViaMysqlCli(
        "/db/V22__business_unit_type_isolation_extended.sql", "/tmp/V22.sql");
  }

  // ------------- information_schema 查询工具 -------------

  private static List<String> listColumns(String table) throws Exception {
    List<String> cols = new ArrayList<>();
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                 + "WHERE TABLE_SCHEMA = '" + MYSQL.getDatabaseName()
                 + "' AND TABLE_NAME = '" + table + "'")) {
      while (rs.next()) {
        cols.add(rs.getString(1));
      }
    }
    return cols;
  }

  private static List<String> listIndexes(String table) throws Exception {
    List<String> idx = new ArrayList<>();
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                 + "WHERE TABLE_SCHEMA = '" + MYSQL.getDatabaseName()
                 + "' AND TABLE_NAME = '" + table + "'")) {
      while (rs.next()) {
        idx.add(rs.getString(1));
      }
    }
    return idx;
  }

  private static long countBusinessUnitTypeColumns() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COUNT(*) FROM information_schema.COLUMNS "
                 + "WHERE TABLE_SCHEMA = '" + MYSQL.getDatabaseName() + "' "
                 + "AND COLUMN_NAME = 'business_unit_type'")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
