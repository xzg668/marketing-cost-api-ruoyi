package com.sanhua.marketingcost.golden;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * V21 业务单元数据隔离 - 端到端集成测试。
 *
 * <p>目标：证明在真实 MyBatis-Plus + BusinessUnitInterceptor 链路下，
 * 被 {@code @DataScope} 标注的查询方法只返回当前登录单元的数据；切换租户后
 * 自动过滤到另一个单元的数据，不会越权看到对方租户的行。
 *
 * <p>流程：
 * <ol>
 *   <li>静态块启动 MySQL 容器并用 JDBC/mysql CLI 跑 V1..V16 + V21</li>
 *   <li>{@link DynamicPropertySource} 把容器 URL 注入 Spring，DataSource 指向容器</li>
 *   <li>每个测试前用 {@link #seedTwoTenants()} 重置 COMMERCIAL / HOUSEHOLD 各 1 行 oa_form</li>
 *   <li>切换 SecurityContext → {@link OaFormMapper#selectList} → 断言只见自家数据</li>
 * </ol>
 *
 * <p>跑得慢（首次拉镜像 + 起 Spring 容器），标 {@code @Tag("integration")}，默认 surefire 排除。
 * 手动触发：
 * <pre>{@code
 *   mvn -pl marketing-cost-biz test \
 *       -Dsurefire.excludedGroups= -Dsurefire.groups=integration \
 *       -Dtest=V21TenantIsolationE2ETest
 * }</pre>
 */
@Tag("integration")
@SpringBootTest
@DisplayName("V21 业务单元数据隔离 - 端到端集成验证")
class V21TenantIsolationE2ETest {

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

  /** V1..V16 基础迁移（不含 DELIMITER，走 JDBC 切分执行）。 */
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

  static {
    // 静态块保证 DataSource 初始化前，容器已就绪且全部迁移已落
    MYSQL.start();
    try {
      runBaseMigrations();
      runV21ViaMysqlCli();
    } catch (Exception e) {
      throw new IllegalStateException("启动阶段初始化数据库失败", e);
    }
  }

  /**
   * 动态注入 Spring DataSource / MyBatis-Plus 配置，把数据源指向容器。
   * 关闭 MP 的自动建表，避免对现有迁移产生干扰。
   */
  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url",
        () -> MYSQL.getJdbcUrl() + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai");
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    // 测试不需要 Redis / 外部依赖，禁用无关自动配置以加速启动
    registry.add("spring.data.redis.repositories.enabled", () -> "false");
  }

  @Autowired
  private OaFormMapper oaFormMapper;

  // ============================ 测试用例 ============================

  @Test
  @DisplayName("商用租户登录后：selectList 仅返回 COMMERCIAL 的 OA，不会看到 HOUSEHOLD")
  void commercialUserOnlySeesOwnData() throws Exception {
    seedTwoTenants();
    loginAs("COMMERCIAL", "ROLE_ACCOUNTANT");

    List<OaForm> rows = oaFormMapper.selectList(null);

    assertThat(rows)
        .as("商用账号查询应只见商用数据")
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.getBusinessUnitType()).isEqualTo("COMMERCIAL"));
    assertThat(rows)
        .extracting(OaForm::getOaNo)
        .as("不应出现对方租户的 OA 编号")
        .doesNotContain("OA-E2E-HH");
  }

  @Test
  @DisplayName("家用租户登录后：selectList 仅返回 HOUSEHOLD 的 OA，不会看到 COMMERCIAL")
  void householdUserOnlySeesOwnData() throws Exception {
    seedTwoTenants();
    loginAs("HOUSEHOLD", "ROLE_ACCOUNTANT");

    List<OaForm> rows = oaFormMapper.selectList(null);

    assertThat(rows)
        .as("家用账号查询应只见家用数据")
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.getBusinessUnitType()).isEqualTo("HOUSEHOLD"));
    assertThat(rows)
        .extracting(OaForm::getOaNo)
        .as("不应出现对方租户的 OA 编号")
        .doesNotContain("OA-E2E-CM");
  }

  @Test
  @DisplayName("切换租户：同一线程依次登录商用 / 家用，互不泄漏")
  void switchingTenantsFlipsScope() throws Exception {
    seedTwoTenants();

    loginAs("COMMERCIAL", "ROLE_ACCOUNTANT");
    List<OaForm> asCommercial = oaFormMapper.selectList(null);
    assertThat(asCommercial)
        .extracting(OaForm::getBusinessUnitType)
        .as("切换后第一次查询：商用")
        .containsOnly("COMMERCIAL");

    loginAs("HOUSEHOLD", "ROLE_ACCOUNTANT");
    List<OaForm> asHousehold = oaFormMapper.selectList(null);
    assertThat(asHousehold)
        .extracting(OaForm::getBusinessUnitType)
        .as("切换后第二次查询：家用")
        .containsOnly("HOUSEHOLD");
  }

  @Test
  @DisplayName("v1.3 方案 C：ROLE_ADMIN 也按登录单元过滤，不再豁免")
  void adminRoleIsAlsoFiltered() throws Exception {
    seedTwoTenants();
    loginAs("HOUSEHOLD", "ROLE_ADMIN");

    List<OaForm> rows = oaFormMapper.selectList(null);

    assertThat(rows)
        .as("admin 登录选择家用单元时，也应只见家用数据（v1.3 方案 C）")
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.getBusinessUnitType()).isEqualTo("HOUSEHOLD"));
  }

  @AfterEach
  void clearSecurityContextAndRows() throws Exception {
    SecurityContextHolder.clearContext();
    // 清掉每个用例插入的行，防止互相影响（硬删而非 MP 逻辑删，避免逻辑删过滤干扰下次种子）
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("DELETE FROM oa_form WHERE oa_no LIKE 'OA-E2E-%'");
    }
  }

  // ============================ 私有辅助 ============================

  /** 给每个测试插入 2 行 oa_form：1 商用 + 1 家用，business_unit_type 显式写入。 */
  private static void seedTwoTenants() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "INSERT INTO oa_form (oa_no, form_type, apply_date, customer, business_unit_type, "
              + "calc_status, created_at, updated_at, deleted) "
              + "VALUES ('OA-E2E-CM', '商用-测试', '2026-04-20', 'E2E-CUST-CM', 'COMMERCIAL', "
              + "'未核算', NOW(), NOW(), 0)");
      stmt.executeUpdate(
          "INSERT INTO oa_form (oa_no, form_type, apply_date, customer, business_unit_type, "
              + "calc_status, created_at, updated_at, deleted) "
              + "VALUES ('OA-E2E-HH', '家用-测试', '2026-04-20', 'E2E-CUST-HH', 'HOUSEHOLD', "
              + "'未核算', NOW(), NOW(), 0)");
    }
  }

  /**
   * 构造一个含 businessUnitType 的 Authentication 并挂到 SecurityContextHolder。
   * BusinessUnitInterceptor 会从这里读 details 里的 KEY_BUSINESS_UNIT_TYPE。
   */
  private static void loginAs(String businessUnitType, String role) {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            "e2e-user-" + businessUnitType,
            null,
            List.of(new SimpleGrantedAuthority(role)));
    Map<String, Object> details = new HashMap<>();
    details.put(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType);
    auth.setDetails(details);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  private static Connection openConnection() throws Exception {
    String url = MYSQL.getJdbcUrl()
        + "?allowMultiQueries=true&useUnicode=true&characterEncoding=utf8";
    return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
  }

  /** 在静态块里跑完 V1..V16 基础迁移，JDBC 朴素切分即可（无 DELIMITER）。 */
  private static void runBaseMigrations() throws Exception {
    try (Connection conn = openConnection();
         Statement stmt = conn.createStatement()) {
      for (String script : BASE_MIGRATION_SCRIPTS) {
        runScriptViaJdbc(stmt, script);
      }
    }
  }

  private static void runScriptViaJdbc(Statement stmt, String classpathResource) throws Exception {
    try (InputStream in = V21TenantIsolationE2ETest.class.getResourceAsStream(classpathResource)) {
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
          // 和 V21DataIsolationDdlTest 对齐：已存在/不存在类错误视作幂等安全
        }
      }
    }
  }

  /**
   * V21 含 {@code DELIMITER //} 存储过程块，JDBC 无法识别。
   * 用容器内 {@code mysql} CLI 执行，并强制 utf8mb4 避免 "家用" 被按 latin1 解码。
   */
  private static void runV21ViaMysqlCli() throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V21__business_unit_type_isolation.sql"),
        "/tmp/V21.sql");
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
}
