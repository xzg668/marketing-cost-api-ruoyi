package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@DisplayName("PLI2-13 类型2新增数据不修改旧联动价")
class PriceLinkedType2NoLegacyMutationIntegrationTest {

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
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE lp_price_linked_item ("
              + "id BIGINT NOT NULL PRIMARY KEY,"
              + "pricing_month CHAR(7) NOT NULL,"
              + "material_code VARCHAR(64) NOT NULL,"
              + "supplier_code VARCHAR(64) NULL,"
              + "formula_expr TEXT NULL,"
              + "formula_expr_cn TEXT NULL,"
              + "tax_included TINYINT NOT NULL,"
              + "effective_from DATE NOT NULL,"
              + "effective_to DATE NULL,"
              + "source_upload_batch_id BIGINT NULL,"
              + "source_sheet_name VARCHAR(128) NULL,"
              + "source_row_number INT NULL,"
              + "source_formula_cell_ref VARCHAR(32) NULL,"
              + "source_formula_expr TEXT NULL,"
              + "source_input_snapshot_json JSON NULL,"
              + "source_tax_included_price DECIMAL(20,8) NULL,"
              + "source_tax_excluded_price DECIMAL(20,8) NULL,"
              + "deleted TINYINT NOT NULL DEFAULT 0"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE lp_price_variable_binding ("
              + "id BIGINT NOT NULL PRIMARY KEY,"
              + "linked_item_id BIGINT NOT NULL,"
              + "token_name VARCHAR(64) NOT NULL,"
              + "factor_identity_id BIGINT NOT NULL,"
              + "factor_monthly_price_id BIGINT NOT NULL,"
              + "source VARCHAR(32) NOT NULL"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute(
          "CREATE TABLE lp_factor_monthly_price ("
              + "id BIGINT NOT NULL PRIMARY KEY,"
              + "factor_identity_id BIGINT NOT NULL,"
              + "price_month CHAR(7) NOT NULL,"
              + "price DECIMAL(20,6) NOT NULL,"
              + "tax_included TINYINT NOT NULL,"
              + "UNIQUE KEY uk_factor_month(factor_identity_id,price_month)"
              + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
    insertLegacyRows();
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("新增类型2公式、快照和绑定后五条旧记录三组快照完全一致")
  void insertingType2VersionLeavesFiveLegacyItemsBindingsAndMonthlyPricesUntouched()
      throws Exception {
    DatabaseSnapshot before = readLegacySnapshot();

    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "INSERT INTO lp_price_linked_item "
              + "(id,pricing_month,material_code,supplier_code,formula_expr,formula_expr_cn,"
              + "tax_included,effective_from,source_upload_batch_id,source_sheet_name,"
              + "source_row_number,source_formula_cell_ref,source_formula_expr,"
              + "source_input_snapshot_json,source_tax_included_price,"
              + "source_tax_excluded_price,deleted) VALUES "
              + "(900,'2026-07','TYPE2-NEW','SUP-TYPE2',"
              + "'([factor_identity_191]+1)','(1#Cu+1)',0,'2026-07-01',"
              + "800,'Sheet1',5,'R5','$E$2+1',"
              + "'{\"factor\":\"1#Cu\"}',91.00000000,80.53097345,0)");
      statement.execute(
          "INSERT INTO lp_price_variable_binding "
              + "(id,linked_item_id,token_name,factor_identity_id,"
              + "factor_monthly_price_id,source) "
              + "VALUES (900,900,'1#Cu',191,316,'TYPE2_FORMULA')");
      statement.execute(
          "INSERT INTO lp_factor_monthly_price "
              + "(id,factor_identity_id,price_month,price,tax_included) "
              + "VALUES (901,191,'2026-07',90.000000,1) "
              + "ON DUPLICATE KEY UPDATE price=VALUES(price)");
    }

    DatabaseSnapshot after = readLegacySnapshot();
    assertThat(after).isEqualTo(before);
    assertThat(after.itemCount()).isEqualTo(5);
    assertThat(after.bindingCount()).isEqualTo(9);
    assertThat(after.monthlyPriceCount()).isEqualTo(3);

    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(
            "SELECT COUNT(*) FROM lp_price_linked_item "
                + "WHERE id IN (142,147,148,151,157) "
                + "AND source_upload_batch_id IS NULL "
                + "AND source_sheet_name IS NULL "
                + "AND source_formula_expr IS NULL "
                + "AND source_input_snapshot_json IS NULL")) {
      assertThat(result.next()).isTrue();
      assertThat(result.getInt(1)).isEqualTo(5);
    }
  }

  private static void insertLegacyRows() throws Exception {
    try (Connection connection = openConnection()) {
      String itemSql =
          "INSERT INTO lp_price_linked_item "
              + "(id,pricing_month,material_code,supplier_code,formula_expr,"
              + "formula_expr_cn,tax_included,effective_from,effective_to,deleted) "
              + "VALUES (?,?,?,?,?,?,0,'2026-07-01',NULL,0)";
      try (PreparedStatement statement = connection.prepareStatement(itemSql)) {
        insertItem(statement, 142L, "301050013", "S001301",
            "([factor_identity_191]+[process_fee])", "(1#Cu+加工费)");
        String brassFormula =
            "([blank_weight]*(([factor_identity_191]*0.15+[factor_identity_192]*0.1"
                + "+[factor_identity_204]*0.75*1.05)*1.02+0.05)"
                + "-([blank_weight]-[net_weight])*(([factor_identity_191]*0.15"
                + "+[factor_identity_192]*0.1+[factor_identity_204]*0.75*1.05)"
                + "*1.02+0.05-0.05)*0.93+[process_fee])";
        String brassFormulaCn =
            "(下料重量*((1#Cu*0.15+1#Zn*0.1+美国柜装黄铜*0.75*1.05)*1.02+0.05)"
                + "-(下料重量-产品净重)*((1#Cu*0.15+1#Zn*0.1"
                + "+美国柜装黄铜*0.75*1.05)*1.02+0.05-0.05)*0.93+加工费)";
        insertItem(statement, 147L, "201500340", "S001052",
            brassFormula, brassFormulaCn);
        insertItem(statement, 148L, "201500340", "S000495",
            brassFormula, brassFormulaCn);
        insertItem(statement, 151L, "201502458", "S001171",
            "((([factor_identity_191]*0.15+[factor_identity_192]*0.1"
                + "+[factor_identity_204]*0.75*1.06)*1.02+0.38"
                + "+[process_fee])*[net_weight])",
            "(((1#Cu*0.15+1#Zn*0.1+美国柜装黄铜*0.75*1.06)"
                + "*1.02+0.38+加工费)*产品净重)");
        insertItem(statement, 157L, "301110045", "S001315",
            "([factor_identity_191]*0.59/0.98+[factor_identity_192]*0.41/0.95"
                + "+[process_fee])",
            "(1#Cu*0.59/0.98+1#Zn*0.41/0.95+加工费)");
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute(
            "INSERT INTO lp_factor_monthly_price "
                + "(id,factor_identity_id,price_month,price,tax_included) VALUES "
                + "(316,191,'2026-07',90.000000,1),"
                + "(317,192,'2026-07',21.680000,1),"
                + "(329,204,'2026-07',53.848000,1)");
        statement.execute(
            "INSERT INTO lp_price_variable_binding "
                + "(id,linked_item_id,token_name,factor_identity_id,"
                + "factor_monthly_price_id,source) VALUES "
                + "(191,142,'材料含税价格',191,316,'EXCEL_FORMULA'),"
                + "(196,147,'1#Cu',191,316,'EXCEL_FORMULA'),"
                + "(197,147,'1#Zn',192,317,'EXCEL_FORMULA'),"
                + "(198,148,'1#Cu',191,316,'EXCEL_FORMULA'),"
                + "(199,148,'1#Zn',192,317,'EXCEL_FORMULA'),"
                + "(204,151,'1#Cu',191,316,'EXCEL_FORMULA'),"
                + "(205,151,'1#Zn',192,317,'EXCEL_FORMULA'),"
                + "(214,157,'1#Cu',191,316,'EXCEL_FORMULA'),"
                + "(215,157,'1#Zn',192,317,'EXCEL_FORMULA')");
      }
    }
  }

  private static void insertItem(
      PreparedStatement statement,
      long id,
      String materialCode,
      String supplierCode,
      String formulaExpr,
      String formulaExprCn) throws Exception {
    statement.setLong(1, id);
    statement.setString(2, "2026-07");
    statement.setString(3, materialCode);
    statement.setString(4, supplierCode);
    statement.setString(5, formulaExpr);
    statement.setString(6, formulaExprCn);
    statement.executeUpdate();
  }

  private static DatabaseSnapshot readLegacySnapshot() throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement()) {
      String items = scalar(statement,
          "SELECT GROUP_CONCAT(CONCAT_WS('|',id,pricing_month,material_code,"
              + "supplier_code,formula_expr,formula_expr_cn,tax_included,"
              + "effective_from,COALESCE(effective_to,'NULL'),deleted) "
              + "ORDER BY id SEPARATOR ';') FROM lp_price_linked_item "
              + "WHERE id IN (142,147,148,151,157)");
      String bindings = scalar(statement,
          "SELECT GROUP_CONCAT(CONCAT_WS('|',id,linked_item_id,token_name,"
              + "factor_identity_id,factor_monthly_price_id,source) "
              + "ORDER BY id SEPARATOR ';') FROM lp_price_variable_binding "
              + "WHERE linked_item_id IN (142,147,148,151,157)");
      String monthlyPrices = scalar(statement,
          "SELECT GROUP_CONCAT(CONCAT_WS('|',id,factor_identity_id,price_month,"
              + "price,tax_included) ORDER BY id SEPARATOR ';') "
              + "FROM lp_factor_monthly_price WHERE id IN (316,317,329)");
      return new DatabaseSnapshot(
          items,
          bindings,
          monthlyPrices,
          count(statement,
              "SELECT COUNT(*) FROM lp_price_linked_item "
                  + "WHERE id IN (142,147,148,151,157)"),
          count(statement,
              "SELECT COUNT(*) FROM lp_price_variable_binding "
                  + "WHERE linked_item_id IN (142,147,148,151,157)"),
          count(statement,
              "SELECT COUNT(*) FROM lp_factor_monthly_price "
                  + "WHERE id IN (316,317,329)"));
    }
  }

  private static String scalar(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }

  private static int count(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }

  private record DatabaseSnapshot(
      String items,
      String bindings,
      String monthlyPrices,
      int itemCount,
      int bindingCount,
      int monthlyPriceCount) {
  }
}
