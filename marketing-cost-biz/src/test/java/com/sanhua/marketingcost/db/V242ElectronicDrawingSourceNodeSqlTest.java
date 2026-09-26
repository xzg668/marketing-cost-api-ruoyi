package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("V242 电子图库源节点 SQL 契约")
class V242ElectronicDrawingSourceNodeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("迁移只新增一张源节点表")
  void createsExactlyOneTable() {
    Matcher matcher = Pattern.compile("(?i)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+([`a-z0-9_]+)")
        .matcher(SQL);
    assertThat(matcher.find()).isTrue();
    assertThat(matcher.group(1).replace("`", ""))
        .isEqualTo("lp_electronic_drawing_source_node");
    assertThat(matcher.find()).isFalse();
    assertThat(SQL)
        .doesNotContainIgnoringCase(" BLOB")
        .doesNotContain("lp_electronic_drawing_import_batch")
        .doesNotContain("lp_electronic_drawing_mapping_rule");
  }

  @Test
  @DisplayName("源节点字段、唯一键和待处理索引完整")
  void sourceNodeContractIsComplete() {
    assertThat(SQL).contains(
        "supplement_version_id BIGINT NOT NULL",
        "source_row_no INT NOT NULL",
        "source_sequence VARCHAR(128) NOT NULL",
        "parent_source_sequence VARCHAR(128) DEFAULT NULL",
        "drawing_code VARCHAR(128) NOT NULL",
        "source_name VARCHAR(180) NOT NULL",
        "qty DECIMAL(20,8) NOT NULL",
        "material VARCHAR(255) DEFAULT NULL",
        "importance_class VARCHAR(64) DEFAULT NULL",
        "hsf_risk_class VARCHAR(64) DEFAULT NULL",
        "reference_weight DECIMAL(20,8) DEFAULT NULL",
        "source_remark VARCHAR(1000) DEFAULT NULL",
        "match_status VARCHAR(32) NOT NULL DEFAULT 'UNMATCHED'",
        "resolved_material_code VARCHAR(64) DEFAULT NULL",
        "resolved_by VARCHAR(128) DEFAULT NULL",
        "resolved_at DATETIME DEFAULT NULL",
        "UNIQUE KEY uk_ed_source_version_sequence (supplement_version_id, source_sequence)",
        "KEY idx_ed_source_version_status (supplement_version_id, match_status)",
        "CONSTRAINT ck_ed_source_resolution_complete CHECK");
  }

  @Test
  @DisplayName("现有版本和明细只增加来源与合成字段")
  void extendsExistingTablesWithExplicitFields() {
    assertThat(SQL).contains(
        "'electronic_drawing_no'",
        "'source_file_name'",
        "'source_file_sha256'",
        "'source_file_size'",
        "'source_sheet_name'",
        "'source_acquired_at'",
        "'source_request_id'",
        "'material_org_code'",
        "'composition_fingerprint'",
        "'node_source_type'",
        "'source_electronic_node_id'",
        "'mapping_status'",
        "uk_qbp_supp_version_prepare_scope",
        "idx_qbp_supp_version_ed_source",
        "idx_qbp_supp_detail_ed_source");
  }

  @Test
  @DisplayName("迁移可重跑且不改写历史数据")
  void migrationIsRerunnableAndNonDestructive() {
    assertThat(SQL).contains(
        "CREATE PROCEDURE v242_add_column_if_not_exists",
        "CREATE PROCEDURE v242_add_index_if_not_exists",
        "CREATE PROCEDURE v242_drop_index_if_exists",
        "INFORMATION_SCHEMA.COLUMNS",
        "INFORMATION_SCHEMA.STATISTICS",
        "CREATE TABLE IF NOT EXISTS lp_electronic_drawing_source_node");
    assertThat(SQL)
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_quote_bom_supplement_version")
        .doesNotContain("DELETE FROM lp_quote_bom_supplement_detail");
  }

  private static String readSql() {
    try {
      return new ClassPathResource("db/V242__electronic_drawing_source_node.sql")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V242 SQL 失败", exception);
    }
  }
}
