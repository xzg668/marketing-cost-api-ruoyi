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
@DisplayName("V60 报价单接入种子")
class V60QuoteIngestSeedDdlTest {

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
      createMinimalSeedTables(stmt);
      seedLegacyMenus(stmt);
    }
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("V60 可执行两次，菜单权限和字典不重复")
  void v60CanRunTwiceWithoutDuplicatingSeeds() throws Exception {
    runV60("v60_first.sql");
    runV60("v60_second.sql");

    assertThat(count(
            "SELECT COUNT(*) FROM sys_menu "
                + "WHERE menu_name IN ('报价单导入','报价单接入','报价单产品 BOM 处理','接入流水')"))
        .isEqualTo(4);
    assertThat(count(
            "SELECT COUNT(*) FROM sys_menu "
            + "WHERE perms IN ('ingest:quote:list','ingest:quote:import',"
            + "'ingest:quote:confirm','ingest:quote:bom-check','ingest:quote:raw',"
                + "'ingest:quote:mock-create','ingest:quote-product-bom:list','ingest:quote-log:list')"))
        .isEqualTo(8);
    assertThat(count("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = 2064"))
        .isEqualTo(1);
    assertThat(count(
            "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = 1 AND menu_id = 2064"))
        .isEqualTo(1);
    assertThat(count("SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = 208"))
        .isEqualTo(3);
    assertThat(count("SELECT COUNT(*) FROM sys_dict_type WHERE dict_type IN ("
            + "'quote_source_type','quote_scenario','quote_ingest_status',"
            + "'quote_classification_status','quote_bom_status','quote_fee_category',"
            + "'quote_writeback_status')"))
        .isEqualTo(7);
    assertThat(count("SELECT COUNT(*) FROM sys_dict_data WHERE dict_type IN ("
            + "'quote_source_type','quote_scenario','quote_ingest_status',"
            + "'quote_classification_status','quote_bom_status','quote_fee_category',"
            + "'quote_writeback_status')"))
        .isEqualTo(44);
    assertThat(count("SELECT COUNT(*) FROM lp_quote_ingest_type_rule"))
        .isEqualTo(7);

    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT order_num FROM sys_menu WHERE menu_id = 201 AND menu_name = 'OA报价单'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getInt(1)).isEqualTo(90);
    }
  }

  private static void createMinimalSeedTables(Statement stmt) throws Exception {
    stmt.execute(
        "CREATE TABLE sys_menu ("
            + "menu_id BIGINT NOT NULL AUTO_INCREMENT,"
            + "menu_name VARCHAR(50) NOT NULL,"
            + "parent_id BIGINT DEFAULT 0,"
            + "order_num INT DEFAULT 0,"
            + "path VARCHAR(200) DEFAULT '',"
            + "component VARCHAR(255) DEFAULT NULL,"
            + "is_frame VARCHAR(1) DEFAULT '1',"
            + "is_cache VARCHAR(1) DEFAULT '0',"
            + "menu_type CHAR(1) DEFAULT '',"
            + "visible CHAR(1) DEFAULT '0',"
            + "status CHAR(1) DEFAULT '0',"
            + "perms VARCHAR(100) DEFAULT NULL,"
            + "icon VARCHAR(100) DEFAULT '#',"
            + "create_by VARCHAR(64) DEFAULT '',"
            + "create_time DATETIME DEFAULT NULL,"
            + "update_by VARCHAR(64) DEFAULT '',"
            + "update_time DATETIME DEFAULT NULL,"
            + "remark VARCHAR(500) DEFAULT '',"
            + "PRIMARY KEY (menu_id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    stmt.execute(
        "CREATE TABLE sys_role_menu ("
            + "role_id BIGINT NOT NULL,"
            + "menu_id BIGINT NOT NULL,"
            + "PRIMARY KEY (role_id, menu_id)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    stmt.execute(
        "CREATE TABLE sys_dict_type ("
            + "dict_id BIGINT NOT NULL AUTO_INCREMENT,"
            + "dict_name VARCHAR(100) NOT NULL,"
            + "dict_type VARCHAR(100) NOT NULL,"
            + "status CHAR(1) NOT NULL DEFAULT '0',"
            + "remark VARCHAR(500) DEFAULT NULL,"
            + "deleted TINYINT NOT NULL DEFAULT 0,"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (dict_id),"
            + "UNIQUE KEY uk_dict_type (dict_type)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    stmt.execute(
        "CREATE TABLE sys_dict_data ("
            + "dict_code BIGINT NOT NULL AUTO_INCREMENT,"
            + "dict_sort INT DEFAULT 0,"
            + "dict_label VARCHAR(100) NOT NULL,"
            + "dict_value VARCHAR(100) NOT NULL,"
            + "dict_type VARCHAR(100) NOT NULL,"
            + "css_class VARCHAR(100) DEFAULT NULL,"
            + "list_class VARCHAR(100) DEFAULT NULL,"
            + "is_default CHAR(1) DEFAULT 'N',"
            + "status CHAR(1) NOT NULL DEFAULT '0',"
            + "remark VARCHAR(500) DEFAULT NULL,"
            + "deleted TINYINT NOT NULL DEFAULT 0,"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (dict_code),"
            + "KEY idx_dict_type (dict_type)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    stmt.execute(
        "CREATE TABLE lp_quote_ingest_type_rule ("
            + "id BIGINT NOT NULL AUTO_INCREMENT,"
            + "rule_code VARCHAR(64) NOT NULL,"
            + "rule_name VARCHAR(128) NOT NULL,"
            + "priority INT NOT NULL DEFAULT 100,"
            + "enabled TINYINT NOT NULL DEFAULT 1,"
            + "source_type VARCHAR(32) DEFAULT NULL,"
            + "process_code VARCHAR(64) DEFAULT NULL,"
            + "process_name_keyword VARCHAR(128) DEFAULT NULL,"
            + "applicant_unit_keyword VARCHAR(128) DEFAULT NULL,"
            + "business_type_keyword VARCHAR(128) DEFAULT NULL,"
            + "product_attr_keyword VARCHAR(128) DEFAULT NULL,"
            + "first_quote_flag TINYINT DEFAULT NULL,"
            + "target_business_unit_type VARCHAR(32) NOT NULL,"
            + "target_quote_scenario VARCHAR(64) NOT NULL,"
            + "confidence INT NOT NULL DEFAULT 100,"
            + "remark VARCHAR(500) DEFAULT NULL,"
            + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
            + "updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
            + "PRIMARY KEY (id),"
            + "UNIQUE KEY uk_quote_ingest_type_rule_code (rule_code)"
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
  }

  private static void seedLegacyMenus(Statement stmt) throws Exception {
    stmt.execute(
        "INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, "
            + "menu_type, visible, status, perms, icon) VALUES "
            + "(200, '报价需求', 0, 1, 'ingest', NULL, 'M', '0', '0', NULL, 'upload'),"
            + "(201, 'OA报价单', 200, 1, 'oa-form', 'ingest/oa-form/index', 'C', '0', '0', "
            + "'ingest:oa-form:list', 'form'),"
            + "(202, 'U9 BOM明细', 200, 2, 'u9Bom', 'ingest/u9Bom/index', 'C', '0', '0', "
            + "'ingest:u9-bom:list', 'component')");
  }

  private static void runV60(String containerFileName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("/db/V60__quote_ingest_enums_menu_seed.sql"),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/" + containerFileName);
    assertThat(result.getExitCode())
        .as("V60 执行失败 stderr=" + result.getStderr())
        .isZero();
  }

  private static int count(String sql) throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      return rs.getInt(1);
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }
}
