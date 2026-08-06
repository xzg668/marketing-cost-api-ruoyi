package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("QBA-01 报价BOM标准/替代结构迁移兼容性")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuoteBomAlternativeMigrationCompatibilityTest {

  private static final String EMPTY_SCHEMA = "qba_empty";
  private static final String UPGRADE_SCHEMA = "qba_upgrade";
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
          "--collation-server=utf8mb4_0900_ai_ci");

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();
    try (Connection connection = openConnection(MYSQL.getDatabaseName());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE DATABASE " + EMPTY_SCHEMA
          + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      statement.execute("CREATE DATABASE " + UPGRADE_SCHEMA
          + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    }
    createLegacyTables(EMPTY_SCHEMA);
    createLegacyTables(UPGRADE_SCHEMA);
    insertHistoricalRows();
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @Order(1)
  @DisplayName("空基础表库可重复执行V199至V201且新增结构完整")
  void migratesEmptyBaseTablesIdempotently() throws Exception {
    runMigrations(EMPTY_SCHEMA, "empty_first");
    runMigrations(EMPTY_SCHEMA, "empty_second");

    assertRawColumnsAndIndex(EMPTY_SCHEMA);
    assertSelectionTableAndIndexes(EMPTY_SCHEMA);
    assertPermissionData(EMPTY_SCHEMA);
    assertThat(rowCount(EMPTY_SCHEMA, "lp_bom_raw_hierarchy")).isZero();
    assertThat(rowCount(EMPTY_SCHEMA, "lp_bom_costing_row")).isZero();
    assertThat(rowCount(EMPTY_SCHEMA, "lp_quote_bom_confirmation")).isZero();
  }

  @Test
  @Order(2)
  @DisplayName("历史升级库迁移不改写旧层级、结算行和确认记录")
  void keepsHistoricalBusinessRowsUnchanged() throws Exception {
    LegacySnapshot before = readLegacySnapshot();

    runMigrations(UPGRADE_SCHEMA, "upgrade");

    LegacySnapshot after = readLegacySnapshot();
    assertThat(after).isEqualTo(before);
    assertRawColumnsAndIndex(UPGRADE_SCHEMA);
    assertSelectionTableAndIndexes(UPGRADE_SCHEMA);
    assertPermissionData(UPGRADE_SCHEMA);

    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(
            "SELECT child_type,alternative_group_key "
                + "FROM lp_bom_raw_hierarchy WHERE id IN (1,2) ORDER BY id")) {
      int rows = 0;
      while (result.next()) {
        rows++;
        assertThat(result.getObject("child_type")).isNull();
        assertThat(result.getObject("alternative_group_key")).isNull();
      }
      assertThat(rows).isEqualTo(2);
    }
  }

  @Test
  @Order(3)
  @DisplayName("同组允许多条历史选择但只允许一条当前选择")
  void enforcesOneCurrentSelectionPerScopeAndGroup() throws Exception {
    insertSelection(UPGRADE_SCHEMA, "SEL-1", "OA-1", 10L, "TOP-1", "2026-07",
        "GROUP-1", 1, 1);
    insertSelection(UPGRADE_SCHEMA, "SEL-2", "OA-1", 10L, "TOP-1", "2026-07",
        "GROUP-1", 2, null);
    insertSelection(UPGRADE_SCHEMA, "SEL-3", "OA-1", 10L, "TOP-1", "2026-07",
        "GROUP-1", 3, null);

    assertThat(rowCount(
        UPGRADE_SCHEMA, "lp_quote_bom_alternative_selection")).isEqualTo(3);
    assertThatThrownBy(() -> insertSelection(
        UPGRADE_SCHEMA, "SEL-4", "OA-1", 10L, "TOP-1", "2026-07",
        "GROUP-1", 4, 1))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_quote_alt_selection_current");
  }

  @Test
  @Order(4)
  @DisplayName("不同OA、产品行、月份和替代组的当前选择互不冲突")
  void isolatesCurrentSelectionByFullQuoteScope() throws Exception {
    insertSelection(UPGRADE_SCHEMA, "SEL-OA", "OA-2", 10L, "TOP-1", "2026-07",
        "GROUP-1", 1, 1);
    insertSelection(UPGRADE_SCHEMA, "SEL-ITEM", "OA-1", 11L, "TOP-1", "2026-07",
        "GROUP-1", 1, 1);
    insertSelection(UPGRADE_SCHEMA, "SEL-TOP", "OA-1", 10L, "TOP-2", "2026-07",
        "GROUP-1", 1, 1);
    insertSelection(UPGRADE_SCHEMA, "SEL-MONTH", "OA-1", 10L, "TOP-1", "2026-08",
        "GROUP-1", 1, 1);
    insertSelection(UPGRADE_SCHEMA, "SEL-GROUP", "OA-1", 10L, "TOP-1", "2026-07",
        "GROUP-2", 1, 1);

    assertThat(currentSelectionCount(UPGRADE_SCHEMA)).isEqualTo(6);
  }

  private static void createLegacyTables(String schema) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE lp_bom_raw_hierarchy ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "price_org_code VARCHAR(32) NULL,"
              + "top_product_code VARCHAR(64) NOT NULL,"
              + "parent_code VARCHAR(64) NULL,"
              + "material_code VARCHAR(64) NOT NULL,"
              + "bom_purpose VARCHAR(32) NULL,"
              + "path VARCHAR(2000) NOT NULL,"
              + "qty_per_top DECIMAL(20,8) NULL,"
              + "build_batch_id VARCHAR(128) NULL,"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE lp_bom_costing_row ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "oa_no VARCHAR(64) NOT NULL,"
              + "material_code VARCHAR(64) NOT NULL,"
              + "path VARCHAR(2000) NULL,"
              + "qty_per_top DECIMAL(20,8) NULL,"
              + "settlement_row_type VARCHAR(32) NULL,"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE lp_quote_bom_confirmation ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "confirm_no VARCHAR(64) NOT NULL,"
              + "oa_no VARCHAR(64) NOT NULL,"
              + "row_count INT NOT NULL,"
              + "replace_count INT NOT NULL,"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE sys_menu ("
              + "menu_id BIGINT NOT NULL,"
              + "menu_name VARCHAR(64) NOT NULL,"
              + "parent_id BIGINT NOT NULL,"
              + "order_num INT NOT NULL,"
              + "path VARCHAR(200) NOT NULL,"
              + "component VARCHAR(255) NULL,"
              + "menu_type CHAR(1) NOT NULL,"
              + "visible CHAR(1) NOT NULL,"
              + "status CHAR(1) NOT NULL,"
              + "perms VARCHAR(255) NULL,"
              + "icon VARCHAR(100) NULL,"
              + "create_by VARCHAR(64) NULL,"
              + "create_time DATETIME NULL,"
              + "update_by VARCHAR(64) NULL,"
              + "update_time DATETIME NULL,"
              + "remark VARCHAR(500) NULL,"
              + "PRIMARY KEY(menu_id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE sys_role_menu ("
              + "role_id BIGINT NOT NULL,"
              + "menu_id BIGINT NOT NULL,"
              + "PRIMARY KEY(role_id,menu_id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
  }

  private static void insertHistoricalRows() throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO lp_bom_raw_hierarchy "
              + "(id,price_org_code,top_product_code,parent_code,material_code,"
              + "bom_purpose,path,qty_per_top,build_batch_id) VALUES "
              + "(1,'210','1145900000302','101850644','201850522','主制造',"
              + "'/1145900000302/101850644/201850522/',1.00000000,'h-old'),"
              + "(2,'210','1145900000302','101850644','201850659','主制造',"
              + "'/1145900000302/101850644/201850659/',1.00000000,'h-old')");
      statement.execute(
          "INSERT INTO lp_bom_costing_row "
              + "(id,oa_no,material_code,path,qty_per_top,settlement_row_type) VALUES "
              + "(1,'OA-OLD','201850347','/old/alternative/',1.00000000,'DEFAULT_LEAF'),"
              + "(2,'OA-OLD','201850547','/old/standard/',1.00000000,'DEFAULT_LEAF')");
      statement.execute(
          "INSERT INTO lp_quote_bom_confirmation "
              + "(id,confirm_no,oa_no,row_count,replace_count) "
              + "VALUES (1,'CONF-OLD','OA-OLD',35,0)");
    }
  }

  private static LegacySnapshot readLegacySnapshot() throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement()) {
      String rawRows = groupedRows(statement,
          "SELECT GROUP_CONCAT(CONCAT_WS('|',id,price_org_code,top_product_code,"
              + "parent_code,material_code,bom_purpose,path,qty_per_top,build_batch_id) "
              + "ORDER BY id SEPARATOR ';') FROM lp_bom_raw_hierarchy");
      String costingRows = groupedRows(statement,
          "SELECT GROUP_CONCAT(CONCAT_WS('|',id,oa_no,material_code,path,"
              + "qty_per_top,settlement_row_type) ORDER BY id SEPARATOR ';') "
              + "FROM lp_bom_costing_row");
      String confirmationRows = groupedRows(statement,
          "SELECT GROUP_CONCAT(CONCAT_WS('|',id,confirm_no,oa_no,row_count,"
              + "replace_count) ORDER BY id SEPARATOR ';') "
              + "FROM lp_quote_bom_confirmation");
      return new LegacySnapshot(rawRows, costingRows, confirmationRows);
    }
  }

  private static String groupedRows(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }

  private static void insertSelection(
      String schema,
      String selectionNo,
      String oaNo,
      long itemId,
      String topProductCode,
      String periodMonth,
      String groupKey,
      int version,
      Integer currentSlot) throws Exception {
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "INSERT INTO lp_quote_bom_alternative_selection ("
                + "selection_no,oa_no,oa_form_item_id,top_product_code,period_month,"
                + "price_org_code,business_unit_type,alternative_group_key,parent_material_code,"
                + "standard_material_code,selected_material_code,selected_child_type,"
                + "selection_source,selection_version,selection_status,current_slot"
                + ") VALUES (?,?,?,?,?,'210','COMMERCIAL',?,'PARENT','STANDARD','SELECTED',"
                + "'STANDARD','AUTO_STANDARD',?,'ACTIVE',?)")) {
      statement.setString(1, selectionNo);
      statement.setString(2, oaNo);
      statement.setLong(3, itemId);
      statement.setString(4, topProductCode);
      statement.setString(5, periodMonth);
      statement.setString(6, groupKey);
      statement.setInt(7, version);
      if (currentSlot == null) {
        statement.setNull(8, java.sql.Types.TINYINT);
      } else {
        statement.setInt(8, currentSlot);
      }
      statement.executeUpdate();
    }
  }

  private static long currentSelectionCount(String schema) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(
            "SELECT COUNT(*) FROM lp_quote_bom_alternative_selection "
                + "WHERE current_slot=1")) {
      assertThat(result.next()).isTrue();
      return result.getLong(1);
    }
  }

  private static long rowCount(String schema, String table) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertThat(result.next()).isTrue();
      return result.getLong(1);
    }
  }

  private static void assertRawColumnsAndIndex(String schema) throws Exception {
    assertNullableColumn(schema, "lp_bom_raw_hierarchy", "child_type", "VARCHAR");
    assertNullableColumn(
        schema, "lp_bom_raw_hierarchy", "alternative_group_key", "CHAR");
    assertThat(indexExists(schema, "lp_bom_raw_hierarchy", "idx_bom_raw_alt_group"))
        .isTrue();
  }

  private static void assertSelectionTableAndIndexes(String schema) throws Exception {
    assertThat(tableExists(schema, "lp_quote_bom_alternative_selection")).isTrue();
    assertNullableColumn(
        schema, "lp_quote_bom_alternative_selection", "current_slot", "TINYINT");
    assertNullableColumn(
        schema, "lp_quote_bom_alternative_selection", "candidate_snapshot_json", "JSON");
    assertThat(indexExists(
        schema, "lp_quote_bom_alternative_selection", "uk_quote_alt_selection_no"))
        .isTrue();
    assertThat(indexExists(
        schema, "lp_quote_bom_alternative_selection", "uk_quote_alt_selection_version"))
        .isTrue();
    assertThat(indexExists(
        schema, "lp_quote_bom_alternative_selection", "uk_quote_alt_selection_current"))
        .isTrue();
    assertThat(indexExists(
        schema, "lp_quote_bom_alternative_selection", "idx_quote_alt_selection_item"))
        .isTrue();
    assertThat(indexColumns(
        schema,
        "lp_quote_bom_alternative_selection",
        "uk_quote_alt_selection_current"))
        .containsExactly(
            "oa_no",
            "oa_form_item_id",
            "top_product_code",
            "period_month",
            "price_org_code",
            "business_unit_type",
            "alternative_group_key",
            "current_slot");
  }

  private static void assertPermissionData(String schema) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement();
        ResultSet menu = statement.executeQuery(
            "SELECT COUNT(*) FROM sys_menu WHERE menu_id=40482 "
                + "AND perms='quote:costing:bom:alternative-select'")) {
      assertThat(menu.next()).isTrue();
      assertThat(menu.getLong(1)).isEqualTo(1);
    }
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement();
        ResultSet roleMenu = statement.executeQuery(
            "SELECT COUNT(*) FROM sys_role_menu WHERE role_id=1 AND menu_id=40482")) {
      assertThat(roleMenu.next()).isTrue();
      assertThat(roleMenu.getLong(1)).isEqualTo(1);
    }
  }

  private static void assertNullableColumn(
      String schema, String table, String column, String expectedType) throws Exception {
    try (Connection connection = openConnection(schema);
        ResultSet result = connection.getMetaData().getColumns(schema, null, table, column)) {
      assertThat(result.next()).as(table + "." + column + " exists").isTrue();
      assertThat(result.getString("TYPE_NAME")).isEqualToIgnoringCase(expectedType);
      assertThat(result.getString("IS_NULLABLE")).isEqualTo("YES");
    }
  }

  private static boolean tableExists(String schema, String table) throws Exception {
    try (Connection connection = openConnection(schema);
        ResultSet result = connection.getMetaData().getTables(schema, null, table, null)) {
      return result.next();
    }
  }

  private static boolean indexExists(String schema, String table, String index)
      throws Exception {
    try (Connection connection = openConnection(schema);
        ResultSet result = connection.getMetaData().getIndexInfo(
            schema, null, table, false, false)) {
      while (result.next()) {
        if (index.equals(result.getString("INDEX_NAME"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static java.util.List<String> indexColumns(
      String schema, String table, String index) throws Exception {
    java.util.List<IndexedColumn> columns = new java.util.ArrayList<>();
    try (Connection connection = openConnection(schema);
        ResultSet result = connection.getMetaData().getIndexInfo(
            schema, null, table, false, false)) {
      while (result.next()) {
        if (index.equals(result.getString("INDEX_NAME"))) {
          columns.add(
              new IndexedColumn(
                  result.getInt("ORDINAL_POSITION"),
                  result.getString("COLUMN_NAME")));
        }
      }
    }
    return columns.stream()
        .sorted(java.util.Comparator.comparingInt(IndexedColumn::position))
        .map(IndexedColumn::name)
        .toList();
  }

  private static void runMigrations(String schema, String runName)
      throws Exception {
    runMigration(
        schema,
        "/db/V199__quote_bom_alternative_selection.sql",
        "v199_" + runName + ".sql");
    runMigration(
        schema,
        "/db/V200__quote_bom_alternative_selection_scope_isolation.sql",
        "v200_" + runName + ".sql");
    runMigration(
        schema,
        "/db/V201__quote_bom_alternative_selection_permission.sql",
        "v201_" + runName + ".sql");
  }

  private static void runMigration(
      String schema, String classpathResource, String targetFile)
      throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(classpathResource),
        "/tmp/" + targetFile);
    var result = MYSQL.execInContainer(
        "sh",
        "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + schema + " < /tmp/" + targetFile);
    assertThat(result.getExitCode())
        .as(
            classpathResource
                + "执行失败，schema="
                + schema
                + "，stderr="
                + result.getStderr())
        .isZero();
  }

  private static Connection openConnection(String schema) throws Exception {
    String jdbcUrl = MYSQL.getJdbcUrl().replace(
        "/" + MYSQL.getDatabaseName(), "/" + schema);
    return DriverManager.getConnection(jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
  }

  private record LegacySnapshot(
      String rawRows,
      String costingRows,
      String confirmationRows) {
  }

  private record IndexedColumn(int position, String name) {
  }
}
