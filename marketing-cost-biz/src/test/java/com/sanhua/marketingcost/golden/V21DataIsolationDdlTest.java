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
 * V21 业务单元数据隔离 DDL 集成测试。
 *
 * <p>用 Testcontainers 临时 mysql:8.4 容器，跑完 V1..V16 之后再用容器内的 {@code mysql} CLI
 * 执行 V21（V21 含 {@code DELIMITER //} 存储过程块，JDBC 不识别，必须走 mysql 客户端）。
 *
 * <p>覆盖 T00 验收标准里的"V21 DDL 在全新空库上幂等执行"：
 * <ol>
 *   <li>所有受隔离业务表均出现 {@code business_unit_type VARCHAR(20)} 列</li>
 *   <li>18 个对应索引 {@code idx_*_but} 均存在</li>
 *   <li>{@code oa_form.form_type LIKE '家用%'} 的历史行回填为 HOUSEHOLD，否则 COMMERCIAL</li>
 *   <li>BOM / 试算等依赖表按 oa_no JOIN 回填正确</li>
 *   <li>价格 / 变量 / 产品属性类表默认回填 COMMERCIAL</li>
 *   <li>重复执行 V21 幂等（不报错，不重复加列）</li>
 *   <li>辅助存储过程 {@code add_column_if_not_exists_v21} / {@code add_index_if_not_exists_v21}
 *       最终被 DROP 清理</li>
 * </ol>
 *
 * <p>跑得慢（首次拉镜像 30-60s），标 {@code @Tag("integration")}，默认 surefire 排除。
 * 手动触发：
 * <pre>{@code mvn -pl marketing-cost-biz test -Dgroups=integration -Dtest=V21DataIsolationDdlTest}</pre>
 */
@Tag("integration")
@DisplayName("V21 业务单元数据隔离 - DDL 集成验证")
class V21DataIsolationDdlTest {

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

  /** V1..V16 的基础迁移（不含 DELIMITER，走 JDBC 切分执行）。 */
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
   * 需要隔离的业务表（与 V21 脚本一一对应）。
   * 注意：lp_price_range_item 是 Phase 1 路由改造的占位表，当前基线未创建，
   * V21 已用 backfill_default_bu_v21 等过程安全跳过，故此测试列表暂不包含它；
   * 等到对应 CREATE TABLE 迁移落地后，再把它加回来。
   */
  private static final List<String> ISOLATED_TABLES = List.of(
      "oa_form",
      "oa_form_item",
      "lp_bom_manage_item",
      "lp_bom_manual_item",
      "lp_cost_run_result",
      "lp_cost_run_part_item",
      "lp_cost_run_cost_item",
      "lp_price_fixed_item",
      "lp_price_linked_item",
      "lp_price_linked_calc_item",
      "lp_price_settle",
      "lp_price_settle_item",
      "lp_make_part_spec",
      "lp_raw_material_breakdown",
      "lp_product_property",
      "lp_price_variable",
      "lp_material_price_type"
  );

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();

    // 1) 跑 V1..V16 基础迁移（JDBC 朴素切分够用）
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      for (String script : BASE_MIGRATION_SCRIPTS) {
        runScriptViaJdbc(stmt, script);
      }
    }

    // 2) 灌一些"历史数据"用于回填验证（V21 执行前就存在，应被 UPDATE 填上 business_unit_type）
    seedLegacyData();

    // 3) V21 走容器内 mysql CLI（DELIMITER // 存储过程 JDBC 跑不了）
    runV21ViaMysqlCli();
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  // ============================ 测试用例 ============================

  @Test
  @DisplayName("所有受隔离业务表均有 business_unit_type VARCHAR(20) 列")
  void allIsolatedTablesHaveColumn() throws Exception {
    for (String table : ISOLATED_TABLES) {
      List<String> cols = listColumns(table);
      assertThat(cols)
          .as("表 %s 应含 business_unit_type 列", table)
          .contains("business_unit_type");
    }
  }

  @Test
  @DisplayName("所有受隔离业务表均有 idx_*_but 索引")
  void allIsolatedTablesHaveIndex() throws Exception {
    for (String table : ISOLATED_TABLES) {
      List<String> indexes = listIndexes(table);
      boolean hasButIndex = indexes.stream().anyMatch(i -> i.endsWith("_but"));
      assertThat(hasButIndex)
          .as("表 %s 应有一个 *_but 结尾的 business_unit_type 索引，实际 = %s", table, indexes)
          .isTrue();
    }
  }

  @Test
  @DisplayName("oa_form 历史回填：form_type LIKE '家用%' → HOUSEHOLD，否则 COMMERCIAL")
  void oaFormBackfilledByFormType() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      // 家用的
      try (ResultSet rs = stmt.executeQuery(
          "SELECT business_unit_type FROM oa_form WHERE oa_no = 'OA-LEGACY-HH'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("HOUSEHOLD");
      }
      // 商用的
      try (ResultSet rs = stmt.executeQuery(
          "SELECT business_unit_type FROM oa_form WHERE oa_no = 'OA-LEGACY-CM'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("COMMERCIAL");
      }
    }
  }

  @Test
  @DisplayName("oa_form_item / BOM / 成本试算 依赖表：按 oa_no JOIN 回填正确")
  void dependentTablesJoinBackfilled() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      assertThat(singleString(stmt,
          "SELECT business_unit_type FROM oa_form_item "
              + "WHERE oa_form_id = (SELECT id FROM oa_form WHERE oa_no = 'OA-LEGACY-HH')"))
          .isEqualTo("HOUSEHOLD");
      assertThat(singleString(stmt,
          "SELECT business_unit_type FROM lp_bom_manage_item WHERE oa_no = 'OA-LEGACY-CM'"))
          .isEqualTo("COMMERCIAL");
    }
  }

  @Test
  @DisplayName("价格 / 变量 / 产品属性 表：历史数据默认回填 COMMERCIAL")
  void priceAndVariableDefaultToCommercial() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      assertThat(singleString(stmt,
          "SELECT business_unit_type FROM lp_material_price_type "
              + "WHERE material_code = 'LEG-PRICE'"))
          .isEqualTo("COMMERCIAL");
    }
  }

  @Test
  @DisplayName("V21 重复执行幂等：再跑一次不报错、行数不变")
  void v21IsIdempotent() throws Exception {
    long beforeCols = countBusinessUnitTypeColumns();
    // 重跑一次，应该不报错且不增加列
    runV21ViaMysqlCli();
    long afterCols = countBusinessUnitTypeColumns();
    assertThat(afterCols)
        .as("重跑 V21 后 business_unit_type 列总数应不变（幂等）")
        .isEqualTo(beforeCols);
    // V4 的 sys_user.business_unit_type（权限口径）+ V21 给 ISOLATED_TABLES 加的业务列
    assertThat(afterCols)
        .as("列总数 = sys_user(V4) + 受隔离业务表(V21)")
        .isEqualTo(ISOLATED_TABLES.size() + 1);
  }

  @Test
  @DisplayName("辅助存储过程在 V21 末尾被 DROP，不残留 *_v21 命名")
  void helperProceduresDropped() throws Exception {
    // 覆盖 add_column_if_not_exists_v21 / add_index_if_not_exists_v21 / backfill_default_bu_v21
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COUNT(*) FROM information_schema.ROUTINES "
                 + "WHERE ROUTINE_SCHEMA = '" + MYSQL.getDatabaseName() + "' "
                 + "AND ROUTINE_NAME LIKE '%\\_v21' ESCAPE '\\\\'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1))
          .as("辅助存储过程应被 V21 末尾 DROP 掉，留在库里会污染命名空间")
          .isZero();
    }
  }

  // ============================ 私有辅助 ============================

  private static Connection openConnection() throws Exception {
    String url = MYSQL.getJdbcUrl()
        + "?allowMultiQueries=true&useUnicode=true&characterEncoding=utf8";
    return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
  }

  /** 用 JDBC 执行不含 DELIMITER 的普通 .sql。完全复用 DdlMigrationTest 的切分思路。 */
  private static void runScriptViaJdbc(Statement stmt, String classpathResource) throws Exception {
    try (InputStream in = V21DataIsolationDdlTest.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalStateException("迁移脚本不存在：" + classpathResource);
      }
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      // V4 含 DELIMITER 块，JDBC 跑不了，直接剥掉 —— 其内部 ALTER 在本测试里不需要（主表已完整定义）
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
      // 只在非幂等失败时抛
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

  /**
   * V21 含 {@code DELIMITER //} 存储过程块，JDBC 无法识别。
   * 用容器内 {@code mysql} CLI 执行：copyFileToContainer → execInContainer。
   */
  private static void runV21ViaMysqlCli() throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V21__business_unit_type_isolation.sql"),
        "/tmp/V21.sql");
    // 强制 --default-character-set=utf8mb4，避免容器里 mysql CLI 默认 latin1 把 "家用" 解成乱码
    ExecResult result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/V21.sql");
    if (result.getExitCode() != 0) {
      throw new IllegalStateException("V21 执行失败: exit=" + result.getExitCode()
          + "\nstdout:\n" + result.getStdout()
          + "\nstderr:\n" + result.getStderr());
    }
  }

  /**
   * 在 V21 之前灌几行"历史数据"，用来验证回填：
   * - OA-LEGACY-HH：家用% 前缀 → 回填应得 HOUSEHOLD
   * - OA-LEGACY-CM：商用% 前缀 → 回填应得 COMMERCIAL
   * - 每个 OA 都关联 1 行 oa_form_item + 1 行 lp_bom_manage_item，验证 JOIN 回填
   * - 价格表灌一行 LEG-PRICE，验证默认 COMMERCIAL
   */
  private static void seedLegacyData() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      // oa_form：注意此时还没有 business_unit_type 列
      stmt.executeUpdate("INSERT INTO oa_form (oa_no, form_type, apply_date, customer) "
          + "VALUES ('OA-LEGACY-HH', '家用-SLQ-01', '2026-04-10', 'CUST-HH')");
      stmt.executeUpdate("INSERT INTO oa_form (oa_no, form_type, apply_date, customer) "
          + "VALUES ('OA-LEGACY-CM', '商用-SHF-01', '2026-04-10', 'CUST-CM')");
      long idHH = singleLong(stmt, "SELECT id FROM oa_form WHERE oa_no = 'OA-LEGACY-HH'");
      long idCM = singleLong(stmt, "SELECT id FROM oa_form WHERE oa_no = 'OA-LEGACY-CM'");

      // oa_form_item：各 1 行（注意：此表无 oa_no，走 oa_form_id 外键关联）
      stmt.executeUpdate("INSERT INTO oa_form_item (oa_form_id, seq, material_no) "
          + "VALUES (" + idHH + ", 1, 'MAT-HH-001')");
      stmt.executeUpdate("INSERT INTO oa_form_item (oa_form_id, seq, material_no) "
          + "VALUES (" + idCM + ", 1, 'MAT-CM-001')");

      // lp_bom_manage_item：各 1 行（created_at/updated_at NOT NULL 无默认，需显式）
      stmt.executeUpdate("INSERT INTO lp_bom_manage_item "
          + "(oa_no, oa_form_id, item_code, item_name, bom_qty, created_at, updated_at) "
          + "VALUES ('OA-LEGACY-HH', " + idHH + ", 'ITM-HH', '家用-部品', 1, NOW(), NOW())");
      stmt.executeUpdate("INSERT INTO lp_bom_manage_item "
          + "(oa_no, oa_form_id, item_code, item_name, bom_qty, created_at, updated_at) "
          + "VALUES ('OA-LEGACY-CM', " + idCM + ", 'ITM-CM', '商用-部品', 1, NOW(), NOW())");

      // 价格表：默认 COMMERCIAL 验证
      stmt.executeUpdate("INSERT INTO lp_material_price_type "
          + "(bill_no, material_code, price_type, period, created_at, updated_at) "
          + "VALUES ('LEG-BILL', 'LEG-PRICE', '固定价', '2026-03', NOW(), NOW())");
    }
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

  private static String singleString(Statement stmt, String sql) throws Exception {
    try (ResultSet rs = stmt.executeQuery(sql)) {
      if (!rs.next()) {
        throw new IllegalStateException("查询无结果：" + sql);
      }
      return rs.getString(1);
    }
  }

  private static long singleLong(Statement stmt, String sql) throws Exception {
    try (ResultSet rs = stmt.executeQuery(sql)) {
      if (!rs.next()) {
        throw new IllegalStateException("查询无结果：" + sql);
      }
      return rs.getLong(1);
    }
  }
}
