package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
@DisplayName("PLI2-01 类型2结构迁移兼容性")
class PriceLinkedType2MigrationCompatibilityTest {

  private static final String EMPTY_SCHEMA = "pli2_empty";
  private static final String UPGRADE_SCHEMA = "pli2_upgrade";
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
      statement.execute(
          "CREATE DATABASE " + EMPTY_SCHEMA
              + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      statement.execute(
          "CREATE DATABASE " + UPGRADE_SCHEMA
              + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    }
    createLegacyBaseTables(EMPTY_SCHEMA);
    createLegacyBaseTables(UPGRADE_SCHEMA);
    createUpgradeOnlyTablesAndData();
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("空表库可执行迁移且新增字段全部允许为空")
  void migratesEmptyTablesAndKeepsNewFieldsNullable() throws Exception {
    runMigration(EMPTY_SCHEMA, "v198_empty.sql");

    assertFactorColumnsAndIndexes(EMPTY_SCHEMA);
    assertLinkedItemColumnsAndIndexes(EMPTY_SCHEMA);

    try (Connection connection = openConnection(EMPTY_SCHEMA);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO lp_factor_identity "
              + "(business_unit_type,factor_seq_no,factor_name,short_name,price_source) "
              + "VALUES ('COMMERCIAL','5','长江1#铜','1#Cu','平均价')");
      statement.execute(
          "INSERT INTO lp_price_linked_item "
              + "(pricing_month,material_code,supplier_code,formula_expr,tax_included,"
              + "process_fee,effective_from,deleted) "
              + "VALUES ('2026-07','MAT-EMPTY','SUP-EMPTY',"
              + "'([factor_identity_1]+[process_fee])',0,4.350001,'2026-07-01',0)");
    }

    assertLegacyRowsHaveNullExtensionFields(EMPTY_SCHEMA);
  }

  @Test
  @DisplayName("包含旧记录的升级库迁移前后四类数据和预览结果完全一致")
  void migratesLegacyDataWithoutRewritingPriceContracts() throws Exception {
    LegacySnapshot before = readLegacySnapshot();

    runMigration(UPGRADE_SCHEMA, "v198_upgrade.sql");

    LegacySnapshot after = readLegacySnapshot();
    assertThat(after.formulaExpr()).isEqualTo(before.formulaExpr());
    assertThat(after.taxIncluded()).isEqualTo(before.taxIncluded());
    assertThat(after.bindingRows()).isEqualTo(before.bindingRows());
    assertThat(after.monthlyPriceRows()).isEqualTo(before.monthlyPriceRows());
    assertThat(after.previewResult()).isEqualByComparingTo(before.previewResult());
    assertThat(after.previewResult()).isEqualByComparingTo("83.495576106195");

    assertFactorColumnsAndIndexes(UPGRADE_SCHEMA);
    assertLinkedItemColumnsAndIndexes(UPGRADE_SCHEMA);
    assertLegacyRowsHaveNullExtensionFields(UPGRADE_SCHEMA);
  }

  private static void createLegacyBaseTables(String schema) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE lp_factor_identity ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "business_unit_type VARCHAR(32) NOT NULL,"
              + "factor_seq_no VARCHAR(64) NOT NULL,"
              + "factor_name VARCHAR(255) NOT NULL,"
              + "short_name VARCHAR(128) NOT NULL,"
              + "price_source VARCHAR(64) NOT NULL,"
              + "identity_hash CHAR(64) NULL,"
              + "status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE lp_price_linked_item ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "pricing_month CHAR(7) NOT NULL,"
              + "material_code VARCHAR(64) NOT NULL,"
              + "supplier_code VARCHAR(64) NULL,"
              + "formula_expr VARCHAR(512) NULL,"
              + "tax_included TINYINT NOT NULL DEFAULT 1,"
              + "process_fee DECIMAL(18,6) NULL,"
              + "effective_from DATE NOT NULL,"
              + "deleted TINYINT NOT NULL DEFAULT 0,"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
  }

  private static void createUpgradeOnlyTablesAndData() throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE lp_factor_monthly_price ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "factor_identity_id BIGINT NOT NULL,"
              + "price_month CHAR(7) NOT NULL,"
              + "price DECIMAL(20,6) NULL,"
              + "tax_included TINYINT NOT NULL DEFAULT 1,"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE lp_price_variable_binding ("
              + "id BIGINT NOT NULL AUTO_INCREMENT,"
              + "linked_item_id BIGINT NOT NULL,"
              + "token_name VARCHAR(32) NOT NULL,"
              + "factor_identity_id BIGINT NULL,"
              + "factor_monthly_price_id BIGINT NULL,"
              + "source VARCHAR(16) NOT NULL,"
              + "PRIMARY KEY(id)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

      statement.execute(
          "INSERT INTO lp_factor_identity "
              + "(id,business_unit_type,factor_seq_no,factor_name,short_name,price_source) "
              + "VALUES (1,'COMMERCIAL','5','长江1#电解铜含税平均价格','1#Cu','平均价')");
      statement.execute(
          "INSERT INTO lp_factor_monthly_price "
              + "(id,factor_identity_id,price_month,price,tax_included) "
              + "VALUES (1,1,'2026-07',90.000000,1)");
      statement.execute(
          "INSERT INTO lp_price_linked_item "
              + "(id,pricing_month,material_code,supplier_code,formula_expr,tax_included,"
              + "process_fee,effective_from,deleted) "
              + "VALUES (1,'2026-07','301050013','S001301',"
              + "'([factor_identity_1]+[process_fee])',0,4.350001,'2026-07-01',0)");
      statement.execute(
          "INSERT INTO lp_price_variable_binding "
              + "(id,linked_item_id,token_name,factor_identity_id,"
              + "factor_monthly_price_id,source) "
              + "VALUES (1,1,'材料含税价格',1,1,'EXCEL_FORMULA')");
    }
  }

  private static LegacySnapshot readLegacySnapshot() throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement()) {
      String formula;
      int taxIncluded;
      BigDecimal processFee;
      try (ResultSet result = statement.executeQuery(
          "SELECT formula_expr,tax_included,process_fee "
              + "FROM lp_price_linked_item WHERE id=1")) {
        assertThat(result.next()).isTrue();
        formula = result.getString("formula_expr");
        taxIncluded = result.getInt("tax_included");
        processFee = result.getBigDecimal("process_fee");
      }

      String bindingRows;
      try (ResultSet result = statement.executeQuery(
          "SELECT GROUP_CONCAT(CONCAT_WS('|',id,linked_item_id,token_name,"
              + "factor_identity_id,factor_monthly_price_id,source) "
              + "ORDER BY id SEPARATOR ';') FROM lp_price_variable_binding")) {
        assertThat(result.next()).isTrue();
        bindingRows = result.getString(1);
      }

      String monthlyPriceRows;
      BigDecimal factorPrice;
      try (ResultSet result = statement.executeQuery(
          "SELECT GROUP_CONCAT(CONCAT_WS('|',id,factor_identity_id,price_month,"
              + "price,tax_included) ORDER BY id SEPARATOR ';'),MAX(price) "
              + "FROM lp_factor_monthly_price")) {
        assertThat(result.next()).isTrue();
        monthlyPriceRows = result.getString(1);
        factorPrice = result.getBigDecimal(2);
      }

      BigDecimal preview = factorPrice.add(processFee);
      if (taxIncluded == 0) {
        preview = preview.divide(new BigDecimal("1.13"), 12, RoundingMode.HALF_UP);
      }
      return new LegacySnapshot(
          formula, taxIncluded, bindingRows, monthlyPriceRows, preview);
    }
  }

  private static void assertFactorColumnsAndIndexes(String schema) throws Exception {
    assertNullableColumn(schema, "lp_factor_identity", "canonical_factor_key", "VARCHAR");
    assertNullableColumn(
        schema, "lp_factor_identity", "canonical_factor_identity_id", "BIGINT");
    assertNullableColumn(schema, "lp_factor_identity", "identity_origin", "VARCHAR");
    assertThat(indexExists(
        schema, "lp_factor_identity", "idx_factor_identity_canonical_key")).isTrue();
    assertThat(indexExists(
        schema, "lp_factor_identity", "idx_factor_identity_canonical_master")).isTrue();
  }

  private static void assertLinkedItemColumnsAndIndexes(String schema) throws Exception {
    assertNullableColumn(schema, "lp_price_linked_item", "source_upload_batch_id", "BIGINT");
    assertNullableColumn(schema, "lp_price_linked_item", "source_sheet_name", "VARCHAR");
    assertNullableColumn(schema, "lp_price_linked_item", "source_row_number", "INT");
    assertNullableColumn(schema, "lp_price_linked_item", "source_formula_cell_ref", "VARCHAR");
    assertNullableColumn(schema, "lp_price_linked_item", "source_formula_expr", "TEXT");
    assertNullableColumn(
        schema, "lp_price_linked_item", "source_input_snapshot_json", "JSON");
    assertNullableColumn(
        schema, "lp_price_linked_item", "source_tax_included_price", "DECIMAL");
    assertNullableColumn(
        schema, "lp_price_linked_item", "source_tax_excluded_price", "DECIMAL");
    assertThat(indexExists(
        schema, "lp_price_linked_item", "idx_price_linked_item_source_trace")).isTrue();
  }

  private static void assertLegacyRowsHaveNullExtensionFields(String schema) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(
            "SELECT fi.canonical_factor_key,fi.canonical_factor_identity_id,"
                + "fi.identity_origin,li.source_upload_batch_id,li.source_sheet_name,"
                + "li.source_row_number,li.source_formula_cell_ref,li.source_formula_expr,"
                + "li.source_input_snapshot_json,li.source_tax_included_price,"
                + "li.source_tax_excluded_price "
                + "FROM lp_factor_identity fi CROSS JOIN lp_price_linked_item li "
                + "ORDER BY fi.id,li.id LIMIT 1")) {
      assertThat(result.next()).isTrue();
      for (int column = 1; column <= 11; column++) {
        assertThat(result.getObject(column)).isNull();
      }
    }
  }

  private static void assertNullableColumn(
      String schema, String table, String column, String expectedType) throws Exception {
    try (Connection connection = openConnection(schema);
        ResultSet result = connection.getMetaData().getColumns(
            schema, null, table, column)) {
      assertThat(result.next()).as(table + "." + column + " exists").isTrue();
      assertThat(result.getString("TYPE_NAME")).isEqualToIgnoringCase(expectedType);
      assertThat(result.getString("IS_NULLABLE")).isEqualTo("YES");
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

  private static void runMigration(String schema, String targetFile) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V198__price_linked_type2_import_basis.sql"),
        "/tmp/" + targetFile);
    var result = MYSQL.execInContainer(
        "sh",
        "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + schema + " < /tmp/" + targetFile);
    assertThat(result.getExitCode())
        .as("V198 执行失败，schema=" + schema + "，stderr=" + result.getStderr())
        .isZero();
  }

  private static Connection openConnection(String schema) throws Exception {
    String jdbcUrl = MYSQL.getJdbcUrl().replace(
        "/" + MYSQL.getDatabaseName(), "/" + schema);
    return DriverManager.getConnection(jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
  }

  private record LegacySnapshot(
      String formulaExpr,
      int taxIncluded,
      String bindingRows,
      String monthlyPriceRows,
      BigDecimal previewResult) {
  }
}
