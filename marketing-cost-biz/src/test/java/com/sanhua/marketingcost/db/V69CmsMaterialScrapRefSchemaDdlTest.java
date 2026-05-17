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
@DisplayName("V69 CMS 原材料对应回收废料 DDL")
class V69CmsMaterialScrapRefSchemaDdlTest {

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
      createV25MaterialScrapRef(stmt);
      seedExistingScrapRef(stmt);
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("V69 可在已有 lp_material_scrap_ref 的库执行两次，并补齐 CMS 字段")
  void v69CanRunTwiceOnExistingMaterialScrapRef() throws Exception {
    runV69("v69_existing_first.sql");
    runV69("v69_existing_second.sql");

    assertThat(tableExists("cms_material_scrap_ref_raw")).isFalse();
    assertThat(tableExists("lp_material_scrap_ref")).isTrue();
    assertThat(columnExists("lp_material_scrap_ref", "material_name")).isTrue();
    assertThat(columnExists("lp_material_scrap_ref", "scrap_name")).isTrue();
    assertThat(columnExists("lp_material_scrap_ref", "source_raw_id")).isFalse();
    assertThat(columnExists("lp_material_scrap_ref", "cms_posting_period")).isTrue();
    assertThat(columnExists("lp_material_scrap_ref", "cms_effective_date")).isTrue();
    assertThat(varcharLength("lp_material_scrap_ref", "business_unit_type")).isGreaterThanOrEqualTo(32);
    assertThat(indexExists("lp_material_scrap_ref", "uk_material_scrap")).isTrue();
    assertThat(indexExists("lp_material_scrap_ref", "idx_material_scrap_ref_bu_material")).isTrue();
    assertThat(indexExists("lp_material_scrap_ref", "idx_material_scrap_ref_source_raw")).isFalse();

    assertThat(count("SELECT COUNT(*) FROM lp_material_scrap_ref WHERE material_code='OLD-MAT'"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("V69 可在空库执行两次，并支持一对多 current 映射")
  void v69CanRunTwiceOnEmptySchemaAndSupportOneToManyMapping() throws Exception {
    String emptyDatabase = "marketing_cost_v69_empty";
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP DATABASE IF EXISTS " + emptyDatabase);
      stmt.execute("CREATE DATABASE " + emptyDatabase + " DEFAULT CHARACTER SET utf8mb4");
    }

    runV69("v69_empty_first.sql", emptyDatabase);
    runV69("v69_empty_second.sql", emptyDatabase);

    assertThat(tableExists(emptyDatabase, "cms_material_scrap_ref_raw")).isFalse();
    assertThat(tableExists(emptyDatabase, "lp_material_scrap_ref")).isTrue();
    assertThat(indexExists(emptyDatabase, "lp_material_scrap_ref", "uk_material_scrap")).isTrue();

    try (Connection conn = openConnection(emptyDatabase);
        Statement stmt = conn.createStatement()) {
      // current 表允许一个原材料对应多个 CMS 回收废料；重复的 material+scrap+BU 仍受唯一键约束。
      stmt.execute(
          "INSERT INTO lp_material_scrap_ref "
              + "(material_code, material_name, scrap_code, scrap_name, business_unit_type, source_type) "
              + "VALUES "
              + "('301050066', '拉制铜管', '301990317', '废紫铜沫（干净）', 'COMMERCIAL', 'CMS_EXCEL'),"
              + "('301050066', '拉制铜管', '301990XXX', '废紫铜沫补充', 'COMMERCIAL', 'CMS_EXCEL')");
      stmt.execute(
          "INSERT IGNORE INTO lp_material_scrap_ref "
              + "(material_code, scrap_code, business_unit_type, source_type) "
              + "VALUES ('301050066', '301990317', 'COMMERCIAL', 'CMS_EXCEL')");
    }

    assertThat(count(emptyDatabase,
        "SELECT COUNT(*) FROM lp_material_scrap_ref WHERE material_code='301050066'"))
        .isEqualTo(2);
  }

  private static void createV25MaterialScrapRef(Statement stmt) throws Exception {
    stmt.execute(
        "CREATE TABLE lp_material_scrap_ref ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "material_code VARCHAR(64) NOT NULL COMMENT '部品或原材料代码',"
            + "scrap_code VARCHAR(64) NOT NULL COMMENT '对应废料代码（CMS 体系）',"
            + "ratio DECIMAL(10,6) DEFAULT 1.0 COMMENT '抵减比例（如铜沫 0.92）',"
            + "effective_from DATE DEFAULT NULL COMMENT '生效日期',"
            + "effective_to DATE DEFAULT NULL COMMENT '失效日期',"
            + "business_unit_type VARCHAR(16) NOT NULL DEFAULT 'COMMERCIAL' COMMENT '业务单元隔离',"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id),"
            + "UNIQUE KEY uk_material_scrap (material_code, scrap_code, business_unit_type),"
            + "KEY idx_scrap_material (material_code)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
  }

  private static void seedExistingScrapRef(Statement stmt) throws Exception {
    stmt.execute(
        "INSERT INTO lp_material_scrap_ref "
            + "(material_code, scrap_code, ratio, business_unit_type) "
            + "VALUES ('OLD-MAT', 'OLD-SCRAP', 1.000000, 'COMMERCIAL')");
  }

  private static void runV69(String containerFileName) throws Exception {
    runV69(containerFileName, MYSQL.getDatabaseName());
  }

  private static void runV69(String containerFileName, String databaseName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V69__cms_material_scrap_ref.sql"),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + databaseName + " < /tmp/" + containerFileName);
    assertThat(result.getExitCode())
        .as("V69 执行失败 stderr=" + result.getStderr())
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

  private static boolean indexExists(String databaseName, String tableName, String indexName)
      throws Exception {
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
    return DriverManager.getConnection(jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
  }
}
