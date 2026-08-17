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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("QCBP-01 协作表迁移兼容性")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuoteCollaborationSchemaMigrationCompatibilityTest {

  private static final String EMPTY_SCHEMA = "qcbp_empty";
  private static final String UPGRADE_SCHEMA = "qcbp_upgrade";
  private static final String RECOVERY_SCHEMA = "qcbp_recovery";
  private static final String MIGRATION = "/db/V206__quote_bom_price_collaboration_schema.sql";
  private static final String APPROVED_RESULT_MIGRATION =
      "/db/V207__quote_collaboration_approved_result_idempotency.sql";
  private static final List<String> NEW_TABLES = List.of(
      "lp_quote_collaboration_task",
      "lp_quote_collaboration_product_task",
      "lp_quote_collaboration_quote_link",
      "lp_quote_collaboration_gap",
      "lp_quote_price_draft",
      "lp_quote_price_draft_field",
      "lp_quote_collaboration_review",
      "lp_quote_collaboration_review_item",
      "lp_quote_collaboration_approved_result",
      "lp_quote_collaboration_external_task",
      "lp_integration_outbox",
      "lp_integration_inbox");
  private static final List<String> LEGACY_TABLES = List.of(
      "oa_form",
      "oa_form_item",
      "lp_quote_bom_status",
      "lp_material_price_type");
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

  private static List<String> legacyDdlBefore;
  private static List<String> legacyRowsBefore;

  @BeforeAll
  static void setUp() throws Exception {
    MYSQL.start();
    try (Connection connection = openConnection(MYSQL.getDatabaseName());
        Statement statement = connection.createStatement()) {
      for (String schema : List.of(EMPTY_SCHEMA, UPGRADE_SCHEMA, RECOVERY_SCHEMA)) {
        statement.execute("CREATE DATABASE " + schema
            + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      }
    }
    createLegacyTables();
    insertLegacyRows();
    legacyDdlBefore = readLegacyDdl();
    legacyRowsBefore = readLegacyRows();
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @Order(1)
  @DisplayName("空库连续执行两次后只有十二张空协作表且字段索引完整")
  void migratesEmptySchemaIdempotently() throws Exception {
    runMigration(EMPTY_SCHEMA, "empty_first.sql");
    runMigration(EMPTY_SCHEMA, "empty_second.sql");
    runApprovedResultMigration(EMPTY_SCHEMA, "empty_v207.sql");

    assertThat(tableNames(EMPTY_SCHEMA)).containsExactlyElementsOf(sorted(NEW_TABLES));
    for (String table : NEW_TABLES) {
      assertThat(rowCount(EMPTY_SCHEMA, table)).as(table).isZero();
      assertTableStorage(EMPTY_SCHEMA, table);
      assertPrimaryKeyAutoIncrement(EMPTY_SCHEMA, table);
    }
    assertColumn(EMPTY_SCHEMA, "lp_quote_collaboration_task", "accounting_month",
        "char", "char(7)", false, null);
    assertColumn(EMPTY_SCHEMA, "lp_quote_price_draft", "tax_rate",
        "decimal", "decimal(10,6)", true, null);
    assertColumn(EMPTY_SCHEMA, "lp_quote_price_draft_field", "target_value_json",
        "json", "json", true, null);
    assertColumn(EMPTY_SCHEMA, "lp_quote_collaboration_approved_result", "validity_months",
        "int", "int", false, "6");
    assertColumn(EMPTY_SCHEMA, "lp_integration_outbox", "payload_json",
        "json", "json", false, null);
    assertColumn(EMPTY_SCHEMA, "lp_integration_inbox", "payload_json",
        "json", "json", false, null);

    assertIndex(EMPTY_SCHEMA, "lp_quote_collaboration_product_task",
        "uk_product_task_active_lock", true, "active_lock_key");
    assertIndex(EMPTY_SCHEMA, "lp_quote_collaboration_quote_link",
        "uk_collaboration_quote_link_active", true, "active_link_key");
    assertIndex(EMPTY_SCHEMA, "lp_quote_collaboration_gap",
        "uk_task_gap_fingerprint", true, "product_task_id", "gap_fingerprint");
    assertIndex(EMPTY_SCHEMA, "lp_quote_price_draft_field",
        "uk_price_draft_field", true,
        "price_draft_id", "section_code", "row_key", "field_code");
    assertIndex(EMPTY_SCHEMA, "lp_quote_collaboration_review",
        "uk_review_round", true, "collaboration_id", "review_round");
    assertIndex(EMPTY_SCHEMA, "lp_integration_outbox",
        "idx_outbox_dispatch", false, "destination", "send_status", "next_retry_at");
    assertIndex(EMPTY_SCHEMA, "lp_integration_inbox",
        "idx_inbox_process", false, "source_system", "process_status", "received_at");
    assertIndex(EMPTY_SCHEMA, "lp_quote_collaboration_approved_result",
        "uk_approved_result_source", true,
        "source_product_task_id", "source_review_id", "result_type");
    assertThat(physicalForeignKeyCount(EMPTY_SCHEMA)).isZero();
  }

  @Test
  @Order(2)
  @DisplayName("升级库执行迁移前后原表DDL、索引约束和历史数据完全不变")
  void keepsExistingTablesAndRowsUnchanged() throws Exception {
    runMigration(UPGRADE_SCHEMA, "upgrade_first.sql");
    runMigration(UPGRADE_SCHEMA, "upgrade_second.sql");
    runApprovedResultMigration(UPGRADE_SCHEMA, "upgrade_v207.sql");

    assertThat(readLegacyDdl()).containsExactlyElementsOf(legacyDdlBefore);
    assertThat(readLegacyRows()).containsExactlyElementsOf(legacyRowsBefore);
    assertThat(tableNames(UPGRADE_SCHEMA))
        .containsExactlyElementsOf(concat(LEGACY_TABLES, NEW_TABLES));
  }

  @Test
  @Order(3)
  @DisplayName("中断后保留部分正确新表时可安全重跑补齐其余表")
  void recoversFromPartialSchemaBySafeRerun() throws Exception {
    runMigration(RECOVERY_SCHEMA, "recovery_initial.sql");
    try (Connection connection = openConnection(RECOVERY_SCHEMA);
        Statement statement = connection.createStatement()) {
      for (String table : NEW_TABLES.subList(6, NEW_TABLES.size())) {
        statement.execute("DROP TABLE `" + table + "`");
      }
    }

    assertThat(tableNames(RECOVERY_SCHEMA))
        .containsExactlyElementsOf(sorted(NEW_TABLES.subList(0, 6)));
    runMigration(RECOVERY_SCHEMA, "recovery_rerun.sql");
    runApprovedResultMigration(RECOVERY_SCHEMA, "recovery_v207.sql");
    assertThat(tableNames(RECOVERY_SCHEMA)).containsExactlyElementsOf(sorted(NEW_TABLES));
    for (String table : NEW_TABLES) {
      assertThat(rowCount(RECOVERY_SCHEMA, table)).isZero();
    }
  }

  @Test
  @Order(4)
  @DisplayName("活动锁、活动报价关联、缺口、审核轮次和审核项唯一键阻止并发重复")
  void enforcesBusinessUniqueness() throws Exception {
    execute(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_task "
            + "(collaboration_no,oa_form_id,oa_no,accounting_month,master_status) "
            + "VALUES ('COL-1',1,'OA-1','2026-08','TECH_PROCESSING')");
    assertDuplicate(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_task "
            + "(collaboration_no,oa_form_id,oa_no,accounting_month,master_status) "
            + "VALUES ('COL-1',2,'OA-2','2026-08','TECH_PROCESSING')",
        "uk_collaboration_no");
    assertDuplicate(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_task "
            + "(collaboration_no,oa_form_id,oa_no,accounting_month,master_status) "
            + "VALUES ('COL-2',1,'OA-1','2026-08','TECH_PROCESSING')",
        "uk_collaboration_form_round");

    insertProductTask("PT-1", "LOCK-1");
    assertDuplicate(EMPTY_SCHEMA, productTaskSql("PT-2", "LOCK-1"),
        "uk_product_task_active_lock");
    insertProductTask("PT-3", null);
    insertProductTask("PT-4", null);

    execute(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_quote_link "
            + "(product_task_id,collaboration_id,oa_form_id,oa_form_item_id,oa_no,"
            + "accounting_month,applicable_org_code,link_type,link_status,active_link_key) "
            + "VALUES (1,1,1,11,'OA-1','2026-08','210','OWNER','WAIT_SOURCE','OA_ITEM:11')");
    assertDuplicate(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_quote_link "
            + "(product_task_id,collaboration_id,oa_form_id,oa_form_item_id,oa_no,"
            + "accounting_month,applicable_org_code,link_type,link_status,active_link_key) "
            + "VALUES (1,1,1,12,'OA-1','2026-08','210','OWNER','WAIT_SOURCE','OA_ITEM:11')",
        "uk_collaboration_quote_link_active");

    execute(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_gap "
            + "(gap_no,product_task_id,gap_category,gap_type,gap_fingerprint,"
            + "reason_code,reason_message,gap_status) "
            + "VALUES ('GAP-1',1,'PRICE','MISSING_PRICE',REPEAT('a',64),"
            + "'NO_PRICE','缺少正式价格','OPEN')");
    assertDuplicate(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_gap "
            + "(gap_no,product_task_id,gap_category,gap_type,gap_fingerprint,"
            + "reason_code,reason_message,gap_status) "
            + "VALUES ('GAP-2',1,'PRICE','MISSING_PRICE',REPEAT('a',64),"
            + "'NO_PRICE','缺少正式价格','OPEN')",
        "uk_task_gap_fingerprint");

    execute(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_review "
            + "(review_no,collaboration_id,review_status,reviewer_user_id,source_task_version,"
            + "product_count,price_draft_count) "
            + "VALUES ('REV-1',1,'PENDING',9,1,1,0)");
    assertDuplicate(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_review "
            + "(review_no,collaboration_id,review_status,reviewer_user_id,source_task_version,"
            + "product_count,price_draft_count) "
            + "VALUES ('REV-2',1,'PENDING',9,1,1,0)",
        "uk_review_round");

    execute(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_review_item "
            + "(review_id,product_task_id,item_type,item_ref_id,item_version) "
            + "VALUES (1,1,'BOM',100,1)");
    assertDuplicate(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_review_item "
            + "(review_id,product_task_id,item_type,item_ref_id,item_version) "
            + "VALUES (1,1,'BOM',100,1)",
        "uk_review_item");

    execute(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_approved_result "
            + "(result_no,result_type,source_product_task_id,source_review_id,product_code,"
            + "product_form,applicable_org_code,source_object_type,source_object_id,source_system,"
            + "structure_fingerprint,validity_policy_code,valid_from,valid_until,result_status) "
            + "VALUES ('RESULT-1','FULL_BOM',1,1,'MAT-1','NORMAL','210',"
            + "'SUPPLEMENT_VERSION',100,'ELECTRONIC_DRAWING',REPEAT('d',64),"
            + "'COLLAB_RESULT_SIX_MONTHS_V1','2026-08-01','2027-02-01','ACTIVE')");
    assertDuplicate(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_collaboration_approved_result "
            + "(result_no,result_type,source_product_task_id,source_review_id,product_code,"
            + "product_form,applicable_org_code,source_object_type,source_object_id,source_system,"
            + "structure_fingerprint,validity_policy_code,valid_from,valid_until,result_status) "
            + "VALUES ('RESULT-2','FULL_BOM',1,1,'MAT-1','NORMAL','210',"
            + "'SUPPLEMENT_VERSION',101,'ELECTRONIC_DRAWING',REPEAT('e',64),"
            + "'COLLAB_RESULT_SIX_MONTHS_V1','2026-08-01','2027-02-01','ACTIVE')",
        "uk_approved_result_source");
  }

  @Test
  @Order(5)
  @DisplayName("价格字段及集成收发件箱幂等键拒绝重复且JSON可真实读写")
  void enforcesDraftAndIntegrationIdempotency() throws Exception {
    execute(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_price_draft "
            + "(draft_no,product_task_id,gap_id,material_code,org_code,price_type,"
            + "source_mode,target_source_type,draft_status) "
            + "VALUES ('DRAFT-1',1,1,'MAT-1','210','LINKED','COPY','LINKED','EDITING')");
    execute(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_price_draft_field "
            + "(price_draft_id,section_code,row_key,field_code,value_type,"
            + "reference_value_json,target_value_json,tech_input_required) "
            + "VALUES (1,'VARIABLE','MAIN','NET_WEIGHT','DECIMAL',"
            + "CAST('0.286' AS JSON),CAST('0.300' AS JSON),1)");
    assertDuplicate(EMPTY_SCHEMA,
        "INSERT INTO lp_quote_price_draft_field "
            + "(price_draft_id,section_code,row_key,field_code,value_type) "
            + "VALUES (1,'VARIABLE','MAIN','NET_WEIGHT','DECIMAL')",
        "uk_price_draft_field");
    assertThat(new java.math.BigDecimal(singleString(
        EMPTY_SCHEMA,
        "SELECT JSON_UNQUOTE(target_value_json) FROM lp_quote_price_draft_field WHERE id=1")))
        .isEqualByComparingTo("0.300");

    execute(EMPTY_SCHEMA,
        "INSERT INTO lp_integration_outbox "
            + "(event_id,idempotency_key,destination,aggregate_type,aggregate_id,"
            + "aggregate_version,event_type,payload_json,payload_hash,send_policy,send_status,"
            + "occurred_at) VALUES ('00000000-0000-0000-0000-000000000001','OUT-1','OA',"
            + "'PRODUCT_TASK',1,1,'TECH_TASK_READY',JSON_OBJECT('taskId',1),REPEAT('b',64),"
            + "'HOLD','HOLD',NOW())");
    assertDuplicate(EMPTY_SCHEMA,
        "INSERT INTO lp_integration_outbox "
            + "(event_id,idempotency_key,destination,aggregate_type,aggregate_id,"
            + "aggregate_version,event_type,payload_json,payload_hash,send_policy,send_status,"
            + "occurred_at) VALUES ('00000000-0000-0000-0000-000000000002','OUT-1','OA',"
            + "'PRODUCT_TASK',1,1,'TECH_TASK_READY',JSON_OBJECT(),REPEAT('b',64),"
            + "'HOLD','HOLD',NOW())",
        "uk_outbox_idempotency_key");

    execute(EMPTY_SCHEMA,
        "INSERT INTO lp_integration_inbox "
            + "(source_system,callback_id,idempotency_key,callback_type,payload_json,payload_hash,"
            + "signature_status,process_status,received_at) VALUES "
            + "('OA','CALL-1','IN-1','TASK_CALLBACK',JSON_OBJECT('status','DONE'),"
            + "REPEAT('c',64),'NOT_CHECKED','RECEIVED',NOW())");
    assertDuplicate(EMPTY_SCHEMA,
        "INSERT INTO lp_integration_inbox "
            + "(source_system,callback_id,idempotency_key,callback_type,payload_json,payload_hash,"
            + "signature_status,process_status,received_at) VALUES "
            + "('OA','CALL-2','IN-1','TASK_CALLBACK',JSON_OBJECT(),REPEAT('c',64),"
            + "'NOT_CHECKED','RECEIVED',NOW())",
        "uk_inbox_idempotency_key");
  }

  private static void createLegacyTables() throws Exception {
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE oa_form ("
          + "id BIGINT NOT NULL AUTO_INCREMENT,oa_no VARCHAR(64) NOT NULL,"
          + "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(id),"
          + "UNIQUE KEY uk_oa_form_no(oa_no)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute("CREATE TABLE oa_form_item ("
          + "id BIGINT NOT NULL AUTO_INCREMENT,oa_form_id BIGINT NOT NULL,"
          + "material_no VARCHAR(64) NULL,calc_status VARCHAR(32) NOT NULL DEFAULT '未核算',"
          + "PRIMARY KEY(id),KEY idx_oa_form_item_form(oa_form_id)) "
          + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute("CREATE TABLE lp_quote_bom_status ("
          + "id BIGINT NOT NULL AUTO_INCREMENT,oa_form_item_id BIGINT NOT NULL,"
          + "bom_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CHECKED',"
          + "PRIMARY KEY(id),UNIQUE KEY uk_quote_bom_status_item(oa_form_item_id)) "
          + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
      statement.execute("CREATE TABLE lp_material_price_type ("
          + "id BIGINT NOT NULL AUTO_INCREMENT,material_code VARCHAR(64) NOT NULL,"
          + "price_type VARCHAR(32) NOT NULL,priority TINYINT NOT NULL DEFAULT 1,"
          + "PRIMARY KEY(id),KEY idx_material_price_type_code(material_code)) "
          + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }
  }

  private static void insertLegacyRows() throws Exception {
    execute(UPGRADE_SCHEMA, "INSERT INTO oa_form(id,oa_no) VALUES (1,'OA-EXISTING')");
    execute(UPGRADE_SCHEMA,
        "INSERT INTO oa_form_item(id,oa_form_id,material_no) VALUES (11,1,'MAT-EXISTING')");
    execute(UPGRADE_SCHEMA,
        "INSERT INTO lp_quote_bom_status(id,oa_form_item_id,bom_status) "
            + "VALUES (21,11,'U9_BOM_EXISTS')");
    execute(UPGRADE_SCHEMA,
        "INSERT INTO lp_material_price_type(id,material_code,price_type) "
            + "VALUES (31,'MAT-EXISTING','联动价')");
  }

  private static List<String> readLegacyDdl() throws Exception {
    List<String> result = new ArrayList<>();
    try (Connection connection = openConnection(UPGRADE_SCHEMA);
        Statement statement = connection.createStatement()) {
      for (String table : LEGACY_TABLES) {
        try (ResultSet rows = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
          assertThat(rows.next()).isTrue();
          result.add(table + "=" + rows.getString(2));
        }
      }
    }
    return result;
  }

  private static List<String> readLegacyRows() throws Exception {
    return List.of(
        singleString(UPGRADE_SCHEMA, "SELECT CONCAT_WS('|',id,oa_no) FROM oa_form WHERE id=1"),
        singleString(UPGRADE_SCHEMA,
            "SELECT CONCAT_WS('|',id,oa_form_id,material_no,calc_status) "
                + "FROM oa_form_item WHERE id=11"),
        singleString(UPGRADE_SCHEMA,
            "SELECT CONCAT_WS('|',id,oa_form_item_id,bom_status) "
                + "FROM lp_quote_bom_status WHERE id=21"),
        singleString(UPGRADE_SCHEMA,
            "SELECT CONCAT_WS('|',id,material_code,price_type,priority) "
                + "FROM lp_material_price_type WHERE id=31"));
  }

  private static void insertProductTask(String taskNo, String lockKey) throws Exception {
    execute(EMPTY_SCHEMA, productTaskSql(taskNo, lockKey));
  }

  private static String productTaskSql(String taskNo, String lockKey) {
    String lockSql = lockKey == null ? "NULL" : "'" + lockKey + "'";
    return "INSERT INTO lp_quote_collaboration_product_task "
        + "(product_task_no,origin_collaboration_id,accounting_month,applicable_org_code,"
        + "product_code,product_form,primary_scope,task_status,original_technician_user_id,"
        + "active_lock_key) VALUES ('" + taskNo + "',1,'2026-08','210','MAT-1',"
        + "'NORMAL','FULL_BOM','TECH_PROCESSING',8," + lockSql + ")";
  }

  private static void assertDuplicate(String schema, String sql, String key) {
    assertThatThrownBy(() -> execute(schema, sql))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining(key);
  }

  private static void assertTableStorage(String schema, String table) throws Exception {
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT ENGINE,TABLE_COLLATION FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("ENGINE")).isEqualToIgnoringCase("InnoDB");
        assertThat(result.getString("TABLE_COLLATION")).startsWith("utf8mb4");
      }
    }
  }

  private static void assertPrimaryKeyAutoIncrement(String schema, String table)
      throws Exception {
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT COLUMN_KEY,EXTRA FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME='id'")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("COLUMN_KEY")).isEqualTo("PRI");
        assertThat(result.getString("EXTRA")).contains("auto_increment");
      }
    }
  }

  private static void assertColumn(
      String schema,
      String table,
      String column,
      String dataType,
      String columnType,
      boolean nullable,
      String defaultValue) throws Exception {
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT "
                + "FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, column);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).as(table + "." + column).isTrue();
        assertThat(result.getString("DATA_TYPE")).isEqualToIgnoringCase(dataType);
        assertThat(result.getString("COLUMN_TYPE")).isEqualToIgnoringCase(columnType);
        assertThat(result.getString("IS_NULLABLE")).isEqualTo(nullable ? "YES" : "NO");
        assertThat(result.getString("COLUMN_DEFAULT")).isEqualTo(defaultValue);
      }
    }
  }

  private static void assertIndex(
      String schema,
      String table,
      String index,
      boolean unique,
      String... expectedColumns) throws Exception {
    List<IndexColumn> actual = new ArrayList<>();
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX")) {
      statement.setString(1, schema);
      statement.setString(2, table);
      statement.setString(3, index);
      try (ResultSet result = statement.executeQuery()) {
        while (result.next()) {
          actual.add(new IndexColumn(
              result.getInt("SEQ_IN_INDEX"),
              result.getString("COLUMN_NAME"),
              result.getInt("NON_UNIQUE") == 0));
        }
      }
    }
    assertThat(actual).isNotEmpty();
    assertThat(actual).allMatch(column -> column.unique() == unique);
    assertThat(actual.stream().map(IndexColumn::name).toList()).containsExactly(expectedColumns);
  }

  private static long physicalForeignKeyCount(String schema) throws Exception {
    try (Connection connection = openConnection(schema);
        var statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA=?")) {
      statement.setString(1, schema);
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getLong(1);
      }
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
        while (rows.next()) {
          result.add(rows.getString(1));
        }
      }
    }
    return result;
  }

  private static long rowCount(String schema, String table) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
      assertThat(result.next()).isTrue();
      return result.getLong(1);
    }
  }

  private static String singleString(String schema, String sql) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }

  private static void execute(String schema, String sql) throws Exception {
    try (Connection connection = openConnection(schema);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void runMigration(String schema, String targetFile) throws Exception {
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource(MIGRATION), "/tmp/" + targetFile);
    var result = MYSQL.execInContainer(
        "sh",
        "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + schema + " < /tmp/" + targetFile);
    assertThat(result.getExitCode())
        .as("V206执行失败，schema=" + schema + "，stderr=" + result.getStderr())
        .isZero();
  }

  private static void runApprovedResultMigration(String schema, String targetFile)
      throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(APPROVED_RESULT_MIGRATION), "/tmp/" + targetFile);
    var result = MYSQL.execInContainer(
        "sh",
        "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + schema + " < /tmp/" + targetFile);
    assertThat(result.getExitCode())
        .as("V207执行失败，schema=" + schema + "，stderr=" + result.getStderr())
        .isZero();
  }

  private static Connection openConnection(String schema) throws Exception {
    String jdbcUrl = MYSQL.getJdbcUrl().replace("/" + MYSQL.getDatabaseName(), "/" + schema);
    return DriverManager.getConnection(jdbcUrl, MYSQL.getUsername(), MYSQL.getPassword());
  }

  private static List<String> concat(List<String> left, List<String> right) {
    List<String> result = new ArrayList<>(left);
    result.addAll(right);
    return result.stream().sorted().toList();
  }

  private static List<String> sorted(List<String> values) {
    return values.stream().sorted().toList();
  }

  private record IndexColumn(int position, String name, boolean unique) {
  }
}
