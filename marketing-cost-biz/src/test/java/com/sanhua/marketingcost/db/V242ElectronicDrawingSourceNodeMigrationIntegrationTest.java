package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@DisplayName("V242 电子图库源节点真实 MySQL 迁移")
class V242ElectronicDrawingSourceNodeMigrationIntegrationTest {

  private static final String FRESH_SCHEMA = "ed_v242_fresh";
  private static final String UPGRADE_SCHEMA = "ed_v242_upgrade";
  private static final String MIGRATION = "/db/V242__electronic_drawing_source_node.sql";
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
    createHistoricalStructure(FRESH_SCHEMA, false);
    createHistoricalStructure(UPGRADE_SCHEMA, true);
    execute(UPGRADE_SCHEMA,
        "INSERT INTO lp_quote_bom_supplement_version "
            + "(id,preparation_id,oa_no,oa_form_item_id,quote_product_code,product_type,"
            + "supplement_scope,bom_source,version_no,version_status,active_flag) VALUES "
            + "(101,501,'OA-HISTORY',601,'P-HISTORY','NON_BARE','NON_BARE_FULL_BOM',"
            + "'TECH_SUPPLEMENT',1,'APPROVED',1)");
    execute(UPGRADE_SCHEMA,
        "INSERT INTO lp_quote_bom_supplement_detail "
            + "(id,supplement_version_id,preparation_id,oa_no,oa_form_item_id,quote_product_code,"
            + "supplement_scope,line_no,level,material_code,manual_flag) VALUES "
            + "(201,101,501,'OA-HISTORY',601,'P-HISTORY','NON_BARE_FULL_BOM',1,0,'P-HISTORY',1)");
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("全新历史结构连续执行两次后只增加一张表且字段索引正确")
  void migratesFreshStructureIdempotently() throws Exception {
    List<String> before = tableNames(FRESH_SCHEMA);
    runMigration(FRESH_SCHEMA, "fresh-first.sql");
    runMigration(FRESH_SCHEMA, "fresh-second.sql");

    List<String> after = tableNames(FRESH_SCHEMA);
    assertThat(after).containsAll(before);
    assertThat(after).hasSize(before.size() + 1);
    assertThat(after).contains("lp_electronic_drawing_source_node");

    assertColumn(FRESH_SCHEMA, "lp_electronic_drawing_source_node", "qty",
        "decimal", "decimal(20,8)", false);
    assertColumn(FRESH_SCHEMA, "lp_electronic_drawing_source_node", "resolved_by",
        "varchar", "varchar(128)", true);
    assertColumn(FRESH_SCHEMA, "lp_quote_bom_supplement_version", "source_file_sha256",
        "char", "char(64)", true);
    assertColumn(FRESH_SCHEMA, "lp_quote_bom_supplement_detail", "source_electronic_node_id",
        "bigint", "bigint", true);
    assertIndex(FRESH_SCHEMA, "lp_electronic_drawing_source_node",
        "uk_ed_source_version_sequence", true, "supplement_version_id", "source_sequence");
    assertIndex(FRESH_SCHEMA, "lp_electronic_drawing_source_node",
        "idx_ed_source_version_status", false, "supplement_version_id", "match_status");
    assertIndex(FRESH_SCHEMA, "lp_quote_bom_supplement_version",
        "uk_qbp_supp_version_prepare_scope", true,
        "preparation_id", "supplement_scope", "version_no");
  }

  @Test
  @DisplayName("升级保留历史版本和明细并允许不同准备记录独立编号")
  void upgradesWithoutLosingExistingData() throws Exception {
    String versionBefore = singleString(UPGRADE_SCHEMA,
        "SELECT CONCAT_WS('|',id,preparation_id,oa_no,quote_product_code,version_status) "
            + "FROM lp_quote_bom_supplement_version WHERE id=101");
    String detailBefore = singleString(UPGRADE_SCHEMA,
        "SELECT CONCAT_WS('|',id,supplement_version_id,line_no,material_code) "
            + "FROM lp_quote_bom_supplement_detail WHERE id=201");

    runMigration(UPGRADE_SCHEMA, "upgrade-first.sql");
    runMigration(UPGRADE_SCHEMA, "upgrade-second.sql");

    assertThat(singleString(UPGRADE_SCHEMA,
        "SELECT CONCAT_WS('|',id,preparation_id,oa_no,quote_product_code,version_status) "
            + "FROM lp_quote_bom_supplement_version WHERE id=101"))
        .isEqualTo(versionBefore);
    assertThat(singleString(UPGRADE_SCHEMA,
        "SELECT CONCAT_WS('|',id,supplement_version_id,line_no,material_code) "
            + "FROM lp_quote_bom_supplement_detail WHERE id=201"))
        .isEqualTo(detailBefore);

    execute(UPGRADE_SCHEMA,
        "INSERT INTO lp_quote_bom_supplement_version "
            + "(preparation_id,oa_no,oa_form_item_id,quote_product_code,product_type,"
            + "supplement_scope,bom_source,version_no,version_status,active_flag) VALUES "
            + "(502,'OA-2',602,'P-2','NON_BARE','NON_BARE_FULL_BOM',"
            + "'ELECTRONIC_DRAWING_EXCEL',1,'DRAFT',1)");
    assertThatThrownBy(() -> execute(UPGRADE_SCHEMA,
        "INSERT INTO lp_quote_bom_supplement_version "
            + "(preparation_id,oa_no,oa_form_item_id,quote_product_code,product_type,"
            + "supplement_scope,bom_source,version_no,version_status,active_flag) VALUES "
            + "(502,'OA-2',603,'P-2','NON_BARE','NON_BARE_FULL_BOM',"
            + "'ELECTRONIC_DRAWING_EXCEL',1,'DRAFT',1)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_qbp_supp_version_prepare_scope");
  }

  @Test
  @DisplayName("源节点唯一键、数量和解析字段完整性约束生效")
  void enforcesSourceNodeConstraints() throws Exception {
    runMigration(FRESH_SCHEMA, "constraints.sql");
    insertVersion(FRESH_SCHEMA, 1001, 2001);
    execute(FRESH_SCHEMA,
        "INSERT INTO lp_electronic_drawing_source_node "
            + "(supplement_version_id,source_row_no,source_sequence,drawing_code,source_name,qty) "
            + "VALUES (1001,8,'1','D-1','零件',1)");

    assertThatThrownBy(() -> execute(FRESH_SCHEMA,
        "INSERT INTO lp_electronic_drawing_source_node "
            + "(supplement_version_id,source_row_no,source_sequence,drawing_code,source_name,qty) "
            + "VALUES (1001,9,'1','D-2','重复序号',1)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_ed_source_version_sequence");
    assertThatThrownBy(() -> execute(FRESH_SCHEMA,
        "INSERT INTO lp_electronic_drawing_source_node "
            + "(supplement_version_id,source_row_no,source_sequence,drawing_code,source_name,qty) "
            + "VALUES (1001,10,'2','D-3','非法数量',0)"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_ed_source_qty_positive");
    assertThatThrownBy(() -> execute(FRESH_SCHEMA,
        "UPDATE lp_electronic_drawing_source_node SET resolved_material_code='M-1' "
            + "WHERE supplement_version_id=1001 AND source_sequence='1'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("ck_ed_source_resolution_complete");
  }

  private static void createHistoricalStructure(String schema, boolean postV236Index)
      throws Exception {
    execute(schema,
        "CREATE TABLE lp_quote_bom_supplement_version ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,preparation_id BIGINT NOT NULL,"
            + "task_no VARCHAR(64) NULL,oa_no VARCHAR(64) NOT NULL,oa_form_item_id BIGINT NOT NULL,"
            + "quote_product_code VARCHAR(64) NOT NULL,product_type VARCHAR(32) NOT NULL,"
            + "supplement_scope VARCHAR(32) NOT NULL,bom_source VARCHAR(32) NOT NULL,"
            + "version_no INT NOT NULL DEFAULT 1,version_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',"
            + "active_flag TINYINT NOT NULL DEFAULT 1,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY(id),UNIQUE KEY uk_qbp_supp_version_task_scope ("
            + (postV236Index ? "supplement_scope,version_no" : "preparation_id,supplement_scope,version_no")
            + ")) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    execute(schema,
        "CREATE TABLE lp_quote_bom_supplement_detail ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,supplement_version_id BIGINT NOT NULL,"
            + "preparation_id BIGINT NOT NULL,oa_no VARCHAR(64) NOT NULL,oa_form_item_id BIGINT NOT NULL,"
            + "quote_product_code VARCHAR(64) NOT NULL,supplement_scope VARCHAR(32) NOT NULL,"
            + "line_no INT NOT NULL,level INT NOT NULL DEFAULT 0,material_code VARCHAR(64) NOT NULL,"
            + "source_u9_bom_id BIGINT NULL,manual_flag TINYINT NOT NULL DEFAULT 1,"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY(id),UNIQUE KEY uk_qbp_supp_detail_line(supplement_version_id,line_no)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
  }

  private static void insertVersion(String schema, long id, long preparationId) throws Exception {
    execute(schema,
        "INSERT INTO lp_quote_bom_supplement_version "
            + "(id,preparation_id,oa_no,oa_form_item_id,quote_product_code,product_type,"
            + "supplement_scope,bom_source,version_no,version_status,active_flag) VALUES ("
            + id + "," + preparationId + ",'OA-" + id + "'," + id + ",'P-" + id
            + "','NON_BARE','NON_BARE_FULL_BOM','ELECTRONIC_DRAWING_EXCEL',1,'DRAFT',1)");
  }

  private static void runMigration(String schema, String targetFile) throws Exception {
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource(MIGRATION), "/tmp/" + targetFile);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + schema + " < /tmp/" + targetFile);
    assertThat(result.getExitCode())
        .as("V242执行失败，schema=" + schema + "，stderr=" + result.getStderr())
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

  private static List<String> tableNames(String schema) throws Exception {
    List<String> result = new ArrayList<>();
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT TABLE_NAME FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME")) {
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

  private static void assertColumn(
      String schema, String table, String column, String dataType, String columnType,
      boolean nullable) throws Exception {
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT DATA_TYPE,COLUMN_TYPE,IS_NULLABLE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, column);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).as(table + "." + column).isTrue();
        assertThat(result.getString("DATA_TYPE")).isEqualToIgnoringCase(dataType);
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
          actualColumns.add(result.getString("COLUMN_NAME"));
          uniqueness.add(result.getInt("NON_UNIQUE") == 0);
        }
      }
    }
    assertThat(actualColumns).containsExactly(columns);
    assertThat(uniqueness).allMatch(value -> value == unique);
  }
}
