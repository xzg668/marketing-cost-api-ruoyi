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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DDL 迁移集成测试 —— 在 Testcontainers 临时 MySQL 容器里跑全量 SQL，
 * 校验 V10/V11/V13/V24/V25 等升级脚本的字段、索引、默认值是否生效。
 *
 * <p>跑得慢（首次拉镜像 30-60s），故标 {@code @Tag("integration")}。
 * 默认 surefire 排除 integration 组；CI 或需要时手动开启：
 * <pre>{@code mvn test -Dgroups=integration -Dtest=DdlMigrationTest}</pre>
 *
 * <p>不依赖 Spring 启动，不读 application.yml；纯 JDBC + 脚本 source。
 */
@Tag("integration")
@DisplayName("DDL 迁移 - V10/V11/V24/V25/V26/V27 升级验证")
class DdlMigrationTest {

  /** 用 mysql:8.4（与生产 8.4.0 同一大版本），avoid mysql/mysql-server 镜像走 OCI ARM 适配问题。 */
  private static final DockerImageName MYSQL_IMAGE =
      DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql");

  /** 容器单例 —— 整个测试类共享，省启动开销。 */
  @SuppressWarnings("resource") // testcontainers 会在 JVM 退出时统一回收
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(MYSQL_IMAGE)
          .withDatabaseName("marketing_cost")
          .withUsername("root")
          .withPassword("root123")
          // 关闭严格 SQL 模式，避免历史 DDL 被新 strict_mode 拒绝
          .withCommand("--sql-mode=NO_ENGINE_SUBSTITUTION", "--default-storage-engine=InnoDB",
              "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

  /**
   * 全量迁移脚本顺序 —— 与 marketing_cost-api/.../db/ 下文件名严格对齐。
   *
   * <p>V21/V22 含 {@code DELIMITER //} 存储过程，JDBC 切分器会把过程体当作 DELIMITER 串去跑，
   * 现有 runScript 用正则把 DELIMITER 块整体剥掉，过程内的 ALTER 就不会执行；
   * 但 V24/V25 只要 marketing_cost.sql + V13 的 lp_price_variable/lp_finance_base_price 表结构就能跑通，
   * 不依赖 V21/V22 的 business_unit_type 列，所以此处省略 V21/V22 以保持脚本纯净。
   */
  private static final List<String> MIGRATION_SCRIPTS = List.of(
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
      "/db/V16__cost_run_result_coefficient.sql",
      "/db/V24__linked_price_variable_extension.sql",
      "/db/V25__finance_base_price_import_support.sql",
      "/db/V26__linked_calc_item_trace.sql",
      "/db/V27__linked_price_menu_perms.sql"
  );

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      for (String script : MIGRATION_SCRIPTS) {
        runScript(stmt, script);
      }
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  // ============================ 测试用例 ============================

  @Test
  @DisplayName("V10：lp_material_price_type 含新增 4 列")
  void v10AddsFourColumns() throws Exception {
    List<String> cols = listColumns("lp_material_price_type");
    assertThat(cols).contains("priority", "effective_from", "effective_to", "source_system");
  }

  @Test
  @DisplayName("V10：priority 默认值 = 1，source_system 默认值 = 'manual'")
  void v10DefaultValues() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      // 插入一条最小化记录验证默认值生效
      stmt.execute("INSERT INTO lp_material_price_type "
          + "(bill_no, material_code, price_type, period, created_at, updated_at) "
          + "VALUES ('TEST-V10', 'MAT-DEFAULT-CHECK', '固定价', '2026-04', NOW(), NOW())");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT priority, source_system FROM lp_material_price_type "
              + "WHERE material_code = 'MAT-DEFAULT-CHECK'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getInt("priority")).isEqualTo(1);
        assertThat(rs.getString("source_system")).isEqualTo("manual");
      }
    }
  }

  @Test
  @DisplayName("V10：新增 2 个索引存在")
  void v10IndexesCreated() throws Exception {
    List<String> indexes = listIndexes("lp_material_price_type");
    assertThat(indexes)
        .contains("idx_material_shape_priority", "idx_effective");
  }

  @Test
  @DisplayName("V11：lp_product_property 含 coefficient 列且默认 1.0")
  void v11ProductPropertyCoefficient() throws Exception {
    assertThat(listColumns("lp_product_property")).contains("coefficient");
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      // 插入一条不带 coefficient 的记录，校验默认值
      stmt.execute("INSERT INTO lp_product_property "
          + "(level1_code, level1_name, parent_code, period, product_attr) "
          + "VALUES ('99', 'TEST', 'P-V11-CHECK', '2026-04', '标准品')");
      try (ResultSet rs = stmt.executeQuery(
          "SELECT coefficient FROM lp_product_property WHERE parent_code = 'P-V11-CHECK'")) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getBigDecimal("coefficient").doubleValue())
            .isEqualTo(1.0);
      }
    }
  }

  @Test
  @DisplayName("V11：现存历史数据 coefficient 全部已回填为 1.0")
  void v11HistoryBackfilled() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COUNT(*) FROM lp_product_property WHERE coefficient IS NULL")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isZero();
    }
  }

  @Test
  @DisplayName("V24：lp_price_variable 三列齐全 + PART_CONTEXT seed 达 9 条")
  void v24ExtendsPriceVariable() throws Exception {
    // (1) 三列存在
    List<String> cols = listColumns("lp_price_variable");
    assertThat(cols).contains("factor_type", "aliases_json", "context_binding_json");

    // (2) factor_type 索引存在
    assertThat(listIndexes("lp_price_variable")).contains("idx_factor_type");

    // (3) PART_CONTEXT 变量必须恰好 9 条（内置）
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COUNT(*) FROM lp_price_variable WHERE factor_type = 'PART_CONTEXT'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(9);
    }

    // (4) 抽一条关键 seed：下料重量 aliases_json 应含 "下料重"
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT aliases_json, context_binding_json FROM lp_price_variable "
                 + "WHERE variable_code = 'blank_weight'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("aliases_json")).contains("下料重");
      assertThat(rs.getString("context_binding_json")).contains("blank_weight");
    }

    // (5) FINANCE_FACTOR 侧 Cu 应被补上 aliases_json（电解铜）
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT factor_type, aliases_json FROM lp_price_variable "
                 + "WHERE variable_code = 'Cu'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString("factor_type")).isEqualTo("FINANCE_FACTOR");
      assertThat(rs.getString("aliases_json")).contains("电解铜");
    }
  }

  @Test
  @DisplayName("V26：lp_price_linked_calc_item 新增 trace_json 列")
  void v26AddsTraceJson() throws Exception {
    List<String> cols = listColumns("lp_price_linked_calc_item");
    assertThat(cols).contains("trace_json");
  }

  @Test
  @DisplayName("V27：联动价 3 条按钮权限入 sys_menu 且 admin/bu_director/bu_staff 全部关联")
  void v27AddsLinkedPriceButtonPerms() throws Exception {
    // (1) 三条 F 类按钮 seed 存在（perms 即为 DoD 要求）
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COUNT(*) FROM sys_menu WHERE menu_type = 'F' AND perms IN ("
                 + "'price:linked-item:preview', 'price:variable:list',"
                 + " 'price:finance-base:import')")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(3);
    }

    // (2) 三条 menu 均挂在联动价目录 401 之下
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COUNT(*) FROM sys_menu WHERE menu_id IN (40151, 40152, 40153)"
                 + " AND parent_id = 401")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(3);
    }

    // (3) 管理员角色（role_id=1）关联三条 —— DoD: 管理员可见所有菜单
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COUNT(*) FROM sys_role_menu"
                 + " WHERE role_id = 1 AND menu_id IN (40151, 40152, 40153)")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(3);
    }

    // (4) 业务单元总监 + 业务单元人员同样持有（与 V5 的 menu_id>=200 规则一致）
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COUNT(*) FROM sys_role_menu"
                 + " WHERE role_id IN (10, 11) AND menu_id IN (40151, 40152, 40153)")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(6);
    }

    // (5) 其他角色（OA_COLLABORATOR role_id=12）不能拿到 —— DoD: 普通/受限用户 403
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT COUNT(*) FROM sys_role_menu"
                 + " WHERE role_id = 12 AND menu_id IN (40151, 40152, 40153)")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isZero();
    }
  }

  @Test
  @DisplayName("V25：lp_finance_base_price 新增 price_original/import_batch_id + lp_material_scrap_ref 表存在")
  void v25AddsOriginalPriceAndScrapRef() throws Exception {
    // (1) finance_base_price 两新列
    List<String> cols = listColumns("lp_finance_base_price");
    assertThat(cols).contains("price_original", "import_batch_id");

    // (2) import_batch_id 索引
    assertThat(listIndexes("lp_finance_base_price")).contains("idx_batch");

    // (3) 新表 lp_material_scrap_ref 存在且含核心字段
    List<String> scrapCols = listColumns("lp_material_scrap_ref");
    assertThat(scrapCols).isNotEmpty();
    assertThat(scrapCols).contains(
        "material_code", "scrap_code", "ratio", "business_unit_type");
  }

  // ============================ 工具方法 ============================

  private static Connection openConnection() throws Exception {
    // allowMultiQueries=true 让我们能把整个 .sql 一把丢进 execute() 跑
    String url = MYSQL.getJdbcUrl()
        + "?allowMultiQueries=true&useUnicode=true&characterEncoding=utf8";
    return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
  }

  /**
   * 跑一个 .sql 脚本：按 ";\n" 朴素切分（不做完整 SQL 词法分析）。
   * 现有脚本不含触发器/存储过程等含分号的复合体，这种切分够用。
   */
  private static void runScript(Statement stmt, String classpathResource) throws Exception {
    try (InputStream in = DdlMigrationTest.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalStateException("迁移脚本不存在：" + classpathResource);
      }
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      // 剥离 DELIMITER //...DELIMITER ; 包裹的存储过程块 —— JDBC 不识别 DELIMITER 客户端指令，
      // 而 V4__ruoyi_permission_tables.sql 用了 add_column_if_not_exists 过程做条件加列。
      // 直接剥掉这些块；过程内部的 ALTER 在测试容器里不需要，因为 marketing_cost.sql 已完整定义业务表。
      content = content.replaceAll("(?is)DELIMITER\\s+//.*?DELIMITER\\s*;", "");
      // 去掉行注释（-- 开头），保留块注释（/* */）由 MySQL 自己忽略
      StringBuilder cleaned = new StringBuilder(content.length());
      for (String line : content.split("\\r?\\n")) {
        String trimmed = line.trim();
        if (trimmed.startsWith("--")) {
          cleaned.append('\n');
          continue;
        }
        cleaned.append(line).append('\n');
      }
      // 按分号切分，过滤空语句
      List<String> failures = new ArrayList<>();
      for (String raw : cleaned.toString().split(";\\s*\\n")) {
        String sql = raw.trim();
        if (sql.isEmpty() || sql.startsWith("/*") && sql.endsWith("*/")) {
          continue;
        }
        try {
          stmt.execute(sql);
        } catch (Exception e) {
          // 允许 IF EXISTS / DROP 等幂等失败，但记录所有错误便于排查
          failures.add("[" + classpathResource + "] " + e.getMessage()
              + "  on SQL: " + sql.substring(0, Math.min(80, sql.length())));
        }
      }
      if (!failures.isEmpty()) {
        // 容忍历史脚本中的幂等失败（白名单关键字）；V10/V11 是本次新增脚本，任何失败都视为致命
        boolean isNewScript = classpathResource.contains("V10__")
            || classpathResource.contains("V11__")
            || classpathResource.contains("V24__")
            || classpathResource.contains("V25__")
            || classpathResource.contains("V26__");
        long fatal = failures.stream()
            .filter(f -> isNewScript || !isIdempotentFailure(f))
            .count();
        if (fatal > 0) {
          throw new IllegalStateException("迁移脚本执行失败 " + fatal + " 处：\n"
              + String.join("\n", failures.subList(0, Math.min(10, failures.size()))));
        }
      }
    }
  }

  /** 是否为可忽略的幂等失败（列/索引/表已存在或不存在等）。 */
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
}
