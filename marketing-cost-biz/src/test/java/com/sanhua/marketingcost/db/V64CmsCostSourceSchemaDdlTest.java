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
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("V64 CMS 成本来源 DDL")
class V64CmsCostSourceSchemaDdlTest {

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

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      createMinimalExistingCostTables(stmt);
      seedExistingCostRows(stmt);
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("V64 可在已有成本库执行两次，并创建 CMS 来源结构")
  void v64CanRunTwiceOnExistingCostSchema() throws Exception {
    runV64("v64_first.sql");
    runV64("v64_second.sql");

    assertThat(tableExists("cms_cost_import_batch")).isTrue();
    assertThat(tableExists("cms_plan_cost_raw")).isTrue();
    assertThat(tableExists("cms_workshop_labor_raw")).isTrue();
    assertThat(tableExists("cms_product_subject_cost_raw")).isTrue();
    assertThat(tableExists("cms_cost_source_effective")).isTrue();
    assertThat(tableExists("cms_cost_source_effective_log")).isTrue();
    assertThat(tableExists("cms_aux_subject_config")).isFalse();
    assertThat(varcharLength("cms_plan_cost_raw", "oa_no")).isGreaterThanOrEqualTo(255);
    assertThat(columnExists("cms_plan_cost_raw", "effective_period")).isTrue();
    assertThat(columnNullable("cms_plan_cost_raw", "effective_period")).isFalse();
    assertThat(columnNullable("cms_workshop_labor_raw", "source_row_id")).isFalse();
    assertThat(columnNullable("cms_product_subject_cost_raw", "source_row_id")).isFalse();

    assertThat(columnExists("lp_salary_cost", "period")).isFalse();
    assertThat(columnExists("lp_salary_cost", "source_import_batch_id")).isFalse();
    assertThat(columnExists("lp_salary_cost", "lock_status")).isFalse();
    assertThat(columnExists("lp_salary_cost", "lock_reason")).isFalse();
    assertThat(columnExists("lp_aux_subject", "source_import_batch_id")).isFalse();
    assertThat(columnExists("lp_aux_subject", "lock_status")).isFalse();
    assertThat(columnExists("lp_aux_subject", "amount_calc_mode")).isFalse();

    assertThat(indexExists("cms_cost_source_effective", "uk_cms_effective_source")).isTrue();
    assertThat(indexExists("cms_plan_cost_raw", "uk_cms_plan_current")).isTrue();
    assertThat(indexExists("cms_workshop_labor_raw", "uk_cms_workshop_source_row")).isTrue();
    assertThat(indexExists("cms_product_subject_cost_raw", "uk_cms_subject_source_row")).isTrue();
    assertThat(indexExists("lp_salary_cost", "uk_salary_cms_lock")).isFalse();
    assertThat(indexExists("lp_aux_subject", "uk_aux_cms_lock")).isFalse();
  }

  @Test
  @DisplayName("V64 可在空库执行两次，并创建 CMS 来源结构")
  void v64CanRunTwiceOnEmptySchema() throws Exception {
    String emptyDatabase = "marketing_cost_empty";
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP DATABASE IF EXISTS " + emptyDatabase);
      stmt.execute("CREATE DATABASE " + emptyDatabase + " DEFAULT CHARACTER SET utf8mb4");
    }

    runV64("v64_empty_first.sql", emptyDatabase);
    runV64("v64_empty_second.sql", emptyDatabase);

    assertThat(tableExists(emptyDatabase, "cms_cost_import_batch")).isTrue();
    assertThat(tableExists(emptyDatabase, "cms_plan_cost_raw")).isTrue();
    assertThat(tableExists(emptyDatabase, "cms_workshop_labor_raw")).isTrue();
    assertThat(tableExists(emptyDatabase, "cms_product_subject_cost_raw")).isTrue();
    assertThat(tableExists(emptyDatabase, "cms_cost_source_effective")).isTrue();
    assertThat(tableExists(emptyDatabase, "cms_cost_source_effective_log")).isTrue();
    assertThat(tableExists(emptyDatabase, "cms_aux_subject_config")).isFalse();
    assertThat(indexExists(emptyDatabase, "cms_cost_source_effective", "uk_cms_effective_source")).isTrue();
    assertThat(indexExists(emptyDatabase, "cms_plan_cost_raw", "uk_cms_plan_current")).isTrue();
    assertThat(indexExists(emptyDatabase, "cms_workshop_labor_raw", "uk_cms_workshop_source_row")).isTrue();
    assertThat(indexExists(emptyDatabase, "cms_product_subject_cost_raw", "uk_cms_subject_source_row")).isTrue();
  }

  private static void createMinimalExistingCostTables(Statement stmt) throws Exception {
    stmt.execute(
        "CREATE TABLE lp_salary_cost ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "material_code VARCHAR(80) NOT NULL,"
            + "product_name VARCHAR(120) DEFAULT NULL,"
            + "spec VARCHAR(120) DEFAULT NULL,"
            + "model VARCHAR(120) DEFAULT NULL,"
            + "ref_material_code VARCHAR(80) DEFAULT NULL,"
            + "direct_labor_cost DECIMAL(14,6) NOT NULL,"
            + "indirect_labor_cost DECIMAL(14,6) NOT NULL,"
            + "source VARCHAR(40) DEFAULT NULL,"
            + "business_unit VARCHAR(80) DEFAULT NULL,"
            + "business_unit_type VARCHAR(20) DEFAULT NULL COMMENT '业务单元（租户口径）',"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id),"
            + "UNIQUE KEY uk_salary_unique (material_code, ref_material_code, business_unit, source),"
            + "KEY idx_salary_cost_but (business_unit_type)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

    stmt.execute(
        "CREATE TABLE lp_aux_subject ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "material_code VARCHAR(64) NOT NULL,"
            + "business_unit_type VARCHAR(20) DEFAULT NULL COMMENT '业务单元',"
            + "product_name VARCHAR(128) DEFAULT NULL,"
            + "spec VARCHAR(128) DEFAULT NULL,"
            + "model VARCHAR(128) DEFAULT NULL,"
            + "ref_material_code VARCHAR(64) DEFAULT NULL,"
            + "aux_subject_code VARCHAR(32) NOT NULL,"
            + "aux_subject_name VARCHAR(128) NOT NULL,"
            + "unit_price DECIMAL(18,6) DEFAULT NULL COMMENT '辅料单价（元，6 位小数对齐其他价格字段）',"
            + "period CHAR(7) NOT NULL,"
            + "source VARCHAR(32) DEFAULT 'import',"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id),"
            + "KEY idx_aux_material (material_code),"
            + "KEY idx_aux_subject (aux_subject_code),"
            + "KEY idx_aux_subject_but (business_unit_type)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
  }

  private static void seedExistingCostRows(Statement stmt) throws Exception {
    stmt.execute(
        "INSERT INTO lp_salary_cost "
            + "(material_code, product_name, direct_labor_cost, indirect_labor_cost, source, "
            + "business_unit, business_unit_type) VALUES "
            + "('1079900000536', '四通换向阀', 4.220988, 0, 'manual-2026-04', "
            + "'四通阀事业部', 'COMMERCIAL')");
    stmt.execute(
        "INSERT INTO lp_aux_subject "
            + "(material_code, business_unit_type, product_name, aux_subject_code, aux_subject_name, "
            + "unit_price, period, source) VALUES "
            + "('1079900000536', 'COMMERCIAL', '四通换向阀', '0201', "
            + "'辅助焊料类', 0.673200, '2026-03', 'manual')");
  }

  private static void runV64(String containerFileName) throws Exception {
    runV64(containerFileName, MYSQL.getDatabaseName());
  }

  private static void runV64(String containerFileName, String databaseName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V64__cms_cost_source_schema.sql"),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + databaseName + " < /tmp/" + containerFileName);
    assertThat(result.getExitCode())
        .as("V64 执行失败 stderr=" + result.getStderr())
        .isZero();
  }

  private static boolean tableExists(String tableName) throws Exception {
    return tableExists(MYSQL.getDatabaseName(), tableName);
  }

  private static boolean tableExists(String databaseName, String tableName) throws Exception {
    try (Connection conn = openConnection(databaseName);
        ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null)) {
      return rs.next();
    }
  }

  private static boolean columnExists(String tableName, String columnName) throws Exception {
    try (Connection conn = openConnection();
        ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, columnName)) {
      return rs.next();
    }
  }

  private static boolean indexExists(String tableName, String indexName) throws Exception {
    return indexExists(MYSQL.getDatabaseName(), tableName, indexName);
  }

  private static boolean indexExists(String databaseName, String tableName, String indexName) throws Exception {
    try (Connection conn = openConnection(databaseName);
        ResultSet rs = conn.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
      while (rs.next()) {
        if (indexName.equals(rs.getString("INDEX_NAME"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static int varcharLength(String tableName, String columnName) throws Exception {
    try (Connection conn = openConnection();
        ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, columnName)) {
      assertThat(rs.next()).isTrue();
      return rs.getInt("COLUMN_SIZE");
    }
  }

  private static boolean columnNullable(String tableName, String columnName) throws Exception {
    try (Connection conn = openConnection();
        ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, columnName)) {
      assertThat(rs.next()).isTrue();
      return "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
    }
  }

  private static int count(String sql) throws Exception {
    return count(MYSQL.getDatabaseName(), sql);
  }

  private static int count(String databaseName, String sql) throws Exception {
    try (Connection conn = openConnection(databaseName);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      return rs.getInt(1);
    }
  }

  private static Connection openConnection() throws Exception {
    return openConnection(MYSQL.getDatabaseName());
  }

  private static Connection openConnection(String databaseName) throws Exception {
    String jdbcUrl = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + databaseName);
    return DriverManager.getConnection(
        jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
  }
}
