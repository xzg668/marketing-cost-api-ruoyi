package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
@DisplayName("QEB-01 最终有效BOM结构迁移兼容性")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuoteEffectiveBomMigrationCompatibilityTest {

  private static final String EMPTY_SCHEMA = "qeb_empty";
  private static final String UPGRADE_SCHEMA = "qeb_upgrade";
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
      createSchema(statement, EMPTY_SCHEMA);
      createSchema(statement, UPGRADE_SCHEMA);
    }
    createV201UpgradeTables();
    insertHistoricalRows();
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @Order(1)
  @DisplayName("完全空库可重复执行V202且只新增两张业务表")
  void migratesCompletelyEmptySchemaIdempotently() throws Exception {
    runMigration(EMPTY_SCHEMA, "empty_first");
    runMigration(EMPTY_SCHEMA, "empty_second");

    assertThat(tableExists(EMPTY_SCHEMA, "lp_quote_effective_bom_node")).isTrue();
    assertThat(tableExists(EMPTY_SCHEMA, "lp_material_quote_shape_policy")).isTrue();
    assertThat(tableCount(EMPTY_SCHEMA)).isEqualTo(2);
    assertNewTableIndexes(EMPTY_SCHEMA);
  }

  @Test
  @Order(2)
  @DisplayName("V201升级库重复迁移不改写历史快照、选择、确认和计价行")
  void upgradesV201SchemaWithoutChangingHistoricalRows() throws Exception {
    LegacySnapshot before = readLegacySnapshot();

    runMigration(UPGRADE_SCHEMA, "upgrade_first");
    runMigration(UPGRADE_SCHEMA, "upgrade_second");

    LegacySnapshot after = readLegacySnapshot();
    assertThat(after).isEqualTo(before);
    assertExistingTableExtensions();
    assertNewTableIndexes(UPGRADE_SCHEMA);
    assertThat(newBusinessTableCount(UPGRADE_SCHEMA)).isEqualTo(2);
    assertThat(tableExists(UPGRADE_SCHEMA, "lp_quote_effective_bom_build")).isFalse();
  }

  @Test
  @Order(3)
  @DisplayName("同一构建内节点键重复被数据库唯一键拒绝")
  void rejectsDuplicateNodeKeyWithinSameBuild() throws Exception {
    insertEffectiveNode("BUILD-A", "NODE-ROOT", "a".repeat(64));

    assertThatThrownBy(
        () -> insertEffectiveNode("BUILD-A", "NODE-ROOT", "a".repeat(64)))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("uk_build_node");
  }

  @Test
  @Order(4)
  @DisplayName("不同构建允许使用相同节点键")
  void allowsSameNodeKeyAcrossDifferentBuilds() throws Exception {
    insertEffectiveNode("BUILD-B", "NODE-ROOT", "b".repeat(64));

    assertThat(countByNodeKey("NODE-ROOT")).isEqualTo(2);
  }

  @Test
  @Order(5)
  @DisplayName("5000节点和规则样本下核心查询命中设计索引")
  void coreQueriesUseDesignedIndexes() throws Exception {
    insertIndexPerformanceFixtures(5_000);

    assertThat(
            explainKey(
                "SELECT * FROM lp_quote_effective_bom_node "
                    + "WHERE build_batch_id='BUILD-PERF'"))
        .isEqualTo("uk_build_node");
    assertThat(
            explainKey(
                "SELECT * FROM lp_material_quote_shape_policy "
                    + "WHERE material_org_code='COMMERCIAL' "
                    + "AND material_code='RULE-02500' "
                    + "AND enabled=1 "
                    + "AND effective_from_month<='2026-08' "
                    + "AND (effective_to_month IS NULL OR effective_to_month>='2026-08')"))
        .isEqualTo("idx_material_month");
  }

  private static void createSchema(Statement statement, String schema) throws Exception {
    statement.execute(
        "CREATE DATABASE " + schema
            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
  }

  private static void createV201UpgradeTables() throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE lp_quote_bom_monthly_snapshot ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "product_code VARCHAR(64) NOT NULL,"
              + "price_org_code VARCHAR(32) NULL,"
              + "customer_code VARCHAR(128) NOT NULL DEFAULT '',"
              + "package_method VARCHAR(128) NOT NULL DEFAULT '',"
              + "cost_period_month VARCHAR(7) NOT NULL,"
              + "bom_batch_id VARCHAR(128) NULL,"
              + "active_flag TINYINT NOT NULL DEFAULT 0,"
              + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
              + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP "
              + "ON UPDATE CURRENT_TIMESTAMP,"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE lp_quote_bom_confirmation ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "confirm_no VARCHAR(64) NOT NULL,"
              + "oa_no VARCHAR(64) NOT NULL,"
              + "row_count INT NOT NULL DEFAULT 0,"
              + "confirm_status VARCHAR(32) NOT NULL,"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE lp_quote_bom_alternative_selection ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "selection_no VARCHAR(64) NOT NULL,"
              + "selection_source VARCHAR(32) NOT NULL,"
              + "selected_material_code VARCHAR(64) NOT NULL,"
              + "selection_status VARCHAR(16) NOT NULL,"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE lp_bom_costing_row ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "build_batch_id VARCHAR(64) NOT NULL,"
              + "material_code VARCHAR(64) NOT NULL,"
              + "qty_per_top DECIMAL(20,8) NOT NULL,"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
  }

  private static void insertHistoricalRows() throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO lp_quote_bom_monthly_snapshot "
              + "(id,product_code,price_org_code,customer_code,package_method,"
              + "cost_period_month,bom_batch_id,active_flag) VALUES "
              + "(1,'1001900000098','210','CUSTOMER-A','STANDARD','2026-07',"
              + "'RAW-OLD',1)");
      statement.execute(
          "INSERT INTO lp_quote_bom_confirmation "
              + "(id,confirm_no,oa_no,row_count,confirm_status) VALUES "
              + "(1,'CONF-OLD','OA-OLD',14,'CONFIRMED')");
      statement.execute(
          "INSERT INTO lp_quote_bom_alternative_selection "
              + "(id,selection_no,selection_source,selected_material_code,"
              + "selection_status) VALUES "
              + "(1,'SEL-OLD','MANUAL_ALTERNATIVE','201850522','ACTIVE')");
      statement.execute(
          "INSERT INTO lp_bom_costing_row "
              + "(id,build_batch_id,material_code,qty_per_top) VALUES "
              + "(1,'COST-OLD','311034930',0.00360000)");
    }
  }

  private static LegacySnapshot readLegacySnapshot() throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement()) {
      return new LegacySnapshot(
          scalar(statement,
              "SELECT CONCAT_WS('|',id,product_code,price_org_code,customer_code,"
                  + "package_method,cost_period_month,bom_batch_id,active_flag) "
                  + "FROM lp_quote_bom_monthly_snapshot WHERE id=1"),
          scalar(statement,
              "SELECT CONCAT_WS('|',id,confirm_no,oa_no,row_count,confirm_status) "
                  + "FROM lp_quote_bom_confirmation WHERE id=1"),
          scalar(statement,
              "SELECT CONCAT_WS('|',id,selection_no,selection_source,"
                  + "selected_material_code,selection_status) "
                  + "FROM lp_quote_bom_alternative_selection WHERE id=1"),
          scalar(statement,
              "SELECT CONCAT_WS('|',id,build_batch_id,material_code,qty_per_top) "
                  + "FROM lp_bom_costing_row WHERE id=1"));
    }
  }

  private static String scalar(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }

  private static void assertExistingTableExtensions() throws Exception {
    assertColumn(
        UPGRADE_SCHEMA, "lp_quote_bom_monthly_snapshot", "freeze_status", "VARCHAR");
    assertColumn(
        UPGRADE_SCHEMA,
        "lp_quote_bom_monthly_snapshot",
        "effective_build_batch_id",
        "VARCHAR");
    assertColumn(
        UPGRADE_SCHEMA,
        "lp_quote_bom_monthly_snapshot",
        "effective_variant_hash",
        "VARCHAR");
    assertColumn(
        UPGRADE_SCHEMA, "lp_quote_bom_monthly_snapshot", "frozen_at", "DATETIME");
    assertColumn(
        UPGRADE_SCHEMA, "lp_quote_bom_monthly_snapshot", "frozen_by", "BIGINT");
    assertColumn(
        UPGRADE_SCHEMA,
        "lp_quote_bom_confirmation",
        "costing_build_batch_id",
        "VARCHAR");
    assertColumn(
        UPGRADE_SCHEMA,
        "lp_quote_bom_alternative_selection",
        "inherited_monthly_snapshot_id",
        "BIGINT");

    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(
            "SELECT freeze_status,effective_build_batch_id,"
                + "effective_variant_hash,frozen_at,frozen_by "
                + "FROM lp_quote_bom_monthly_snapshot WHERE id=1")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getString("freeze_status")).isEqualTo("DRAFT");
      assertThat(result.getObject("effective_build_batch_id")).isNull();
      assertThat(result.getObject("effective_variant_hash")).isNull();
      assertThat(result.getObject("frozen_at")).isNull();
      assertThat(result.getObject("frozen_by")).isNull();
    }
    assertThat(singleNullableValue(
        "lp_quote_bom_confirmation", "costing_build_batch_id")).isNull();
    assertThat(singleNullableValue(
        "lp_quote_bom_alternative_selection",
        "inherited_monthly_snapshot_id")).isNull();
  }

  private static Object singleNullableValue(String table, String column) throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(
            "SELECT " + column + " FROM " + table + " WHERE id=1")) {
      assertThat(result.next()).isTrue();
      return result.getObject(1);
    }
  }

  private static void assertNewTableIndexes(String schema) throws Exception {
    assertThat(indexColumns(schema, "lp_quote_effective_bom_node", "uk_build_node"))
        .containsExactly("build_batch_id", "node_key");
    assertThat(indexColumns(
        schema, "lp_quote_effective_bom_node", "idx_variant_hash"))
        .containsExactly("effective_variant_hash");
    assertThat(indexColumns(
        schema, "lp_quote_effective_bom_node", "idx_material_code"))
        .containsExactly("material_code");
    assertThat(indexColumns(
        schema, "lp_material_quote_shape_policy", "idx_material_month"))
        .containsExactly(
            "material_org_code",
            "material_code",
            "effective_from_month",
            "effective_to_month",
            "enabled");
  }

  private static void insertEffectiveNode(
      String buildBatchId, String nodeKey, String variantHash) throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        var statement = connection.prepareStatement(
            "INSERT INTO lp_quote_effective_bom_node ("
                + "build_batch_id,origin_monthly_snapshot_id,effective_variant_hash,"
                + "top_product_code,cost_period_month,price_org_code,"
                + "node_key,node_level,sort_seq,node_path,"
                + "material_code,qty_per_parent,qty_per_top,"
                + "effective_material_shape,shape_resolution_source,"
                + "source_bom_type,created_at"
                + ") VALUES (?,1,?,'1001900000098','2026-07','210',"
                + "?,0,0,'/1001900000098/','1001900000098',"
                + "1.00000000,1.00000000,'MANUFACTURE','U9','U9',NOW())")) {
      statement.setString(1, buildBatchId);
      statement.setString(2, variantHash);
      statement.setString(3, nodeKey);
      statement.executeUpdate();
    }
  }

  private static long countByNodeKey(String nodeKey) throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        var statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM lp_quote_effective_bom_node WHERE node_key=?")) {
      statement.setString(1, nodeKey);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
  }

  private static void insertIndexPerformanceFixtures(int rowCount) throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        var nodeStatement =
            connection.prepareStatement(
                "INSERT INTO lp_quote_effective_bom_node ("
                    + "build_batch_id,origin_monthly_snapshot_id,effective_variant_hash,"
                    + "top_product_code,cost_period_month,price_org_code,"
                    + "node_key,node_level,sort_seq,node_path,material_code,"
                    + "qty_per_parent,qty_per_top,effective_material_shape,"
                    + "shape_resolution_source,source_bom_type,created_at"
                    + ") VALUES ('BUILD-PERF',1,?,"
                    + "'P-PERF','2026-08','210',?,1,?,?,?,"
                    + "1.00000000,1.00000000,'PURCHASE','U9','U9',NOW())");
        var policyStatement =
            connection.prepareStatement(
                "INSERT INTO lp_material_quote_shape_policy ("
                    + "material_org_code,material_code,policy_mode,fixed_target_shape,"
                    + "effective_from_month,effective_to_month,enabled"
                    + ") VALUES ('COMMERCIAL',?,'FIXED','PURCHASE','2026-01',NULL,1)")) {
      connection.setAutoCommit(false);
      for (int index = 0; index < rowCount; index++) {
        String suffix = String.format(Locale.ROOT, "%05d", index);
        String materialCode = "M-PERF-" + suffix;
        nodeStatement.setString(1, "c".repeat(64));
        nodeStatement.setString(2, "NODE-" + suffix);
        nodeStatement.setInt(3, index);
        nodeStatement.setString(4, "/P-PERF/" + materialCode + "/");
        nodeStatement.setString(5, materialCode);
        nodeStatement.addBatch();

        policyStatement.setString(1, "RULE-" + suffix);
        policyStatement.addBatch();
      }
      nodeStatement.executeBatch();
      policyStatement.executeBatch();
      connection.commit();
    }
  }

  private static String explainKey(String sql) throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("EXPLAIN " + sql)) {
      assertThat(result.next()).isTrue();
      return result.getString("key");
    }
  }

  private static void assertColumn(
      String schema, String table, String column, String expectedType) throws Exception {
    try (Connection connection = openConnection(schema);
        ResultSet result = connection.getMetaData().getColumns(
            schema, null, table, column)) {
      assertThat(result.next()).as(table + "." + column + " exists").isTrue();
      assertThat(result.getString("TYPE_NAME")).isEqualToIgnoringCase(expectedType);
    }
  }

  private static boolean tableExists(String schema, String table) throws Exception {
    try (Connection connection = openConnection(schema);
        ResultSet result = connection.getMetaData().getTables(
            schema, null, table, new String[] {"TABLE"})) {
      return result.next();
    }
  }

  private static long tableCount(String schema) throws Exception {
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE'")) {
      statement.setString(1, schema);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
  }

  private static long newBusinessTableCount(String schema) throws Exception {
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME IN "
                + "('lp_quote_effective_bom_node','lp_material_quote_shape_policy')")) {
      statement.setString(1, schema);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
    }
  }

  private static List<String> indexColumns(
      String schema, String table, String index) throws Exception {
    List<IndexedColumn> columns = new ArrayList<>();
    try (Connection connection = openConnection(schema);
        ResultSet result = connection.getMetaData().getIndexInfo(
            schema, null, table, false, false)) {
      while (result.next()) {
        if (index.equals(result.getString("INDEX_NAME"))) {
          columns.add(new IndexedColumn(
              result.getInt("ORDINAL_POSITION"),
              result.getString("COLUMN_NAME")));
        }
      }
    }
    return columns.stream()
        .sorted(Comparator.comparingInt(IndexedColumn::position))
        .map(IndexedColumn::name)
        .toList();
  }

  private static void runMigration(String schema, String runName) throws Exception {
    String target = "/tmp/v202_" + runName + ".sql";
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(
            "/db/V202__quote_effective_bom_and_shape_policy.sql"),
        target);
    var result = MYSQL.execInContainer(
        "sh",
        "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + schema + " < " + target);
    assertThat(result.getExitCode())
        .as("V202执行失败，schema=" + schema + "，stderr=" + result.getStderr())
        .isZero();
  }

  private static Connection openConnection(String schema) throws Exception {
    String jdbcUrl = MYSQL.getJdbcUrl().replace(
        "/" + MYSQL.getDatabaseName(), "/" + schema);
    return DriverManager.getConnection(
        jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
  }

  private record LegacySnapshot(
      String monthlySnapshot,
      String confirmation,
      String alternativeSelection,
      String costingRow) {
  }

  private record IndexedColumn(int position, String name) {
  }
}
