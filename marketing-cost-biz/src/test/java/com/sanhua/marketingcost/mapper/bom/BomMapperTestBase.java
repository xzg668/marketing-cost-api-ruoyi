package com.sanhua.marketingcost.mapper.bom;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * BOM 三层架构 4 张表 MapperTest 共用基类。
 *
 * <p>职责：
 * <ul>
 *   <li>启动 testcontainers MySQL 8.4 容器</li>
 *   <li>按顺序落全套迁移脚本（marketing_cost.sql + V2..V16 走 JDBC；V21 / V40 走 mysql CLI）</li>
 *   <li>把容器的 JDBC URL / 账号动态注入到 Spring DataSource</li>
 * </ul>
 *
 * <p>继承：4 个子类共享同一个 {@link DynamicPropertyRegistry} 来源 + {@link SpringBootTest}，
 * Spring TestContext 框架会缓存 ApplicationContext，容器和 Spring 上下文各起一次。
 *
 * <p>运行：子类各自带 {@code @Tag("integration")}（JUnit 5 的 {@code @Tag} 不从父类继承，
 * 所以必须放在子类上），surefire 默认排除 —— 老测试零影响。手动执行：
 * <pre>{@code
 *   mvn -pl marketing-cost-biz test -Dsurefire.excludedGroups= -Dsurefire.groups=integration \
 *       -Dtest='Bom*MapperTest'
 * }</pre>
 */
@SpringBootTest
public abstract class BomMapperTestBase {

  /** 与 V21 E2E 测试保持同一个镜像，省一次拉镜像的时间 */
  private static final DockerImageName MYSQL_IMAGE =
      DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql");

  /**
   * 基础迁移脚本：marketing_cost 主 schema + 附属 schema + V2..V16。
   *
   * <p>JDBC 按 {@code ;} 朴素切分执行；V4 虽含 DELIMITER 存储过程块，但基础 table 定义
   * 能落下，切分异常由 {@link #runScriptViaJdbc} ignore（和 V21TenantIsolationE2ETest 对齐）。
   */
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
      "/db/V16__cost_run_result_coefficient.sql");

  /** V24..V37 持续给 lp_price_variable 加字段，Spring 启动时的 VariableRegistry 会依赖这些列 */
  private static final List<String> LATE_MIGRATION_SCRIPTS = List.of(
      "/db/V24__linked_price_variable_extension.sql",
      "/db/V25__finance_base_price_import_support.sql",
      "/db/V26__linked_calc_item_trace.sql",
      "/db/V27__linked_price_menu_perms.sql",
      "/db/V28__linked_price_finance_base_fallback.sql",
      "/db/V31__unified_resolver_model.sql",
      "/db/V32__fix_finance_resolver_params_charset.sql",
      "/db/V33__price_variable_admin_perms.sql",
      "/db/V34__price_variable_binding.sql",
      "/db/V35__price_linked_item_soft_delete.sql",
      "/db/V36__row_local_placeholder.sql",
      "/db/V37__fix_blank_net_weight_unit_scale.sql");

  @SuppressWarnings("resource")
  protected static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(MYSQL_IMAGE)
          .withDatabaseName("marketing_cost")
          .withUsername("root")
          .withPassword("root123")
          .withCommand(
              "--sql-mode=NO_ENGINE_SUBSTITUTION",
              "--default-storage-engine=InnoDB",
              "--character-set-server=utf8mb4",
              "--collation-server=utf8mb4_0900_ai_ci");

  static {
    // 静态块：Spring DataSource 初始化前，容器就绪且所有迁移已落
    MYSQL.start();
    try {
      runMigrationsViaJdbc(BASE_MIGRATION_SCRIPTS);
      runScriptViaMysqlCli("/db/V21__business_unit_type_isolation.sql", "V21");
      runScriptViaMysqlCli("/db/V22__business_unit_type_isolation_extended.sql", "V22");
      runMigrationsViaJdbc(LATE_MIGRATION_SCRIPTS);
      runScriptViaMysqlCli("/db/V40__bom_three_layer_and_rules.sql", "V40");
    } catch (Exception e) {
      // 把 root cause 的文字信息拼进 message，避免 surefire 只保留 Caused by 的短描述
      Throwable root = e;
      while (root.getCause() != null) {
        root = root.getCause();
      }
      throw new IllegalStateException(
          "BOM MapperTest 基类初始化失败：root="
              + root.getClass().getName() + " msg=" + root.getMessage(),
          e);
    }
  }

  /**
   * 把容器 URL 注入 Spring DataSource。
   *
   * <p>同时禁用 Redis 自动配置，避免测试环境缺 Redis 时启动失败。
   */
  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> MYSQL.getJdbcUrl()
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&allowPublicKeyRetrieval=true&useSSL=false");
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.data.redis.repositories.enabled", () -> "false");
  }

  // ============================ 私有辅助 ============================

  /** 把一批脚本按 {@code ;} 切分走 JDBC 执行；异常被 ignore（幂等安全）。 */
  private static void runMigrationsViaJdbc(List<String> scripts) throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      for (String script : scripts) {
        runScriptViaJdbc(stmt, script);
      }
    }
  }

  /**
   * JDBC 朴素按 {@code ;} 切分执行单个脚本。
   *
   * <p>DELIMITER // 块（V4 里的 add_column_if_not_exists）切坏后 server 会抛语法错，
   * 本方法 ignore 这类异常——基础 table 定义仍然能落下。和 V21DataIsolationDdlTest
   * 的约定保持一致：已存在/不存在类错误视作幂等安全。
   */
  private static void runScriptViaJdbc(Statement stmt, String classpathResource) throws Exception {
    try (InputStream in = BomMapperTestBase.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalStateException("迁移脚本不存在：" + classpathResource);
      }
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String raw : content.split(";")) {
        String sql = raw.trim();
        if (sql.isEmpty() || (sql.startsWith("/*") && sql.endsWith("*/"))) {
          continue;
        }
        try {
          stmt.execute(sql);
        } catch (Exception ignore) {
          // 幂等安全：已存在/不存在/DELIMITER 切分错 —— 底层 table 照样落
        }
      }
    }
  }

  /**
   * 用容器内 mysql CLI 执行脚本。
   *
   * <p>V21 含 DELIMITER 存储过程块；V40 含中文 INSERT + 多行 CREATE 复杂结构，
   * JDBC 按 {@code ;} 切分容易切坏某条语句。CLI 一次性把整个文件交给 mysql server
   * 解析，与手工 {@code docker exec ... mysql < file.sql} 行为一致（已手工验证）。
   */
  private static void runScriptViaMysqlCli(String classpathResource, String logTag) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(classpathResource), "/tmp/" + logTag + ".sql");
    ExecResult result =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
                + " " + MYSQL.getDatabaseName() + " < /tmp/" + logTag + ".sql");
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          logTag + " mysql CLI 执行失败: exit=" + result.getExitCode()
              + "\nstdout:\n" + result.getStdout()
              + "\nstderr:\n" + result.getStderr());
    }
  }

  protected static Connection openConnection() throws Exception {
    String url = MYSQL.getJdbcUrl()
        + "?allowMultiQueries=true&useUnicode=true&characterEncoding=utf8"
        + "&allowPublicKeyRetrieval=true&useSSL=false";
    return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
  }
}
