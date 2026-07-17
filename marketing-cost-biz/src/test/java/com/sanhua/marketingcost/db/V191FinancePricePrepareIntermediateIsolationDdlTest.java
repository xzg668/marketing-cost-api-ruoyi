package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@DisplayName("V191 财务价格准备中间结果隔离 DDL")
class V191FinancePricePrepareIntermediateIsolationDdlTest {
  private static final DockerImageName MYSQL_IMAGE =
      DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql");

  @SuppressWarnings("resource")
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
      .withDatabaseName("marketing_cost")
      .withUsername("root")
      .withPassword("root123")
      .withCommand("--sql-mode=NO_ENGINE_SUBSTITUTION", "--default-storage-engine=InnoDB");

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();
    try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
      stmt.execute("CREATE TABLE lp_make_part_price_calc_row ("
          + "id BIGINT NOT NULL AUTO_INCREMENT, oa_no VARCHAR(64) NOT NULL DEFAULT '',"
          + "business_unit_type VARCHAR(32), pricing_month VARCHAR(7) NOT NULL DEFAULT '',"
          + "price_as_of_time DATETIME NOT NULL, parent_material_no VARCHAR(64) NOT NULL DEFAULT '',"
          + "child_material_no VARCHAR(64) NOT NULL DEFAULT '', scrap_code VARCHAR(64) NOT NULL DEFAULT '',"
          + "PRIMARY KEY(id), UNIQUE KEY uk_make_part_price_current_as_of "
          + "(oa_no,pricing_month,price_as_of_time,parent_material_no,child_material_no,scrap_code),"
          + "KEY idx_make_part_price_as_of_lookup "
          + "(parent_material_no,oa_no,business_unit_type,pricing_month,price_as_of_time)) ENGINE=InnoDB");
      stmt.execute("CREATE TABLE lp_price_linked_calc_item ("
          + "id BIGINT NOT NULL AUTO_INCREMENT, business_unit_type VARCHAR(32) NOT NULL,"
          + "calc_scene VARCHAR(32) NOT NULL, factor_source VARCHAR(32), oa_no VARCHAR(64) NOT NULL,"
          + "item_code VARCHAR(64) NOT NULL, pricing_month VARCHAR(7) NOT NULL, price_as_of_time DATETIME,"
          + "PRIMARY KEY(id), UNIQUE KEY uk_pl_calc_quote_scene_as_of "
          + "(business_unit_type,calc_scene,oa_no,item_code,pricing_month,price_as_of_time),"
          + "KEY idx_pl_calc_quote_as_of_lookup "
          + "(calc_scene,oa_no,item_code,pricing_month,price_as_of_time)) ENGINE=InnoDB");
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  void migrationIsRepeatableAndAllowsOaFinanceRowsToCoexist() throws Exception {
    runV191("v191_first.sql");
    runV191("v191_second.sql");
    assertThat(columnExists("lp_make_part_price_calc_row", "price_scenario_type")).isTrue();
    assertThat(indexExists("lp_make_part_price_calc_row", "uk_make_part_price_current_as_of_scene"))
        .isTrue();
    assertThat(indexExists("lp_price_linked_calc_item", "uk_pl_calc_quote_scene_as_of_factor"))
        .isTrue();

    try (Connection conn = openConnection(); Statement stmt = conn.createStatement()) {
      String makePrefix = "INSERT INTO lp_make_part_price_calc_row "
          + "(oa_no,pricing_month,price_as_of_time,price_scenario_type,parent_material_no,child_material_no,scrap_code) VALUES ";
      stmt.execute(makePrefix + "('OA-1','2026-05','2026-05-18 10:00:00','OA_LOCKED','P','C','S')");
      stmt.execute(makePrefix + "('OA-1','2026-05','2026-05-18 10:00:00','FINANCE_QUOTE_BASE','P','C','S')");
      assertThatThrownBy(() -> stmt.execute(
          makePrefix + "('OA-1','2026-05','2026-05-18 10:00:00','OA_LOCKED','P','C','S')"))
          .hasMessageContaining("Duplicate entry");

      String linkedPrefix = "INSERT INTO lp_price_linked_calc_item "
          + "(business_unit_type,calc_scene,factor_source,oa_no,item_code,pricing_month,price_as_of_time) VALUES ";
      stmt.execute(linkedPrefix
          + "('COMMERCIAL','QUOTE','OA_LOCKED','OA-1','MAT','2026-05','2026-05-18 10:00:00')");
      stmt.execute(linkedPrefix
          + "('COMMERCIAL','QUOTE','FINANCE_QUOTE_BASE','OA-1','MAT','2026-05','2026-05-18 10:00:00')");
      assertThatThrownBy(() -> stmt.execute(linkedPrefix
          + "('COMMERCIAL','QUOTE','FINANCE_QUOTE_BASE','OA-1','MAT','2026-05','2026-05-18 10:00:00')"))
          .hasMessageContaining("Duplicate entry");
    }
  }

  private static void runV191(String fileName) throws Exception {
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource(
        "/db/V191__finance_price_prepare_intermediate_isolation.sql"), "/tmp/" + fileName);
    var result = MYSQL.execInContainer("sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/" + fileName);
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }

  private static boolean columnExists(String table, String column) throws Exception {
    try (Connection conn = openConnection(); ResultSet rs = conn.getMetaData().getColumns(
        null, null, table, column)) {
      return rs.next();
    }
  }

  private static boolean indexExists(String table, String index) throws Exception {
    try (Connection conn = openConnection(); ResultSet rs = conn.getMetaData().getIndexInfo(
        null, null, table, false, false)) {
      while (rs.next()) {
        if (index.equals(rs.getString("INDEX_NAME"))) {
          return true;
        }
      }
      return false;
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }
}
